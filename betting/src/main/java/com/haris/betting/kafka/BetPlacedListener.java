package com.haris.betting.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BetPlacedListener {

    private final SelectionStatsService stats;
    private final JsonMapper jsonMapper;

    public BetPlacedListener(SelectionStatsService stats, JsonMapper jsonMapper) {
        this.stats = stats;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${betting.kafka.bet-placed-topic}")
    public void onBetPlaced(String payload) {
        BetPlacedEvent event = jsonMapper.readValue(payload, BetPlacedEvent.class);
        stats.apply(event);
    }
}