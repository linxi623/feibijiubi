package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.vo.VideoEpisodePageVO;
import com.feibijiubi.backend.videosource.vo.VideoPlaybackVO;

/**
 * M6-A 公开只读播放/选集。实现必须保持 GET 纯读：不联网、不计播放、不写 Redis/MySQL。
 */
public interface VideoPlaybackService {

    VideoPlaybackVO playback(Integer currentUserId, Integer vid, String lineId);

    VideoEpisodePageVO episodes(Integer currentUserId, Integer vid, String cursor, String size);
}
