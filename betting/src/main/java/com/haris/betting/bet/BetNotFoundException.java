package com.haris.betting.bet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class BetNotFoundException extends RuntimeException{

    public BetNotFoundException (UUID id){
        super("Bet not found: " + id);
    }
}
