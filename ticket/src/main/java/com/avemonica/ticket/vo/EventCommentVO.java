package com.avemonica.ticket.vo;

import lombok.Data;

import java.util.List;

@Data
public class EventCommentVO {

    private String id;

    private String eventId;

    private String userId;

    private String username;

    private String avatar;

    private String content;

    private List<String> imageUrls;

    private Integer likeCount;

    private Integer dislikeCount;

    /**
     * 当前登录用户对该评论的投票：
     * 1 点赞
     * -1 拉踩
     * null 未操作
     */
    private Integer myVote;

    private String createTime;
}