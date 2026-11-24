package com.haris.betting.bet;

import com.haris.betting.bet.dto.PlaceBetRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BetServiceTest {

    private static final String KEY = "key-1";

    @Mock
    private BetRepository bets;

    @InjectMocks
    private BetService service;

    private PlaceBetRequest request(String stake, String odds) {
        return new PlaceBetRequest("u1", "match-1", new BigDecimal(stake), new BigDecimal(odds));
    }

    private Bet existingBet(String stake, String odds) {
        return new Bet("u1", "match-1", new BigDecimal(stake), new BigDecimal(odds),
                new BigDecimal("23.50"), KEY);
    }

    // ---------- place ----------

    @Test
    void placeWithNewKeyCreatesBet() {
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(bets.saveAndFlush(any(Bet.class))).thenAnswer(inv -> inv.getArgument(0));

        PlacementResult result = service.place(request("10.00", "2.35"), KEY);

        assertThat(result.created()).isTrue();
        assertThat(result.bet().getStatus()).isEqualTo(BetStatus.PLACED);
        assertThat(result.bet().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.bet().getPotentialPayout()).isEqualByComparingTo("23.50");
        verify(bets).saveAndFlush(any(Bet.class));
    }

    @Test
    void placeRoundsPayoutHalfUp() {
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(bets.saveAndFlush(any(Bet.class))).thenAnswer(inv -> inv.getArgument(0));

        // 4.50 x 1.010 = 4.545 exactly: HALF_UP gives 4.55 (HALF_EVEN would give 4.54)
        PlacementResult result = service.place(request("4.50", "1.010"), KEY);

        assertThat(result.bet().getPotentialPayout()).isEqualByComparingTo("4.55");
        assertThat(result.bet().getPotentialPayout().scale()).isEqualTo(2);
    }

    @Test
    void placeWithSameKeyAndSameRequestReplaysOriginal() {
        Bet existing = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        PlacementResult result = service.place(request("10.00", "2.35"), KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.bet()).isSameAs(existing);
        verify(bets, never()).saveAndFlush(any());
    }

    @Test
    void placeReplayIgnoresDifferenceInScale() {
        Bet existing = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        PlacementResult result = service.place(request("10.0", "2.350"), KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.bet()).isSameAs(existing);
    }

    @Test
    void placeWithSameKeyAndDifferentStakeThrowsConflict() {
        Bet existing = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.place(request("99.00", "2.35"), KEY))
                .isInstanceOf(BetConflictException.class);
        verify(bets, never()).saveAndFlush(any());
    }

    @Test
    void placeWithSameKeyAndDifferentSelectionThrowsConflict() {
        Bet existing = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));
        PlaceBetRequest other = new PlaceBetRequest("u1", "match-2", new BigDecimal("10.00"), new BigDecimal("2.35"));

        assertThatThrownBy(() -> service.place(other, KEY))
                .isInstanceOf(BetConflictException.class);
    }

    @Test
    void placeLosingTheRaceReplaysTheWinner() {
        Bet winner = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(bets.saveAndFlush(any(Bet.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        PlacementResult result = service.place(request("10.00", "2.35"), KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.bet()).isSameAs(winner);
        verify(bets, times(2)).findByIdempotencyKey(KEY);
    }

    @Test
    void placeLosingTheRaceToADifferentRequestThrowsConflict() {
        Bet winner = existingBet("10.00", "2.35");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.empty(), Optional.of(winner));
        when(bets.saveAndFlush(any(Bet.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.place(request("99.00", "2.35"), KEY))
                .isInstanceOf(BetConflictException.class);
    }

    @Test
    void placeRethrowsViolationWhenNoBetWithTheKeyExists() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("check constraint");
        when(bets.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(bets.saveAndFlush(any(Bet.class))).thenThrow(violation);

        assertThatThrownBy(() -> service.place(request("10.00", "2.35"), KEY))
                .isSameAs(violation);
    }

    // ---------- get ----------

    @Test
    void getReturnsExistingBet() {
        Bet bet = existingBet("10.00", "2.35");
        when(bets.findById(bet.getId())).thenReturn(Optional.of(bet));

        assertThat(service.get(bet.getId())).isSameAs(bet);
    }

    @Test
    void getUnknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(bets.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(BetNotFoundException.class);
    }

    // ---------- cancel ----------

    @Test
    void cancelSetsStatusCancelled() {
        Bet bet = existingBet("10.00", "2.35");
        when(bets.findById(bet.getId())).thenReturn(Optional.of(bet));

        Bet result = service.cancel(bet.getId());

        assertThat(result.getStatus()).isEqualTo(BetStatus.CANCELLED);
    }

    @Test
    void cancelUnknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(bets.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(id))
                .isInstanceOf(BetNotFoundException.class);
    }

    @Test
    void cancelSettledBetThrowsInvalidTransition() {
        Bet bet = existingBet("10.00", "2.35");
        bet.settle(BetStatus.WON);
        when(bets.findById(bet.getId())).thenReturn(Optional.of(bet));

        assertThatThrownBy(() -> service.cancel(bet.getId()))
                .isInstanceOf(InvalidBetStatusTransitionException.class);
    }

    // ---------- settle ----------

    @Test
    void settleSetsRequestedResult() {
        Bet bet = existingBet("10.00", "2.35");
        when(bets.findById(bet.getId())).thenReturn(Optional.of(bet));

        Bet result = service.settle(bet.getId(), BetStatus.WON);

        assertThat(result.getStatus()).isEqualTo(BetStatus.WON);
    }

    @Test
    void settleUnknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(bets.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settle(id, BetStatus.WON))
                .isInstanceOf(BetNotFoundException.class);
    }

    @Test
    void settleCancelledBetThrowsInvalidTransition() {
        Bet bet = existingBet("10.00", "2.35");
        bet.cancel();
        when(bets.findById(bet.getId())).thenReturn(Optional.of(bet));

        assertThatThrownBy(() -> service.settle(bet.getId(), BetStatus.LOST))
                .isInstanceOf(InvalidBetStatusTransitionException.class);
    }
}