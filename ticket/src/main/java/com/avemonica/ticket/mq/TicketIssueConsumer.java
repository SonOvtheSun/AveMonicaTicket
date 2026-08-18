package com.avemonica.ticket.mq;

import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.service.TicketIssueProcessor;
import com.avemonica.ticket.service.TicketPurchasedCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TicketIssueConsumer {

    private static final String KEY_PURCHASED_SPEC =
            "event:purchased:spectators:";

    private final ObjectMapper objectMapper;
    private final TicketIssueProcessor ticketIssueProcessor;
    private final StringRedisTemplate redisTemplate;
    private final TicketPurchasedCacheService ticketPurchasedCacheService;

    public TicketIssueConsumer(
            ObjectMapper objectMapper,
            TicketIssueProcessor ticketIssueProcessor,
            StringRedisTemplate redisTemplate,
            TicketPurchasedCacheService ticketPurchasedCacheService
    ) {
        this.objectMapper = objectMapper;
        this.ticketIssueProcessor = ticketIssueProcessor;
        this.redisTemplate = redisTemplate;
        this.ticketPurchasedCacheService = ticketPurchasedCacheService;
    }

    @KafkaListener(
            topics = "order-ticket-issue-topic",
            groupId = "ticket-issue-group",
            containerFactory = "ticketIssueKafkaListenerContainerFactory"
    )
    public void consumeTicketIssueMessage(
            ConsumerRecord<String, String> record
    ) {
        try {
            /*
             * 1. 读取 Debezium Outbox Event ID
             */
            String outboxEventId = readHeader(record, "id");

            /*
             * 我们之前配置：
             *
             * event_type:header:type
             *
             * 所以这里可以顺手读取。
             */
            String eventType = readHeader(record, "type");

            if (outboxEventId == null || outboxEventId.isBlank()) {
                throw new RuntimeException(
                        "Kafka消息缺少Outbox Event ID Header"
                );
            }

            /*
             * 2. 解析业务 payload
             */
            TicketIssueMessage message =
                    objectMapper.readValue(
                            record.value(),
                            TicketIssueMessage.class
                    );

            /*
             * 3. MySQL幂等出票
             *
             * 内部：
             * Inbox INSERT
             * +
             * OrderTicket INSERT
             *
             * 同一个事务。
             */
            boolean firstProcessed =
                    ticketIssueProcessor.processOnce(
                            outboxEventId,
                            eventType,
                            message
                    );

            /*
             * 无论是否重复事件都执行。
             *
             * 如果第一次出票成功但Redis失败，
             * 下一次retry会被Inbox阻止重复出票，
             * 然后重新执行这里。
             */
            ticketPurchasedCacheService.markPurchased(message);

        } catch (Exception e) {
            log.error(
                    "处理异步出票消息失败，topic={}, partition={}, offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e
            );

            /*
             * 必须继续抛异常。
             *
             * 不能 catch 后吞掉，
             * 否则 Kafka 会认为消费成功。
             */
            throw new RuntimeException(
                    "处理出票消息失败",
                    e
            );
        }
    }

    private void markPurchased(TicketIssueMessage message) {

        Long eventId = message.getEventId();
        Long sessionId = message.getSessionId();
        List<Long> spectatorIds = message.getSpectatorIds();

        if (eventId == null
                || sessionId == null
                || spectatorIds == null
                || spectatorIds.isEmpty()) {
            throw new RuntimeException(
                    "更新已购名单失败：出票消息参数不完整"
            );
        }

        String sceneKey =
                eventId + ":" + sessionId;

        String purchasedSetKey =
                KEY_PURCHASED_SPEC + sceneKey;

        String[] values = spectatorIds.stream()
                .map(String::valueOf)
                .toArray(String[]::new);

        redisTemplate.opsForSet().add(
                purchasedSetKey,
                values
        );

        redisTemplate.expire(
                purchasedSetKey,
                30,
                TimeUnit.DAYS
        );
    }

    private String readHeader(
            ConsumerRecord<String, String> record,
            String name
    ) {
        Header header =
                record.headers().lastHeader(name);

        if (header == null || header.value() == null) {
            return null;
        }

        return new String(
                header.value(),
                StandardCharsets.UTF_8
        );
    }
}