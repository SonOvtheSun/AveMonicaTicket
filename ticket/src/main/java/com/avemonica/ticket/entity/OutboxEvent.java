package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_outbox_event")
public class OutboxEvent {

    @TableId(type = IdType.INPUT)
    private String id;

    private String aggregateType;

    private String aggregateId;

    private String eventType;

    private String topic;

    private String payload;

    private LocalDateTime createTime;
}