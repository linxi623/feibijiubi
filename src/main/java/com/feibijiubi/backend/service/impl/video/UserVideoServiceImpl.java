package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.entity.UserVideo;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.mapper.UserVideoMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.UserVideoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserVideoServiceImpl implements UserVideoService {
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final UserVideoMapper userVideoMapper;
    private final UserMapper userMapper;

    public UserVideoServiceImpl(VideoMapper videoMapper,
                                VideoStatusMapper videoStatusMapper,
                                UserVideoMapper userVideoMapper, UserMapper userMapper) {
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.userVideoMapper = userVideoMapper;
        this.userMapper = userMapper;
    }

    @Override
    public void increasePlayCount(Integer vid) {
        validateVideo(vid);

        int statusRows = videoStatusMapper.increasePlayTimes(vid);
        if (statusRows != 1) {
            throw new BusinessException(500, "播放量更新失败");
        }
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

        int statusRows = isSet
                ? videoStatusMapper.increaseLikeTimes(vid, isLike)
                : videoStatusMapper.decreaseLikeTimes(vid, isLike);

        if (statusRows != 1) {
            throw new BusinessException(500, "视频点赞统计更新失败");
        }


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
        int statusRows = videoStatusMapper.increaseCoinTimes(vid, coin);
        int userRows = userMapper.decreaseCoin(currentUserId, coin);
        if (statusRows != 1 || userRows != 1) {
            throw new BusinessException(500, "视频投币失败");
        }

        userVideo.setCoin(coin);
        userVideo.setCoinedAt(LocalDateTime.now());
        int userVideoRows = userVideoMapper.updateCoin(userVideo);
        if (userVideoRows != 1) {
            throw new BusinessException(500, "视频投币失败");
        }
    }

    @Override
    public void increaseShare(Integer vid) {
        validateVideo(vid);

        int statusRows = videoStatusMapper.increaseShareTimes(vid);
        if (statusRows != 1) {
            throw new BusinessException(500, "分享失败");
        }
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

        int statusRows = isCollect
                ? videoStatusMapper.increaseCollectTimes(vid)
                : videoStatusMapper.decreaseCollectTimes(vid);

        if (statusRows != 1) {
            throw new BusinessException(500, "收藏统计更新失败");
        }

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
}
