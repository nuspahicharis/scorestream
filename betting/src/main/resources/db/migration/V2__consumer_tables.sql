CREATE TABLE processed_events (
                                  event_id     UUID        NOT NULL,
                                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

CREATE TABLE selection_stats (
                                 selection_id VARCHAR(100)  NOT NULL,
                                 bet_count    BIGINT        NOT NULL,
                                 total_stake  NUMERIC(19,2) NOT NULL,
                                 CONSTRAINT pk_selection_stats PRIMARY KEY (selection_id)
);