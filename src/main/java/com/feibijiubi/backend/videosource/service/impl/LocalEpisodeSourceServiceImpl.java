package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeSourceCaptureDTO;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeSourceConfirmDTO;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeMediaCandidate;
import com.feibijiubi.backend.videosource.entity.LocalEpisodeSourceBinding;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsDetailSnapshot;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsJsonAdapter;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsLineEntry;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsPlayLine;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.integration.media.HlsManifestParseException;
import com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedCmsHttpClient;
import com.feibijiubi.backend.videosource.mapper.LocalEpisodeSourceMapper;
import com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.videosource.service.LocalEpisodeSourceService;
import com.feibijiubi.backend.videosource.service.ExternalMediaProbe;
import com.feibijiubi.backend.videosource.service.ExternalPlaybackLineResolver;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeSourceVO;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * S2 草稿来源确认与 M6-B 已导入剧集加线/换线。
 *
 * <p>抓取网络严格在事务外；候选只入库。确认在同一短事务内先锁 {@code local_video_episode}，
 * 已导入时再按固定顺序锁原 {@code video}，不跨网络持锁、不反向锁、不写 video/时长/统计。</p>
 */
@Service
public class LocalEpisodeSourceServiceImpl implements LocalEpisodeSourceService {
    private final LocalEpisodeSourceMapper mapper;
    private final LocalVideoCatalogMapper catalog;
    private final VideoMapper videoMapper;
    private final ExternalVideoSourceRegistry registry;
    private final ExternalVideoSourceClientProvider clients;
    private final ExternalMediaProbe probe;
    private final ExternalVideoSourcePreviewQuotaService quota;
    private final Semaphore permits;
    private final TransactionTemplate transaction;

