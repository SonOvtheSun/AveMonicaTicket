package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.UserBehavior;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {

    /**
     * 只保留某个登录用户最近 limit 条行为，其余删除。
     */
    @Delete("""
            DELETE FROM tb_user_behavior
            WHERE user_id = #{userId}
              AND id NOT IN (
                  SELECT id FROM (
                      SELECT id
                      FROM tb_user_behavior
                      WHERE user_id = #{userId}
                      ORDER BY create_time DESC, id DESC
                      LIMIT #{limit}
                  ) AS keep_ids
              )
            """)
    int deleteOldByUserId(@Param("userId") Long userId,
                          @Param("limit") Integer limit);

    /**
     * 只保留某个游客最近 limit 条行为，其余删除。
     * 这里限定 user_id IS NULL，避免误删后续已经绑定用户ID的行为。
     */
    @Delete("""
            DELETE FROM tb_user_behavior
            WHERE user_id IS NULL
              AND visitor_id = #{visitorId}
              AND id NOT IN (
                  SELECT id FROM (
                      SELECT id
                      FROM tb_user_behavior
                      WHERE user_id IS NULL
                        AND visitor_id = #{visitorId}
                      ORDER BY create_time DESC, id DESC
                      LIMIT #{limit}
                  ) AS keep_ids
              )
            """)
    int deleteOldByVisitorId(@Param("visitorId") String visitorId,
                             @Param("limit") Integer limit);

    /**
     * 查询最近有新行为、且画像未刷新到最新行为时间的用户。
     *
     * 只处理登录用户：
     * 1. tb_user_recommend_profile 当前以 user_id 为主键；
     * 2. 游客 visitorId 暂时不生成画像，后续首页游客推荐走冷启动。
     */
    @Select("""
        SELECT active_user.user_id
        FROM (
            SELECT
                user_id,
                MAX(create_time) AS last_behavior_time
            FROM tb_user_behavior
            WHERE user_id IS NOT NULL
              AND create_time >= #{since}
            GROUP BY user_id
            ORDER BY last_behavior_time DESC
            LIMIT #{limit}
        ) active_user
        LEFT JOIN tb_user_recommend_profile profile
          ON profile.user_id = active_user.user_id
        WHERE profile.user_id IS NULL
           OR profile.update_time < active_user.last_behavior_time
        """)
    List<Long> selectUserIdsNeedProfileRefresh(@Param("since") LocalDateTime since,
                                               @Param("limit") Integer limit);
}