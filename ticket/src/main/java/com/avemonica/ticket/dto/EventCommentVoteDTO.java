package com.avemonica.ticket.dto;

import lombok.Data;

@Data
public class EventCommentVoteDTO {

    private String commentId;

    /**
     * 1 点赞，-1 拉踩
     */
    private Integer voteType;
}