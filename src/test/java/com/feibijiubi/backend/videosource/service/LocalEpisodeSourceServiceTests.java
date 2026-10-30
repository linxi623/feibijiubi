package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.dto.*;
import com.feibijiubi.backend.videosource.entity.*;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.videosource.integration.applecms.*;
import com.feibijiubi.backend.videosource.integration.media.*;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper;
import com.feibijiubi.backend.videosource.service.impl.LocalEpisodeSourceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No sockets/DB. Transaction-manager test double proves boundary/rollback requests, NOT MySQL rollback. */
class LocalEpisodeSourceServiceTests {
    static final String URL = "https://media.example.invalid/episode.m3u8?token=private-test";
    final LocalEpisodeSourceMapper mapper = mock(LocalEpisodeSourceMapper.class);
    final LocalVideoCatalogMapper catalog = mock(LocalVideoCatalogMapper.class);
    final ExternalVideoSourceClientProvider clients = mock(ExternalVideoSourceClientProvider.class);
    final ExternalVideoSourceDetailClient detail = mock(ExternalVideoSourceDetailClient.class);
    final ExternalMediaProbe probe = mock(ExternalMediaProbe.class);
    final ExternalVideoSourcePreviewQuotaService quota = mock(ExternalVideoSourcePreviewQuotaService.class);
    final TestTransactions tx = new TestTransactions();
    final LocalVideoEpisode episode = new LocalVideoEpisode();
    final ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
    LocalEpisodeSourceService service;
    LocalEpisodeMediaCandidate candidate;

