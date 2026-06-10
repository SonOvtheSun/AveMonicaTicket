package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 艺人/乐队实体类
 */
@Data
@TableName("tb_artist")
public class Artist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 艺人/乐队名称
     */
    private String name;

    /**
     * 艺人头像URL
     */
    private String avatarUrl;

    /**
     * 艺人简介
     */
    private String description;

    /**
     * 审核状态 (0:待审核, 1:审核通过, 2:被驳回)
     */
    private Integer auditStatus;

    /**
     * 创建者用户ID
     */
    private Long createBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 国家或地区
     */
    private String region;

    /**
     * 音乐风格
     */
    private String style;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer recentEventCount;

    private Integer editAuditStatus;
    private String pendingPayload;
    private LocalDateTime auditSubmitTime;
}