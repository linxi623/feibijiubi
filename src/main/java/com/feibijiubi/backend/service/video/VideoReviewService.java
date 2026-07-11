package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.dto.VideoReviewDTO;

public interface VideoReviewService {
    void videoReview(Integer currentUserId, Integer vid, VideoReviewDTO request);
}
