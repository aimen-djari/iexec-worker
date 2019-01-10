package com.iexec.worker.executor;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.dapp.DappType;
import com.iexec.common.replicate.AvailableReplicateModel;
import com.iexec.common.utils.BytesUtils;
import com.iexec.worker.chain.ContributionService;
import com.iexec.worker.docker.DockerComputationService;
import com.iexec.worker.feign.CustomFeignClient;
import com.iexec.worker.result.MetadataResult;
import com.iexec.worker.result.ResultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static com.iexec.common.replicate.ReplicateStatus.*;
import static com.iexec.worker.chain.ContributionService.*;

@Slf4j
@Service
public class TaskExecutorService {

    // external services
    private DockerComputationService dockerComputationService;
    private ResultService resultService;
    private ContributionService contributionService;
    private CustomFeignClient feignClient;

    // internal variables
    private int maxNbExecutions;
    private ThreadPoolExecutor executor;

    public TaskExecutorService(DockerComputationService dockerComputationService,
                               ContributionService contributionService,
                               ResultService resultService,
                               CustomFeignClient feignClient) {
        this.dockerComputationService = dockerComputationService;
        this.resultService = resultService;
        this.contributionService = contributionService;
        this.feignClient = feignClient;

        maxNbExecutions = Runtime.getRuntime().availableProcessors() / 2;
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(maxNbExecutions);
    }

    public boolean canAcceptMoreReplicate() {
        return executor.getActiveCount() < maxNbExecutions;
    }

    public void addReplicate(AvailableReplicateModel model) {
        ContributionAuthorization contribAuth = model.getContributionAuthorization();

        CompletableFuture.supplyAsync(() -> executeTask(model), executor)
                .thenAccept(metadataResult -> tryToContribute(contribAuth, metadataResult));
    }

    private MetadataResult executeTask(AvailableReplicateModel model) {
        String chainTaskId = model.getContributionAuthorization().getChainTaskId();

        if (contributionService.isChainTaskInitialized(chainTaskId)) {
            feignClient.updateReplicateStatus(chainTaskId, RUNNING);

            if (model.getDappType().equals(DappType.DOCKER)) {

                feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADING);
                boolean isImagePulled = dockerComputationService.dockerPull(chainTaskId, model.getDappName());
                if (isImagePulled) {
                    feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOADED);
                } else {
                    feignClient.updateReplicateStatus(chainTaskId, APP_DOWNLOAD_FAILED);
                }

                feignClient.updateReplicateStatus(chainTaskId, COMPUTING);
                try {
                    MetadataResult metadataResult = dockerComputationService.dockerRun(chainTaskId, model.getDappName(), model.getCmd(), model.getMaxExecutionTime());
                    //save metadataResult (without zip payload) in memory
                    resultService.addMetaDataResult(chainTaskId, metadataResult);
                    feignClient.updateReplicateStatus(chainTaskId, COMPUTED);
                    return metadataResult;
                } catch (Exception e) {
                    log.error("Error in the run of the application [error:{}]", e.getMessage());
                }
            }
        } else {
            log.warn("Task NOT initialized on chain [chainTaskId:{}]", chainTaskId);
        }
        return new MetadataResult();
    }

    private void tryToContribute(ContributionAuthorization contribAuth, MetadataResult metadataResult) {
        if (metadataResult.getDeterministHash().isEmpty()) {
            return;
        }
        String chainTaskId = contribAuth.getChainTaskId();


        if (metadataResult.getExecutionEnclaveSignature() != null) {

            String resultSeal = computeResultSeal(contribAuth.getWorkerWallet(), contribAuth.getChainTaskId(), metadataResult.getDeterministHash());
            String resultHash = computeResultHash(contribAuth.getChainTaskId(), metadataResult.getDeterministHash());

            boolean isEnclaveSignatureValid = isEnclaveSignatureValid(
                    resultHash,
                    resultSeal,
                    metadataResult.getExecutionEnclaveSignature(),
                    contribAuth.getEnclave());
            if (!isEnclaveSignatureValid) {
                log.error("The worker cannot contribute, enclaveSignature not valid [chainTaskId:{}, " +
                                "isEnclaveSignatureValid:{}, " +
                                "resultHash:{}, " +
                                "resultSeal:{}, " +
                                "enclaveR:{}, " +
                                "enclaveS:{}, " +
                                "enclaveAddress:{}",
                        chainTaskId,
                        isEnclaveSignatureValid,
                        resultHash,
                        resultSeal,
                        metadataResult.getExecutionEnclaveSignature().getR(),
                        metadataResult.getExecutionEnclaveSignature().getS(),
                        contribAuth.getEnclave());
                //return;
            }

        }

        if (!contributionService.canContribute(chainTaskId)) {
            log.warn("The worker cannot contribute since the contribution wouldn't be valid [chainTaskId:{}", chainTaskId);
            feignClient.updateReplicateStatus(chainTaskId, ERROR);
            return;
        }

        if (!contributionService.hasEnoughGas()) {
            feignClient.updateReplicateStatus(chainTaskId, ERROR);
            System.exit(0);
        }

        feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTING);
        if (contributionService.contribute(contribAuth, metadataResult.getDeterministHash(), metadataResult.getExecutionEnclaveSignature())) {
            feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTED);
        } else {
            feignClient.updateReplicateStatus(chainTaskId, CONTRIBUTE_FAILED);
        }
    }
}
