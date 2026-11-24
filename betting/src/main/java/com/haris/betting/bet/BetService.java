package com.haris.betting.bet;

import com.haris.betting.bet.dto.PlaceBetRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Service
public class BetService {

    private final BetRepository bets;

    public BetService(BetRepository bets) {
        this.bets = bets;
    }

    @Transactional
    public Bet place(PlaceBetRequest request, String idempotencyKey){
        BigDecimal potentialPayout = request.stake()
                .multiply(request.odds())
                .setScale(2, RoundingMode.HALF_UP);

        Bet bet = new Bet(
                request.userId(),
                request.selectionId(),
                request.stake(),
                request.odds(),
                potentialPayout,
                idempotencyKey
        );

        return bets.save(bet);
    }


    @Transactional(readOnly = true)
    public Bet get(UUID id) {
        return bets.findById(id).orElseThrow(() -> new BetNotFoundException(id));
    }
}
