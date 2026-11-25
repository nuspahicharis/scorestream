package com.haris.betting.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BetEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BetEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final String topic;

    public BetEventPublisher(KafkaTemplate<String, String> kafka,
                             JsonMapper jsonMapper,
                             @Value("${betting.kafka.bet-placed-topic}") String topic) {
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    public void publish(BetPlacedEvent event) {
        try {
            String json = jsonMapper.writeValueAsString(event);
            kafka.send(topic, event.betId().toString(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish BetPlaced for bet {}", event.betId(), ex);
                        } else {
                            log.info("Published BetPlaced for bet {} to partition {} offset {}",
                                    event.betId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (RuntimeException e) {
            log.error("Could not send BetPlaced for bet {}", event.betId(), e);
        }
    }
}