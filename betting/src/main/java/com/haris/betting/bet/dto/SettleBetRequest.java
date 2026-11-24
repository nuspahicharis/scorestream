package com.haris.betting.bet.dto;

import com.haris.betting.bet.BetStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record SettleBetRequest(
        @NotNull
        BetStatus result
) {

    @AssertTrue(message = "result must be WON or LOST")
    public boolean isSettlementResult() {
        return result == null || result == BetStatus.WON || result == BetStatus.LOST;
    }
}