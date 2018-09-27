package com.iexec.worker;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class Controller {

    @Value("${worker.name}")
    private String workerName;
    private CoreClient coreClient;

    public Controller(CoreClient coreClient) {
        this.coreClient = coreClient;
    }

    @GetMapping("/michel")
    public String hello(@RequestParam(name="name", required=false, defaultValue="Stranger") String name) {
        return coreClient.hello(name);
    }

    @GetMapping("/getTask")
    public String getTask() {
        Replicate replicate = coreClient.getReplicate(workerName);
        if (replicate.getTaskId() == null){
            return "NO TASK AVAILABLE";
        }

        coreClient.updateReplicateStatus(replicate.getTaskId(), ReplicateStatus.RUNNING, workerName);

        // simulate some work on the task
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        coreClient.updateReplicateStatus(replicate.getTaskId(), ReplicateStatus.COMPLETED, workerName);
        return ReplicateStatus.COMPLETED.toString();
    }
}