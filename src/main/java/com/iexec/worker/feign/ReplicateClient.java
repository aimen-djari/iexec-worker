package com.iexec.worker.feign;

import com.iexec.common.chain.ContributionAuthorization;
import com.iexec.common.notification.TaskNotification;
import com.iexec.common.notification.TaskNotificationType;
import com.iexec.common.replicate.ReplicateDetails;
import com.iexec.common.replicate.ReplicateStatus;
import feign.FeignException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@FeignClient(name = "ReplicateClient",
        url = "${core.protocol}://${core.host}:${core.port}",
        configuration = FeignConfiguration.class)
public interface ReplicateClient {

    @GetMapping("/replicates/available")
    ContributionAuthorization getAvailableReplicate(
            @RequestParam(name = "blockNumber") long blockNumber,
            @RequestHeader("Authorization") String bearerToken
    ) throws FeignException;

    @GetMapping("/replicates/interrupted")
    List<TaskNotification> getMissedTaskNotifications(
            @RequestParam(name = "blockNumber") long blockNumber,
            @RequestHeader("Authorization") String bearerToken
    ) throws FeignException;

    @PostMapping("/replicates/{chainTaskId}/updateStatus")
    TaskNotificationType updateReplicateStatus(@PathVariable(name = "chainTaskId") String chainTaskId,
                                               @RequestParam(name = "replicateStatus") ReplicateStatus replicateStatus,
                                               @RequestHeader("Authorization") String bearerToken,
                                               @RequestBody ReplicateDetails details) throws FeignException;
}