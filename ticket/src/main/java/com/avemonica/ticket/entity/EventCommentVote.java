package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_event_comment_vote")
public class EventCommentVote {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long commentId;

    private Long userId;

    /**
     * 1 点赞，-1 拉踩
     */
    private Integer voteType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}