    public LocalEpisodeSourceServiceImpl(LocalEpisodeSourceMapper mapper, LocalVideoCatalogMapper catalog,
            VideoMapper videoMapper, ExternalVideoSourceRegistry registry, ExternalVideoSourceClientProvider clients,
            ExternalMediaProbe probe, ExternalVideoSourcePreviewQuotaService quota,
            ExternalVideoSourceProperties properties, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.videoMapper = videoMapper;
        this.registry = registry;
        this.clients = clients;
        this.probe = probe;
        this.quota = quota;
        // Separate from preview's semaphore; both spend the SAME per-admin Redis quota.
        this.permits = new Semaphore(properties.getMaxConcurrentRequests());
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public LocalEpisodeSourceVO.Candidate capture(Integer adminId, long episodeId,
                                                  LocalEpisodeSourceCaptureDTO request) {
        outsideTransaction();
        requireAdmin(adminId);
        requireId(episodeId);
        if (request == null || request.workId() == null
                || !request.workId().matches("[1-9][0-9]{0," + (RestrictedCmsHttpClient.MAX_WORK_ID_LENGTH - 1) + "}")
                || request.lineOrdinal() == null || request.lineOrdinal() < 1 || request.lineOrdinal() > 16
                || request.entryOrdinal() == null || request.entryOrdinal() < 1 || request.entryOrdinal() > 1000
                || request.expectedRevision() == null || request.expectedRevision() < 0
                || (request.targetBindingId() != null && request.targetBindingId() <= 0)) {
            throw new BusinessException(400, "来源选择参数不合法");
        }
        ExternalVideoSourceDescriptor source = requireSource(request.sourceCode());
        // Consistent preflight read ends before any network IO. Rechecked under lock after IO.
        Integer preflightVid = inTransaction(() -> {
            LocalVideoEpisode episode = requireEpisode(catalog.selectEpisodeById(episodeId));
            if (episode.getVid() != null) requireImportedContext(episode);
            requireRevision(episodeId, request.expectedRevision());
            requireTarget(episodeId, request.targetBindingId(), source.code(), request.workId());
            return episode.getVid();
        });
        if (!permits.tryAcquire()) throw new BusinessException(429, "来源探测并发已达上限");
        LocalEpisodeMediaCandidate candidate;
        try {
            quota.checkAndConsume(adminId);
            candidate = fetchCandidate(source, episodeId, request, adminId);
        } finally {
            permits.release();
        }
        return inTransaction(() -> {
            LocalVideoEpisode episode = requireEpisode(mapper.lockEpisode(episodeId));
            requireSameVid(preflightVid, episode);
            if (episode.getVid() != null) lockImportedContext(episode);
            requireRevision(episodeId, request.expectedRevision());
            requireTarget(episodeId, request.targetBindingId(), source.code(), request.workId());
            requireOne(mapper.insertCandidate(candidate));
            if (candidate.getId() == null || candidate.getId() <= 0) throw persistenceFailure();
            return LocalEpisodeSourceVO.Candidate.from(requireCandidate(episodeId, candidate.getId()));
        });
    }

    @Override
    public LocalEpisodeSourceVO confirm(Integer adminId, long episodeId, long candidateId,
                                        LocalEpisodeSourceConfirmDTO request) {
        outsideTransaction();
        requireAdmin(adminId);
        requireId(episodeId);
        requireId(candidateId);
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0
                || request.expectedRevision() == Long.MAX_VALUE || request.durationSeconds() == null
                || !Double.isFinite(request.durationSeconds()) || request.durationSeconds() <= 0
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw new BusinessException(400, "请提交有效版本、有限正时长和1..500字符的确认理由");
        }
        return inTransaction(() -> {
            LocalVideoEpisode episode = requireEpisode(mapper.lockEpisode(episodeId));
            // M6-B：已导入剧集先按固定顺序锁原 video，再完成版本/候选/来源/目标核对；不写 video、不改时长。
            ImportedEpisode imported = episode.getVid() == null ? null : lockImportedContext(episode);
            requireRevision(episodeId, request.expectedRevision());
            LocalEpisodeMediaCandidate candidate = requireCandidate(episodeId, candidateId);
            if (!Objects.equals(candidate.getExpectedRevision(), request.expectedRevision())
                    || candidate.getConfirmedAt() != null) throw stale();
            ExternalVideoSourceDescriptor source = requireSource(candidate.getSourceCode());
            validateAddress(source, candidate.getAddress());
            if (!ExternalMediaAddressDigest.sha256Hex(candidate.getAddress()).equals(candidate.getAddressHash())) {
                throw persistenceFailure();
            }
            requireTarget(episodeId, candidate.getTargetBindingId(), candidate.getSourceCode(), candidate.getWorkId());
            if (imported != null) {
                requireImportedCandidate(candidate, request.durationSeconds(), imported.durationSeconds());
            } else if (episode.getDurationSeconds() != null
                    && Double.compare(episode.getDurationSeconds(), request.durationSeconds()) != 0) {
                // 草稿阶段保持原行为：新增来源不得静默改变已确认的统一时长。
                throw new BusinessException(409, "已有统一时长，新来源必须确认同一时长；本接口不改写时间轴");
            }
            mapper.initializeState(episodeId);
            requireOne(mapper.advanceRevision(episodeId, request.expectedRevision()));
            requireOne(mapper.confirmCandidate(episodeId, candidateId, request.durationSeconds(), adminId, request.reason()));
            if (candidate.getTargetBindingId() == null) {
                LocalEpisodeSourceBinding binding = new LocalEpisodeSourceBinding();
                binding.setEpisodeId(episodeId);
                binding.setCandidateId(candidateId);
                requireOne(mapper.insertBinding(binding));
                if (binding.getId() == null || binding.getId() <= 0) throw persistenceFailure();
            } else {
                requireOne(mapper.replaceBinding(episodeId, candidate.getTargetBindingId(), candidateId));
            }
            if (imported == null && episode.getDurationSeconds() == null) {
                requireOne(mapper.setDuration(episodeId, request.durationSeconds()));
            }
            return readCurrent(episodeId);
        });
    }

    @Override
    public LocalEpisodeSourceVO current(long episodeId) {
        outsideTransaction();
        requireId(episodeId);
        return inTransaction(() -> readCurrent(episodeId));
    }

    @Override
    public List<LocalEpisodeSourceVO.Candidate> candidates(long episodeId, long afterId, int limit) {
        outsideTransaction();
        requireId(episodeId);
        if (afterId < 0 || limit < 1 || limit > 100) throw new BusinessException(400, "分页参数不合法");
        return inTransaction(() -> {
            requireEpisode(catalog.selectEpisodeById(episodeId));
            return mapper.selectCandidates(episodeId, afterId, limit).stream()
                    .map(LocalEpisodeSourceVO.Candidate::from).toList();
        });
    }

