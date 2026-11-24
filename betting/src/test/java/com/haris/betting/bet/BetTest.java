package com.haris.betting.bet;

import com.haris.betting.bet.dto.PlaceBetRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BetTest {

    private Bet newBet() {
        return new Bet("u1", "match-1", new BigDecimal("10.00"), new BigDecimal("2.00"),
                new BigDecimal("20.00"), "key-1");
    }

    private PlaceBetRequest request(String userId, String selectionId, String stake, String odds) {
        return new PlaceBetRequest(userId, selectionId, new BigDecimal(stake), new BigDecimal(odds));
    }

    @Test
    void newBetIsPlaced() {
        Bet bet = newBet();

        assertThat(bet.getStatus()).isEqualTo(BetStatus.PLACED);
        assertThat(bet.getId()).isNotNull();
        assertThat(bet.getUpdatedAt()).isEqualTo(bet.getCreatedAt());
    }

    @Test
    void cancelOnPlacedBetSetsCancelled() {
        Bet bet = newBet();

        bet.cancel();

        assertThat(bet.getStatus()).isEqualTo(BetStatus.CANCELLED);
    }

    @Test
    void cancelDoesNotMoveUpdatedAtBackwards() {
        Bet bet = newBet();

        bet.cancel();

        assertThat(bet.getUpdatedAt()).isAfterOrEqualTo(bet.getCreatedAt());
    }

    @Test
    void cancellingTwiceIsANoOp() {
        Bet bet = newBet();
        bet.cancel();
        var updatedAfterFirstCancel = bet.getUpdatedAt();

        bet.cancel();

        assertThat(bet.getStatus()).isEqualTo(BetStatus.CANCELLED);
        assertThat(bet.getUpdatedAt()).isEqualTo(updatedAfterFirstCancel);
    }

    @Test
    void settleWonOnPlacedBetSetsWon() {
        Bet bet = newBet();

        bet.settle(BetStatus.WON);

        assertThat(bet.getStatus()).isEqualTo(BetStatus.WON);
    }

    @Test
    void settleLostOnPlacedBetSetsLost() {
        Bet bet = newBet();

        bet.settle(BetStatus.LOST);

        assertThat(bet.getStatus()).isEqualTo(BetStatus.LOST);
    }

    @Test
    void settleWithPlacedIsRejected() {
        Bet bet = newBet();

        assertThatThrownBy(() -> bet.settle(BetStatus.PLACED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.PLACED);
    }

    @Test
    void settleWithCancelledIsRejected() {
        Bet bet = newBet();

        assertThatThrownBy(() -> bet.settle(BetStatus.CANCELLED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.PLACED);
    }

    @Test
    void cancelOnWonBetThrows() {
        Bet bet = newBet();
        bet.settle(BetStatus.WON);

        assertThatThrownBy(bet::cancel)
                .isInstanceOf(InvalidBetStatusTransitionException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.WON);
    }

    @Test
    void cancelOnLostBetThrows() {
        Bet bet = newBet();
        bet.settle(BetStatus.LOST);

        assertThatThrownBy(bet::cancel)
                .isInstanceOf(InvalidBetStatusTransitionException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.LOST);
    }

    @Test
    void settleOnAlreadySettledBetThrows() {
        Bet bet = newBet();
        bet.settle(BetStatus.WON);

        assertThatThrownBy(() -> bet.settle(BetStatus.LOST))
                .isInstanceOf(InvalidBetStatusTransitionException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.WON);
    }

    @Test
    void settleOnCancelledBetThrows() {
        Bet bet = newBet();
        bet.cancel();

        assertThatThrownBy(() -> bet.settle(BetStatus.WON))
                .isInstanceOf(InvalidBetStatusTransitionException.class);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.CANCELLED);
    }

    @Test
    void matchesIdenticalRequest() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u1", "match-1", "10.00", "2.00"))).isTrue();
    }

    @Test
    void matchesIgnoresDifferenceInScale() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u1", "match-1", "10.0", "2.000"))).isTrue();
    }

    @Test
    void doesNotMatchDifferentUser() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u2", "match-1", "10.00", "2.00"))).isFalse();
    }

    @Test
    void doesNotMatchDifferentSelection() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u1", "match-2", "10.00", "2.00"))).isFalse();
    }

    @Test
    void doesNotMatchDifferentStake() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u1", "match-1", "10.01", "2.00"))).isFalse();
    }

    @Test
    void doesNotMatchDifferentOdds() {
        Bet bet = newBet();

        assertThat(bet.matches(request("u1", "match-1", "10.00", "2.01"))).isFalse();
    }
}