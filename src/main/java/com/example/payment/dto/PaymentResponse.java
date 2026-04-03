package com.example.payment.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private UUID transactionId;
    private String status;
    private Long amount;
    private String description;
    private LocalDateTime createdAt;
    private String idempotencyKey;
}
