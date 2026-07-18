package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.entity.UserVideo;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.mapper.UserVideoMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.UserVideoService;
import com.feibijiubi.backend.service.video.VideoStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserVideoServiceImpl implements UserVideoService {
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final UserVideoMapper userVideoMapper;
    private final UserMapper userMapper;

    private final VideoStatusService videoStatusService;
    private final VideoStatusProperties videoStatusProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void increasePlayCount(Integer vid) {
        validateVideo(vid);

        recordStatus(vid, VideoStatusEventType.PLAY, 1,
                () -> {
                    int rows = videoStatusMapper.increasePlayTimes(vid);
                    if(rows != 1) {
                        throw new BusinessException(500, "播放量更新失败");
                    }
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePlayProgress(Integer vid, Integer currentUserId, Double playTime) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (playTime == null || !Double.isFinite(playTime) || playTime < 0) {
            throw new BusinessException(400, "播放进度不合法");
        }

        Video video = validateVideo(vid);
        if (playTime > video.getDuration()) {
            throw new BusinessException(400, "播放进度不能超过视频时长");
        }

        UserVideo userVideo = getOrCreateUserVideo(currentUserId, vid);
        userVideo.setPlayTime(playTime);
        userVideo.setPlayedAt(LocalDateTime.now());

        int userVideoRows = userVideoMapper.updatePlay(userVideo);
        if (userVideoRows != 1) {
            throw new BusinessException(500, "播放进度保存失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordLike(Integer currentUserId, Integer vid, Boolean isLike, Boolean isSet) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (isLike == null || isSet == null) {
            throw new BusinessException(400, "点赞参数不合法");
        }
        validateVideo(vid);

        UserVideo userVideo = getOrCreateUserVideo(currentUserId, vid);

        boolean stateChanged;
        if (isLike) {
            stateChanged = !isSet.equals(userVideo.getLiked());
            if (!stateChanged) {
                return;
            }
            userVideo.setLiked(isSet);
            if (isSet) {
                userVideo.setLikedAt(LocalDateTime.now());
            }
        } else {
            stateChanged = !isSet.equals(userVideo.getUnliked());
            if (!stateChanged) {
                return;
            }
            userVideo.setUnliked(isSet);
        }

        int userVideoRows = userVideoMapper.updateLike(userVideo);
        if (userVideoRows != 1) {
            throw new BusinessException(500, "点赞状态更新失败");
        }

        long delta = isSet ? 1L : -1L;
        VideoStatusEventType type = isLike
                ? VideoStatusEventType.LIKE
                : VideoStatusEventType.UNLIKE;

        recordStatus(vid, type, delta,
                () -> {
                    int rows = isSet
                            ? videoStatusMapper.increaseLikeTimes(vid, isLike)
                            : videoStatusMapper.decreaseLikeTimes(vid, isLike);
                    if (rows != 1) {
                        throw new BusinessException(500, "视频点赞统计更新失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void increaseCoin(Integer currentUserId, Integer vid, Byte coin) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }

        if (coin == null || coin < 1 || coin > 2) {
            throw new BusinessException(400, "单次只能投一个或两个硬币");
        }

        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        Integer coinValue = user.getCoin();
        if (coinValue == null) {
            throw new BusinessException(500, "用户硬币数据异常");
        }
        if (coin > coinValue) {
            throw new BusinessException(400, "硬币数量不够");
        }

        validateVideo(vid);
        UserVideo userVideo = getOrCreateUserVideo(currentUserId, vid);

        if (userVideo.getCoin() != null && userVideo.getCoin() != 0) {
            throw new BusinessException(400, "用户无法对同一个视频多次投币");
        }

        int userRows = userMapper.decreaseCoin(currentUserId, coin);
        if (userRows != 1) {
            throw new BusinessException(500, "视频投币失败");
        }

        userVideo.setCoin(coin);
        userVideo.setCoinedAt(LocalDateTime.now());
        int userVideoRows = userVideoMapper.updateCoin(userVideo);
        if (userVideoRows != 1) {
            throw new BusinessException(500, "视频投币失败");
        }

        recordStatus(
                vid, VideoStatusEventType.COIN, coin.longValue(),
                () -> {
                    if (videoStatusMapper.increaseCoinTimes(vid, coin) != 1) {
                        throw new BusinessException(500, "视频投币失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void increaseShare(Integer vid) {
        validateVideo(vid);

        recordStatus(vid, VideoStatusEventType.SHARE, 1,
                () -> {
                    if (videoStatusMapper.increaseShareTimes(vid) != 1) {
                        throw new BusinessException(500, "分享失败");
                    }
                }
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void Collect(Integer currentUserId, Integer vid, Boolean isCollect) {
        if(currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (isCollect == null) {
            throw new BusinessException(400, "收藏参数不合法");
        }
        validateVideo(vid);

        UserVideo userVideo = getOrCreateUserVideo(currentUserId, vid);

        if (isCollect.equals(userVideo.getCollect())) {
            return;
        }

        userVideo.setCollect(isCollect);

        int userVideoRows = userVideoMapper.updateCollect(userVideo);
        if (userVideoRows != 1) {
            throw new BusinessException(500, "收藏状态更新失败");
        }

        long delta = isCollect ? 1L : -1L;
        recordStatus(vid, VideoStatusEventType.COLLECT, delta,
                () -> {
                    int rows = isCollect
                            ? videoStatusMapper.increaseCollectTimes(vid)
                            : videoStatusMapper.decreaseCollectTimes(vid);
                    if (rows != 1) {
                        throw new BusinessException(500, "收藏统计更新失败");
                    }
                }
        );

    }

    private UserVideo getOrCreateUserVideo(Integer currentUserId, Integer vid) {
        userVideoMapper.ensureExists(currentUserId, vid);

        UserVideo userVideo = userVideoMapper.selectByUidAndVid(currentUserId, vid);
        if (userVideo == null) {
            throw new BusinessException(500, "用户视频关系创建失败");
        }
        return userVideo;
    }

    private Video validateVideo(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }

        Video video = videoMapper.selectPublishedByVid(vid);
        if (video == null) {
            throw new BusinessException(404, "视频不存在");
        }
        if (video.getDuration() == null || video.getDuration() <= 0) {
            throw new BusinessException(500, "视频时长数据异常");
        }
        return video;
    }

    private void recordStatus(
            Integer vid,
            VideoStatusEventType type,
            long delta,
            Runnable synchronousFallback
    ) {
        if (videoStatusProperties.isAsyncEnabled()) {
            videoStatusService.createEvent(vid, type, delta);
            return;
        }
        synchronousFallback.run();
    }
}
