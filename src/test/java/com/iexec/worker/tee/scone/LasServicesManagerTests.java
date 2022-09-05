package com.iexec.worker.tee.scone;

import com.iexec.common.task.TaskDescription;
import com.iexec.common.tee.TeeEnclaveProvider;
import com.iexec.sms.api.SmsClient;
import com.iexec.sms.api.config.SconeServicesConfiguration;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.docker.DockerService;
import com.iexec.worker.sgx.SgxService;
import com.iexec.worker.tee.TeeServicesConfigurationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.mockito.Mockito.*;

class LasServicesManagerTests {
    private static final String CONTAINER_NAME = "containerName";

    private static final String CHAIN_TASK_ID_1 = "chainTaskId1";
    private static final TaskDescription TASK_DESCRIPTION_1 = TaskDescription
            .builder()
            .chainTaskId(CHAIN_TASK_ID_1)
            .build();
    private static final String CHAIN_TASK_ID_2 = "chainTaskId2";
    private static final TaskDescription TASK_DESCRIPTION_2 = TaskDescription
            .builder()
            .chainTaskId(CHAIN_TASK_ID_2)
            .build();


    private static final String LAS_IMAGE_URI_1 = "lasImage1";
    private static final String LAS_IMAGE_URI_2 = "lasImage2";

    private static final SconeServicesConfiguration CONFIG_1 = new SconeServicesConfiguration(
            null,
            null,
            LAS_IMAGE_URI_1);
    private static final SconeServicesConfiguration CONFIG_2 = new SconeServicesConfiguration(
            null,
            null,
            LAS_IMAGE_URI_2);
    private static final SconeServicesConfiguration CONFIG_3 = new SconeServicesConfiguration(
            null,
            null,
            LAS_IMAGE_URI_1);

    @Mock
    SmsClient mockedSmsClient;
    @Mock
    LasService mockedLasService1;
    @Mock
    LasService mockedLasService2;

