package com.example.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class RetryablePaymentClientTest {

    @Mock
    private RestTemplate restTemplate;

    private RetryablePaymentClient retryablePaymentClient;

    @BeforeEach
    void setUp() {
        retryablePaymentClient = new RetryablePaymentClient(restTemplate);
        ReflectionTestUtils.setField(retryablePaymentClient, "downstreamUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(retryablePaymentClient, "maxRetry", 3);
        ReflectionTestUtils.setField(retryablePaymentClient, "baseDelayMs", 1L);
    }

    @Test
    void submitPayment_whenTimeoutsOccur_usesSingleIdempotencyKeyAcrossAllRetries() {
        PaymentRequest request = PaymentRequest.builder().amount(100_000L).description("retry").build();
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(PaymentResponse.class)))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> retryablePaymentClient.submitPayment(request))
            .isInstanceOf(MaxRetryExceededException.class);

        ArgumentCaptor<HttpEntity<PaymentRequest>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(3)).exchange(
            eq("http://localhost:8080/api/payments"),
            eq(HttpMethod.POST),
            entityCaptor.capture(),
            eq(PaymentResponse.class)
        );
        List<String> idempotencyKeys = entityCaptor.getAllValues().stream()
            .map(httpEntity -> httpEntity.getHeaders().getFirst("X-Idempotency-Key"))
            .collect(Collectors.toList());

        assertThat(idempotencyKeys).hasSize(3);
        assertThat(idempotencyKeys.stream().distinct()).hasSize(1);
        assertThat(UUID.fromString(idempotencyKeys.get(0))).isNotNull();
    }

    @Test
    void submitPayment_whenRetryConditionApplies_callsHttpExactlyThreeTimesAndThrowsAfterThirdAttempt() {
        PaymentRequest request = PaymentRequest.builder().amount(200_000L).description("max-retry").build();
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(PaymentResponse.class)))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> retryablePaymentClient.submitPayment(request))
            .isInstanceOf(MaxRetryExceededException.class);

        verify(restTemplate, times(3)).exchange(
            eq("http://localhost:8080/api/payments"),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(PaymentResponse.class)
        );
    }

    @Test
    void submitPayment_whenTimeoutsOccur_usesExponentialBackoffDelayForRetries() throws Exception {
        PaymentRequest request = PaymentRequest.builder().amount(300_000L).description("backoff").build();
        ReflectionTestUtils.setField(retryablePaymentClient, "baseDelayMs", 100L);

        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(PaymentResponse.class)))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new ResourceAccessException("timeout"))
            .thenReturn(org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.builder()
                .transactionId(UUID.randomUUID())
                .status("SUCCESS")
                .amount(300_000L)
                .description("backoff")
                .idempotencyKey("k")
                .build()));

        long start = System.currentTimeMillis();
        PaymentResponse response = retryablePaymentClient.submitPayment(request);
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(response).isNotNull();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300L);
        assertThat(elapsedMs).isLessThan(1_500L);
    }

    @Test
    void submitPayment_whenClientErrorOccurs_doesNotRetryAndThrowsImmediately() {
        PaymentRequest request = PaymentRequest.builder().amount(100_000L).description("bad-request").build();
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(PaymentResponse.class)))
            .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> retryablePaymentClient.submitPayment(request))
            .isInstanceOf(HttpClientErrorException.class);

        verify(restTemplate, times(1)).exchange(
            eq("http://localhost:8080/api/payments"),
            eq(HttpMethod.POST),
            any(HttpEntity.class),
            eq(PaymentResponse.class)
        );
    }
}
