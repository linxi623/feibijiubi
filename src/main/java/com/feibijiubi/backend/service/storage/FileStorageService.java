package com.feibijiubi.backend.service.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String uploadImage(MultipartFile file, String directory);
}
