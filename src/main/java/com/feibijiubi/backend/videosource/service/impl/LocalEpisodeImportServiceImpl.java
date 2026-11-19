package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.infrastructure.storage.service.FileStorageService;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Category;
import com.feibijiubi.backend.video.entity.UploadTempFile;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.enums.VideoReviewStatus;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.mapper.CategoryMapper;
import com.feibijiubi.backend.video.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeImportMapper;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.videosource.service.LocalEpisodeImportService;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeImportVO;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * M5 单集导入：锁本地剧集 → 核对 revision / 统一时长 / 已确认且仍启用的来源 → 事务外检查并复制封面
 * → 同一短事务创建 video / video_status、CAS 绑定 vid 与首次导入 revision、CAS 认领封面。
 *
 * <h2>为什么分成两个事务</h2>
 * <p>第一次短事务只做判定：已绑定且未软删立即返回（常规重试在任何 COS I/O 之前结束）；
 * 未导入则校验版本、时长、来源、分类与临时封面记录。COS 的 objectExists / copyObject 发生在
 * 两个事务之间，不持有本地剧集行锁。第二次短事务重新锁同一行、重新核对全部条件后才写入。</p>
 *
 * <h2>封面所有权</h2>
 * <p>复用现有管理员临时封面上传（{@code temp/covers/{uid}/...}）与
 * {@link FileStorageService#buildFormalCoverKey} 的唯一正式 key；不共享对象、不拉取外链。
 * 临时文件行的 {@code status=0 → 1} CAS 是认领点，必须影响 1 行。提交成功后才删除临时对象；
 * 只有确认事务回滚（或复制后尚未进入数据库事务、该 copy 确定无稿件引用）时才补偿删除正式 copy。
 * 提交结果未知时两个对象都不删除：正式 copy 可能已被已提交的 video 引用。</p>
 *
 * <h2>不读取媒体地址</h2>
 * <p>导入只读取已确认来源的 {@code source_code}，不读取候选 address / address_hash / manifest_hash，
 * 也不返回或记录任何播放地址。M5 外部稿件的 {@code video_url/video_key} 保持 NULL。</p>
 */
@Slf4j
@Service
public class LocalEpisodeImportServiceImpl implements LocalEpisodeImportService {

    private static final byte FILE_TYPE_COVER = 2;
    private static final byte TEMP_STATUS_WAIT_SUBMIT = 0;
    private static final int SOURCE_TYPE_REPOST = 2;
    private static final int VISIBILITY_PUBLIC = 0;
    private static final int VISIBILITY_PRIVATE = 1;
    private static final int MAX_SOURCE_BINDINGS = 32;

    private final LocalEpisodeImportMapper importMapper;
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final UploadTempFileMapper uploadTempFileMapper;
    private final CategoryMapper categoryMapper;
    private final FileStorageService fileStorageService;
    private final ExternalVideoSourceRegistry registry;
    private final TransactionTemplate transaction;

    public LocalEpisodeImportServiceImpl(LocalEpisodeImportMapper importMapper,
                                         VideoMapper videoMapper,
                                         VideoStatusMapper videoStatusMapper,
                                         UploadTempFileMapper uploadTempFileMapper,
                                         CategoryMapper categoryMapper,
                                         FileStorageService fileStorageService,
                                         ExternalVideoSourceRegistry registry,
                                         PlatformTransactionManager transactionManager) {
        this.importMapper = importMapper;
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.uploadTempFileMapper = uploadTempFileMapper;
        this.categoryMapper = categoryMapper;
        this.fileStorageService = fileStorageService;
        this.registry = registry;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public LocalEpisodeImportVO importEpisode(Integer adminId, long episodeId,
                                              LocalEpisodeImportDTO request) {
        outsideTransaction();
        int operator = requireAdmin(adminId);
        requireId(episodeId);
        ImportInput input = requireRequest(request, operator);

        // 1) 锁内判定。已绑定且未软删的常规重试在这里直接返回，不触碰 COS。
        LocalEpisodeImportVO reused = inTransaction(() -> preflight(episodeId, input));
        if (reused != null) {
            return reused;
        }

        // 2) 事务外准备封面：检查临时对象并复制到唯一正式 key。失败时若 copy 已发生，
        //    因尚未进入任何数据库事务，可以确定该 copy 没有被稿件引用，允许补偿删除。
        String formalCoverKey = fileStorageService.buildFormalCoverKey(operator, input.tempCoverKey());
        String coverUrl = copyCoverOutsideTransaction(input.tempCoverKey(), formalCoverKey);

        // 3) 短事务重锁、重校验、写入；afterCompletion 按真实提交结果决定清理哪个对象。
        //    若事务根本没有开始（getTransaction 失败或同步未激活），该 copy 确定无稿件引用，
        //    由外层 catch 补偿删除；一旦注册成功，清理只由 afterCompletion 决定。
        CoverCleanup cleanup = new CoverCleanup(formalCoverKey, input.tempCoverKey());
        boolean[] cleanupRegistered = {false};
        try {
            return inTransaction(() -> {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    throw persistenceFailure();
                }
                TransactionSynchronizationManager.registerSynchronization(cleanup);
                cleanupRegistered[0] = true;
                return finalizeImport(episodeId, input, operator, formalCoverKey, coverUrl, cleanup);
            });
        } catch (RuntimeException e) {
            if (!cleanupRegistered[0]) {
                deleteObjectQuietly(formalCoverKey);
            }
            throw e;
        }
    }

    private LocalEpisodeImportVO preflight(long episodeId, ImportInput input) {
        LocalVideoEpisode episode = requireEpisode(importMapper.lockEpisodeForImport(episodeId));
        if (episode.getVid() != null) {
            return reused(episode, requireBoundVideo(episode.getVid()));
        }
        requireRevision(episodeId, input.expectedRevision());
        requireDuration(episode);
        requireConfirmedEnabledSource(episodeId);
        requireCategory(input.mcId(), input.scId());
        requireClaimableTempCover(uploadTempFileMapper.selectByObjectKey(input.tempCoverKey()), input.adminId());
        return null;
    }

    private LocalEpisodeImportVO finalizeImport(long episodeId, ImportInput input, int operator,
                                                String formalCoverKey, String coverUrl,
                                                CoverCleanup cleanup) {
        LocalVideoEpisode episode = requireEpisode(importMapper.lockEpisodeForImport(episodeId));
        if (episode.getVid() != null) {
            // 另一个请求已先提交：返回原绑定；本次 copy 确定未被任何稿件引用，提交后清理。
            return reused(episode, requireBoundVideo(episode.getVid()));
        }
        requireRevision(episodeId, input.expectedRevision());
        double duration = requireDuration(episode);
        requireConfirmedEnabledSource(episodeId);
        Category category = requireCategory(input.mcId(), input.scId());
        requireClaimableTempCover(uploadTempFileMapper.selectByObjectKey(input.tempCoverKey()), operator);

        Video video = buildVideo(operator, input, category, duration, formalCoverKey, coverUrl);
        requireOne(videoMapper.insert(video));
        if (video.getVid() == null || video.getVid() <= 0) {
            throw persistenceFailure();
        }
        requireOne(videoStatusMapper.insertDefault(video.getVid()));
        requireOne(importMapper.bindVidAndImportRevision(episodeId, video.getVid(), input.expectedRevision()));

        // 认领时原子复核所有者、类型、过期时间和状态；失败则 video/status/绑定全部回滚。
        if (importMapper.claimTempCover(input.tempCoverKey(), operator, LocalDateTime.now()) != 1) {
            throw new BusinessException(409, "封面临时文件已不可认领，请重新上传");
        }
        cleanup.claimed = true;

        return new LocalEpisodeImportVO(episodeId, video.getVid(), duration,
                input.expectedRevision(), VideoReviewStatus.PENDING.getCode(), false);
    }

    private Video buildVideo(int operator, ImportInput input, Category category, double duration,
                             String formalCoverKey, String coverUrl) {
        Video video = new Video();
        video.setUid(operator);
        video.setTitle(input.title());
        video.setSourceType(SOURCE_TYPE_REPOST);
        video.setVisibility((byte) input.visibility());
        video.setDuration(duration);
        video.setMcId(category.getMcId());
        video.setScId(category.getScId());
        video.setTags(null);
        video.setDescription(null);
        video.setCoverUrl(coverUrl);
        video.setCoverKey(formalCoverKey);
        // M5 外部稿件没有本站播放对象：不写假 key，也不把外链塞进 video。
        video.setVideoUrl(null);
        video.setVideoKey(null);
        video.setStatus((byte) VideoReviewStatus.PENDING.getCode());
        video.setCreatedAt(LocalDateTime.now());
        return video;
    }

    private String copyCoverOutsideTransaction(String tempCoverKey, String formalCoverKey) {
        boolean copyAttempted = false;
        try {
            if (!fileStorageService.objectExists(tempCoverKey)) {
                throw new BusinessException(400, "封面临时文件不存在");
            }
            copyAttempted = true;
            fileStorageService.copyObject(tempCoverKey, formalCoverKey);
        } catch (BusinessException e) {
            if (copyAttempted) deleteObjectQuietly(formalCoverKey);
            throw e;
        } catch (RuntimeException e) {
            if (copyAttempted) deleteObjectQuietly(formalCoverKey);
            throw new BusinessException(500, "封面文件检查或复制失败");
        }
        try {
            return fileStorageService.Key2Url(formalCoverKey);
        } catch (RuntimeException e) {
            deleteObjectQuietly(formalCoverKey);
            throw new BusinessException(500, "封面地址生成失败");
        }
    }

    private void requireConfirmedEnabledSource(long episodeId) {
        List<String> codes = importMapper.selectConfirmedSourceCodes(episodeId);
        if (codes.size() > MAX_SOURCE_BINDINGS) {
            throw persistenceFailure();
        }
        if (codes.isEmpty()) {
            throw new BusinessException(409, "剧集尚无可导入的已确认来源");
        }
        boolean anyEnabled = false;
        for (String code : new LinkedHashSet<>(codes)) {
            try {
                registry.requireEnabledSource(code);
                anyEnabled = true;
            } catch (IllegalStateException ignored) {
                // 该来源已停用或配置已移除；只要还有另一个已确认来源可用就允许导入。
            }
        }
        if (!anyEnabled) {
            throw new BusinessException(409, "剧集来源均已停用，不能导入");
        }
    }

    private Category requireCategory(String mcId, String scId) {
        Category category = categoryMapper.selectByMcIdAndScId(mcId, scId);
        if (category == null) {
            throw new BusinessException(400, "mcId/scId 不是有效的二级分类组合");
        }
        return category;
    }

    private static UploadTempFile requireClaimableTempCover(UploadTempFile tempFile, int adminId) {
        if (tempFile == null) {
            throw new BusinessException(400, "封面临时文件记录不存在");
        }
        if (!Integer.valueOf(adminId).equals(tempFile.getUid())) {
            throw new BusinessException(403, "封面临时文件不属于当前用户");
        }
        if (tempFile.getFileType() == null || tempFile.getFileType() != FILE_TYPE_COVER) {
            throw new BusinessException(400, "封面临时文件类型不正确");
        }
        if (tempFile.getStatus() == null || tempFile.getStatus() != TEMP_STATUS_WAIT_SUBMIT) {
            throw new BusinessException(409, "封面临时文件已被认领或清理");
        }
        if (tempFile.getExpireAt() == null || !tempFile.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "封面临时文件已过期");
        }
        return tempFile;
    }

    private static double requireDuration(LocalVideoEpisode episode) {
        Double duration = episode.getDurationSeconds();
        if (duration == null || !Double.isFinite(duration) || duration <= 0) {
            throw new BusinessException(409, "剧集尚未确认有限且大于0的统一时长");
        }
        return duration;
    }

    private static LocalEpisodeImportVO reused(LocalVideoEpisode episode, Video video) {
        Double duration = episode.getDurationSeconds() != null
                ? episode.getDurationSeconds()
                : video.getDuration();
        if (duration == null || !Double.isFinite(duration) || duration <= 0) {
            throw new BusinessException(500, "导入视频时长数据异常");
        }
        if (video.getStatus() == null) {
            throw new BusinessException(500, "导入视频状态数据异常");
        }
        return new LocalEpisodeImportVO(episode.getId(), episode.getVid(), duration,
                episode.getImportRevision(), video.getStatus().intValue(), true);
    }

    private Video requireBoundVideo(int vid) {
        Video video = videoMapper.selectByVid(vid);
        if (video == null) {
            throw new BusinessException(500, "导入视频数据异常");
        }
        if (video.getDeletedAt() != null) {
            throw new BusinessException(409, "已导入视频已删除，不能通过导入复活");
        }
        return video;
    }

    private long revision(long episodeId) {
        Long value = importMapper.selectSourceRevision(episodeId);
        return value == null ? 0L : value;
    }

    private void requireRevision(long episodeId, long expected) {
        if (revision(episodeId) != expected) {
            throw new BusinessException(409, "EPISODE_REVISION_CONFLICT：状态已变化，请查询后重试");
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataAccessException | TransactionException e) {
            // JDBC/事务异常可能带 SQL 参数；不保留原始异常或 cause。
            throw persistenceFailure();
        }
    }

    private static LocalVideoEpisode requireEpisode(LocalVideoEpisode episode) {
        if (episode == null) {
            throw new BusinessException(404, "本地剧集不存在");
        }
        return episode;
    }

    private static ImportInput requireRequest(LocalEpisodeImportDTO request, int adminId) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new BusinessException(400, "expectedRevision 必须 >= 0");
        }
        String title = requireTitle(request.title());
        String mcId = requireCategoryId(request.mcId(), "mcId");
        String scId = requireCategoryId(request.scId(), "scId");
        int visibility = request.visibility() == null ? VISIBILITY_PRIVATE : request.visibility();
        if (visibility != VISIBILITY_PUBLIC && visibility != VISIBILITY_PRIVATE) {
            throw new BusinessException(400, "visibility 只能是 0（公开）或 1（私密）");
        }
        String tempCoverKey = requireTempCoverKey(request.tempCoverKey(), adminId);
        return new ImportInput(request.expectedRevision(), tempCoverKey, title, mcId, scId,
                visibility, adminId);
    }

    private static String requireTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "title 不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(400, "title 长度不能超过 " + MAX_TITLE_LENGTH);
        }
        return normalized;
    }

    private static String requireCategoryId(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(400, name + " 不能为空");
        }
        if (normalized.length() > MAX_CATEGORY_ID_LENGTH) {
            throw new BusinessException(400, name + " 长度不能超过 " + MAX_CATEGORY_ID_LENGTH);
        }
        return normalized;
    }

    private static String requireTempCoverKey(String tempCoverKey, int adminId) {
        String normalized = tempCoverKey == null ? "" : tempCoverKey;
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "tempCoverKey 不能为空");
        }
        if (normalized.length() > MAX_TEMP_COVER_KEY_LENGTH) {
            throw new BusinessException(400, "tempCoverKey 长度不能超过 " + MAX_TEMP_COVER_KEY_LENGTH);
        }
        if (normalized.contains("..") || !normalized.startsWith("temp/covers/" + adminId + "/")) {
            throw new BusinessException(400, "封面临时文件路径不合法");
        }
        return normalized;
    }

    private static int requireAdmin(Integer adminId) {
        if (adminId == null || adminId <= 0) {
            throw new BusinessException(401, "未登录");
        }
        return adminId;
    }

    private static void requireId(long id) {
        if (id <= 0) {
            throw new BusinessException(400, "id必须为正数");
        }
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("导入操作不可在外层事务中调用");
        }
    }

    private static void requireOne(int count) {
        if (count != 1) {
            throw persistenceFailure();
        }
    }

    private static BusinessException persistenceFailure() {
        return new BusinessException(500, "IMPORT_PERSISTENCE_FAILED：导入写入或读取失败");
    }

    private void deleteObjectQuietly(String objectKey) {
        try {
            fileStorageService.deleteObject(objectKey);
        } catch (RuntimeException e) {
            // 不打印 key：临时/正式封面 key 不进入日志。
            log.warn("封面补偿清理失败");
        }
    }

    private record ImportInput(long expectedRevision, String tempCoverKey, String title,
                               String mcId, String scId, int visibility, int adminId) {
    }

    /**
     * 提交结果决定清理哪个 COS 对象：
     * <ul>
     *   <li>COMMITTED + claimed：导入已提交，删除临时对象；正式 copy 已被 video 引用，保留。</li>
     *   <li>COMMITTED + !claimed：本次是并发后的复用返回，正式 copy 未被引用，删除。</li>
     *   <li>ROLLED_BACK：数据库回滚已确认，正式 copy 未被引用，删除；临时对象保留。</li>
     *   <li>UNKNOWN：提交结果未知，正式 copy 可能已被已提交稿件引用，两个对象都不删除。</li>
     * </ul>
     */
    private final class CoverCleanup implements TransactionSynchronization {
        private final String formalCoverKey;
        private final String tempCoverKey;
        private boolean claimed;

        private CoverCleanup(String formalCoverKey, String tempCoverKey) {
            this.formalCoverKey = formalCoverKey;
            this.tempCoverKey = tempCoverKey;
        }

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) {
                if (claimed) {
                    deleteObjectQuietly(tempCoverKey);
                } else {
                    deleteObjectQuietly(formalCoverKey);
                }
            } else if (status == STATUS_ROLLED_BACK) {
                deleteObjectQuietly(formalCoverKey);
            }
        }
    }
}
