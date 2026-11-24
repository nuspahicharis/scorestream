CREATE TABLE bets (
                      id               UUID          NOT NULL,
                      user_id          VARCHAR(100)  NOT NULL,
                      selection_id     VARCHAR(100)  NOT NULL,
                      stake            NUMERIC(19,2) NOT NULL,
                      odds             NUMERIC(10,3) NOT NULL,
                      potential_payout NUMERIC(19,2) NOT NULL,
                      status           VARCHAR(20)   NOT NULL,
                      idempotency_key  VARCHAR(100)  NOT NULL,
                      version          BIGINT        NOT NULL DEFAULT 0,
                      created_at       TIMESTAMPTZ   NOT NULL,
                      updated_at       TIMESTAMPTZ   NOT NULL,

                      CONSTRAINT pk_bets PRIMARY KEY (id),
                      CONSTRAINT uq_bets_idempotency_key UNIQUE (idempotency_key),
                      CONSTRAINT ck_bets_stake_positive CHECK (stake > 0),
                      CONSTRAINT ck_bets_odds_min CHECK (odds >= 1.01),
                      CONSTRAINT ck_bets_status CHECK (status IN ('PLACED', 'CANCELLED', 'WON', 'LOST'))
);

CREATE INDEX idx_bets_user_id ON bets (user_id);