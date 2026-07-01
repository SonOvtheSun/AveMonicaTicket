package com.avemonica.ticket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Mapper
public interface RecommendQueryMapper {

    /**
     * 根据演出ID批量查询演出绑定的艺人。
     */
    @Select("""
            <script>
            SELECT
                event_id AS eventId,
                artist_id AS artistId
            FROM tb_event_artist
            WHERE event_id IN
            <foreach collection='eventIds' item='eventId' open='(' separator=',' close=')'>
                #{eventId}
            </foreach>
            </script>
            """)
    List<Map<String, Object>> selectEventArtistPairs(@Param("eventIds") List<Long> eventIds);

    /**
     * 查询一批演出的最低票价。
     * 注意：当前票档是 session 级结构，所以从 tb_ticket_category 统计。
     */
    @Select("""
            <script>
            SELECT
                event_id AS eventId,
                MIN(price) AS minPrice
            FROM tb_ticket_category
            WHERE event_id IN
            <foreach collection='eventIds' item='eventId' open='(' separator=',' close=')'>
                #{eventId}
            </foreach>
            GROUP BY event_id
            </script>
            """)
    List<Map<String, Object>> selectEventMinPrices(@Param("eventIds") List<Long> eventIds);
}