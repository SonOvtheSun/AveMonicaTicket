package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_event_comment")
public class EventComment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long eventId;

    private Long userId;

    private String content;

    /**
     * JSON 数组字符串，例如：
     * ["/uploads/comment/a.webp", "/uploads/comment/b.webp"]
     */
    private String imageUrls;

    private Integer likeCount;

    private Integer dislikeCount;

    /**
     * 1正常，2隐藏，3删除
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}