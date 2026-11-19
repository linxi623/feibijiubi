package com.feibijiubi.backend.video.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.converter.VideoConverter;
import com.feibijiubi.backend.video.dto.VideoReviewDTO;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.video.enums.VideoReviewStatus;
import com.feibijiubi.backend.user.mapper.UserFollowMapper;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.video.service.VideoReviewService;
import com.feibijiubi.backend.video.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.video.vo.AdminVideoListItemVO;
import com.feibijiubi.backend.videosource.service.ExternalPlaybackLineResolver;
import com.github.pagehelper.PageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class VideoReviewImpl implements VideoReviewService {
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final UserMapper userMapper;
    private final UserFollowMapper userFollowMapper;
    private final ExternalPlaybackLineResolver externalPlaybackLines;

    public VideoReviewImpl(VideoMapper videoMapper, VideoStatusMapper videoStatusMapper,
                           UserMapper userMapper, UserFollowMapper userFollowMapper,
                           ExternalPlaybackLineResolver externalPlaybackLines) {
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.userMapper = userMapper;
        this.userFollowMapper = userFollowMapper;
        this.externalPlaybackLines = externalPlaybackLines;
    }

    @Override
    public void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request) {
        if(currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if(vid == null || vid <= 0){
            throw new BusinessException(400, "视频参数不合法");
        }
        if (request == null || !StringUtils.hasText(request.getResult())) {
            throw new BusinessException(400, "审核结果不能为空");
        }

        VideoReviewStatus targetStatus = parseTargetStatus(request.getResult());

        if(targetStatus == VideoReviewStatus.REJECTED &&
        !StringUtils.hasText(request.getReason())){
            throw new BusinessException(400, "驳回视频必须存在原因");
        }
        if(targetStatus == VideoReviewStatus.REMOVED &&
        !StringUtils.hasText(request.getReason())){
            throw new BusinessException(400, "删除视频必须存在原因");
        }
        Video video = videoMapper.selectByVid(vid);
        if(video == null){
            throw new BusinessException(404, "视频不存在");
        }

        VideoReviewStatus currentStatus =
                VideoReviewStatus.fromCode(video.getStatus());
        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(
                    409,
                    "视频当前状态为“"
                            + currentStatus.getDesc()
                            + "”，不能变更为“"
                            + targetStatus.getDesc()
                            + "”"
            );
        }
        // M5 外部稿件没有本站播放对象（video_url / video_key 均为 NULL）。在唯一的状态写入口
        // 统一阻止它进入 APPROVED；旧 COS 行两列非空，行为不变。M6-A 只有显式开启
        // playback-enabled 且同一资格判定确认至少一条可用 HLS 线路时才放行——不能只看开关。
        if (targetStatus == VideoReviewStatus.APPROVED && !hasPlaybackMedia(video)
                && !externalPlaybackLines.hasAvailableHlsLine(video)) {
            throw new BusinessException(409, "视频尚无可用播放媒体，不能审核通过");
        }

        int rows = videoMapper.updateReviewStatus(vid, currentStatus.getCode(), targetStatus.getCode());
        if(rows != 1){
            throw new BusinessException(409, "视频已经由其他管理员审核，请刷新后重试");
        }
    }

    @Override
    public AdminVideoDetailVO getVideo(Integer currentUserId, Integer vid) {
        if(currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if(vid == null || vid <= 0){
            throw new BusinessException(400, "视频参数无效");
        }
        Video video = videoMapper.selectByVid(vid);
        if(video == null){
            throw new BusinessException(404, "视频不存在");
        }
        VideoStatus videoStatus = videoStatusMapper.selectByVid(vid);

        if(videoStatus == null){
            throw new BusinessException(404, "视频状态为空");
        }
        User author = userMapper.selectById(video.getUid());
        if (author == null) {
            throw new BusinessException(500, "视频作者数据异常");
        }

        return VideoConverter.toAdminVideoDetailVO(
                video,
                videoStatus,
                author,
                videoMapper.countVideoByUid(video.getUid()),
                userFollowMapper.countFans(video.getUid())
        );
    }

    @Override
    public List<AdminVideoListItemVO> getVideoList(Integer page, Byte status, Integer quantity) {
        if(page == null){
            page = 1;
        }
        if(quantity == null){
            quantity = 10;
        }
        if(status == null){
            status = 0;
        }
        PageHelper.startPage(page, quantity);
        return videoMapper.selectByStatus(status);
    }

    private VideoReviewStatus parseTargetStatus(String result) {
        try {
            VideoReviewStatus targetStatus = VideoReviewStatus.valueOf(
                    result.trim().toUpperCase(Locale.ROOT)
            );

            if (targetStatus != VideoReviewStatus.APPROVED
                    && targetStatus != VideoReviewStatus.REJECTED) {
                throw new BusinessException(
                        400,
                        "审核结果只能是 APPROVED 或 REJECTED"
                );
            }

            return targetStatus;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    400,
                    "审核结果只能是 APPROVED 或 REJECTED"
            );
        }
    }

    /** 旧 COS 行两列均非空；外部稿件两列均为 NULL，须由 M6-A 资格判定另行放行。 */
    private static boolean hasPlaybackMedia(Video video) {
        return StringUtils.hasText(video.getVideoUrl()) && StringUtils.hasText(video.getVideoKey());
    }
}
