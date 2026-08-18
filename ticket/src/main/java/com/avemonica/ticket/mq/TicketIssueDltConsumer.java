package com.avemonica.ticket.mq;

import com.avemonica.ticket.entity.TicketIssueFailure;
import com.avemonica.ticket.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class TicketIssueDltConsumer {

    private final OrderService orderService;

    public TicketIssueDltConsumer(
            OrderService orderService
    ) {
        this.orderService = orderService;
    }

    @KafkaListener(
            topics = "order-ticket-issue-topic.DLT",
            groupId = "ticket-issue-dlt-group"
    )
    public void consume(
            ConsumerRecord<String, String> record
    ) {

        String outboxEventId =
                readHeader(record, "id");

        String eventType =
                readHeader(record, "type");

        String errorClass =
                readHeader(
                        record,
                        KafkaHeaders.DLT_EXCEPTION_FQCN
                );

        String errorMessage =
                readHeader(
                        record,
                        KafkaHeaders.DLT_EXCEPTION_MESSAGE
                );

        if (!StringUtils.hasText(outboxEventId)) {
            throw new RuntimeException(
                    "DLT消息缺少Outbox Event ID"
            );
        }

        Long orderId;

        try {
            orderId =
                    Long.valueOf(
                            record.key()
                    );
        } catch (Exception e) {
            throw new RuntimeException(
                    "DLT消息缺少有效订单ID"
            );
        }

        TicketIssueFailure failure =
                new TicketIssueFailure();

        failure.setOutboxEventId(
                outboxEventId
        );

        failure.setOrderId(
                orderId
        );

        failure.setEventType(
                eventType
        );

        failure.setPayload(
                record.value()
        );

        failure.setErrorClass(
                errorClass
        );

        failure.setErrorMessage(
                errorMessage == null
                        ? "出票重试耗尽"
                        : errorMessage
        );

        failure.setDltTopic(
                record.topic()
        );

        failure.setDltPartition(
                record.partition()
        );

        failure.setDltOffset(
                record.offset()
        );

        failure.setAttemptCount(3);

        orderService.recordTicketIssueFailure(
                failure
        );
    }

    private String readHeader(
            ConsumerRecord<String, String> record,
            String name
    ) {

        Header header =
                record.headers().lastHeader(name);

        if (header == null
                || header.value() == null) {
            return null;
        }

        return new String(
                header.value(),
                StandardCharsets.UTF_8
        );
    }
}