package com.example.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.model.IdempotencyKey;
import com.example.payment.service.IdempotencyService;
import com.example.payment.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentControllerUnitTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private PaymentController paymentController;

    @Test
    void createPayment_whenTransactionTemplateReturnsNull_throwsIllegalStateException() {
        PaymentRequest request = PaymentRequest.builder().amount(1_000L).description("test").build();
        String key = "unit-test-key";
        when(idempotencyService.findKey(eq(key))).thenReturn(Optional.empty());
        when(idempotencyService.markInFlight(eq(key))).thenReturn(IdempotencyKey.builder().id(key).status("IN_FLIGHT").build());
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenReturn(null);

        assertThatThrownBy(() -> paymentController.createPayment(key, request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rolled back");
    }

    @Test
    void createPayment_whenAmountIsNull_returnsUnprocessableEntity() {
        PaymentRequest request = PaymentRequest.builder().amount(null).description("null-amount").build();

        ResponseEntity<?> response = paymentController.createPayment("key-null-amount", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isInstanceOf(Map.class);
    }

    @Test
    void createPayment_whenAmountIsZero_returnsUnprocessableEntity() {
        PaymentRequest request = PaymentRequest.builder().amount(0L).description("zero-amount").build();

        ResponseEntity<?> response = paymentController.createPayment("key-zero-amount", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isInstanceOf(Map.class);
    }

    @Test
    void createPayment_whenExistingKeyHasUnknownStatus_processesAsNewAndReturnsCreated() throws Exception {
        String key = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder().amount(99_000L).description("unknown-status").build();
        IdempotencyKey existing = IdempotencyKey.builder()
            .id(key)
            .status("UNKNOWN")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();
        PaymentResponse paymentResponse = PaymentResponse.builder()
            .transactionId(UUID.randomUUID())
            .status("SUCCESS")
            .amount(99_000L)
            .description("unknown-status")
            .createdAt(LocalDateTime.now())
            .idempotencyKey(key)
            .build();

        when(idempotencyService.findKey(eq(key))).thenReturn(Optional.of(existing));
        when(idempotencyService.isExpired(eq(existing))).thenReturn(false);
        when(idempotencyService.markInFlight(eq(key))).thenReturn(existing);
        when(paymentService.processPayment(eq(key), any(PaymentRequest.class))).thenReturn(paymentResponse);
        when(objectMapper.writeValueAsString(eq(paymentResponse))).thenReturn("{\"status\":\"SUCCESS\"}");
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<PaymentResponse> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        ResponseEntity<?> response = paymentController.createPayment(key, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(paymentResponse);
    }

    @Test
    void createPayment_whenSerializeFails_throwsRuntimeException() throws Exception {
        String key = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder().amount(12_000L).description("serialize-fail").build();
        PaymentResponse paymentResponse = PaymentResponse.builder()
            .transactionId(UUID.randomUUID())
            .status("SUCCESS")
            .amount(12_000L)
            .description("serialize-fail")
            .createdAt(LocalDateTime.now())
            .idempotencyKey(key)
            .build();

        when(idempotencyService.findKey(eq(key))).thenReturn(Optional.empty());
        when(idempotencyService.markInFlight(eq(key))).thenReturn(IdempotencyKey.builder().id(key).status("IN_FLIGHT").build());
        when(paymentService.processPayment(eq(key), any(PaymentRequest.class))).thenReturn(paymentResponse);
        when(objectMapper.writeValueAsString(eq(paymentResponse)))
            .thenThrow(new JsonProcessingException("serialization failed") {
            });
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<PaymentResponse> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        assertThatThrownBy(() -> paymentController.createPayment(key, request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("serialize");
    }

    @Test
    void createPayment_whenDeserializeCachedResponseFails_throwsRuntimeException() throws Exception {
        String key = UUID.randomUUID().toString();
        PaymentRequest request = PaymentRequest.builder().amount(12_000L).description("deserialize-fail").build();
        IdempotencyKey cached = IdempotencyKey.builder()
            .id(key)
            .status(IdempotencyKey.STATUS_COMPLETED)
            .responseBody("{invalid-json}")
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();

        when(idempotencyService.findKey(eq(key))).thenReturn(Optional.of(cached));
        when(idempotencyService.isExpired(eq(cached))).thenReturn(false);
        when(objectMapper.readValue(eq("{invalid-json}"), eq(PaymentResponse.class)))
            .thenThrow(new JsonProcessingException("deserialization failed") {
            });

        assertThatThrownBy(() -> paymentController.createPayment(key, request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("deserialize");
    }
}