    private LocalEpisodeMediaCandidate fetchCandidate(ExternalVideoSourceDescriptor source, long episodeId,
            LocalEpisodeSourceCaptureDTO request, int adminId) {
        AppleCmsDetailSnapshot snapshot;
        try {
            snapshot = clients.createDetailClient(source).fetchWork(request.workId());
        } catch (RuntimeException e) {
            // No upstream exception/cause escapes to the global logger.
            throw new BusinessException(502, "SOURCE_DETAIL_FAILED：来源详情获取失败");
        }
        if (snapshot == null || !request.workId().equals(snapshot.workIdentity())) {
            throw new BusinessException(502, "SOURCE_DETAIL_MISMATCH：来源详情不匹配");
        }
        AppleCmsPlayLine line = snapshot.playLines().stream()
                .filter(l -> l.snapshotOrdinal() == request.lineOrdinal()).findFirst()
                .orElseThrow(() -> new BusinessException(409, "线路位置已变化，请重新预览"));
        AppleCmsLineEntry entry = line.entries().stream()
                .filter(e -> e.snapshotOrdinal() == request.entryOrdinal()).findFirst()
                .orElseThrow(() -> new BusinessException(409, "条目位置已变化，请重新预览"));
        if (entry.episodeLabel().length() > AppleCmsJsonAdapter.MAX_EPISODE_LABEL_LENGTH) {
            throw new BusinessException(502, "SOURCE_ENTRY_INVALID：来源条目不合法");
        }
        validateAddress(source, entry.address());
        LocalEpisodeMediaCandidate c = new LocalEpisodeMediaCandidate();
        c.setEpisodeId(episodeId);
        c.setExpectedRevision(request.expectedRevision());
        c.setTargetBindingId(request.targetBindingId());
        c.setSourceCode(source.code());
        c.setWorkId(request.workId());
        c.setLineOrdinal(request.lineOrdinal());
        c.setEntryOrdinal(request.entryOrdinal());
        c.setLabel(entry.episodeLabel());
        c.setAddress(entry.address());
        c.setAddressHash(ExternalMediaAddressDigest.sha256Hex(entry.address()));
        c.setCapturedBy(adminId);
        try {
            var result = probe.probe(source, entry.address());
            c.setCandidateSeconds(result.durationSeconds().doubleValue());
            c.setManifestHash(result.manifestSha256());
            c.setProbeStatus("SUCCEEDED");
        } catch (HlsManifestParseException e) {
            c.setProbeStatus("UNSUPPORTED_MANIFEST");
        } catch (CmsHttpRequestException e) {
            c.setProbeStatus("FETCH_FAILED");
        } catch (IllegalArgumentException e) {
            c.setProbeStatus("PROBE_REJECTED");
        } catch (RuntimeException e) {
            throw new BusinessException(502, "MEDIA_PROBE_FAILED：探测失败，未保存候选");
        }
        return c;
    }

    private LocalEpisodeSourceVO readCurrent(long episodeId) {
        LocalVideoEpisode episode = requireEpisode(catalog.selectEpisodeById(episodeId));
        List<LocalEpisodeSourceBinding> bindings = mapper.selectBindings(episodeId);
        if (bindings.size() > 32) throw persistenceFailure();
        return new LocalEpisodeSourceVO(revision(episodeId), episode.getDurationSeconds(), bindings.stream()
                .map(b -> new LocalEpisodeSourceVO.Binding(b.getId(),
                        LocalEpisodeSourceVO.Candidate.from(requireCandidate(episodeId, b.getCandidateId())))).toList());
    }

    private void requireTarget(long episodeId, Long bindingId, String sourceCode, String workId) {
        List<LocalEpisodeSourceBinding> bindings = mapper.selectBindings(episodeId);
        if (bindings.size() > 32) throw persistenceFailure();
        if (bindingId == null) {
            if (bindings.size() == 32) throw new BusinessException(409, "单剧集最多32条来源关联");
            return;
        }
        LocalEpisodeSourceBinding binding = bindings.stream().filter(b -> bindingId.equals(b.getId()))
                .findFirst().orElseThrow(() -> new BusinessException(404, "来源关联不存在"));
        LocalEpisodeMediaCandidate existing = requireCandidate(episodeId, binding.getCandidateId());
        if (!sourceCode.equals(existing.getSourceCode()) || !workId.equals(existing.getWorkId())) {
            throw new BusinessException(409, "替换必须保持原来源和作品，其他来源请新建关联");
        }
    }

    private ExternalVideoSourceDescriptor requireSource(String code) {
        if (!registry.previewEnabled()) throw new BusinessException(503, "外部来源功能未启用");
        ExternalVideoSourceDescriptor descriptor;
        try {
            descriptor = registry.requirePreviewSource(code);
        } catch (IllegalStateException e) {
            throw new BusinessException(404, "来源未配置或未启用");
        }
        if (descriptor.mediaPolicy().isEmpty()) throw new BusinessException(503, "来源缺少独立媒体允许列表");
        return descriptor;
    }

