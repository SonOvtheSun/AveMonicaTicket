package com.avemonica.ticket.service;

public interface UploadFileService {

    /**
     * 删除本地上传文件。
     * url 示例：/uploads/avatar/xxxx.webp
     */
    boolean deleteUploadFile(String url);

    /**
     * 判断是否是本项目本地上传文件。
     */
    boolean isLocalUploadUrl(String url);
}