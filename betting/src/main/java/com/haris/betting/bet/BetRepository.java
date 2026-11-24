package com.haris.betting.bet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BetRepository extends JpaRepository<Bet, UUID> {

    Optional<Bet> findByIdempotencyKey(String idempotencyKey);
}