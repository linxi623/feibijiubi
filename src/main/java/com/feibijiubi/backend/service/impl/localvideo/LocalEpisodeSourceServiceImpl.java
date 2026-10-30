package com.feibijiubi.backend.service.impl.localvideo;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.dto.LocalEpisodeSourceCaptureDTO;
import com.feibijiubi.backend.dto.LocalEpisodeSourceConfirmDTO;
import com.feibijiubi.backend.entity.LocalEpisodeMediaCandidate;
import com.feibijiubi.backend.entity.LocalEpisodeSourceBinding;
import com.feibijiubi.backend.entity.LocalVideoEpisode;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsDetailSnapshot;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsJsonAdapter;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsLineEntry;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsPlayLine;
import com.feibijiubi.backend.integration.videosource.media.HlsManifestParseException;
import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException;
import com.feibijiubi.backend.integration.videosource.transport.RestrictedCmsHttpClient;
import com.feibijiubi.backend.mapper.LocalEpisodeSourceMapper;
import com.feibijiubi.backend.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.service.localvideo.LocalEpisodeSourceService;
import com.feibijiubi.backend.service.video.external.ExternalMediaProbe;
import com.feibijiubi.backend.service.video.external.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.service.video.external.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.service.video.external.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.service.video.external.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.vo.LocalEpisodeSourceVO;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Synchronous network outside transactions; all mutations serialize on the local episode row. */
@Service
public class LocalEpisodeSourceServiceImpl implements LocalEpisodeSourceService {
    private final LocalEpisodeSourceMapper mapper;
    private final LocalVideoCatalogMapper catalog;
    private final ExternalVideoSourceRegistry registry;
    private final ExternalVideoSourceClientProvider clients;
    private final ExternalMediaProbe probe;
    private final ExternalVideoSourcePreviewQuotaService quota;
    private final Semaphore permits;
    private final TransactionTemplate transaction;

    public LocalEpisodeSourceServiceImpl(LocalEpisodeSourceMapper mapper, LocalVideoCatalogMapper catalog,
            ExternalVideoSourceRegistry registry, ExternalVideoSourceClientProvider clients,
            ExternalMediaProbe probe, ExternalVideoSourcePreviewQuotaService quota,
            ExternalVideoSourceProperties properties, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.catalog = catalog;
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
        inTransaction(() -> {
            requireDraft(catalog.selectEpisodeById(episodeId));
            requireRevision(episodeId, request.expectedRevision());
            requireTarget(episodeId, request.targetBindingId(), source.code(), request.workId());
            return null;
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
            requireDraft(mapper.lockEpisode(episodeId));
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
            LocalVideoEpisode episode = requireDraft(mapper.lockEpisode(episodeId));
            requireRevision(episodeId, request.expectedRevision());
            LocalEpisodeMediaCandidate candidate = requireCandidate(episodeId, candidateId);
            if (!Objects.equals(candidate.getExpectedRevision(), request.expectedRevision())
                    || candidate.getConfirmedAt() != null) throw stale();
            ExternalVideoSourceDescriptor source = requireSource(candidate.getSourceCode());
            validateAddress(source, candidate.getAddress());
            if (!sha256(candidate.getAddress()).equals(candidate.getAddressHash())) throw persistenceFailure();
            requireTarget(episodeId, candidate.getTargetBindingId(), candidate.getSourceCode(), candidate.getWorkId());
            // Adding another source must not silently change the episode's already confirmed timeline.
            if (episode.getDurationSeconds() != null
                    && Double.compare(episode.getDurationSeconds(), request.durationSeconds()) != 0) {
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
            if (episode.getDurationSeconds() == null) requireOne(mapper.setDuration(episodeId, request.durationSeconds()));
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
        c.setAddressHash(sha256(entry.address()));
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

    private static LocalVideoEpisode requireDraft(LocalVideoEpisode episode) {
        requireEpisode(episode);
        if (episode.getVid() != null) throw new BusinessException(409, "已导入剧集不能通过草稿接口变更来源或时长");
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
