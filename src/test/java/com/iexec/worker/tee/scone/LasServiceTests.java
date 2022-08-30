package com.iexec.worker.tee.scone;

import com.iexec.common.docker.DockerRunFinalStatus;
import com.iexec.common.docker.DockerRunRequest;
import com.iexec.common.docker.DockerRunResponse;
import com.iexec.common.docker.client.DockerClientInstance;
import com.iexec.common.sgx.SgxDriverMode;
import com.iexec.worker.config.WorkerConfigurationService;
import com.iexec.worker.docker.DockerService;
import com.iexec.worker.sgx.SgxService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LasServiceTests {
    private static final String CONTAINER_NAME = "iexec-las";
    private static final String REGISTRY_NAME = "registryName";
    private static final String IMAGE_URI = REGISTRY_NAME +"/some/image/name:x.y";
    public static final String REGISTRY_USERNAME = "registryUsername";
    public static final String REGISTRY_PASSWORD = "registryPassword";

    @Captor
    ArgumentCaptor<DockerRunRequest> dockerRunRequestArgumentCaptor;

    @Mock
    SconeConfiguration sconeConfiguration;
    @Mock
    WorkerConfigurationService workerConfigService;
    @Mock
    DockerService dockerService;
    @Mock
    SgxService sgxService;
    @Mock
    private DockerClientInstance dockerClientInstanceMock;

    LasService lasService;

    @BeforeEach
    void init() throws Exception {
        MockitoAnnotations.openMocks(this);
        lasService = spy(new LasService(
                CONTAINER_NAME,
                IMAGE_URI,
                sconeConfiguration,
                workerConfigService,
                sgxService,
                dockerService
        ));

        when(sconeConfiguration.getRegistryName()).thenReturn(REGISTRY_NAME);
        when(sconeConfiguration.getRegistryUsername()).thenReturn(REGISTRY_USERNAME);
        when(sconeConfiguration.getRegistryPassword()).thenReturn(REGISTRY_PASSWORD);
        when(dockerService.getClient()).thenReturn(dockerClientInstanceMock);
        when(dockerService.getClient(REGISTRY_NAME, REGISTRY_USERNAME, REGISTRY_PASSWORD))
                .thenReturn(dockerClientInstanceMock);
    }

    // region start
    @Test
    void shouldStartLasService() {
        when(dockerClientInstanceMock.pullImage(IMAGE_URI)).thenReturn(true);
        when(dockerService.run(any()))
                .thenReturn(DockerRunResponse.builder().finalStatus(DockerRunFinalStatus.SUCCESS).build());
        when(sgxService.getSgxDriverMode()).thenReturn(SgxDriverMode.LEGACY);

        Assertions.assertThat(lasService.start()).isTrue();
        verify(dockerService).run(dockerRunRequestArgumentCaptor.capture());
        DockerRunRequest dockerRunRequest = dockerRunRequestArgumentCaptor.getValue();
        Assertions.assertThat(dockerRunRequest).isEqualTo(
                DockerRunRequest.builder()
                        .containerName(CONTAINER_NAME)
                        .imageUri(IMAGE_URI)
                        .sgxDriverMode(SgxDriverMode.LEGACY)
                        .maxExecutionTime(0)
                        .build()
        );
        assertTrue(lasService.isStarted());
    }

    @Test
    void shouldNotStartLasServiceSinceUnknownRegistry() {
        LasService lasService = new LasService(
                CONTAINER_NAME,
                "unknownRegistry",
                sconeConfiguration,
                workerConfigService,
                sgxService,
                dockerService
        );
        Exception exception = assertThrows(
                RuntimeException.class,
                lasService::start
        );

        Assertions.assertThat(exception.getMessage().contains("not from a known registry"))
                .isTrue();
        assertFalse(lasService.isStarted());
    }

    @Test
    void shouldNotStartLasServiceSinceClientError() throws Exception {
        when(dockerService.getClient(REGISTRY_NAME, REGISTRY_USERNAME, REGISTRY_PASSWORD))
                .thenReturn(null);

        Assertions.assertThat(lasService.start()).isFalse();
        assertFalse(lasService.isStarted());
    }

    @Test
    void shouldNotStartLasServiceSinceCannotPullImage() {
        when(dockerClientInstanceMock.pullImage(IMAGE_URI)).thenReturn(false);

        Assertions.assertThat(lasService.start()).isFalse();
        assertFalse(lasService.isStarted());
    }

    @Test
    void shouldNotStartLasServiceSinceCannotRunDockerContainer() {
        when(dockerClientInstanceMock.pullImage(IMAGE_URI)).thenReturn(true);
        when(dockerService.run(any()))
                .thenReturn(DockerRunResponse.builder().finalStatus(DockerRunFinalStatus.FAILED).build());

        Assertions.assertThat(lasService.start()).isFalse();
        assertFalse(lasService.isStarted());
    }
    // endregion

    // region stopAndRemoveContainer
    @Test
    void shouldStopAndRemoveContainer() {
        when(lasService.isStarted()).thenReturn(true).thenCallRealMethod();
        when(dockerClientInstanceMock.stopAndRemoveContainer(CONTAINER_NAME)).thenReturn(true);
        when(dockerClientInstanceMock.isContainerPresent(CONTAINER_NAME)).thenReturn(false);

        assertTrue(lasService.stopAndRemoveContainer());
        assertFalse(lasService.isStarted());

        verify(dockerClientInstanceMock).stopAndRemoveContainer(CONTAINER_NAME);
        verify(dockerClientInstanceMock).isContainerPresent(CONTAINER_NAME);
    }

    @Test
    void shouldNotStopSinceNotStarted() {
        when(lasService.isStarted()).thenReturn(false);

        assertTrue(lasService.stopAndRemoveContainer());
        assertFalse(lasService.isStarted());

        verify(dockerClientInstanceMock, times(0)).stopAndRemoveContainer(CONTAINER_NAME);
        verify(dockerClientInstanceMock, times(0)).isContainerPresent(CONTAINER_NAME);
    }

    @Test
    void shouldFailTotStopAndRemoveContainer() {
        when(lasService.isStarted()).thenReturn(true).thenCallRealMethod();
        when(dockerClientInstanceMock.stopAndRemoveContainer(CONTAINER_NAME)).thenReturn(false);
        when(dockerClientInstanceMock.isContainerPresent(CONTAINER_NAME)).thenReturn(true);

        assertFalse(lasService.stopAndRemoveContainer());
        assertTrue(lasService.isStarted());

        verify(dockerClientInstanceMock).stopAndRemoveContainer(CONTAINER_NAME);
        verify(dockerClientInstanceMock).isContainerPresent(CONTAINER_NAME);
    }
    // endregion
}