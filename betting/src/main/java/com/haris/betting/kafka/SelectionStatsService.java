package com.haris.betting.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelectionStatsService {

    private static final Logger log = LoggerFactory.getLogger(SelectionStatsService.class);

    private final JdbcClient jdbc;

    public SelectionStatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(BetPlacedEvent event) {
        int claimed = jdbc.sql("""
                INSERT INTO processed_events (event_id)
                VALUES (:eventId)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", event.eventId())
                .update();

        if (claimed == 0) {
            log.info("Skipping duplicate event {}", event.eventId());
            return;
        }

        jdbc.sql("""
                INSERT INTO selection_stats (selection_id, bet_count, total_stake)
                VALUES (:selectionId, 1, :stake)
                ON CONFLICT (selection_id) DO UPDATE
                SET bet_count   = selection_stats.bet_count + 1,
                    total_stake = selection_stats.total_stake + EXCLUDED.total_stake
                """)
                .param("selectionId", event.selectionId())
                .param("stake", event.stake())
                .update();

        log.info("Applied event {} to selection {}", event.eventId(), event.selectionId());
    }
}