    @BeforeEach void setup() throws Exception {
        properties.setPreviewEnabled(true);
        properties.setMaxConcurrentRequests(1);
        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(true);
        source.setCode("demo");
        source.setName("Demo");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(Set.of("api.example.invalid:443"));
        source.setMediaAllowedEndpoints(Set.of("media.example.invalid:443"));
        properties.setSources(List.of(source));
        service = new LocalEpisodeSourceServiceImpl(mapper, catalog, new ExternalVideoSourceRegistry(properties),
                clients, probe, quota, properties, tx);
        episode.setId(1L);
        when(catalog.selectEpisodeById(1)).thenReturn(episode);
        when(mapper.lockEpisode(1)).thenReturn(episode);
        when(mapper.selectBindings(1)).thenReturn(List.of());
        when(clients.createDetailClient(any())).thenReturn(detail);
        var policy = new ExternalVideoSourceRegistry(properties).requirePreviewSource("demo").mediaPolicy().orElseThrow();
        when(detail.fetchWork("7")).thenReturn(new AppleCmsJsonAdapter(policy).parseDetail(("""
                {"code":1,"list":[{"vod_id":7,"vod_name":"Demo","type_id":1,
                "vod_play_from":"hls","vod_play_url":"Episode 1$%s"}]}
                """).formatted(URL).getBytes(StandardCharsets.UTF_8)));
        when(probe.probe(any(), eq(URL))).thenReturn(new HlsMediaDurationCandidate(new BigDecimal("12.5"), 2, "a".repeat(64)));
        candidate = new LocalEpisodeMediaCandidate();
        candidate.setId(9L); candidate.setEpisodeId(1L); candidate.setExpectedRevision(0L);
        candidate.setSourceCode("demo"); candidate.setWorkId("7"); candidate.setLineOrdinal(1); candidate.setEntryOrdinal(1);
        candidate.setLabel("Episode 1"); candidate.setAddress(URL);
        candidate.setAddressHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(URL.getBytes(StandardCharsets.UTF_8))));
        candidate.setProbeStatus("SUCCEEDED"); candidate.setCandidateSeconds(12.5);
        when(mapper.selectCandidate(1, 9)).thenAnswer(i -> candidate);
        when(mapper.insertCandidate(any())).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            candidate = i.getArgument(0); candidate.setId(9L); candidate.setCapturedAt(LocalDateTime.now()); return 1;
        });
        when(mapper.advanceRevision(1, 0)).thenReturn(1);
        when(mapper.confirmCandidate(eq(1L), eq(9L), anyDouble(), eq(7), anyString())).thenReturn(1);
        when(mapper.insertBinding(any())).thenAnswer(i -> { ((LocalEpisodeSourceBinding)i.getArgument(0)).setId(3L); return 1; });
        when(mapper.setDuration(1, 12.5)).thenReturn(1);
    }

    LocalEpisodeSourceCaptureDTO captureRequest() { return new LocalEpisodeSourceCaptureDTO("demo", "7", 1, 1, 0L, null); }
    LocalEpisodeSourceConfirmDTO confirmRequest() { return new LocalEpisodeSourceConfirmDTO(0L, 12.5, "人工核对同一集"); }
    void code(int code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(BusinessException.class, call).getCode());
    }

    @Test void captureNetworksOutsideTransactionsAndPersistsOnlyServerEvidence() {
        var snapshot = detail.fetchWork("7");
        clearInvocations(detail);
        when(detail.fetchWork("7")).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verify(quota).checkAndConsume(7);
            return snapshot;
        });
        when(probe.probe(any(), anyString())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new HlsMediaDurationCandidate(new BigDecimal("12.5"), 2, "a".repeat(64));
        });
        var result = service.capture(7, 1, captureRequest());
        assertEquals(9, result.id()); assertEquals("SUCCEEDED", result.probeStatus());
        assertEquals(12.5, result.candidateSeconds()); assertFalse(result.toString().contains("private-test"));
        assertEquals(URL, candidate.getAddress()); assertEquals(7, candidate.getCapturedBy());
        assertEquals("a".repeat(64), candidate.getManifestHash());
        assertFalse(candidate.toString().contains(URL));
        assertEquals(2, tx.commits);
        verify(mapper, never()).setDuration(anyLong(), anyDouble());
    }

    @Test void invalidInputRejectedBeforeQuotaAndDb() {
        code(400, () -> service.capture(7, 1, new LocalEpisodeSourceCaptureDTO("demo", " 7", 1, 1, 0L, null)));
        verifyNoInteractions(mapper, catalog, clients, quota, probe);
    }
    @Test void missingLoginRejected() {
        code(401, () -> service.capture(null, 1, captureRequest()));
        verifyNoInteractions(mapper, catalog, clients, quota);
    }
    @Test void disabledByDefaultRejectsBeforeDb() {
        service = new LocalEpisodeSourceServiceImpl(mapper, catalog, new ExternalVideoSourceRegistry(new ExternalVideoSourceProperties()),
                clients, probe, quota, properties, tx);
        code(503, () -> service.capture(7, 1, captureRequest()));
        verifyNoInteractions(mapper, catalog, clients, quota);
    }
    @Test void unknownSourceRejected() {
        code(404, () -> service.capture(7, 1, new LocalEpisodeSourceCaptureDTO("missing", "7", 1, 1, 0L, null)));
        verifyNoInteractions(mapper, quota, clients);
    }
    @Test void stalePreflightDoesNotSpendQuota() {
        when(mapper.selectRevision(1)).thenReturn(1L);
        code(409, () -> service.capture(7, 1, captureRequest())); verifyNoInteractions(quota, clients);
    }
    @Test void stateChangedDuringNetworkDiscardsCandidate() {
        when(mapper.selectRevision(1)).thenReturn(0L, 1L);
        code(409, () -> service.capture(7, 1, captureRequest()));
        verify(mapper, never()).insertCandidate(any()); assertEquals(1, tx.rollbacks);
    }
    @Test void importedEpisodeRefusesCaptureAndConfirm() {
        episode.setVid(55);
        code(409, () -> service.capture(7, 1, captureRequest()));
        code(409, () -> service.confirm(7, 1, 9, confirmRequest())); verifyNoInteractions(clients, quota);
    }
    @Test void missingEpisodeIs404() {
        when(catalog.selectEpisodeById(1)).thenReturn(null);
        code(404, () -> service.capture(7, 1, captureRequest())); verifyNoInteractions(clients, quota);
    }
    @Test void unsupportedManifestStoresExplicitFailureNotFalseZero() {
        when(probe.probe(any(), anyString())).thenAnswer(i -> HlsMediaDurationParser.parse("bad".getBytes(StandardCharsets.UTF_8)));
        var result = service.capture(7, 1, captureRequest());
        assertEquals("UNSUPPORTED_MANIFEST", result.probeStatus()); assertNull(result.candidateSeconds()); assertNull(candidate.getManifestHash());
    }
    @Test void upstreamFailureRedactedAndPermitReleased() {
        when(detail.fetchWork("7")).thenThrow(new IllegalStateException(URL));
        for (int i = 0; i < 2; i++) {
            var error = assertThrows(BusinessException.class, () -> service.capture(7, 1, captureRequest()));
            assertEquals(502, error.getCode()); assertNull(error.getCause()); assertFalse(error.getMessage().contains(URL));
        }
        verify(mapper, never()).insertCandidate(any()); verify(quota, times(2)).checkAndConsume(7);
    }
    @Test void quotaFailureReleasesPermitAndNeverFetches() {
        doThrow(new BusinessException(429, "quota")).doNothing().when(quota).checkAndConsume(7);
        code(429, () -> service.capture(7, 1, captureRequest()));
        service.capture(7, 1, captureRequest()); verify(detail, times(1)).fetchWork("7");
    }
    @Test void concurrentCaptureRejectedWithoutSecondQuota() {
        AtomicBoolean checked = new AtomicBoolean();
        doAnswer(i -> {
            code(429, () -> service.capture(7, 1, captureRequest())); checked.set(true); return null;
        }).when(quota).checkAndConsume(7);
        service.capture(7, 1, captureRequest()); assertTrue(checked.get()); verify(quota, times(1)).checkAndConsume(7);
    }
    @Test void confirmationWritesAllPartsInsideOneTransaction() {
        when(mapper.confirmCandidate(1, 9, 12.5, 7, "人工核对同一集")).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return 1;
        });
        when(mapper.selectRevision(1)).thenReturn(0L, 1L);
        doAnswer(i -> {
            var binding = (LocalEpisodeSourceBinding) i.getArgument(0);
            binding.setId(3L);
            when(mapper.selectBindings(1)).thenReturn(List.of(binding));
            return 1;
        }).when(mapper).insertBinding(any());
        when(mapper.setDuration(1, 12.5)).thenAnswer(i -> { episode.setDurationSeconds(12.5); return 1; });
        var result = service.confirm(7, 1, 9, confirmRequest());
        assertEquals(1, result.revision()); assertEquals(12.5, result.durationSeconds());
        assertEquals(3, result.bindings().get(0).id()); assertEquals(9, result.bindings().get(0).candidate().id());
        var order = inOrder(mapper);
        order.verify(mapper).lockEpisode(1); order.verify(mapper).selectRevision(1);
        order.verify(mapper).selectCandidate(1, 9); order.verify(mapper).selectBindings(1);
        order.verify(mapper).initializeState(1); order.verify(mapper).advanceRevision(1, 0);
        order.verify(mapper).confirmCandidate(1, 9, 12.5, 7, "人工核对同一集");
        order.verify(mapper).insertBinding(any()); order.verify(mapper).setDuration(1, 12.5);
        assertEquals(1, tx.commits); verifyNoInteractions(clients, probe, quota);
    }
    @Test void manualDurationAllowedWhenProbeFailedWithReason() {
        candidate.setProbeStatus("UNSUPPORTED_MANIFEST"); candidate.setCandidateSeconds(null);
        service.confirm(7, 1, 9, confirmRequest()); verify(mapper).confirmCandidate(1, 9, 12.5, 7, "人工核对同一集");
    }
    @Test void confirmationRejectsStaleCandidateEvenWithCurrentRequestVersion() {
        candidate.setExpectedRevision(1L);
        code(409, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).advanceRevision(anyLong(), anyLong());
    }
    @Test void confirmationIsNotTransparentReplay() {
        candidate.setConfirmedAt(LocalDateTime.now());
        code(409, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).insertBinding(any());
    }
    @Test void candidateScopedToEpisode() {
        when(mapper.selectCandidate(1, 9)).thenReturn(null);
        code(404, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).insertBinding(any());
    }
    @Test void cannotChangeAlreadyConfirmedTimeline() {
        episode.setDurationSeconds(20.0);
        code(409, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).advanceRevision(anyLong(), anyLong());
    }
    @Test void sameDurationAdditionalSourceDoesNotRewriteEpisode() {
        episode.setDurationSeconds(12.5);
        service.confirm(7, 1, 9, confirmRequest()); verify(mapper, never()).setDuration(anyLong(), anyDouble());
    }
    @Test void invalidDurationOrReasonRejectedBeforeDb() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1}) {
            code(400, () -> service.confirm(7, 1, 9, new LocalEpisodeSourceConfirmDTO(0L, value, "reason")));
        }
        code(400, () -> service.confirm(7, 1, 9, new LocalEpisodeSourceConfirmDTO(0L, 12.5, " ")));
        verifyNoInteractions(mapper, catalog);
    }
    @Test void corruptAddressHashRejectedWithoutWrites() {
        candidate.setAddressHash("0".repeat(64));
        code(500, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).initializeState(anyLong());
    }
    @Test void sqlFailureRedactedAndRollbackRequested() {
        doThrow(new DataAccessResourceFailureException(URL)).when(mapper).insertBinding(any());
        var error = assertThrows(BusinessException.class, () -> service.confirm(7, 1, 9, confirmRequest()));
        assertEquals(500, error.getCode()); assertNull(error.getCause()); assertFalse(error.getMessage().contains(URL));
        assertEquals(1, tx.rollbacks); assertEquals(0, tx.commits); verify(mapper, never()).setDuration(anyLong(), anyDouble());
    }
    @Test void zeroWriteCountRequestsRollback() {
        when(mapper.advanceRevision(1, 0)).thenReturn(0);
        code(500, () -> service.confirm(7, 1, 9, confirmRequest()));
        assertEquals(1, tx.rollbacks); verify(mapper, never()).confirmCandidate(anyLong(), anyLong(), anyDouble(), anyInt(), anyString());
    }
    @Test void refusesEnclosingTransactionBeforeNetwork() {
        new TransactionTemplate(tx).execute(s -> {
            assertThrows(IllegalStateException.class, () -> service.capture(7, 1, captureRequest())); return null;
        }); verifyNoInteractions(mapper, clients, quota);
    }
    @Test void replacementUsesExplicitBindingAndKeepsOldEvidence() {
        var binding = new LocalEpisodeSourceBinding(); binding.setId(3L); binding.setEpisodeId(1L); binding.setCandidateId(8L);
        var old = new LocalEpisodeMediaCandidate(); old.setSourceCode("demo"); old.setWorkId("7");
        when(mapper.selectBindings(1)).thenReturn(List.of(binding)); when(mapper.selectCandidate(1, 8)).thenReturn(old);
        candidate.setTargetBindingId(3L);
        when(mapper.replaceBinding(1, 3, 9)).thenAnswer(i -> { binding.setCandidateId(9L); return 1; });
        service.confirm(7, 1, 9, confirmRequest());
        verify(mapper).replaceBinding(1, 3, 9); verify(mapper, never()).insertBinding(any()); assertNull(old.getConfirmedAt());
    }
    @Test void listsAreBoundedAndDoNotNetwork() {
        when(mapper.selectCandidates(1, 0, 20)).thenReturn(List.of(candidate));
        assertEquals(1, service.candidates(1, 0, 20).size());
        code(400, () -> service.candidates(1, 0, 101)); verifyNoInteractions(clients, probe, quota);
    }

    @Test void noMediaPolicyRefusesBeforeQuotaOrDatabase() {
        properties.getSources().get(0).setMediaAllowedEndpoints(Set.of());
        service = new LocalEpisodeSourceServiceImpl(mapper, catalog, new ExternalVideoSourceRegistry(properties),
                clients, probe, quota, properties, tx);
        code(503, () -> service.capture(7, 1, captureRequest())); verifyNoInteractions(mapper, catalog, quota, clients);
    }
    @Test void newBindingsHaveHardCapAndNoNetworkAfterRejection() {
        when(mapper.selectBindings(1)).thenReturn(Collections.nCopies(32, new LocalEpisodeSourceBinding()));
        code(409, () -> service.capture(7, 1, captureRequest())); verifyNoInteractions(clients, quota);
    }
    @Test void missingReplacementTargetRejectedBeforeNetwork() {
        code(404, () -> service.capture(7, 1, new LocalEpisodeSourceCaptureDTO("demo", "7", 1, 1, 0L, 44L)));
        verifyNoInteractions(clients, quota);
    }
    @Test void replacementCannotChangeSourceWorkScope() {
        var b = new LocalEpisodeSourceBinding(); b.setId(3L); b.setCandidateId(8L);
        var old = new LocalEpisodeMediaCandidate(); old.setSourceCode("demo"); old.setWorkId("8");
        when(mapper.selectBindings(1)).thenReturn(List.of(b)); when(mapper.selectCandidate(1, 8)).thenReturn(old);
        code(409, () -> service.capture(7, 1, new LocalEpisodeSourceCaptureDTO("demo", "7", 1, 1, 0L, 3L)));
        verifyNoInteractions(clients, quota);
    }
    @Test void confirmationRevalidatesCurrentMediaAllowlist() {
        candidate.setAddress("https://api.example.invalid/episode.m3u8");
        code(502, () -> service.confirm(7, 1, 9, confirmRequest())); verify(mapper, never()).initializeState(anyLong());
    }
    @Test void generatedKeyFailureRollsBackCapture() {
        doAnswer(i -> 1).when(mapper).insertCandidate(any());
        code(500, () -> service.capture(7, 1, captureRequest())); assertEquals(1, tx.rollbacks);
    }

    static final class TestTransactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus s) { rollbacks++; }
    }
}
