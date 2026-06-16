package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.service.UploadFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class UploadFileServiceImpl implements UploadFileService {

    @Value("${avemonica.upload.base-path}")
    private String basePath;

    @Override
    public boolean isLocalUploadUrl(String url) {
        return StringUtils.hasText(url) && url.startsWith("/uploads/");
    }

    @Override
    public boolean deleteUploadFile(String url) {
        if (!isLocalUploadUrl(url)) {
            return false;
        }

        try {
            String relativePath = url.substring("/uploads".length());
            relativePath = relativePath.replaceFirst("^/+", "");

            Path uploadRoot = Paths.get(basePath).toAbsolutePath().normalize();
            Path targetPath = uploadRoot.resolve(relativePath).normalize();

            // 防止 ../ 目录穿越，避免误删 uploads 目录外文件
            if (!targetPath.startsWith(uploadRoot)) {
                throw new BusinessException("非法文件路径");
            }

            boolean deleted = Files.deleteIfExists(targetPath);
            log.info("删除上传文件，url={}, path={}, deleted={}", url, targetPath, deleted);
            return deleted;

        } catch (IOException e) {
            log.warn("删除上传文件失败，url={}", url, e);
            return false;
        }
    }
}