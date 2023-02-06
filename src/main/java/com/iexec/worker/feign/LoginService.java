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

package com.iexec.worker.feign;

import com.iexec.common.security.Signature;
import com.iexec.common.utils.SignatureUtils;
import com.iexec.worker.chain.CredentialsService;
import com.iexec.worker.feign.client.CoreClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;

import java.util.Objects;

@Slf4j
@Service
public class LoginService {

    static final String TOKEN_PREFIX = "Bearer ";
    private String jwtToken;

    private final CredentialsService credentialsService;
    private final CoreClient coreClient;

    LoginService(CredentialsService credentialsService, CoreClient coreClient) {
        this.credentialsService = credentialsService;
        this.coreClient = coreClient;
    }

    public String getToken() {
        return jwtToken;
    }

    public String login() {
        final String oldToken = jwtToken;
        expireToken();

        String workerAddress = credentialsService.getCredentials().getAddress();
        ECKeyPair ecKeyPair = credentialsService.getCredentials().getEcKeyPair();

        ResponseEntity<String> challengeResponse = coreClient.getChallenge(workerAddress);
        if (!challengeResponse.getStatusCode().is2xxSuccessful()) {
            log.error("Cannot login, failed to get challenge [status:{}]", challengeResponse.getStatusCode());
            return "";
        }
        String challenge = challengeResponse.getBody();
        if (StringUtils.isEmpty(challenge)) {
            log.error("Cannot login, challenge is empty [challenge:{}]", challenge);
            return "";
        }

        Signature signature = SignatureUtils.hashAndSign(challenge, workerAddress, ecKeyPair);
        ResponseEntity<String> tokenResponse = coreClient.login(workerAddress, signature);
        if (!tokenResponse.getStatusCode().is2xxSuccessful()) {
            log.error("Cannot login, failed to get token [status:{}]", tokenResponse.getStatusCode());
            return "";
        }
        String token = tokenResponse.getBody();
        if (StringUtils.isEmpty(token)) {
            log.error("Cannot login, token is empty [token:{}]", token);
            return "";
        }

        jwtToken = TOKEN_PREFIX + token;
        log.info("Retrieved {} JWT token from scheduler", Objects.equals(oldToken, jwtToken) ? "existing" : "new");
        return jwtToken;
    }

    public void expireToken() {
        jwtToken = "";
    }
}
