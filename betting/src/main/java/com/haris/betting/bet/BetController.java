package com.haris.betting.bet;

import com.haris.betting.bet.dto.BetResponse;
import com.haris.betting.bet.dto.PlaceBetRequest;
import com.haris.betting.bet.dto.SettleBetRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/bets")
public class BetController {

    private final BetService service;

    public BetController(BetService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BetResponse> place(
            @Valid @RequestBody PlaceBetRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        PlacementResult result = service.place(request, idempotencyKey);
        Bet bet = result.bet();
        BetResponse body = BetResponse.from(bet);

        if (!result.created()) {
            return ResponseEntity.ok(body);          // replay: 200, nothing new created
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(bet.getId())
                .toUri();

        return ResponseEntity.created(location).body(body);   // 201
    }

    @GetMapping("/{id}")
    public BetResponse get(@PathVariable UUID id) {
        return BetResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    public BetResponse cancel(@PathVariable UUID id) {
        return BetResponse.from(service.cancel(id));
    }

    @PostMapping("/{id}/settle")
    public BetResponse settle(@PathVariable UUID id, @Valid @RequestBody SettleBetRequest request) {
        return BetResponse.from(service.settle(id, request.result()));
    }
}