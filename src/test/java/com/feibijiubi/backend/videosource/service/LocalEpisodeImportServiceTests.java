package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.infrastructure.storage.service.FileStorageService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Category;
import com.feibijiubi.backend.video.entity.UploadTempFile;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.mapper.CategoryMapper;
import com.feibijiubi.backend.video.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeImportMapper;
import com.feibijiubi.backend.videosource.service.impl.LocalEpisodeImportServiceImpl;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeImportVO;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M5 service tests. No sockets, no database, no COS: the transaction manager is a test double,
 * so these tests prove transaction boundaries / rollback requests and cleanup decisions, NOT
 * real MySQL rollback, real row locks or real COS atomicity.
 */
class LocalEpisodeImportServiceTests {

    static final String TEMP_KEY = "temp/covers/7/20250101/cover.png";
    static final String FORMAL_KEY = "covers/7/20250101/formal.png";
    static final String COVER_URL = "https://cos.example.invalid/" + FORMAL_KEY;

    final LocalEpisodeImportMapper importMapper = mock(LocalEpisodeImportMapper.class);
    final VideoMapper videoMapper = mock(VideoMapper.class);
    final VideoStatusMapper videoStatusMapper = mock(VideoStatusMapper.class);
    final UploadTempFileMapper uploadTempFileMapper = mock(UploadTempFileMapper.class);
    final CategoryMapper categoryMapper = mock(CategoryMapper.class);
    final FileStorageService storage = mock(FileStorageService.class);
    final TestTransactions tx = new TestTransactions();
    final LocalVideoEpisode episode = new LocalVideoEpisode();
    final Video existingVideo = new Video();
    final UploadTempFile tempCover = new UploadTempFile();
    final ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
    LocalEpisodeImportService service;

