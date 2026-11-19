package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper;
import com.feibijiubi.backend.videosource.service.ExternalPlaybackLineResolver;
import com.feibijiubi.backend.videosource.service.VideoPlaybackService;
import com.feibijiubi.backend.videosource.vo.VideoEpisodePageVO;
import com.feibijiubi.backend.videosource.vo.VideoPlaybackVO;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * M6-A 播放/选集的只读实现。
 *
 * <h2>与公开详情一致的可见性</h2>
 * <p>先用 {@link VideoMapper#selectPublishedByVid(Integer)}：不存在、未过审、已软删都得到同一个 404；
 * 私密且不是作者本人得到 403。这里<b>不</b>检查管理员角色，因此管理员在公共侧没有绕过。
 * 不使用 {@code PublishedVideoGuard}：它没有私密可见性判定，且其 500“时长数据异常”不属于只读播放语义；
 * 六个互动入口仍只走该 guard，本类不改变进度/统计契约。</p>
 *
 * <h2>纯读</h2>
 * <p>没有 {@code @Transactional}、Redis、配额、并发许可、网络客户端或探测组件。线路/选集查询都是
 * 单条有界 SELECT；线路最多 33 行（32 条 + 溢出探测），选集 {@code limit+1} 行且按本地 episode.id
 * keyset 分页。没有全量计数，也不跨剧集读取。</p>
 *
 * <h2>旧 COS</h2>
 * <p>旧 COS 视频没有 {@code local_video_episode} 绑定：播放返回 200 {@code playable=false /
 * NOT_BOUND}，选集返回空页；<b>不</b>回退到 {@code video.video_url}，也不改写该列。旧详情接口仍按原样
 * 返回 {@code videoUrl}。</p>
 */
@Service
public class VideoPlaybackServiceImpl implements VideoPlaybackService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_CURSOR_LENGTH = 19;

    private final VideoMapper videoMapper;
    private final VideoPlaybackMapper playbackMapper;
    private final ExternalPlaybackLineResolver lineResolver;

    public VideoPlaybackServiceImpl(VideoMapper videoMapper,
                                    VideoPlaybackMapper playbackMapper,
                                    ExternalPlaybackLineResolver lineResolver) {
        this.videoMapper = videoMapper;
        this.playbackMapper = playbackMapper;
        this.lineResolver = lineResolver;
    }

    @Override
    public VideoPlaybackVO playback(Integer currentUserId, Integer vid, String lineId) {
        requirePositiveVid(vid);
        Long requestedLineId = parseLineId(lineId);
        Video video = requireVisibleVideo(currentUserId, vid);

        ExternalPlaybackLineResolver.Resolution resolution = lineResolver.resolve(video, requestedLineId);
        List<VideoPlaybackVO.Line> lines = resolution.getLines().stream()
                .map(line -> new VideoPlaybackVO.Line(line.getLineId(), line.getLabel(), line.isAvailable()))
                .toList();
        ExternalPlaybackLineResolver.ResolvedLine selected = resolution.getSelected();
        return new VideoPlaybackVO(
                video.getVid(),
                resolution.isPlayable(),
                resolution.getReason(),
                selected == null ? null : "HLS",
                selected == null ? null : selected.getAddress(),
                selected == null ? null : Long.valueOf(selected.getLineId()),
                selected == null ? null : selected.getLabel(),
                lines);
    }

    @Override
    public VideoEpisodePageVO episodes(Integer currentUserId, Integer vid, String cursor, String size) {
        requirePositiveVid(vid);
        long afterId = parseCursor(cursor);
        int pageSize = parsePageSize(size);
        Video video = requireVisibleVideo(currentUserId, vid);

        LocalVideoEpisode current = loadEpisode(video.getVid());
        if (current == null || current.getSeriesId() == null) {
            return new VideoEpisodePageVO(List.of(), false, null);
        }
        List<LocalVideoEpisode> rows;
        try {
            rows = playbackMapper.selectVisibleEpisodesAfter(
                    current.getSeriesId(), afterId, currentUserId, pageSize + 1);
        } catch (DataAccessException e) {
            throw dataFailure();
        }

        boolean hasMore = rows.size() > pageSize;
        int itemCount = Math.min(rows.size(), pageSize);
        List<VideoEpisodePageVO.Item> items = rows.subList(0, itemCount).stream()
                .map(row -> new VideoEpisodePageVO.Item(row.getVid(), row.getDisplayTitle()))
                .toList();
        String nextCursor = hasMore ? String.valueOf(rows.get(itemCount - 1).getId()) : null;
        return new VideoEpisodePageVO(items, hasMore, nextCursor);
    }

    private Video requireVisibleVideo(Integer currentUserId, Integer vid) {
        Video video;
        try {
            video = videoMapper.selectPublishedByVid(vid);
        } catch (DataAccessException e) {
            throw dataFailure();
        }
        if (video == null) {
            throw new BusinessException(404, "视频不存在");
        }
        if (Objects.equals(video.getVisibility(), (byte) 1)
                && !Objects.equals(video.getUid(), currentUserId)) {
            throw new BusinessException(403, "你无权查看此视频");
        }
        return video;
    }

    private LocalVideoEpisode loadEpisode(int vid) {
        try {
            return playbackMapper.selectEpisodeByVid(vid);
        } catch (DataAccessException e) {
            throw dataFailure();
        }
    }

    private static void requirePositiveVid(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }
    }

    /** 缺省 null；只接受 1..19 位十进制正整数，非法/溢出统一 400 播放线路不合法。 */
    private static Long parseLineId(String text) {
        if (text == null) {
            return null;
        }
        long value = parsePositiveDecimal(text, "播放线路不合法");
        return value;
    }

    /** 缺省 20；只接受 1..50，非法/越界统一 400 分页参数不合法。 */
    private static int parsePageSize(String text) {
        if (text == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (text.isEmpty() || text.length() > 2 || text.charAt(0) == '0') {
            throw pagingFailure();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw pagingFailure();
            }
        }
        int value = Integer.parseInt(text);
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw pagingFailure();
        }
        return value;
    }

    /** 缺省 0；游标是本地 episode.id 的十进制文本，非法统一 400 分页参数不合法。 */
    private static long parseCursor(String text) {
        if (text == null) {
            return 0L;
        }
        if (text.isEmpty() || text.length() > MAX_CURSOR_LENGTH) {
            throw pagingFailure();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw pagingFailure();
            }
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            throw pagingFailure();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw pagingFailure();
        }
    }

    private static long parsePositiveDecimal(String text, String message) {
        if (text.isEmpty() || text.length() > MAX_CURSOR_LENGTH) {
            throw new BusinessException(400, message);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new BusinessException(400, message);
            }
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            throw new BusinessException(400, message);
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, message);
        }
        if (value <= 0) {
            throw new BusinessException(400, message);
        }
        return value;
    }

    private static BusinessException pagingFailure() {
        return new BusinessException(400, "分页参数不合法");
    }

    private static BusinessException dataFailure() {
        // 固定对外文案，不保留可能含 SQL 参数/结果的原始异常。
        return new BusinessException(500, "PLAYBACK_QUERY_FAILED：播放数据读取失败");
    }
}
