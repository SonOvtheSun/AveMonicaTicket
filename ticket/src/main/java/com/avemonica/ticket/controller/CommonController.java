package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/common")
public class CommonController {

    // 1. 注入 yml 中的各个路径配置
    @Value("${avemonica.upload.base-path}")
    private String basePath;

    @Value("${avemonica.upload.avatar-dir}")
    private String avatarDir;

    @Value("${avemonica.upload.poster-dir}")
    private String posterDir;

    @Value("${avemonica.upload.scrollbar-dir}")
    private String scrollbarDir;

    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "type", defaultValue = "poster") String type) {
        if (file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        // 2. 根据前端传来的 type 动态分配子目录
        String subDir;
        switch (type) {
            case "avatar":
                subDir = avatarDir;
                break;
            case "scrollbar":
            case "bg":
                subDir = scrollbarDir;
                break;
            case "poster":
            default:
                subDir = posterDir;
                break;
        }

        try {
            // 3. 生成新文件名 (UUID)
            String suffix = getSuffix(file);

            String newFileName = UUID.randomUUID().toString().replace("-", "") + suffix;

            // 4. 拼接绝对物理路径 (用于后端写入文件，例如：D:/项目/avemonica-uploads/avatar/)
            String fullPhysicalPath = basePath + subDir;
            File dir = new File(fullPhysicalPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }


            // 5. 写入磁盘
            file.transferTo(new File(fullPhysicalPath + newFileName));

            // 6. 拼接出网络相对路径返回给前端 (例如：/uploads/avatar/xxxx.jpg)
            // 注意：这里的 "/uploads" 要和 WebConfig 里的映射路径保持一致
            String virtualUrl = "/uploads" + subDir + newFileName;

            log.info("文件分类上传成功，类型: {}, 访问路径: {}", type, virtualUrl);
            return Result.success(virtualUrl);

        } catch (IOException e) {
            log.error("文件上传失败", e);
            throw new BusinessException("文件上传失败");
        }
    }

    private String getSuffix(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();

        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String contentType = file.getContentType();

        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        if ("image/png".equals(contentType)) {
            return ".png";
        }
        if ("image/gif".equals(contentType)) {
            return ".gif";
        }
        if ("image/avif".equals(contentType)) {
            return ".avif";
        }
        if ("image/svg+xml".equals(contentType)) {
            return ".svg";
        }

        return ".jpg";
    }
}