package com.feibijiubi.backend.service.impl.storage;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.TencentCosProperties;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TencentCosStorageServiceImpl implements FileStorageService{
    private final COSClient cosClient;
    private final TencentCosProperties properties;

    private static final long MAX_IMAGE_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_EXTENSIONS = Set.of("image/jpg", "image/jpeg", "image/png");

    @Override
    public String uploadImage(MultipartFile file, String directory) {
        validateImage(file);

        String objectKey = buildObjectKey(file, directory);

        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());

            PutObjectRequest request = new PutObjectRequest(
                    properties.getBucket(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );

            cosClient.putObject(request);
            return buildUrl(objectKey);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(500, "文件上传失败");
        } catch (CosServiceException e) {
            log.error("腾讯云 COS 服务异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "文件上传失败");
        } catch (CosClientException e) {
            log.error("腾讯云 COS 客户端异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "文件上传失败");
        }
    }

    private void validateImage(MultipartFile file) {
        if(file == null || file.isEmpty()) {
            throw new BusinessException(400, "图片不能为空");
        }

        if(file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过2MB");
        }

        String contentType = file.getContentType();
        if(!ALLOWED_CONTENT_EXTENSIONS.contains(contentType)) {
            throw new BusinessException(400, "图片格式不支持");
        }
    }

    private String buildObjectKey(MultipartFile file, String directory) {
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);

        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;

        return directory + "/" + date + "/" + filename;
    }

    private String getExtension(String filename) {
        if(!StringUtils.hasText(filename) || !filename.contains(".")) {
            throw new BusinessException(400, "文件后缀不能为空");
        }

        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();

        if(!Set.of("jpg", "jpeg", "png").contains(extension)) {
            throw new BusinessException(400, "不支持该文件后缀");
        }
        return extension;
    }

    private String buildUrl(String objectKey) {
        String baseUrl = properties.getBaseUrl();

        if(baseUrl.endsWith("/")) {
            return baseUrl + objectKey;
        }
        return baseUrl + "/" + objectKey;
    }
}
