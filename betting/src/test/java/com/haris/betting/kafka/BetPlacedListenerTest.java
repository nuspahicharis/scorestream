package com.haris.betting.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BetPlacedListenerTest {

    @Mock
    private SelectionStatsService stats;

    private BetPlacedListener listener() {
        return new BetPlacedListener(stats, JsonMapper.builder().build());
    }

    @Test
    void parsesPayloadAndDelegatesToService() {
        UUID eventId = UUID.randomUUID();
        UUID betId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","betId":"%s","userId":"u1","selectionId":"match-1",
                 "stake":10.00,"odds":2.00,"potentialPayout":20.00,
                 "occurredAt":"2026-11-25T12:00:00Z"}
                """.formatted(eventId, betId);

        listener().onBetPlaced(payload);

        ArgumentCaptor<BetPlacedEvent> captor = ArgumentCaptor.forClass(BetPlacedEvent.class);
        verify(stats).apply(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().betId()).isEqualTo(betId);
        assertThat(captor.getValue().selectionId()).isEqualTo("match-1");
        assertThat(captor.getValue().stake()).isEqualByComparingTo("10.00");
    }

    @Test
    void malformedPayloadThrowsAndDoesNotTouchTheService() {
        assertThatThrownBy(() -> listener().onBetPlaced("not json"))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(stats);
    }
}