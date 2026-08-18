package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.OutboxEvent;
import com.avemonica.ticket.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OutboxEventWriter {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventMapper outboxEventMapper,
                             ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType,
                      String aggregateId,
                      String eventType,
                      String topic,
                      Object payload) {

        try {
            OutboxEvent event = new OutboxEvent();

            event.setId(UUID.randomUUID().toString());

            event.setAggregateType(aggregateType);
            event.setAggregateId(aggregateId);

            event.setEventType(eventType);
            event.setTopic(topic);

            event.setPayload(
                    objectMapper.writeValueAsString(payload)
            );

            event.setCreateTime(LocalDateTime.now());

            outboxEventMapper.insert(event);

        } catch (Exception e) {
            throw new RuntimeException(
                    "写入 Outbox 事件失败：" + e.getMessage(),
                    e
            );
        }
    }
}