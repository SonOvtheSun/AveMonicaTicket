package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户推荐画像表实体
 */
@Data
@TableName("tb_user_recommend_profile")
public class UserRecommendProfile {

    /**
     * 用户ID。
     * 这里不是雪花生成的新ID，而是直接使用用户ID作为主键。
     */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /**
     * 风格偏好 JSON 字符串。
     * 例如：{"摇滚":35,"流行":20}
     */
    private String styleProfile;

    /**
     * 城市偏好 JSON 字符串。
     * 例如：{"上海":30,"北京":15}
     */
    private String cityProfile;

    /**
     * 艺人偏好 JSON 字符串。
     * 例如：{"10001":50,"10002":20}
     */
    private String artistProfile;

    /**
     * 偏好最低票价。
     */
    private BigDecimal priceMin;

    /**
     * 偏好最高票价。
     */
    private BigDecimal priceMax;

    /**
     * 画像更新时间。
     */
    private LocalDateTime updateTime;
}