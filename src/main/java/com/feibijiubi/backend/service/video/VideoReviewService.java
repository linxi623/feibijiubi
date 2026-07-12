package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.dto.VideoReviewDTO;
import com.feibijiubi.backend.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.vo.AdminVideoListItemVO;

import java.util.List;

public interface VideoReviewService {
    void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request);

    AdminVideoDetailVO getVideo(Integer currentUserId, Integer vid);

    List<AdminVideoListItemVO> getVideoList(Integer page, Byte status, Integer quantity);
}
