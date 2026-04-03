package com.example.payment.service;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.model.Payment;
import com.example.payment.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest request) {
        Payment payment = Payment.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(idempotencyKey)
            .amount(request.getAmount())
            .description(request.getDescription())
            .status(Payment.STATUS_SUCCESS)
            .build();
        try {
            Payment savedPayment = paymentRepository.save(payment);
            return toResponse(savedPayment);
        } catch (Exception ex) {
            payment.setStatus(Payment.STATUS_FAILED);
            log.error("Failed to process payment for key={}", idempotencyKey, ex);
            throw new RuntimeException("Xử lý thanh toán thất bại", ex);
        }
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByTransactionId(UUID transactionId) {
        return paymentRepository.findById(transactionId).map(this::toResponse);
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
            .transactionId(payment.getId())
            .status(payment.getStatus())
            .amount(payment.getAmount())
            .description(payment.getDescription())
            .createdAt(payment.getCreatedAt())
            .idempotencyKey(payment.getIdempotencyKey())
            .build();
    }
}
