/*
 * Copyright 2021 IEXEC BLOCKCHAIN TECH
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

package com.iexec.worker.config;

import com.iexec.worker.feign.CustomBlockchainAdapterClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BlockchainAdapterConfigurationService {
    private final Integer blockTime;

    public BlockchainAdapterConfigurationService(CustomBlockchainAdapterClient customBlockchainAdapterClient) {
        // blockchain adapter uses seconds for block time whereas we want millis
        this.blockTime = customBlockchainAdapterClient.getBlockTime() * 1000;
        log.info("Received block time [{}]", this.blockTime);
    }

    public Integer getBlockTime() {
        return blockTime;
    }
}
