package com.example.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.model.Payment;
import com.example.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void processPayment_whenRepositorySaveSucceeds_createsSuccessPaymentAndReturnsResponse() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder()
            .amount(150_000L)
            .description("Thanh toan don hang #123")
            .build();
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0, Payment.class);
            if (payment.getCreatedAt() == null) {
                payment.setCreatedAt(LocalDateTime.now());
            }
            return payment;
        });

        PaymentResponse response = paymentService.processPayment(idempotencyKey, request);

        verify(paymentRepository, times(1)).save(any(Payment.class));
        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getAmount()).isEqualTo(request.getAmount());
        assertThat(response.getStatus()).isEqualTo(Payment.STATUS_SUCCESS);
        assertThat(response.getIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    void processPayment_whenRepositorySaveThrowsException_throwsRuntimeException() {
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder()
            .amount(150_000L)
            .description("Failing payment")
            .build();
        when(paymentRepository.save(any(Payment.class))).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> paymentService.processPayment(idempotencyKey, request))
            .isInstanceOf(RuntimeException.class);
        verify(paymentRepository, times(1)).save(any(Payment.class));
    }
}
