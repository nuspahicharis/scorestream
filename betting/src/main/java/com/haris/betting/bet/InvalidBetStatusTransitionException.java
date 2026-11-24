package com.haris.betting.bet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidBetStatusTransitionException extends RuntimeException {

    public InvalidBetStatusTransitionException(UUID id, BetStatus from, BetStatus to) {
        super("Bet " + id + " cannot move from " + from + " to " + to);
    }
}