    private static void validateAddress(ExternalVideoSourceDescriptor source, String address) {
        try {
            source.mediaPolicy().orElseThrow().validate(address);
        } catch (RuntimeException e) {
            throw new BusinessException(502, "MEDIA_ADDRESS_REJECTED：来源媒体地址不受允许");
        }
    }

    private void requireImportedContext(LocalVideoEpisode episode) {
        validateImportedContext(episode, videoMapper.selectByVid(episode.getVid()));
    }

    private ImportedEpisode lockImportedContext(LocalVideoEpisode episode) {
        // 固定顺序：调用方已持有 episode 行锁，这里才锁原 video；不得在锁内做网络 IO。
        return validateImportedContext(episode, mapper.lockVideoForUpdate(episode.getVid()));
    }

    private static ImportedEpisode validateImportedContext(LocalVideoEpisode episode, Video video) {
        if (episode.getVid() == null || video == null || !episode.getVid().equals(video.getVid())) {
            throw new BusinessException(500, "IMPORTED_VIDEO_DATA_FAILED：导入视频数据异常");
        }
        if (video.getDeletedAt() != null) {
            throw new BusinessException(409, "已导入视频已删除，不能变更播放线路");
        }
        if (video.getVideoUrl() != null || video.getVideoKey() != null) {
            throw new BusinessException(409, "已导入视频不是外部稿件，不能通过来源接口变更线路");
        }
        Double episodeDuration = episode.getDurationSeconds();
        Double videoDuration = video.getDuration();
        if (!positiveFinite(episodeDuration) || !positiveFinite(videoDuration)
                || Double.compare(episodeDuration, videoDuration) != 0) {
            throw new BusinessException(409, "剧集与导入视频的统一时长不一致，不能变更播放线路");
        }
        return new ImportedEpisode(episodeDuration);
    }

    private static void requireImportedCandidate(LocalEpisodeMediaCandidate candidate,
                                                 double confirmedSeconds, double timelineSeconds) {
        if (!ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(candidate.getProbeStatus(),
                candidate.getManifestHash(), candidate.getCandidateSeconds())) {
            throw new BusinessException(409, "已导入剧集只能确认具有成功HLS证据的候选，失败候选不能替换当前线路");
        }
        if (Double.compare(confirmedSeconds, timelineSeconds) != 0) {
            throw new BusinessException(409, "确认时长必须等于剧集与原视频的统一时长");
        }
    }

    private static void requireSameVid(Integer preflightVid, LocalVideoEpisode episode) {
        if (!Objects.equals(preflightVid, episode.getVid())) {
            // 预检后发生首次导入/换绑定时不能按旧状态自动保存候选。
            throw new BusinessException(409, "EPISODE_IMPORT_STATE_CHANGED：剧集导入状态已变化，请刷新后重新选择线路");
        }
    }

    private static boolean positiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private long revision(long episodeId) {
        Long value = mapper.selectRevision(episodeId);
        return value == null ? 0 : value;
    }

    private void requireRevision(long episodeId, long expected) {
        if (revision(episodeId) != expected) throw stale();
    }

    private LocalEpisodeMediaCandidate requireCandidate(long episodeId, long candidateId) {
        LocalEpisodeMediaCandidate c = mapper.selectCandidate(episodeId, candidateId);
        if (c == null) throw new BusinessException(404, "候选不存在");
        return c;
    }

    private <T> T inTransaction(Supplier<T> work) {
        try {
            return transaction.execute(status -> work.get());
        } catch (DataAccessException | TransactionException e) {
            // JDBC errors may include SQL parameters. Do not retain the raw exception or cause.
            throw persistenceFailure();
        }
    }

    private static LocalVideoEpisode requireEpisode(LocalVideoEpisode episode) {
        if (episode == null) throw new BusinessException(404, "本地剧集不存在");
        return episode;
    }

    private static void requireId(long id) {
        if (id <= 0) throw new BusinessException(400, "id必须为正数");
    }

    private static void requireAdmin(Integer id) {
        if (id == null || id <= 0) throw new BusinessException(401, "未登录");
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("来源操作不可在外层事务中调用");
        }
    }

    private static void requireOne(int count) {
        if (count != 1) throw persistenceFailure();
    }

    private static BusinessException stale() {
        return new BusinessException(409, "EPISODE_REVISION_CONFLICT：状态已变化，请查询后重新选择候选");
    }

    private static BusinessException persistenceFailure() {
        return new BusinessException(500, "SOURCE_PERSISTENCE_FAILED：来源记录保存或读取失败");
    }

    /** 已导入剧集的统一时长；仅用于本次确认/候选判定，不写回 video。 */
    private record ImportedEpisode(double durationSeconds) {
    }
}
