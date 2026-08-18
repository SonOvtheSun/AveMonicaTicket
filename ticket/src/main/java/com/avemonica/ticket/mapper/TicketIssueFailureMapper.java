package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.TicketIssueFailure;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketIssueFailureMapper
        extends BaseMapper<TicketIssueFailure> {

    /**
     * DLT本身也可能重复投递，所以这里必须幂等。
     *
     * 注意：
     * duplicate时不修改status，
     * 防止一条已经人工修复成功的记录又被重新变成待处理。
     */
    @Insert("""
        INSERT INTO tb_ticket_issue_failure (
            outbox_event_id,
            order_id,
            event_type,
            payload,
            error_class,
            error_message,
            dlt_topic,
            dlt_partition,
            dlt_offset,
            attempt_count,
            status,
            fail_time
        )
        VALUES (
            #{outboxEventId},
            #{orderId},
            #{eventType},
            #{payload},
            #{errorClass},
            #{errorMessage},
            #{dltTopic},
            #{dltPartition},
            #{dltOffset},
            #{attemptCount},
            0,
            NOW(3)
        )
        ON DUPLICATE KEY UPDATE
            error_class = #{errorClass},
            error_message = #{errorMessage},
            dlt_topic = #{dltTopic},
            dlt_partition = #{dltPartition},
            dlt_offset = #{dltOffset},
            attempt_count = #{attemptCount}
        """)
    int upsert(TicketIssueFailure failure);
}