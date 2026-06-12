package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 演出主表实体类
 */
@Data
@TableName("tb_event")
public class Event {

    /**
     * 主键 ID，对应数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 演出标题
     */
    private String title;

    /**
     * 演出时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime showTime;

    /**
     * 场馆名称
     */
    private String venue;

    /**
     * 详细地址
     */
    private String address;

    /**
     * 演出海报图 URL
     */
    private String posterUrl;

    /**
     * 演出详情长图 URL
     */
    private String detailsUrl;

    /**
     * 演出状态 (1.预售中；2.在售；3.停售；4.隐藏)
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建者用户ID
     */
    private Long createBy;

    /**
     * 审核状态 (0:待审核, 1:审核通过, 2:被驳回)
     */
    private Integer auditStatus;

    private String city;

    /**
     * 开票时间（仅在 status = 2 预售时必须设定）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime saleTime;

    /**
     * 绑定的票档列表（非数据库字段，仅用于向前端返回嵌套数据）
     */
    @TableField(exist = false)
    private List<TicketCategory> tickets;

    /**
     * 绑定的参演艺人列表（非数据库字段，仅用于向前端返回嵌套数据）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> artists;

    @TableField(exist = false)
    private List<Map<String, Object>> pendingArtists;

    private String style;

    private Integer runningTime;

    private Integer editAuditStatus;

    private String pendingPayload;

    private LocalDateTime auditSubmitTime;

    private Integer pageViews;
    private Integer wantCount;

    // 🚨 补充一个标识字段：当前登录用户是否已点“想看” (不映射到数据库表中)
    @TableField(exist = false)
    private Boolean hasWanted;


    // ==========================================
    // 💡 状态字典常量定义，拒绝魔法数字
    // ==========================================

    public static final int STATUS_HIDDEN = 4;  // 隐藏 (信息调整中/草稿)
    public static final int STATUS_ONSALE = 1;  // 售票中
    public static final int STATUS_PRESALE = 2; // 预售中
    public static final int STATUS_OFFLINE = 3; // 下架 (保留展示，不可购买)

    // 演出审核状态
    public static final int AUDIT_PENDING = 0;  // 待审核
    public static final int AUDIT_APPROVED = 1; // 已通过
    public static final int AUDIT_REJECTED = 2; // 已驳回
    public static final Integer AUDIT_REVOKED = 3;

    public static final Integer EDIT_AUDIT_PENDING = 0;
    public static final Integer EDIT_AUDIT_REJECTED = 2;


}