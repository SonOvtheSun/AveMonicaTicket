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

    /**
     * 最近/未来关联演出数量，仅用于 C 端展示，不映射数据库字段。
     */
    @TableField(exist = false)
    private Integer recentEventCount;
}
