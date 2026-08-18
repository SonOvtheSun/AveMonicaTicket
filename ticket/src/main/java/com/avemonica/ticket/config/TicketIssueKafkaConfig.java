package com.avemonica.ticket.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Slf4j
@Configuration
public class TicketIssueKafkaConfig {

    public static final String ISSUE_TOPIC =
            "order-ticket-issue-topic";

    public static final String ISSUE_DLT_TOPIC =
            "order-ticket-issue-topic.DLT";

    /**
     * 本地单Broker，所以副本数1。
     *
     * 因为下面发送DLT时使用partition=-1，
     * DLT不需要和原Topic保持相同分区数量。
     */
    @Bean
    public NewTopic ticketIssueDltTopic() {
        return TopicBuilder.name(ISSUE_DLT_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler ticketIssueErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate
    ) {

        /*
         * 重试耗尽以后，把原消息转移到DLT。
         */
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        (record, exception) ->
                                new TopicPartition(
                                        ISSUE_DLT_TOPIC,
                                        -1
                                )
                );

        /*
         * DLT发送本身如果失败，不能假装恢复成功。
         */
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(
                Duration.ofSeconds(10)
        );

        /*
         * 2秒间隔，最多重试2次。
         *
         * 总执行次数：
         * 首次1次 + retry 2次 = 3次。
         */
        FixedBackOff backOff =
                new FixedBackOff(
                        2000L,
                        2L
                );

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        recoverer,
                        backOff
                );

        errorHandler.setRetryListeners(
                (
                        ConsumerRecord<?, ?> record,
                        Exception exception,
                        int deliveryAttempt
                ) -> log.warn(
                        "出票消费失败，准备重试，attempt={}, topic={}, partition={}, offset={}, error={}",
                        deliveryAttempt,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception.getMessage()
                )
        );

        return errorHandler;
    }

    /**
     * 只让出票Consumer使用这套ErrorHandler，
     * 避免影响order-create-topic以及以后其他Consumer。
     */
    @Bean("ticketIssueKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String>
    ticketIssueKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Qualifier("ticketIssueErrorHandler")
            DefaultErrorHandler errorHandler
    ) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        factory.setCommonErrorHandler(errorHandler);

        factory.getContainerProperties()
                .setAckMode(
                        ContainerProperties.AckMode.RECORD
                );

        return factory;
    }
}