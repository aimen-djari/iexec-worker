/*
 * Copyright 2020-2023 IEXEC BLOCKCHAIN TECH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iexec.worker.executor;

import com.iexec.common.contribution.Contribution;
import com.iexec.common.lifecycle.purge.PurgeService;
import com.iexec.common.replicate.*;
import com.iexec.common.result.ComputedFile;
import com.iexec.commons.poco.chain.ChainReceipt;
import com.iexec.commons.poco.chain.WorkerpoolAuthorization;
import com.iexec.commons.poco.notification.TaskNotificationExtra;
import com.iexec.commons.poco.task.TaskDescription;
import com.iexec.common.utils.IexecEnvUtils;
import com.iexec.common.utils.IexecFileHelper;
import com.iexec.worker.chain.ContributionService;
import com.iexec.worker.chain.IexecHubService;
import com.iexec.worker.chain.RevealService;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.compute.ComputeManagerService;
import com.iexec.worker.compute.app.AppComputeResponse;
import com.iexec.worker.compute.post.PostComputeResponse;
import com.iexec.worker.compute.pre.PreComputeResponse;
import com.iexec.worker.dataset.DataService;
import com.iexec.worker.docker.DockerService;
import com.iexec.worker.pubsub.SubscriptionService;
import com.iexec.worker.result.ResultService;
import com.iexec.worker.tee.TeeService;
import com.iexec.worker.tee.TeeServicesManager;
import com.iexec.worker.utils.LoggingUtils;
import com.iexec.worker.utils.WorkflowException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


import com.iexec.sms.api.TeeSessionGenerationResponse;

import java.util.Optional;
import java.util.function.Predicate;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject; 
import org.json.JSONArray; 

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.iexec.common.replicate.ReplicateStatus.APP_DOWNLOAD_FAILED;
import static com.iexec.common.replicate.ReplicateStatus.DATA_DOWNLOAD_FAILED;
import static com.iexec.common.replicate.ReplicateStatusCause.*;
import static java.util.Objects.requireNonNull;


@Slf4j
@Service
public class TaskManagerService {

    private static final String CONTRIBUTE = "contribute";
    private static final String CONTRIBUTE_AND_FINALIZE = "contributeAndFinalize";

    private final IexecHubService iexecHubService;
    private final ContributionService contributionService;
    private final RevealService revealService;
    private final ComputeManagerService computeManagerService;
    private final TeeServicesManager teeServicesManager;
    private final DataService dataService;
    private final ResultService resultService;
    private final DockerService dockerService;
    private final SubscriptionService subscriptionService;
    private final WorkerConfigurationService workerConfigService;
    private final PurgeService purgeService;
    private final String workerWalletAddress;

    public TaskManagerService(
            IexecHubService iexecHubService,
            ContributionService contributionService,
            RevealService revealService,
            ComputeManagerService computeManagerService,
            TeeServicesManager teeServicesManager,
            DataService dataService,
            ResultService resultService,
            DockerService dockerService,
            SubscriptionService subscriptionService,
            WorkerConfigurationService workerConfigService,
            PurgeService purgeService,
            String workerWalletAddress) {
        this.iexecHubService = iexecHubService;
        this.contributionService = contributionService;
        this.revealService = revealService;
        this.computeManagerService = computeManagerService;
        this.teeServicesManager = teeServicesManager;
        this.dataService = dataService;
        this.resultService = resultService;
        this.dockerService = dockerService;
        this.subscriptionService = subscriptionService;
        this.workerConfigService = workerConfigService;
        this.purgeService = purgeService;
        this.workerWalletAddress = workerWalletAddress;
    }

    ReplicateActionResponse start(TaskDescription taskDescription) {
        final String chainTaskId = taskDescription.getChainTaskId();
        Optional<ReplicateStatusCause> oErrorStatus =
                contributionService.getCannotContributeStatusCause(chainTaskId);
        String context = "start";
        if (oErrorStatus.isPresent()) {
            return getFailureResponseAndPrintError(oErrorStatus.get(),
                    context, chainTaskId);
        }

        // result encryption is not supported for standard tasks
        if (!taskDescription.isTeeTask() && taskDescription.isResultEncryption()) {
            return getFailureResponseAndPrintError(TASK_DESCRIPTION_INVALID,
                    context, chainTaskId);
        }

        if (taskDescription.isTeeTask()) {
            // If any TEE prerequisite is not met,
            // then we won't be able to run the task.
            // So it should be aborted right now.
            final TeeService teeService = teeServicesManager.getTeeService(taskDescription.getTeeFramework());
            final Optional<ReplicateStatusCause> teePrerequisitesIssue = teeService.areTeePrerequisitesMetForTask(chainTaskId);
            if (teePrerequisitesIssue.isPresent()) {
                log.error("TEE prerequisites are not met [chainTaskId: {}, issue: {}]", chainTaskId, teePrerequisitesIssue.get());
                return getFailureResponseAndPrintError(teePrerequisitesIssue.get(), context, chainTaskId);
            }
        }
        return ReplicateActionResponse.success();
    }

    ReplicateActionResponse downloadApp(TaskDescription taskDescription) {
        final String chainTaskId = taskDescription.getChainTaskId();
        Optional<ReplicateStatusCause> oErrorStatus =
                contributionService.getCannotContributeStatusCause(chainTaskId);
        String context = "download app";
        if (oErrorStatus.isPresent()) {
            return getFailureResponseAndPrintError(oErrorStatus.get(),
                    context, chainTaskId);
        }

        if (computeManagerService.downloadApp(taskDescription)) {
            return ReplicateActionResponse.success();
        }
        return triggerPostComputeHookOnError(chainTaskId, context, taskDescription,
                APP_DOWNLOAD_FAILED, APP_IMAGE_DOWNLOAD_FAILED);
    }

    /*
     * Note: In order to keep a linear replicate workflow, we'll always have
     * the steps: APP_DOWNLOADING, ..., DATA_DOWNLOADING, ..., COMPUTING
     * (even when the dataset requested is 0x0).
     * In the 0x0 dataset case, we'll have an empty uri, and we'll consider
     * the dataset as downloaded
     *
     * Note2: TEE datasets are not downloaded by the worker. Due to some technical
     * limitations with SCONE technology (production enclaves not being able to
     * read non trusted regions of the file system), the file will be directly
     * fetched inside the pre-compute enclave.
     */

    /**
     * Download dataset file and input files if needed.
     *
     * @param taskDescription Description of the task.
     * @return ReplicateActionResponse containing success
     * or error statuses.
     */
    ReplicateActionResponse downloadData(TaskDescription taskDescription) {
        requireNonNull(taskDescription, "task description must not be null");
        String chainTaskId = taskDescription.getChainTaskId();
        Optional<ReplicateStatusCause> errorStatus =
                contributionService.getCannotContributeStatusCause(chainTaskId);
        String context = "download data";
        if (errorStatus.isPresent()) {
            return getFailureResponseAndPrintError(errorStatus.get(),
                    context, chainTaskId);
        }
        // Return early if TEE task
        if (taskDescription.isTeeTask()) {
            log.info("Dataset and input files will be downloaded by the pre-compute enclave [chainTaskId:{}]", chainTaskId);
            return ReplicateActionResponse.success();
        }
        try {
            // download dataset for standard task
            if (!taskDescription.containsDataset()) {
                log.info("No dataset for this task [chainTaskId:{}]", chainTaskId);
            } else {
                String datasetUri = taskDescription.getDatasetUri();
                log.info("Downloading dataset [chainTaskId:{}, uri:{}, name:{}]",
                        chainTaskId, datasetUri, taskDescription.getDatasetName());
                dataService.downloadStandardDataset(taskDescription);
            }
            // download input files for standard task
            if (!taskDescription.containsInputFiles()) {
                log.info("No input files for this task [chainTaskId:{}]", chainTaskId);
            } else {
                log.info("Downloading input files [chainTaskId:{}]", chainTaskId);
                dataService.downloadStandardInputFiles(chainTaskId, taskDescription.getInputFiles());
            }
        } catch (WorkflowException e) {
            return triggerPostComputeHookOnError(chainTaskId, context, taskDescription,
                    DATA_DOWNLOAD_FAILED, e.getReplicateStatusCause());
        }
        return ReplicateActionResponse.success();
    }

    private ReplicateActionResponse triggerPostComputeHookOnError(String chainTaskId,
                                                                  String context,
                                                                  TaskDescription taskDescription,
                                                                  ReplicateStatus errorStatus,
                                                                  ReplicateStatusCause errorCause) {
        // log original error
        logError(errorCause, context, chainTaskId);
        boolean isOk = resultService.writeErrorToIexecOut(chainTaskId, errorStatus, errorCause);
        // try to run post-compute
        if (isOk && computeManagerService.runPostCompute(taskDescription, null).isSuccessful()) {
            //Graceful error, worker will be prompt to contribute
            return ReplicateActionResponse.failure(errorCause);
        }
        //Download failed hard, worker cannot contribute
        logError(POST_COMPUTE_FAILED_UNKNOWN_ISSUE, context, chainTaskId);
        return ReplicateActionResponse.failure(POST_COMPUTE_FAILED_UNKNOWN_ISSUE);
    }

    ReplicateActionResponse compute(TaskDescription taskDescription) {
        final String chainTaskId = taskDescription.getChainTaskId();
        Optional<ReplicateStatusCause> oErrorStatus =
                contributionService.getCannotContributeStatusCause(chainTaskId);
        String context = "compute";
        if (oErrorStatus.isPresent()) {
            return getFailureResponseAndPrintError(oErrorStatus.get(), context, chainTaskId);
        }

        if (taskDescription.isTeeTask()) {
            TeeService teeService = teeServicesManager.getTeeService(taskDescription.getTeeFramework());
            if (!teeService.prepareTeeForTask(chainTaskId)) {
                return getFailureResponseAndPrintError(TEE_PREPARATION_FAILED,
                        context, chainTaskId);
            }
        }

        WorkerpoolAuthorization workerpoolAuthorization =
                contributionService.getWorkerpoolAuthorization(chainTaskId);

        PreComputeResponse preResponse =
                computeManagerService.runPreCompute(taskDescription,
                        workerpoolAuthorization);
        if (!preResponse.isSuccessful()) {
            final ReplicateActionResponse failureResponseAndPrintError;
            failureResponseAndPrintError = getFailureResponseAndPrintError(
                    preResponse.getExitCause(),
                    context,
                    chainTaskId
            );
            return failureResponseAndPrintError;
        }
        
        TeeSessionGenerationResponse session = preResponse.getSecureSession();
        log.info("Creating session [Session:{}]", session.getSessionId().toString());
        
        
        AppComputeResponse appResponse = runXTDXcontainer(session.getSessionId().toString(), taskDescription);
        
        log.info("App response [App Response:{}]", appResponse.toString());
        
        if(!appResponse.isSuccessful()) {
        	final ReplicateStatusCause cause = appResponse.getExitCause();
        	 return ReplicateActionResponse.failureWithDetails(
                     ReplicateStatusDetails.builder()
                             .cause(cause)
                             .exitCode(appResponse.getExitCode())
                             .computeLogs(
                                     ComputeLogs.builder()
                                             .stdout(appResponse.getStdout())
                                             .stderr(appResponse.getStderr())
                                             .build()
                             )
                             .build());
        }
        
        
        return ReplicateActionResponse.successWithLogs(
                ComputeLogs.builder()
                        .stdout(appResponse.getStdout())
                        .stderr(appResponse.getStderr())
                        .build()
        );
    }
    
    public AppComputeResponse runXTDXcontainer(String sessionId, TaskDescription taskDescription) {
    	 ReplicateStatusCause exitCause = null;
         String stdout = "";
         String stderr = "";
         int exitCode = 0;
         
        try {
            // Define variables for unknown elements
            String platformIp = "192.168.122.5";          // e.g., "192.168.1.100"
            String imageName = taskDescription.getAppUri().toString();         // e.g., "myImage:latest"
            String kmsEndpoint = "20.185.225.192:3333";        // e.g., "kms1.endpoint"
            String chainTaskId = taskDescription.getChainTaskId();            // taskid
            int targetPort = 8080;                       // Target port inside the container
            int publishedPort = 30001;                   // Port published on the host
            
            //final List<String> env = IexecEnvUtils.getComputeStageEnvList(taskDescription);
            final String cmd = taskDescription.getCmd();
            final long maxExecutionTime = taskDescription.getMaxExecutionTime();
            
            String workerHost = workerConfigService.getWorkerHost();

            // Auth information (set it if needed)
            String userName = "";                        // User for image registry
            String password = "";                        // Password for image registry

            // Construct the URL using the platform IP
            URL url = new URL("http://" + platformIp + ":8383/sw/api/v1/container");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set up the connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true); // Enable input/output for the connection

         // Constructing the JSON payload using JSONObject
            JSONObject imageInfo = new JSONObject()
                .put("ImageName", imageName)
                .put("RegisterAuthInfo", new JSONObject()
                    .put("UserName", userName)
                    .put("Password", password))
                .put("Cmd", cmd)
                .put("MaxExecutionTime", maxExecutionTime);

            JSONArray portsArray = new JSONArray()
                .put(new JSONObject()
                    .put("TargetPort", targetPort)
                    .put("PublishedPort", publishedPort));

            JSONArray mountsArray = new JSONArray()
                .put(new JSONObject()
                    .put("Type", "bind")
                    .put("Target", IexecFileHelper.SLASH_IEXEC_IN)
                    .put("Source", workerConfigService.getTaskInputDir(chainTaskId))
                    .put("RW", false))
                .put(new JSONObject()
                    .put("Type", "bind")
                    .put("Target", IexecFileHelper.SLASH_IEXEC_OUT)
                    .put("Source", workerConfigService.getTaskIexecOutDir(chainTaskId))
                    .put("RW", true));

            // Final JSON payload
            JSONObject jsonPayload = new JSONObject()
                .put("Name", chainTaskId)
                .put("ImageInfo", imageInfo)
                .put("Ports", portsArray)
                .put("Env", new JSONArray())  // Empty array for environment variables
                .put("Mounts", mountsArray)
                .put("KmsEndpoints", new JSONArray().put(kmsEndpoint))
                .put("SessionId", sessionId)
                .put("WorkerHost", workerHost);
            
            log.info("JsonPayload: [jsonPayload:{}]", jsonPayload.toString());

            // Send the request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Get the response code
            int responseCode = connection.getResponseCode();
            log.info("Response Code: [responseCode:{}]", responseCode);

            // You can process the response here if needed (e.g., read InputStream)
            if (responseCode >= 200 && responseCode < 300) {
                // Read the response
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    
                    String responseBody = response.toString();
                    
                    ObjectMapper mapper = new ObjectMapper();

                    // Parse the main JSON object
                    JsonNode rootNode = mapper.readTree(responseBody);

                    // Parse the message field as a JSON object
                    JsonNode messageNode = mapper.readTree(rootNode.get("message").asText());

                    String exitCauseString = messageNode.get("ExitCause").asText();
                    
                    exitCause = null;
                    stdout = messageNode.get("Stdout").asText();
                    stderr = messageNode.get("Stderr").asText();
                    exitCode = messageNode.get("ExitCode").asInt();
                }
            } else {
                // If the response code is not successful, log the error
                log.error("Failed to execute container. Response Code: {}", responseCode);
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponse.append(errorLine);
                    }
                    log.error("Error response: {}", errorResponse.toString());
                    String responseBody = errorResponse.toString();
                    
                    ObjectMapper mapper = new ObjectMapper();

                    // Parse the main JSON object
                    JsonNode rootNode = mapper.readTree(responseBody);

                    // Parse the message field as a JSON object
                    JsonNode messageNode = mapper.readTree(rootNode.get("message").asText());

                    String exitCauseString = messageNode.get("ExitCause").asText();
                    
                    exitCause = ReplicateStatusCause.APP_COMPUTE_FAILED;
                    stdout = messageNode.get("Stdout").asText();
                    stderr = messageNode.get("Stderr").asText();
                    exitCode = messageNode.get("ExitCode").asInt();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        
        
        
        return AppComputeResponse.builder()
                .exitCause(exitCause)
                .stdout(stdout)
                .stderr(stderr)
                .exitCode(exitCode)
                .build();
    }
    
    public void deleteXTDXcontainer() {
    	log.info("Deleting xTDX container");
        try {
        	
        	
            // Define variables for unknown elements
            String platformIp = "192.168.122.5";

            // Construct the URL using the platform IP
            URL url = new URL("http://" + platformIp + ":8383/sw/api/v1/container");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set up the connection
            connection.setRequestMethod("DELETE");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true); // Enable input/output for the connection

         

            // Final JSON payload
            JSONObject jsonPayload = new JSONObject();

            // Send the request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Get the response code
            int responseCode = connection.getResponseCode();
            log.info("Response Code: [responseCode:{}]", responseCode);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }
    
    public void cancelXTDXTask() {
    	log.info("Canceling xTDX task");
        try {
        	
        	
            // Define variables for unknown elements
            String platformIp = "192.168.122.5";

            // Construct the URL using the platform IP
            URL url = new URL("http://" + platformIp + ":8383/sw/api/v1/container/cancel");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Set up the connection
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true); // Enable input/output for the connection

         

            // Final JSON payload
            JSONObject jsonPayload = new JSONObject();

            // Send the request
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Get the response code
            int responseCode = connection.getResponseCode();
            log.info("Response Code: [responseCode:{}]", responseCode);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

    /**
     * Call {@link ContributionService#contribute(Contribution)} or {@link IexecHubService#contributeAndFinalize(Contribution, String, String)}
     * depending on the context.
     * <p>
     * The method has been developed to avoid code duplication.
     *
     * @param chainTaskId ID of the task
     * @param context     Either {@link TaskManagerService#CONTRIBUTE} or {@link TaskManagerService#CONTRIBUTE_AND_FINALIZE}
     * @return The response of the 'contribute' or 'contributeAndFinalize' action
     */
    private ReplicateActionResponse contributeOrContributeAndFinalize(String chainTaskId, String context) {
        Optional<ReplicateStatusCause> oErrorStatus = contributionService.getCannotContributeStatusCause(chainTaskId);
        if (oErrorStatus.isPresent()) {
            return getFailureResponseAndPrintError(oErrorStatus.get(),
                    context, chainTaskId);
        }

        if (!hasEnoughGas()) {
            return getFailureResponseAndPrintError(OUT_OF_GAS,
                    context, chainTaskId);
        }

        ComputedFile computedFile = resultService.getComputedFile(chainTaskId);
        if (computedFile == null) {
            logError("computed file error", context, chainTaskId);
            return ReplicateActionResponse.failure(DETERMINISM_HASH_NOT_FOUND);
        }

        Contribution contribution = contributionService.getContribution(computedFile);
        if (contribution == null) {
            logError("get contribution error", context, chainTaskId);
            return ReplicateActionResponse.failure(ENCLAVE_SIGNATURE_NOT_FOUND);//TODO update status
        }

        ReplicateActionResponse response = ReplicateActionResponse.failure(CHAIN_RECEIPT_NOT_VALID);
        if (context.equals(CONTRIBUTE)) {
            log.debug("contribute [contribution:{}]", contribution);
            Optional<ChainReceipt> oChainReceipt = contributionService.contribute(contribution);

            if (oChainReceipt.isPresent() && isValidChainReceipt(chainTaskId, oChainReceipt.get())) {
                response = ReplicateActionResponse.success(oChainReceipt.get());
            }
        } else if (context.equals(CONTRIBUTE_AND_FINALIZE)) {
            oErrorStatus = contributionService.getCannotContributeAndFinalizeStatusCause(chainTaskId);
            if (oErrorStatus.isPresent()) {
                return getFailureResponseAndPrintError(oErrorStatus.get(),
                        context, chainTaskId);
            }

            String callbackData = computedFile.getCallbackData();
            String resultLink = resultService.uploadResultAndGetLink(chainTaskId);
            log.debug("contributeAndFinalize [contribution:{}, resultLink:{}, callbackData:{}]",
                    contribution, resultLink, callbackData);
            Optional<ChainReceipt> oChainReceipt = iexecHubService.contributeAndFinalize(contribution, resultLink, callbackData);

            if (oChainReceipt.isPresent() && isValidChainReceipt(chainTaskId, oChainReceipt.get())) {
                final ReplicateStatusDetails details = ReplicateStatusDetails.builder()
                        .resultLink(resultLink)
                        .chainCallbackData(callbackData)
                        .chainReceipt(oChainReceipt.get())
                        .build();
                response = ReplicateActionResponse.builder()
                        .isSuccess(true)
                        .details(details)
                        .build();
            }
        }
        return response;
    }

    ReplicateActionResponse contribute(String chainTaskId) {
        return contributeOrContributeAndFinalize(chainTaskId, CONTRIBUTE);
    }

    ReplicateActionResponse reveal(String chainTaskId,
                                   TaskNotificationExtra extra) {
        String context = "reveal";
        if (extra == null || extra.getBlockNumber() == 0) {
            return getFailureResponseAndPrintError(CONSENSUS_BLOCK_MISSING,
                    context, chainTaskId);
        }
        long consensusBlock = extra.getBlockNumber();

        ComputedFile computedFile =
                resultService.getComputedFile(chainTaskId);
        String resultDigest = computedFile != null ?
                computedFile.getResultDigest() : "";

        if (resultDigest.isEmpty()) {
            logError("get result digest error", context, chainTaskId);
            return ReplicateActionResponse.failure(DETERMINISM_HASH_NOT_FOUND);
        }

        if (!revealService.isConsensusBlockReached(chainTaskId,
                consensusBlock)) {
            return getFailureResponseAndPrintError(BLOCK_NOT_REACHED,
                    context, chainTaskId
            );
        }

        if (!revealService.repeatCanReveal(chainTaskId,
                resultDigest)) {
            return getFailureResponseAndPrintError(CANNOT_REVEAL,
                    context, chainTaskId);
        }

        if (!hasEnoughGas()) {
            logError(OUT_OF_GAS, context, chainTaskId);
            // Don't we prefer an OUT_OF_GAS?
            System.exit(0);
        }

        Optional<ChainReceipt> oChainReceipt =
                revealService.reveal(chainTaskId, resultDigest);
        if (oChainReceipt.isEmpty() ||
                !isValidChainReceipt(chainTaskId, oChainReceipt.get())) {
            return getFailureResponseAndPrintError(CHAIN_RECEIPT_NOT_VALID,
                    context, chainTaskId
            );
        }

        return ReplicateActionResponse.success(oChainReceipt.get());
    }

    ReplicateActionResponse uploadResult(String chainTaskId) {
        String resultLink = resultService.uploadResultAndGetLink(chainTaskId);
        String context = "upload result";
        if (resultLink.isEmpty()) {
            return getFailureResponseAndPrintError(RESULT_LINK_MISSING,
                    context, chainTaskId
            );
        }

        ComputedFile computedFile =
                resultService.getComputedFile(chainTaskId);
        String callbackData = computedFile != null ?
                computedFile.getCallbackData() : "";

        log.info("Result uploaded [chainTaskId:{}, resultLink:{}, callbackData:{}]",
                chainTaskId, resultLink, callbackData);

        return ReplicateActionResponse.success(resultLink, callbackData);
    }

    ReplicateActionResponse contributeAndFinalize(String chainTaskId) {
        return contributeOrContributeAndFinalize(chainTaskId, CONTRIBUTE_AND_FINALIZE);
    }

    ReplicateActionResponse complete(String chainTaskId) {
        purgeService.purgeAllServices(chainTaskId);

        if (!resultService.purgeTask(chainTaskId)) {
            return ReplicateActionResponse.failure();
        }

        return ReplicateActionResponse.success();
    }

    /**
     * To abort a task, the worker must, first, remove currently running containers
     * related to the task in question, unsubscribe from the task's notifications,
     * then remove result folders.
     *
     * @param chainTaskId
     * @return
     */
    boolean abort(String chainTaskId) {
        log.info("Aborting task [chainTaskId:{}]", chainTaskId);
        cancelXTDXTask();
        deleteXTDXcontainer();
        log.info("Stopped task containers [chainTaskId:{}]", chainTaskId);
        subscriptionService.unsubscribeFromTopic(chainTaskId);
        boolean isSuccess = purgeService.purgeAllServices(chainTaskId);
        if (!isSuccess) {
            log.error("Failed to abort task [chainTaskId:{}]", chainTaskId);
        }
        return isSuccess;
    }

    boolean hasEnoughGas() {
        if (iexecHubService.hasEnoughGas()) {
            return true;
        }

        String noEnoughGas = String.format("Out of gas! please refill your " +
                        "wallet [walletAddress:%s]",
                workerWalletAddress);
        LoggingUtils.printHighlightedMessage(noEnoughGas);
        return false;
    }

    //TODO Move that to contribute & reveal services
    boolean isValidChainReceipt(String chainTaskId,
                                ChainReceipt chainReceipt) {
        if (chainReceipt == null) {
            log.warn("The chain receipt is empty, nothing will be sent to the" +
                    " core [chainTaskId:{}]", chainTaskId);
            return false;
        }

        if (chainReceipt.getBlockNumber() == 0) {
            log.warn("The blockNumber of the receipt is equal to 0, status " +
                    "will not be updated in the core [chainTaskId:{}]", chainTaskId);
            return false;
        }

        return true;
    }

    private ReplicateActionResponse getFailureResponseAndPrintError(ReplicateStatusCause cause, String context, String chainTaskId) {
        logError(cause, context, chainTaskId);
        return ReplicateActionResponse.failure(cause);
    }

    /**
     * This method, which a <String> 'cause' should disappear at some point
     * Each error should have it proper ReplicateStatusCause so the core could
     * keep track of it.
     */
    private void logError(String cause, String failureContext,
                          String chainTaskId) {
        log.error("Failed to {} [chainTaskId:'{}', cause:'{}']", failureContext,
                chainTaskId, cause);
    }

    private void logError(ReplicateStatusCause cause, String failureContext,
                          String chainTaskId) {
        logError(cause != null ? cause.toString() : "", failureContext,
                chainTaskId);
    }

}
