package com.haris.betting.kafka;

import com.haris.betting.bet.Bet;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public record BetPlacedEvent(
        UUID eventId,
        UUID betId,
        String userId,
        String selectionId,
        BigDecimal stake,
        BigDecimal odds,
        BigDecimal potentialPayout,
        Instant occurredAt
) {
    public static BetPlacedEvent from(Bet bet) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("bet-placed:" + bet.getId()).getBytes(StandardCharsets.UTF_8));
        return new BetPlacedEvent(eventId, bet.getId(), bet.getUserId(), bet.getSelectionId(),
                bet.getStake(), bet.getOdds(), bet.getPotentialPayout(), bet.getCreatedAt());
    }
}