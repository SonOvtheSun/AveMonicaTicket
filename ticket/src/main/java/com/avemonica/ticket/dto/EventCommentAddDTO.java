package com.avemonica.ticket.dto;

import lombok.Data;

import java.util.List;

@Data
public class EventCommentAddDTO {

    private Long eventId;

    private String content;

    /**
     * 前端先通过 /api/common/upload 上传评论图片，
     * 然后把返回的图片 URL 数组传过来。
     */
    private List<String> imageUrls;
}