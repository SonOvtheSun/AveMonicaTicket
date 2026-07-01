package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户演出推荐结果表实体
 */
@Data
@TableName("tb_user_recommend_event")
public class UserRecommendEvent {

    /**
     * 推荐记录ID，使用 MyBatis-Plus 雪花算法生成。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID。
     */
    private Long userId;

    /**
     * 推荐的演出ID。
     */
    private Long eventId;

    /**
     * 推荐分数。
     */
    private BigDecimal score;

    /**
     * 推荐理由。
     */
    private String reason;

    /**
     * 推荐场景：
     * home 首页推荐
     * detail 详情页推荐
     * search 搜索兜底推荐
     */
    private String scene;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;


    // ==========================================
    // 推荐场景常量
    // ==========================================

    public static final String SCENE_HOME = "home";
    public static final String SCENE_DETAIL = "detail";
    public static final String SCENE_SEARCH = "search";
}