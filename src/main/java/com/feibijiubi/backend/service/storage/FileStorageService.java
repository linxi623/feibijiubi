package com.feibijiubi.backend.service.storage;

import com.feibijiubi.backend.dto.VideoUploadPrepareDTO;
import com.feibijiubi.backend.vo.VideoUploadPrepareVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String uploadImage(Integer currentUserId, MultipartFile file, String directory);

    VideoUploadPrepareVO uploadPrepare(Integer currentUserId, VideoUploadPrepareDTO request);

    String Url2Key(String url);

    String Key2Url(String key);

    boolean objectExists(String objectKey);

    void copyObject(String sourceKey, String targetKey);

    void deleteObject(String objectKey);

    String buildFormalVideoKey(Integer currentUserId, String tempVideoKey);

    String buildFormalCoverKey(Integer currentUserId, String tempCoverKey);
}
