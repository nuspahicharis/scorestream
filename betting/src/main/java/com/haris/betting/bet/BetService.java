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

    // Deliberately NOT @Transactional: a failed insert aborts a Postgres transaction,
    // so the retry lookup below must run in a fresh one. Each repository call already runs in its own transaction.
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
            // Another request with the same key won the race (unique constraint),
            // or the violation came from something else, in which case we rethrow.
            Bet winner = bets.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, request);
        }
    }

    @Transactional(readOnly = true)
    public Bet get(UUID id) {
        return bets.findById(id).orElseThrow(() -> new BetNotFoundException(id));
    }

    private PlacementResult replay(Bet existing, PlaceBetRequest request) {
        if (sameRequest(existing, request)) {
            return new PlacementResult(existing, false);
        }
        throw new BetConflictException(existing.getId());
    }

    private boolean sameRequest(Bet existing, PlaceBetRequest request) {
        return existing.getUserId().equals(request.userId())
                && existing.getSelectionId().equals(request.selectionId())
                && existing.getStake().compareTo(request.stake()) == 0
                && existing.getOdds().compareTo(request.odds()) == 0;
    }
}