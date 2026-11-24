package com.haris.betting.bet;

import com.haris.betting.bet.dto.PlaceBetRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Service
public class BetService {

    private final BetRepository bets;

    public BetService(BetRepository bets) {
        this.bets = bets;
    }

    // Deliberately NOT @Transactional.
    public PlacementResult place(PlaceBetRequest request, String idempotencyKey) {
        Optional<Bet> existing = bets.findByIdempotencyKey(idempotencyKey);

        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

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

        try {
            return new PlacementResult(bets.saveAndFlush(bet), true);
        } catch (DataIntegrityViolationException e) {
            Bet winner = bets.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);

            return replay(winner, request);
        }
    }


    @Transactional(readOnly = true)
    public Bet get(UUID id) {
        return bets.findById(id).orElseThrow(() -> new BetNotFoundException(id));
    }

    @Transactional
    public Bet cancel(UUID id) {
        Bet bet = bets.findById(id).orElseThrow(()-> new BetNotFoundException((id)));

        bet.cancel();

        return bet;
    }

    @Transactional
    public Bet settle(UUID id, BetStatus result) {
        Bet bet = bets.findById(id).orElseThrow(() -> new BetNotFoundException(id));

        bet.settle(result);

        return bet;
    }

    private PlacementResult replay(Bet existing, PlaceBetRequest request) {
        if (!existing.matches(request)) {
            throw new BetConflictException(existing.getId());
        }

        return new PlacementResult(existing, false);
    }

}