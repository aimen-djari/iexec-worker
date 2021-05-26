/*
 * Copyright 2020 IEXEC BLOCKCHAIN TECH
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

package com.iexec.worker.tee.scone;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.sms.SmsService;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LAS: local attestation service.
 * Local service used to perform SGX specific operations to attest the enclave
 * (eg compute enclave measurement - MREnclave - and attest it through Intel
 * Attestation Service).
 * It must be on the same machine as the attested program/enclave.
 * 
 * MREnclave: an enclave identifier, created by hashing all its
 * code. It guarantees that a code behaves exactly as expected.
 */
@Service
public class SconeLasConfiguration {

    @Getter
    @Value("${scone.show-version}")
    private boolean showVersion;

    @Getter
    @Value("${scone.log-level}")
    private String logLevel;

    @Getter
    @Value("${scone.registry.username}")
    private String registryUsername;

    @JsonIgnore
    @Getter
    @Value("${scone.registry.password}")
    private String registryPassword;

    @Value("${scone.las.image}")
    private String lasImage;

    @Value("${scone.las.version}")
    private String lasVersion;

    @Getter
    @Value("${scone.las.port}")
    private int lasPort;

    @Getter
    private final String lasContainerName;

    @Getter
    private final String casUrl;

    public SconeLasConfiguration(SmsService smsService,
            WorkerConfigurationService workerConfigService) {
        // "iexec-las-0xWalletAddress" as lasContainerName to avoid naming conflict
        // when running multiple workers on the same machine.
        lasContainerName = "iexec-las-" + workerConfigService.getWorkerWalletAddress();
        // Get cas url from sms
        casUrl = smsService.getSconeCasUrl();
        if (StringUtils.isEmpty(casUrl)) {
            throw new BeanInstantiationException(this.getClass(), "Missing cas url");
        }
    }

    public String getLasImageUri() {
        return lasImage + ":" + lasVersion;
    }

    public String getLasUrl() {
        return lasContainerName + ":" + lasPort;
    }
}