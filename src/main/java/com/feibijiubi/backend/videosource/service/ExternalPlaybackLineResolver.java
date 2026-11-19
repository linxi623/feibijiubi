package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * M6-A：外部线路的<b>唯一资格判定</b>。播放接口与审核闸门都调用这里，避免两套“什么算可播放”的规则。
 *
 * <h2>一条线路可用的条件（全部满足）</h2>
 * <ol>
 *   <li>{@code playback-enabled=true}（默认开启；显式关闭时所有外部线路不可用）；</li>
 *   <li>binding 当前指针所指的候选已人工确认（{@code confirmed_at/confirmed_seconds} 非空）；</li>
 *   <li>候选有 HLS 探测成功证据：{@code probe_status=SUCCEEDED}、{@code manifest_hash} 为 64 位
 *       小写十六进制、{@code candidate_seconds} 有限且 &gt; 0；失败/未知格式不会被当作 HLS；</li>
 *   <li>确认时长与剧集统一时长、视频统一时长都相等（精确比较，不取近似）；</li>
 *   <li>来源当前存在且启用，来源媒体允许列表重新校验通过；</li>
 *   <li>存储地址重新计算的 SHA-256 与 {@code address_hash} 完全一致（摘要算法与 S2 抓取共用
 *       {@link ExternalMediaAddressDigest}，不改变原始 URL 字节）。</li>
 * </ol>
 *
 * <h2>只读、无网络、不泄漏</h2>
 * <p>本类只读两张本地表并做内存判定：不解析 DNS、不发 HTTP、不探测媒体、不写 Redis/MySQL、不计播放。
 * 线路查询按剧集限定且最多 33 行，超过 32 条固定失败；不做逐线路查询（无 N+1）。返回对象只在内存中
 * 持有 {@code address}，不覆盖 {@code toString()}，服务层只把选中线路的地址放进播放响应。</p>
 */
@Component
public class ExternalPlaybackLineResolver {

    /** 与 S2 的每剧集来源关联上限一致；多出的第 33 行只用于发现溢出。 */
    public static final int MAX_BINDINGS = 32;

    public static final String REASON_OK = "OK";
    public static final String REASON_NOT_BOUND = "NOT_BOUND";
    public static final String REASON_PLAYBACK_DISABLED = "PLAYBACK_DISABLED";
    public static final String REASON_SOURCE_DISABLED = "SOURCE_DISABLED";
    public static final String REASON_NO_PLAYABLE_ADDRESS = "NO_PLAYABLE_ADDRESS";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final String PROBE_SUCCEEDED = "SUCCEEDED";

    private final VideoPlaybackMapper mapper;
    private final ExternalVideoSourceRegistry registry;