    @BeforeEach
    void setup() {
        properties.setPreviewEnabled(true);
        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(true);
        source.setCode("demo");
        source.setName("Demo");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(Set.of("api.example.invalid:443"));
        source.setMediaAllowedEndpoints(Set.of());
        properties.setSources(List.of(source));
        service = newService();

        episode.setId(1L);
        episode.setSeriesId(2L);
        episode.setDurationSeconds(12.5);
        episode.setVid(null);
        when(importMapper.lockEpisodeForImport(1)).thenReturn(episode);
        when(importMapper.selectSourceRevision(1)).thenReturn(0L);
        when(importMapper.selectConfirmedSourceCodes(1)).thenReturn(List.of("demo"));
        when(categoryMapper.selectByMcIdAndScId("movie", "action")).thenReturn(category());

        tempCover.setId(1);
        tempCover.setUid(7);
        tempCover.setFileType((byte) 2);
        tempCover.setObjectKey(TEMP_KEY);
        tempCover.setStatus((byte) 0);
        tempCover.setExpireAt(LocalDateTime.now().plusDays(1));
        when(uploadTempFileMapper.selectByObjectKey(TEMP_KEY)).thenReturn(tempCover);

        when(storage.buildFormalCoverKey(7, TEMP_KEY)).thenReturn(FORMAL_KEY);
        when(storage.Key2Url(FORMAL_KEY)).thenReturn(COVER_URL);
        when(storage.objectExists(TEMP_KEY)).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        doAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storage).copyObject(TEMP_KEY, FORMAL_KEY);
        when(videoMapper.insert(any())).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            ((Video) i.getArgument(0)).setVid(55);
            return 1;
        });
        when(videoStatusMapper.insertDefault(55)).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        when(importMapper.bindVidAndImportRevision(1, 55, 0L)).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            episode.setVid(55);
            episode.setImportRevision(0L);
            return 1;
        });
        when(importMapper.claimTempCover(eq(TEMP_KEY), eq(7), any(LocalDateTime.class))).thenAnswer(i -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        existingVideo.setVid(55);
        existingVideo.setStatus((byte) 0);
        existingVideo.setDuration(12.5);
    }

    LocalEpisodeImportService newService() {
        return new LocalEpisodeImportServiceImpl(importMapper, videoMapper, videoStatusMapper,
                uploadTempFileMapper, categoryMapper, storage,
                new ExternalVideoSourceRegistry(properties), tx);
    }

    static Category category() {
        Category category = new Category();
        category.setMcId("movie");
        category.setScId("action");
        return category;
    }

    LocalEpisodeImportDTO request() {
        return new LocalEpisodeImportDTO(0L, TEMP_KEY, "  导入标题  ", "movie", "action", null);
    }

    void code(int code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(BusinessException.class, call).getCode());
    }

    @Test
    void missingLoginAndInvalidIdsAreRejectedBeforeAnyDatabaseOrCos() {
        code(401, () -> service.importEpisode(null, 1, request()));
        code(401, () -> service.importEpisode(0, 1, request()));
        code(400, () -> service.importEpisode(7, 0, request()));
        verifyNoInteractions(importMapper, videoMapper, videoStatusMapper, uploadTempFileMapper,
                categoryMapper, storage);
    }

    @Test
    void invalidRequestIsRejectedBeforeAnyDatabaseOrCos() {
        code(400, () -> service.importEpisode(7, 1, null));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(null, TEMP_KEY, "t", "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(-1L, TEMP_KEY, "t", "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, " ", "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, "x".repeat(81), "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, "t", " ", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, "t", "movie", "x".repeat(21), null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, "t", "movie", "action", 2)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, "temp/covers/8/cover.png", "t", "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, "temp/covers/7/../8/cover.png", "t", "movie", "action", null)));
        code(400, () -> service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, "x".repeat(501), "t", "movie", "action", null)));
        verifyNoInteractions(importMapper, videoMapper, videoStatusMapper, uploadTempFileMapper,
                categoryMapper, storage);
    }

    @Test
    void reusedBindingReturnsOriginalVidBeforeAnyCosOrWrite() {
        episode.setVid(55);
        episode.setImportRevision(3L);
        when(videoMapper.selectByVid(55)).thenReturn(existingVideo);

        LocalEpisodeImportVO result = service.importEpisode(7, 1, request());

        assertTrue(result.reused());
        assertEquals(1L, result.episodeId());
        assertEquals(55, result.vid());
        assertEquals(12.5, result.durationSeconds());
        assertEquals(3L, result.importRevision());
        assertEquals(0, result.status());
        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
        verify(videoStatusMapper, never()).insertDefault(anyInt());
        verify(importMapper, never()).bindVidAndImportRevision(anyLong(), anyInt(), anyLong());
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
        verifyNoInteractions(uploadTempFileMapper);
        verify(importMapper, never()).selectSourceRevision(anyLong());
        verify(importMapper, never()).selectConfirmedSourceCodes(anyLong());
        assertEquals(1, tx.commits);
        assertEquals(0, tx.rollbacks);
    }

    @Test
    void softDeletedBindingIsFixed409AndIsNotResurrected() {
        episode.setVid(55);
        existingVideo.setDeletedAt(LocalDateTime.now());
        when(videoMapper.selectByVid(55)).thenReturn(existingVideo);

        code(409, () -> service.importEpisode(7, 1, request()));

        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
        verify(importMapper, never()).bindVidAndImportRevision(anyLong(), anyInt(), anyLong());
        assertEquals(0, tx.commits);
        assertEquals(1, tx.rollbacks);
    }

    @Test
    void boundEpisodeWithMissingOrInvalidVideoIs500BeforeCos() {
        episode.setVid(55);
        when(videoMapper.selectByVid(55)).thenReturn(null);
        code(500, () -> service.importEpisode(7, 1, request()));

        when(videoMapper.selectByVid(55)).thenReturn(existingVideo);
        episode.setDurationSeconds(null);
        existingVideo.setDuration(null);
        code(500, () -> service.importEpisode(7, 1, request()));
        verifyNoInteractions(storage);
    }

    @Test
    void firstImportCreatesPendingVideoAndClaimsCoverInsideTransactions() {
        doAnswer(i -> {
            assertEquals(2, tx.commits, "临时对象只能在认领事务提交后删除");
            return null;
        }).when(storage).deleteObject(TEMP_KEY);

        LocalEpisodeImportVO result = service.importEpisode(7, 1, request());

        assertFalse(result.reused());
        assertEquals(1L, result.episodeId());
        assertEquals(55, result.vid());
        assertEquals(12.5, result.durationSeconds());
        assertEquals(0L, result.importRevision());
        assertEquals(0, result.status());

        ArgumentCaptor<Video> captor = ArgumentCaptor.forClass(Video.class);
        verify(videoMapper).insert(captor.capture());
        Video written = captor.getValue();
        assertEquals(7, written.getUid());
        assertEquals("导入标题", written.getTitle());
        assertEquals(2, written.getSourceType());
        assertEquals((byte) 1, written.getVisibility(), "默认私密");
        assertEquals(12.5, written.getDuration());
        assertEquals("movie", written.getMcId());
        assertEquals("action", written.getScId());
        assertEquals(FORMAL_KEY, written.getCoverKey());
        assertEquals(COVER_URL, written.getCoverUrl());
        assertNull(written.getVideoUrl());
        assertNull(written.getVideoKey());
        assertEquals((byte) 0, written.getStatus());

        verify(storage).objectExists(TEMP_KEY);
        verify(storage).copyObject(TEMP_KEY, FORMAL_KEY);
        verify(storage).deleteObject(TEMP_KEY);
        verify(storage, never()).deleteObject(FORMAL_KEY);
        verify(videoStatusMapper).insertDefault(55);
        verify(importMapper).bindVidAndImportRevision(1, 55, 0L);
        verify(importMapper).claimTempCover(eq(TEMP_KEY), eq(7), any(LocalDateTime.class));
        assertEquals(2, tx.commits);
        assertEquals(0, tx.rollbacks);
    }

    @Test
    void previewToggleDoesNotBlockImportOfAnEnabledConfirmedSource() {
        properties.setPreviewEnabled(false);
        service = newService();

        LocalEpisodeImportVO result = service.importEpisode(7, 1, request());

        assertFalse(result.reused());
        assertEquals(55, result.vid());
    }

    @Test
    void staleRevisionStopsBeforeCos() {
        when(importMapper.selectSourceRevision(1)).thenReturn(1L);

        code(409, () -> service.importEpisode(7, 1, request()));

        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
        assertEquals(1, tx.rollbacks);
    }

    @Test
    void unconfirmedOrDisabledSourceStopsBeforeCos() {
        when(importMapper.selectConfirmedSourceCodes(1)).thenReturn(List.of());
        code(409, () -> service.importEpisode(7, 1, request()));
        verifyNoInteractions(storage);

        when(importMapper.selectConfirmedSourceCodes(1)).thenReturn(List.of("demo"));
        properties.getSources().get(0).setEnabled(false);
        service = newService();
        code(409, () -> service.importEpisode(7, 1, request()));
        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
    }

    @Test
    void oneEnabledSourceIsEnoughWhenAnotherConfirmedSourceIsDisabled() {
        when(importMapper.selectConfirmedSourceCodes(1)).thenReturn(List.of("removed", "demo"));

        LocalEpisodeImportVO result = service.importEpisode(7, 1, request());

        assertFalse(result.reused());
        assertEquals(55, result.vid());
    }

    @Test
    void durationMustBeConfirmedFiniteAndPositive() {
        for (Double invalid : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0}) {
            episode.setDurationSeconds(invalid);
            code(409, () -> service.importEpisode(7, 1, request()));
        }
        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
    }

    @Test
    void invalidCategoryStopsBeforeCos() {
        when(categoryMapper.selectByMcIdAndScId("movie", "action")).thenReturn(null);

        code(400, () -> service.importEpisode(7, 1, request()));

        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
    }

    @Test
    void importStoresCanonicalCategoryIdsFromTheDatabase() {
        when(categoryMapper.selectByMcIdAndScId("MOVIE", "ACTION")).thenReturn(category());

        service.importEpisode(7, 1,
                new LocalEpisodeImportDTO(0L, TEMP_KEY, "标题", "MOVIE", "ACTION", null));

        ArgumentCaptor<Video> captor = ArgumentCaptor.forClass(Video.class);
        verify(videoMapper).insert(captor.capture());
        assertEquals("movie", captor.getValue().getMcId());
        assertEquals("action", captor.getValue().getScId());
    }

    @Test
    void tempCoverMustExistBelongToActorHaveCoverTypeBeFreshAndUnclaimed() {
        tempCover.setUid(8);
        code(403, () -> service.importEpisode(7, 1, request()));
        tempCover.setUid(7);

        tempCover.setFileType((byte) 1);
        code(400, () -> service.importEpisode(7, 1, request()));
        tempCover.setFileType((byte) 2);

        tempCover.setExpireAt(LocalDateTime.now().minusSeconds(1));
        code(400, () -> service.importEpisode(7, 1, request()));
        tempCover.setExpireAt(LocalDateTime.now().plusDays(1));

        tempCover.setStatus((byte) 1);
        code(409, () -> service.importEpisode(7, 1, request()));
        tempCover.setStatus((byte) 2);
        code(409, () -> service.importEpisode(7, 1, request()));

        when(uploadTempFileMapper.selectByObjectKey(TEMP_KEY)).thenReturn(null);
        code(400, () -> service.importEpisode(7, 1, request()));

        verifyNoInteractions(storage);
        verify(videoMapper, never()).insert(any());
    }

    @Test
    void missingCosObjectIsRejectedWithoutWrites() {
        when(storage.objectExists(TEMP_KEY)).thenReturn(false);

        code(400, () -> service.importEpisode(7, 1, request()));

        verify(storage, never()).copyObject(anyString(), anyString());
        verify(videoMapper, never()).insert(any());
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
    }

    @Test
    void copyFailureCleansTheFreshCopyAndNeverStartsTheWriteTransaction() {
        doThrow(new BusinessException(500, "复制文件失败"))
                .when(storage).copyObject(TEMP_KEY, FORMAL_KEY);

        code(500, () -> service.importEpisode(7, 1, request()));

        verify(storage).deleteObject(FORMAL_KEY);
        verify(storage, never()).deleteObject(TEMP_KEY);
        verify(videoMapper, never()).insert(any());
        verify(importMapper, never()).bindVidAndImportRevision(anyLong(), anyInt(), anyLong());
        assertEquals(1, tx.commits, "只有预检事务提交");
    }

    @Test
    void objectExistsFailureDoesNotDeleteAnything() {
        doThrow(new BusinessException(500, "检查文件失败")).when(storage).objectExists(TEMP_KEY);

        code(500, () -> service.importEpisode(7, 1, request()));

        verify(storage, never()).deleteObject(anyString());
        verify(videoMapper, never()).insert(any());
    }

    @Test
    void finalRevisionChangeRollsBackAndDeletesTheUnreferencedCopy() {
        when(importMapper.selectSourceRevision(1)).thenReturn(0L, 1L);

        code(409, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(storage, never()).deleteObject(TEMP_KEY);
        verify(videoMapper, never()).insert(any());
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
    }

    @Test
    void concurrentBindingDuringCoverCopyReturnsReusedAndDeletesThisRequestsCopy() {
        LocalVideoEpisode bound = new LocalVideoEpisode();
        bound.setId(1L);
        bound.setVid(55);
        bound.setImportRevision(0L);
        when(importMapper.lockEpisodeForImport(1)).thenReturn(episode, bound);
        when(videoMapper.selectByVid(55)).thenReturn(existingVideo);

        LocalEpisodeImportVO result = service.importEpisode(7, 1, request());

        assertTrue(result.reused());
        assertEquals(55, result.vid());
        verify(storage).deleteObject(FORMAL_KEY);
        verify(storage, never()).deleteObject(TEMP_KEY);
        verify(videoMapper, never()).insert(any());
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
        assertEquals(2, tx.commits);
    }

    // Override thenAnswer stubs without invoking their transaction assertions during setup.
    private static int writeResult(int rows) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        return rows;
    }

    @Test
    void videoInsertFailureRollsBackAndDeletesTheCopy() {
        doAnswer(i -> writeResult(0)).when(videoMapper).insert(any());

        code(500, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(storage, never()).deleteObject(TEMP_KEY);
        verify(videoStatusMapper, never()).insertDefault(anyInt());
        verify(importMapper, never()).bindVidAndImportRevision(anyLong(), anyInt(), anyLong());
    }

    @Test
    void generatedVidMissingRollsBackAndDeletesTheCopy() {
        doAnswer(i -> writeResult(1)).when(videoMapper).insert(any());

        code(500, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(videoStatusMapper, never()).insertDefault(anyInt());
    }

    @Test
    void videoStatusFailureRollsBackAndDeletesTheCopy() {
        doAnswer(i -> writeResult(0)).when(videoStatusMapper).insertDefault(55);

        code(500, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(importMapper, never()).bindVidAndImportRevision(anyLong(), anyInt(), anyLong());
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
    }

    @Test
    void bindCasFailureRollsBackAndDeletesTheCopy() {
        doAnswer(i -> writeResult(0)).when(importMapper).bindVidAndImportRevision(1, 55, 0L);

        code(500, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(importMapper, never()).claimTempCover(anyString(), anyInt(), any());
    }

    @Test
    void coverClaimCasFailureIs409RollsBackAndDeletesTheCopy() {
        doAnswer(i -> writeResult(0)).when(importMapper)
                .claimTempCover(eq(TEMP_KEY), eq(7), any(LocalDateTime.class));

        code(409, () -> service.importEpisode(7, 1, request()));

        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
        verify(storage, never()).deleteObject(TEMP_KEY);
    }

    @Test
    void unknownCommitResultKeepsTheFormalCopy() {
        tx.failCommitAttempt = 2;

        code(500, () -> service.importEpisode(7, 1, request()));

        verify(storage, never()).deleteObject(anyString());
        assertEquals(1, tx.commits, "预检提交成功，最终提交结果未知");
        assertEquals(0, tx.rollbacks);
    }

    @Test
    void sqlFailureRollsBackAndDeletesTheCopyWithoutLeakingKeys() {
        doThrow(new DataAccessResourceFailureException("jdbc " + TEMP_KEY))
                .when(importMapper).bindVidAndImportRevision(1, 55, 0L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.importEpisode(7, 1, request()));

        assertEquals(500, error.getCode());
        assertFalse(error.getMessage().contains(TEMP_KEY));
        assertFalse(error.getMessage().contains(FORMAL_KEY));
        assertEquals(1, tx.rollbacks);
        verify(storage).deleteObject(FORMAL_KEY);
    }

    @Test
    void errorMessagesDoNotContainCoverKeysOrUrls() {
        doAnswer(i -> writeResult(0)).when(importMapper)
                .claimTempCover(eq(TEMP_KEY), eq(7), any(LocalDateTime.class));
        BusinessException claimed = assertThrows(BusinessException.class,
                () -> service.importEpisode(7, 1, request()));
        assertFalse(claimed.getMessage().contains(TEMP_KEY));
        assertFalse(claimed.getMessage().contains(FORMAL_KEY));
        assertFalse(claimed.getMessage().contains(COVER_URL));
    }

    @Test
    void refusesEnclosingTransactionBeforeAnyWork() {
        new TransactionTemplate(tx).execute(status -> {
            assertThrows(IllegalStateException.class, () -> service.importEpisode(7, 1, request()));
            return null;
        });
        verifyNoInteractions(importMapper, videoMapper, videoStatusMapper, uploadTempFileMapper,
                categoryMapper, storage);
    }

    /** Test double: proves boundary/rollback requests, not a real database. */
    static final class TestTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        int commitAttempts;
        int failCommitAttempt = -1;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitAttempts++;
            if (commitAttempts == failCommitAttempt) {
                throw new TransactionSystemException("commit result unknown");
            }
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
