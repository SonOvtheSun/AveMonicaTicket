package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.LocalDateTime;

@Data
@TableName("tb_artist")
public class Artist {

    /**
     * 艺人ID。
     * 使用 MyBatis-Plus 雪花算法生成。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String avatarUrl;

    private String description;

    private String region;

    private String style;

    private Integer auditStatus;

    private Integer editAuditStatus;

    private String pendingPayload;

    private LocalDateTime auditSubmitTime;

    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer likeCount;

    private Long heatValue;

    private Integer recentWeekLikeCount;

    private Integer recentEventCount;

    private String firstLetter;

    private LocalDateTime heatUpdateTime;

    @TableField(exist = false)
    private Boolean isFavorited;
}
