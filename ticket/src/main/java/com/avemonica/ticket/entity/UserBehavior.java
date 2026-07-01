package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户行为表实体
 */
@Data
@TableName("tb_user_behavior")
public class UserBehavior {

    /**
     * 行为ID，使用 MyBatis-Plus 雪花算法生成。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID，未登录用户为空。
     */
    private Long userId;

    /**
     * 游客设备ID，用于未登录用户行为记录。
     */
    private String visitorId;

    /**
     * 演出ID。
     */
    private Long eventId;

    /**
     * 行为类型：
     * 1 浏览演出
     * 2 想看演出
     * 3 取消想看
     * 4 创建订单
     * 5 支付成功
     * 6 评论
     * 7 分享
     * 8 退款成功
     */
    private Integer behaviorType;

    /**
     * 行为权重。
     */
    private Integer behaviorWeight;

    /**
     * 行为时间。
     */
    private LocalDateTime createTime;


    // ==========================================
    // 行为类型常量
    // ==========================================

    public static final int TYPE_VIEW_EVENT = 1;
    public static final int TYPE_WANT_EVENT = 2;
    public static final int TYPE_CANCEL_WANT_EVENT = 3;
    public static final int TYPE_CREATE_ORDER = 4;
    public static final int TYPE_PAY_ORDER = 5;
    public static final int TYPE_COMMENT = 6;
    public static final int TYPE_SHARE = 7;
    public static final int TYPE_REFUND_SUCCESS = 8;


    // ==========================================
    // 行为权重常量
    // ==========================================

    public static final int WEIGHT_VIEW_EVENT = 1;
    public static final int WEIGHT_WANT_EVENT = 5;
    public static final int WEIGHT_CANCEL_WANT_EVENT = -5;
    public static final int WEIGHT_CREATE_ORDER = 10;
    public static final int WEIGHT_PAY_ORDER = 20;
    public static final int WEIGHT_COMMENT = 8;
    public static final int WEIGHT_SHARE = 6;
    public static final int WEIGHT_REFUND_SUCCESS = -10;
}