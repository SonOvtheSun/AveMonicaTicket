package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.UserRecommendEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserRecommendEventMapper extends BaseMapper<UserRecommendEvent> {

    /**
     * 查询需要刷新首页推荐结果的用户。
     *
     * 刷新条件：
     * 1. 用户已有画像，但没有推荐结果；
     * 2. 用户画像更新时间晚于推荐结果更新时间；
     * 3. 推荐结果超过 staleBefore，定期兜底刷新。
     */
    @Select("""
            SELECT p.user_id
            FROM tb_user_recommend_profile p
            LEFT JOIN (
                SELECT
                    user_id,
                    MAX(update_time) AS last_recommend_time
                FROM tb_user_recommend_event
                WHERE scene = #{scene}
                GROUP BY user_id
            ) r ON r.user_id = p.user_id
            WHERE r.last_recommend_time IS NULL
               OR r.last_recommend_time < p.update_time
               OR r.last_recommend_time < #{staleBefore}
            ORDER BY
                CASE WHEN r.last_recommend_time IS NULL THEN 0 ELSE 1 END,
                p.update_time DESC
            LIMIT #{limit}
            """)
    List<Long> selectUserIdsNeedRecommendRefresh(@Param("scene") String scene,
                                                 @Param("staleBefore") LocalDateTime staleBefore,
                                                 @Param("limit") Integer limit);
}