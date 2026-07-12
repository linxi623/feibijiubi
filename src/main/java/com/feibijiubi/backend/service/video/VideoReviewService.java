package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.dto.VideoReviewDTO;
import com.feibijiubi.backend.vo.AllVideoDetailVO;
import com.feibijiubi.backend.vo.UnpubVideoListItemVO;

import java.util.List;

public interface VideoReviewService {
    void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request);

    AllVideoDetailVO getVideo(Integer currentUserId, Integer vid);

    List<UnpubVideoListItemVO> getVideoList(Integer page, Byte status, Integer quantity);
}
