package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ticket_issue_failure")
public class TicketIssueFailure {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outboxEventId;

    private Long orderId;

    private String eventType;

    private String payload;

    private String errorClass;

    private String errorMessage;

    private String dltTopic;

    private Integer dltPartition;

    private Long dltOffset;

    private Integer attemptCount;

    /**
     * 0 待补偿
     * 1 补偿成功
     */
    private Integer status;

    private LocalDateTime failTime;

    private LocalDateTime repairTime;

    private Long repairOperatorId;
}