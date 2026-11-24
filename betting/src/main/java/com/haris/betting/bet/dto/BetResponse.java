package com.haris.betting.bet.dto;

import com.haris.betting.bet.Bet;
import com.haris.betting.bet.BetStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BetResponse(
        UUID id,
        String userId,
        String selectionId,
        BigDecimal stake,
        BigDecimal odds,
        BigDecimal potentialPayout,
        BetStatus status,
        Instant createdAt
) {

    public static BetResponse from(Bet bet) {
        return new BetResponse(
                bet.getId(),
                bet.getUserId(),
                bet.getSelectionId(),
                bet.getStake(),
                bet.getOdds(),
                bet.getPotentialPayout(),
                bet.getStatus(),
                bet.getCreatedAt()
        );
    }
}