package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.converter.VideoConverter;
import com.feibijiubi.backend.dto.VideoReviewDTO;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.enums.VideoReviewStatus;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.VideoReviewService;
import com.feibijiubi.backend.vo.AllVideoDetailVO;
import com.feibijiubi.backend.vo.UnpubVideoListItemVO;
import com.github.pagehelper.PageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Service
public class VideoReviewImpl implements VideoReviewService {
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;

    public VideoReviewImpl(final VideoMapper videoMapper, VideoStatusMapper videoStatusMapper) {
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
    }

    @Override
    public void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request) {
        if(currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if(vid == null || vid <= 0){
            throw new BusinessException(400, "视频参数不合法");
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

        int rows = videoMapper.updateReviewStatus(vid, currentStatus.getCode(), targetStatus.getCode());
        if(rows != 1){
            throw new BusinessException(409, "视频已经由其他管理员审核，请刷新后重试");
        }
    }

    @Override
    public AllVideoDetailVO getVideo(Integer currentUserId, Integer vid) {
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
        VideoStatus videoStatus =  videoStatusMapper.selectByVid(vid);

        if(videoStatus == null){
            throw new BusinessException(404, "视频状态为空");
        }

        return VideoConverter.toAllVideoDetailVO(video, videoStatus);
    }

    @Override
    public List<UnpubVideoListItemVO> getVideoList(Integer page, Byte status, Integer quantity) {
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
        return videoMapper.selectUnpub(status);
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
}
