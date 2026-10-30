package com.feibijiubi.backend.video.service;

import com.feibijiubi.backend.video.dto.VideoReviewDTO;
import com.feibijiubi.backend.video.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.video.vo.AdminVideoListItemVO;

import java.util.List;

public interface VideoReviewService {
    void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request);

    AdminVideoDetailVO getVideo(Integer currentUserId, Integer vid);

    List<AdminVideoListItemVO> getVideoList(Integer page, Byte status, Integer quantity);
}
