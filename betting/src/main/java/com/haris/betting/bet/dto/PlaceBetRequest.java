package com.haris.betting.bet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlaceBetRequest(

        @NotBlank @Size(max = 100)
        String userId,

        @NotBlank @Size(max = 100)
        String selectionId,

        @NotNull
        @DecimalMin(value = "0.01", message = "stake must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "stake allows at most 2 decimal places")
        BigDecimal stake,

        @NotNull
        @DecimalMin(value = "1.01", message = "odds must be at least 1.01")
        @Digits(integer = 7, fraction = 3, message = "odds allow at most 3 decimal places")
        BigDecimal odds
) {
}