package com.haris.betting.bet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bets")
public class Bet {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "selection_id", nullable = false, length = 100)
    private String selectionId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal stake;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal odds;

    @Column(name = "potential_payout", nullable = false, precision = 19, scale = 2)
    private BigDecimal potentialPayout;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BetStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Bet() {
        // required by JPA
    }

    public Bet(String userId, String selectionId, BigDecimal stake, BigDecimal odds,
               BigDecimal potentialPayout, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.selectionId = selectionId;
        this.stake = stake;
        this.odds = odds;
        this.potentialPayout = potentialPayout;
        this.idempotencyKey = idempotencyKey;
        this.status = BetStatus.PLACED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public String getSelectionId() { return selectionId; }
    public BigDecimal getStake() { return stake; }
    public BigDecimal getOdds() { return odds; }
    public BigDecimal getPotentialPayout() { return potentialPayout; }
    public BetStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // Status changes will go through methods like cancel(), written later,
    // so the transition rules live in one place. No public setters on purpose.
}