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

package com.iexec.worker.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iexec.common.chain.WorkerpoolAuthorization;
import com.iexec.common.security.Signature;
import com.iexec.common.tee.TeeWorkflowSharedConfiguration;
import com.iexec.common.web.ApiResponseBody;
import com.iexec.sms.api.SmsClient;
import com.iexec.sms.api.SmsClientProvider;
import com.iexec.sms.api.TeeSessionGenerationError;
import com.iexec.sms.api.TeeSessionGenerationResponse;
import com.iexec.worker.chain.CredentialsService;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Optional;

import static org.mockito.Mockito.*;

class SmsServiceTests {

    private final static TeeSessionGenerationResponse SESSION = mock(TeeSessionGenerationResponse.class);

    private static final String SIGNATURE = "random-signature";

    @Mock
    private CredentialsService credentialsService;
    @Mock
    private SmsClient smsClient;
    @Mock
    private SmsClientProvider smsClientProvider;
    @InjectMocks
    private SmsService smsService;

    @BeforeEach
    void beforeEach() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldCreateTeeSession() throws TeeSessionGenerationException {
        Signature signatureStub = new Signature(SIGNATURE);
        WorkerpoolAuthorization workerpoolAuthorization = mock(WorkerpoolAuthorization.class);
        when(credentialsService.hashAndSignMessage(workerpoolAuthorization.getHash()))
                .thenReturn(signatureStub);
        when(smsClient.generateTeeSession(signatureStub.getValue(), workerpoolAuthorization))
                .thenReturn(ApiResponseBody.<TeeSessionGenerationResponse, TeeSessionGenerationError>builder().data(SESSION).build());
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.of(smsClient));

        TeeSessionGenerationResponse returnedSessionId = smsService.createTeeSession(workerpoolAuthorization);
        Assertions.assertThat(returnedSessionId).isEqualTo(SESSION);
        verify(smsClientProvider).getSmsClientForTask(any());
    }

    @Test
    void shouldNotCreateTeeSessionWhenNoSmsClient() {
        Signature signatureStub = new Signature(SIGNATURE);
        WorkerpoolAuthorization workerpoolAuthorization = mock(WorkerpoolAuthorization.class);
        when(credentialsService.hashAndSignMessage(workerpoolAuthorization.getHash()))
                .thenReturn(signatureStub);
        when(smsClient.generateTeeSession(signatureStub.getValue(), workerpoolAuthorization))
                .thenReturn(ApiResponseBody.<TeeSessionGenerationResponse, TeeSessionGenerationError>builder().data(SESSION).build());
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.empty());

        final TeeSessionGenerationException exception = Assertions.catchThrowableOfType(() -> smsService.createTeeSession(workerpoolAuthorization), TeeSessionGenerationException.class);
        Assertions.assertThat(exception.getTeeSessionGenerationError()).isEqualTo(TeeSessionGenerationError.NO_SMS_FOR_TASK);
        verify(smsClientProvider).getSmsClientForTask(any());
        verify(smsClient, times(0)).generateTeeSession(signatureStub.getValue(), workerpoolAuthorization);
    }

    @Test
    void shouldNotCreateTeeSessionOnFeignException() throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final byte[] responseBody = mapper.writeValueAsBytes(ApiResponseBody.<Void, TeeSessionGenerationError>builder().error(TeeSessionGenerationError.NO_SESSION_REQUEST).build());
        final Request request = Request.create(Request.HttpMethod.GET, "url",
                new HashMap<>(), null, new RequestTemplate());

        Signature signatureStub = new Signature(SIGNATURE);
        WorkerpoolAuthorization workerpoolAuthorization = mock(WorkerpoolAuthorization.class);
        when(credentialsService.hashAndSignMessage(workerpoolAuthorization.getHash()))
                .thenReturn(signatureStub);
        when(smsClient.generateTeeSession(signatureStub.getValue(), workerpoolAuthorization))
                .thenThrow(new FeignException.InternalServerError("", request, responseBody, null ));   //FIXME
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.of(smsClient));

        final TeeSessionGenerationException exception = Assertions.catchThrowableOfType(() -> smsService.createTeeSession(workerpoolAuthorization), TeeSessionGenerationException.class);
        Assertions.assertThat(exception.getTeeSessionGenerationError()).isEqualTo(TeeSessionGenerationError.NO_SESSION_REQUEST);
        verify(smsClientProvider).getSmsClientForTask(any());
        verify(smsClient).generateTeeSession(signatureStub.getValue(), workerpoolAuthorization);
    }

    @Test
    void shouldGetTeeWorkflowConfiguration() {
        TeeWorkflowSharedConfiguration teeWorkflowConfiguration = mock(TeeWorkflowSharedConfiguration.class);
        when(smsClient.getTeeWorkflowConfiguration()).thenReturn(teeWorkflowConfiguration);
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.of(smsClient));

        Assertions.assertThat(smsService.getTeeWorkflowConfiguration(any())).isEqualTo(teeWorkflowConfiguration);
        verify(smsClientProvider).getSmsClientForTask(any());
        verify(smsClient).getTeeWorkflowConfiguration();
    }

    @Test
    void shouldNotGetTeeWorkflowConfigurationWhenNoSmsClient() {
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.empty());

        Assertions.assertThat(smsService.getTeeWorkflowConfiguration(any())).isNull();
        verify(smsClientProvider).getSmsClientForTask(any());
        verify(smsClient, times(0)).getTeeWorkflowConfiguration();
    }

    @Test
    void shouldNotGetTeeWorkflowConfigurationOnException() {
        when(smsClient.getTeeWorkflowConfiguration()).thenThrow(FeignException.class);
        when(smsClientProvider.getSmsClientForTask(any())).thenReturn(Optional.of(smsClient));

        Assertions.assertThat(smsService.getTeeWorkflowConfiguration(any())).isNull();
        verify(smsClientProvider).getSmsClientForTask(any());
        verify(smsClient).getTeeWorkflowConfiguration();
    }

}