    @Mock
    SconeConfiguration sconeConfiguration;
    @Mock
    TeeServicesConfigurationService teeServicesConfigurationService;
    @Mock
    WorkerConfigurationService workerConfigService;
    @Mock
    SgxService sgxService;
    @Mock
    DockerService dockerService;
    @InjectMocks
    @Spy
    LasServicesManager lasServicesManager;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);

        doReturn(CONTAINER_NAME).when(lasServicesManager).createLasContainerName();
        when(lasServicesManager.createLasService(LAS_IMAGE_URI_1)).thenReturn(mockedLasService1);
        when(lasServicesManager.createLasService(LAS_IMAGE_URI_2)).thenReturn(mockedLasService2);
    }

    // region startLasService
    @Test
    void shouldStartLasServiceWhenLasNotYetCreated() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(null);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(mockedLasService1.start()).thenReturn(true);

        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_1));
    }

    @Test
    void shouldNotStartLasServiceWhenAlreadyStarted() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(mockedLasService1);
        when(mockedLasService1.isStarted()).thenReturn(true);

        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_1));

        verifyNoInteractions(teeServicesConfigurationService, mockedSmsClient);
    }

    @Test
    void shouldStartLasServiceWhenLasCreatedButNotStarted() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(mockedLasService1);
        when(mockedLasService1.isStarted()).thenReturn(false);
        when(mockedLasService1.start()).thenReturn(true);

        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_1));

        verifyNoInteractions(teeServicesConfigurationService, mockedSmsClient);
    }

    @Test
    void shouldStartTwoLasServicesForDifferentLasImageUri() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(null);
        when(lasServicesManager.getLas(CHAIN_TASK_ID_2)).thenReturn(null);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_2)).thenReturn(CONFIG_2);
        when(mockedLasService1.start()).thenReturn(true);
        when(mockedLasService2.start()).thenReturn(true);

        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_1));
        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_2));

        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenCallRealMethod();
        when(lasServicesManager.getLas(CHAIN_TASK_ID_2)).thenCallRealMethod();
        Assertions.assertNotEquals(lasServicesManager.getLas(CHAIN_TASK_ID_1), lasServicesManager.getLas(CHAIN_TASK_ID_2));
    }

    @Test
    void shouldStartOnlyOneLasServiceForSameLasImageUri() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(null);
        when(lasServicesManager.getLas(CHAIN_TASK_ID_2)).thenReturn(null);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_2)).thenReturn(CONFIG_3);
        when(mockedSmsClient.getTeeServicesConfiguration(TeeEnclaveProvider.SCONE))
                .thenReturn(CONFIG_1)
                .thenReturn(CONFIG_3);
        when(mockedLasService1.start()).thenReturn(true);

        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_1));
        Assertions.assertTrue(lasServicesManager.startLasService(CHAIN_TASK_ID_2));

        Assertions.assertEquals(lasServicesManager.getLas(CHAIN_TASK_ID_1), lasServicesManager.getLas(CHAIN_TASK_ID_2));
    }

    @Test
    void shouldNotStartLasServiceSinceMissingTeeWorkflowConfiguration() {
        when(lasServicesManager.getLas(CHAIN_TASK_ID_1)).thenReturn(null);
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(null);

        Assertions.assertFalse(lasServicesManager.startLasService(CHAIN_TASK_ID_1));

        verify(mockedLasService1, times(0)).start();
    }
    // endregion

    // region stopLasServices
    @Test
    void shouldStopLasServices() {
        // Two steps:
        // 1- Filling the LAS map with `lasServicesManager.startLasService(...)`
        //    and setting their `isStarted` values to `true`;
        // 2- Calling `lasServicesManager.stopLasServices` and checking `isStarted` is back to `false`.
        final Map<String, Boolean> areStarted = new HashMap<>(Map.of(
                CHAIN_TASK_ID_1, false,
                CHAIN_TASK_ID_2, false
        ));

        startLasService(CHAIN_TASK_ID_1, CONFIG_1, mockedLasService1, areStarted);
        startLasService(CHAIN_TASK_ID_2, CONFIG_2, mockedLasService2, areStarted);

        lasServicesManager.stopLasServices();
        Assertions.assertFalse(areStarted.get(CHAIN_TASK_ID_1));
        Assertions.assertFalse(areStarted.get(CHAIN_TASK_ID_2));
    }

    private void startLasService(String chainTaskId,
                                 SconeServicesConfiguration config,
                                 LasService lasService,
                                 Map<String, Boolean> areStarted) {
        when(teeServicesConfigurationService.getTeeServicesConfiguration(chainTaskId)).thenReturn(config);

        when(lasService.start()).then(invocation -> {
            areStarted.put(chainTaskId, true);
            return true;
        });
        doAnswer(invocation -> areStarted.put(chainTaskId, false)).when(lasService).stopAndRemoveContainer();

        lasServicesManager.startLasService(chainTaskId);
        Assertions.assertTrue(areStarted.get(chainTaskId));
    }
    // endregion

    // region createLasContainerName
    @Test
    void shouldCreateLasContainerNameWithProperCharLength() {
        LasServicesManager lasServicesManager = new LasServicesManager(
                sconeConfiguration, smsClientProvider, workerConfigService,
                sgxService, dockerService);
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger(32, new Random()));
        when(workerConfigService.getWorkerWalletAddress())
                .thenReturn(Credentials.create(keyPair).getAddress());
        String createdLasContainerName = lasServicesManager.createLasContainerName();
        Assertions.assertTrue(
            createdLasContainerName.length() < 64);
        //more checks about
        String expectedPrefix = "iexec-las";
        Assertions.assertTrue(createdLasContainerName.startsWith(expectedPrefix));
        Assertions.assertTrue(createdLasContainerName
            .contains(workerConfigService.getWorkerWalletAddress()));
        int minimumLength = String.format("%s-%s-", 
            expectedPrefix, workerConfigService.getWorkerWalletAddress()).length();
        Assertions.assertTrue(createdLasContainerName.length() > minimumLength);
    }

    @Test
    void shouldCreateLasContainerNameWithRandomness() {
        LasServicesManager lasServicesManager = new LasServicesManager(
                sconeConfiguration, smsClientProvider, workerConfigService,
                sgxService, dockerService);
        ECKeyPair keyPair = ECKeyPair.create(new BigInteger(32, new Random()));
        when(workerConfigService.getWorkerWalletAddress())
                .thenReturn(Credentials.create(keyPair).getAddress());
        //calling twice should return different values
        Assertions.assertNotEquals(lasServicesManager.createLasContainerName(), 
            lasServicesManager.createLasContainerName());
    }
    // endregion

    // region getLas
    @Test
    void shouldGetLas() {
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(mockedLasService1.start()).thenReturn(true);

        lasServicesManager.startLasService(CHAIN_TASK_ID_1); // Filling the LAS map

        Assertions.assertEquals(mockedLasService1, lasServicesManager.getLas(CHAIN_TASK_ID_1));
    }

    @Test
    void shouldNotGetLasSinceNoLasInMap() {
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(mockedLasService1.start()).thenReturn(true);

        Assertions.assertNull(lasServicesManager.getLas(CHAIN_TASK_ID_1));
    }

    @Test
    void shouldNotGetLasSinceNoLasInMapForGivenTask() {
        when(teeServicesConfigurationService.getTeeServicesConfiguration(CHAIN_TASK_ID_1)).thenReturn(CONFIG_1);
        when(mockedLasService1.start()).thenReturn(true);

        lasServicesManager.startLasService(CHAIN_TASK_ID_1); // Filling the LAS map

        Assertions.assertNull(lasServicesManager.getLas(CHAIN_TASK_ID_2));
    }
    // endregion
}