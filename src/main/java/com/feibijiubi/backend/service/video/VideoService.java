package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.dto.VideoSubmitDTO;
import com.feibijiubi.backend.dto.VideoUploadPrepareDTO;
import com.feibijiubi.backend.vo.VideoSubmitVO;
import org.springframework.web.multipart.MultipartFile;


public interface VideoService {
    String uploadCover(Integer currentUserId, MultipartFile file);

    VideoSubmitVO submitVideo(Integer currentUserId, VideoSubmitDTO request);
}
