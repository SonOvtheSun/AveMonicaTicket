package com.avemonica.ticket.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InboxEventMapper {

    /**
     * 返回：
     * 1 = 第一次消费
     * 0 = 已经消费过
     */
    @Insert("""
        INSERT IGNORE INTO tb_inbox_event (
            consumer_group,
            event_id,
            event_type,
            aggregate_id,
            processed_time
        )
        VALUES (
            #{consumerGroup},
            #{eventId},
            #{eventType},
            #{aggregateId},
            NOW(3)
        )
        """)
    int insertIgnore(
            @Param("consumerGroup") String consumerGroup,
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("aggregateId") String aggregateId
    );
}