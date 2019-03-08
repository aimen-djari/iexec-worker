package com.iexec.worker.feign;

import com.iexec.common.chain.ChainReceipt;
import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.config.PublicConfiguration;
import com.iexec.common.config.WorkerConfigurationModel;
import com.iexec.common.disconnection.InterruptedReplicateModel;
import com.iexec.common.replicate.ReplicateStatus;
import com.iexec.common.security.Signature;
import com.iexec.common.utils.SignatureUtils;
import com.iexec.worker.chain.CredentialsService;
import com.iexec.worker.config.CoreConfigurationService;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class CustomFeignClient {


    private static final int RETRY_TIME = 5000;
    private static final String TOKEN_PREFIX = "Bearer ";
    private final String url;

    private CoreClient coreClient;
    private TaskClient taskClient;
    private WorkerClient workerClient;
    private ReplicateClient replicateClient;
    private CredentialsService credentialsService;
    private String currentToken;

    public CustomFeignClient(CoreClient coreClient,
                             TaskClient taskClient,
                             WorkerClient workerClient,
                             ReplicateClient replicateClient,
                             CredentialsService credentialsService,
                             CoreConfigurationService coreConfigurationService) {
        this.coreClient = coreClient;
        this.taskClient = taskClient;
        this.workerClient = workerClient;
        this.replicateClient = replicateClient;
        this.credentialsService = credentialsService;
        this.url = coreConfigurationService.getUrl();
        this.currentToken = "";
    }

    public PublicConfiguration getPublicConfiguration() {
        try {
            return workerClient.getPublicConfiguration();
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getPublicConfiguration, will retry");
                sleep();
                return getPublicConfiguration();
            }
        }
        return null;
    }

    public String getCoreVersion() {
        try {
            return coreClient.getCoreVersion();
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getCoreVersion, will retry");
                sleep();
                return getCoreVersion();
            }
        }
        return null;
    }

    public String ping() {
        try {
            return workerClient.ping(getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to ping [instance:{}]", url);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return workerClient.ping(getToken());
            }
        }

        return "";
    }

    public void registerWorker(WorkerConfigurationModel model) {
        try {
            workerClient.registerWorker(getToken(), model);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to registerWorker, will retry [instance:{}]", url);
                sleep();
                registerWorker(model);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                workerClient.registerWorker(getToken(), model);
            }
        }
    }

    public List<InterruptedReplicateModel> getInterruptedReplicates() {
        try {
            return taskClient.getInterruptedReplicates(getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getInterruptedReplicates, will retry [instance:{}]", url);
                sleep();
                return getInterruptedReplicates();
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return getInterruptedReplicates();
            }
        }
        return null;
	}

    public List<String> getTasksInProgress(){
        try {
            return workerClient.getCurrentTasks(getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to get tasks in progress, will retry [instance:{}]", url);
                sleep();
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return workerClient.getCurrentTasks(getToken());
            }
        }

        return Collections.emptyList();
    }

    public ContributionAuthorization getAvailableReplicate(long lastAvailableBlockNumber) {
        try {
            return taskClient.getAvailableReplicate(lastAvailableBlockNumber, getToken());
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getAvailableReplicate [instance:{}]", url);
            } else if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                return taskClient.getAvailableReplicate(lastAvailableBlockNumber, getToken());
            }
        }
        return null;
    }

    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status) {
        updateReplicateStatus(chainTaskId, status, null);
    }

    public void updateReplicateStatus(String chainTaskId, ReplicateStatus status, ChainReceipt chainReceipt) {
        log.info(status.toString() + " [chainTaskId:{}]", chainTaskId);

        // chainReceipt should not be null since it goes in the request body
        if (chainReceipt == null) {
            chainReceipt = ChainReceipt.builder().build();
        }

        try {
            replicateClient.updateReplicateStatus(chainTaskId, status, getToken(), chainReceipt);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to updateReplicateStatus, will retry [instance:{}]", url);
                sleep();
                updateReplicateStatus(chainTaskId, status, chainReceipt);
                return;
            }

            if (HttpStatus.valueOf(e.status()).equals(HttpStatus.UNAUTHORIZED)) {
                generateNewToken();
                log.info(status.toString() + " [chainTaskId:{}]", chainTaskId);
                replicateClient.updateReplicateStatus(chainTaskId, status, getToken(), chainReceipt);
            }
        }
    }

    private String getChallenge(String workerAddress) {
        try {
            return workerClient.getChallenge(workerAddress);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to getChallenge, will retry [instance:{}]", url);
                sleep();
                return getChallenge(workerAddress);
            }
        }
        return null;
    }

    private String login(String workerAddress, Signature signature) {
        try {
            return workerClient.login(workerAddress, signature);
        } catch (FeignException e) {
            if (e.status() == 0) {
                log.error("Failed to login, will retry [instance:{}]", url);
                sleep();
                return login(workerAddress, signature);
            }
        }
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_TIME);
        } catch (InterruptedException e) {
        }
    }

    private String getToken() {
        if (currentToken.isEmpty()) {
            String workerAddress = credentialsService.getCredentials().getAddress();
            ECKeyPair ecKeyPair = credentialsService.getCredentials().getEcKeyPair();
            String challenge = getChallenge(workerAddress);

            Signature signature = SignatureUtils.hashAndSign(challenge, workerAddress, ecKeyPair);
            currentToken = TOKEN_PREFIX + login(workerAddress, signature);
        }

        return currentToken;
    }

    private void expireToken() {
        currentToken = "";
    }

    private String generateNewToken() {
        expireToken();
        return getToken();
    }

}
