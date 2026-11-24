package com.haris.betting.bet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class BetConflictException extends RuntimeException {

    public BetConflictException(UUID existingBetId) {
        super("Idempotency-Key was already used for a different request (bet " + existingBetId + ")");
    }
}