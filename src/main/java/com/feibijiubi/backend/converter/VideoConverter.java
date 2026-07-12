package com.feibijiubi.backend.converter;

import com.feibijiubi.backend.entity.UserVideo;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.vo.AllVideoDetailVO;
import com.feibijiubi.backend.vo.VideoDetailVO;
import com.feibijiubi.backend.vo.VideoListItemVO;
import com.feibijiubi.backend.vo.VideoSubmitVO;

import java.util.Collections;
import java.util.List;

public final class VideoConverter {
    private VideoConverter() {
    }
    public static AllVideoDetailVO toAllVideoDetailVO(Video video, VideoStatus videoStatus) {
        AllVideoDetailVO vo = new AllVideoDetailVO();
        vo.setVideo(video);
        vo.setVideoStatus(videoStatus);
        return vo;
    }

    public static VideoSubmitVO toVideoSubmitVO(Video video) {
        if (video == null) {
            return null;
        }

        VideoSubmitVO vo = new VideoSubmitVO();
        vo.setVid(video.getVid());
        vo.setTitle(video.getTitle());
        vo.setCoverUrl(video.getCoverUrl());
        vo.setVideoUrl(video.getVideoUrl());
        return vo;
    }

    public static VideoListItemVO toVideoListItemVO(Video video, VideoStatus videoStatus) {
        if (video == null) {
            return null;
        }

        VideoListItemVO vo = new VideoListItemVO();
        vo.setVid(video.getVid());
        vo.setUid(video.getUid());
        vo.setTitle(video.getTitle());
        vo.setCoverUrl(video.getCoverUrl());
        vo.setDuration(video.getDuration());
        vo.setCreatedAt(video.getCreatedAt());

        if (videoStatus != null) {
            vo.setPlayTimes(videoStatus.getPlayTimes());
            vo.setCommentTimes(videoStatus.getCommentTimes());
        }
        return vo;
    }

    public static List<VideoListItemVO> toVideoListItemVOList(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return Collections.emptyList();
        }

        return videos.stream()
                .map(video -> toVideoListItemVO(video, null))
                .toList();
    }

    public static VideoDetailVO toVideoDetailVO(Video video,
                                                VideoStatus videoStatus,
                                                UserVideo userVideo) {
        if (video == null) {
            return null;
        }

        VideoDetailVO vo = new VideoDetailVO();
        vo.setVid(video.getVid());
        vo.setUid(video.getUid());
        vo.setTitle(video.getTitle());
        vo.setSourceType(video.getSourceType());
        vo.setDuration(video.getDuration());
        vo.setMcId(video.getMcId());
        vo.setScId(video.getScId());
        vo.setTags(video.getTags());
        vo.setDescription(video.getDescription());
        vo.setCoverUrl(video.getCoverUrl());
        vo.setVideoUrl(video.getVideoUrl());
        vo.setCreatedAt(video.getCreatedAt());

        setVideoStatus(vo, videoStatus);
        setUserVideoStatus(vo, userVideo);
        return vo;
    }

    private static void setVideoStatus(VideoDetailVO vo, VideoStatus videoStatus) {
        if (videoStatus == null) {
            return;
        }

        vo.setPlayTimes(videoStatus.getPlayTimes());
        vo.setLikeTimes(videoStatus.getLikeTimes());
        vo.setCoinTimes(videoStatus.getCoinTimes());
        vo.setCollectTimes(videoStatus.getCollectTimes());
        vo.setCommentTimes(videoStatus.getCommentTimes());
        vo.setDanmuTimes(videoStatus.getDanmuTimes());
        vo.setShareTimes(videoStatus.getShareTimes());
    }

    private static void setUserVideoStatus(VideoDetailVO vo, UserVideo userVideo) {
        if (userVideo == null) {
            vo.setLiked(false);
            vo.setCoin((byte) 0);
            vo.setCollected(false);
            vo.setPlayTime(0D);
            return;
        }

        vo.setLiked(Boolean.TRUE.equals(userVideo.getLiked()));
        vo.setCoin(userVideo.getCoin() == null ? (byte) 0 : userVideo.getCoin());
        vo.setCollected(Boolean.TRUE.equals(userVideo.getCollect()));
        vo.setPlayTime(userVideo.getPlayTime() == null ? 0D : userVideo.getPlayTime());
    }
}