    public ExternalPlaybackLineResolver(VideoPlaybackMapper mapper,
                                        ExternalVideoSourceRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    /**
     * 审核闸门专用：是否至少有一条线路通过<b>与播放接口完全相同</b>的资格判定。
     *
     * <p>播放开关关闭时直接返回 false，<b>不查询</b> S2/M5 表：默认部署下外部稿件仍按 M5 规则拒绝，
     * 也不会因为缺少来源表而把审核变成 500。</p>
     */
    public boolean hasAvailableHlsLine(Video video) {
        if (video == null || !registry.playbackEnabled()) {
            return false;
        }
        return resolve(video, null).isPlayable();
    }

    /**
     * 迷你共用谓词：候选是否具备与 M6-A 相同的成功 HLS 证据。
     *
     * <p>只判断探测状态、清单摘要格式和候选秒数，不判断地址、来源、确认时长或人工确认。
     * M6-B 的已导入线路确认与播放/审核资格判定共用这里，避免复制一份会漂移的 HLS 规则。</p>
     */
    public static boolean hasSuccessfulHlsEvidence(String probeStatus, String manifestHash,
                                                   Double candidateSeconds) {
        return PROBE_SUCCEEDED.equals(probeStatus)
                && manifestHash != null && SHA256_HEX.matcher(manifestHash).matches()
                && positiveFinite(candidateSeconds);
    }

    /**
     * 解析当前视频的外部线路。
     *
     * @param video          已通过公开可见性校验的视频（{@code vid}/{@code duration} 已加载）
     * @param requestedLineId 指定的本地 binding id；null 表示按 binding id 取第一条可用线路
     * @throws BusinessException 400 指定线路不属于本视频所绑剧集；500 数据溢出/读取失败
     */
    public Resolution resolve(Video video, Long requestedLineId) {
        if (video == null || video.getVid() == null) {
            throw new BusinessException(400, "视频参数不合法");
        }
        LocalVideoEpisode episode = loadEpisode(video.getVid());
        if (episode == null) {
            if (requestedLineId != null) {
                throw invalidLine();
            }
            return new Resolution(REASON_NOT_BOUND, List.of(), null);
        }
        List<LocalEpisodePlaybackLine> rows = loadLines(episode.getId());
        if (rows.size() > MAX_BINDINGS) {
            throw dataFailure();
        }
        if (rows.isEmpty()) {
            if (requestedLineId != null) {
                throw invalidLine();
            }
            return new Resolution(REASON_NOT_BOUND, List.of(), null);
        }
        if (requestedLineId != null && !containsBinding(rows, requestedLineId)) {
            // 指定线路必须属于当前 vid 所绑剧集；不静默回退到默认线路。
            throw invalidLine();
        }

        boolean playbackEnabled = registry.playbackEnabled();
        boolean anySourceEnabled = false;
        List<ResolvedLine> lines = new ArrayList<>(rows.size());
        for (LocalEpisodePlaybackLine row : rows) {
            ExternalVideoSourceDescriptor source = enabledSource(row.getSourceCode());
            boolean sourceEnabled = source != null;
            anySourceEnabled |= sourceEnabled;
            boolean available = playbackEnabled && sourceEnabled
                    && qualifies(episode, video, row, source);
            lines.add(new ResolvedLine(row.getBindingId(), row.getLabel(), available,
                    sourceEnabled, row.getAddress()));
        }

        if (!playbackEnabled) {
            return new Resolution(REASON_PLAYBACK_DISABLED, lines, null);
        }
        if (requestedLineId != null) {
            ResolvedLine requested = lines.stream()
                    .filter(line -> line.getLineId() == requestedLineId)
                    .findFirst()
                    .orElseThrow(ExternalPlaybackLineResolver::invalidLine);
            if (requested.isAvailable()) {
                return new Resolution(REASON_OK, lines, requested);
            }
            return new Resolution(requested.isSourceEnabled()
                    ? REASON_NO_PLAYABLE_ADDRESS : REASON_SOURCE_DISABLED, lines, null);
        }

        ResolvedLine first = lines.stream()
                .filter(ResolvedLine::isAvailable)
                .findFirst()
                .orElse(null);
        if (first != null) {
            return new Resolution(REASON_OK, lines, first);
        }
        return new Resolution(anySourceEnabled
                ? REASON_NO_PLAYABLE_ADDRESS : REASON_SOURCE_DISABLED, lines, null);
    }

    private boolean qualifies(LocalVideoEpisode episode, Video video,
                              LocalEpisodePlaybackLine row,
                              ExternalVideoSourceDescriptor source) {
        if (row.getConfirmedAt() == null || !positiveFinite(row.getConfirmedSeconds())) {
            return false;
        }
        if (!hasSuccessfulHlsEvidence(row.getProbeStatus(), row.getManifestHash(), row.getCandidateSeconds())) {
            return false;
        }
        if (!positiveFinite(episode.getDurationSeconds()) || !positiveFinite(video.getDuration())) {
            return false;
        }
        if (Double.compare(row.getConfirmedSeconds(), episode.getDurationSeconds()) != 0
                || Double.compare(row.getConfirmedSeconds(), video.getDuration()) != 0) {
            return false;
        }

        String address = row.getAddress();
        if (address == null || address.isEmpty()) {
            return false;
        }
        String addressHash = row.getAddressHash();
        if (addressHash == null || !SHA256_HEX.matcher(addressHash).matches()) {
            return false;
        }
        if (!ExternalMediaAddressDigest.sha256Hex(address).equals(addressHash)) {
            return false;
        }

        Optional<ExternalVideoUrlPolicy> mediaPolicy = source.mediaPolicy();
        if (mediaPolicy.isEmpty()) {
            return false;
        }
        try {
            // 只做纯语法/精确 host:port 允许列表校验；不联网、不改写地址。
            mediaPolicy.get().validate(address);
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    private ExternalVideoSourceDescriptor enabledSource(String sourceCode) {
        try {
            return registry.requireEnabledSource(sourceCode);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean containsBinding(List<LocalEpisodePlaybackLine> rows, long bindingId) {
        return rows.stream().anyMatch(row -> row.getBindingId() != null && row.getBindingId() == bindingId);
    }

    private static boolean positiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }

    private LocalVideoEpisode loadEpisode(int vid) {
        try {
            return mapper.selectEpisodeByVid(vid);
        } catch (DataAccessException e) {
            // JDBC/MyBatis 异常可能携带 SQL 参数或结果；不保留原始异常或 cause。
            throw dataFailure();
        }
    }

    private List<LocalEpisodePlaybackLine> loadLines(long episodeId) {
        try {
            return mapper.selectPlaybackLines(episodeId);
        } catch (DataAccessException e) {
            throw dataFailure();
        }
    }

    private static BusinessException invalidLine() {
        return new BusinessException(400, "播放线路不合法");
    }

    private static BusinessException dataFailure() {
        return new BusinessException(500, "PLAYBACK_QUERY_FAILED：播放数据读取失败");
    }

    /** 一次解析结果；{@code selected} 为空表示不可播放。 */
    public static final class Resolution {
        private final String reason;
        private final List<ResolvedLine> lines;
        private final ResolvedLine selected;

        private Resolution(String reason, List<ResolvedLine> lines, ResolvedLine selected) {
            this.reason = reason;
            this.lines = List.copyOf(lines);
            this.selected = selected;
        }

        public String getReason() {
            return reason;
        }

        public List<ResolvedLine> getLines() {
            return lines;
        }

        public ResolvedLine getSelected() {
            return selected;
        }

        public boolean isPlayable() {
            return selected != null;
        }
    }

    /**
     * 单条线路的解析结果。{@code address} 只在服务端内存中保存；默认 {@code Object.toString()} 不输出
     * 字段，因此日志打印本对象不会带出签名地址。
     */
    public static final class ResolvedLine {
        private final long lineId;
        private final String label;
        private final boolean available;
        private final boolean sourceEnabled;
        private final String address;

        private ResolvedLine(long lineId, String label, boolean available,
                             boolean sourceEnabled, String address) {
            this.lineId = lineId;
            this.label = label;
            this.available = available;
            this.sourceEnabled = sourceEnabled;
            this.address = address;
        }

        public long getLineId() {
            return lineId;
        }

        public String getLabel() {
            return label;
        }

        public boolean isAvailable() {
            return available;
        }

        public boolean isSourceEnabled() {
            return sourceEnabled;
        }

        public String getAddress() {
            return address;
        }
    }
}
