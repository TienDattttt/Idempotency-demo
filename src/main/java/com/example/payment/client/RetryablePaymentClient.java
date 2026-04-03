package com.example.payment.client;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class RetryablePaymentClient {

    private final RestTemplate restTemplate;

    @Value("${payment.downstream.url:http://localhost:8080}")
    private String downstreamUrl;

    @Value("${payment.retry.max-retry:3}")
    private int maxRetry;

    @Value("${payment.retry.base-delay-ms:1000}")
    private long baseDelayMs;

    public PaymentResponse submitPayment(PaymentRequest request) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String endpoint = downstreamUrl + "/api/payments";

        for (int attempt = 0; attempt < maxRetry; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("X-Idempotency-Key", idempotencyKey);
                HttpEntity<PaymentRequest> httpEntity = new HttpEntity<>(request, headers);

                ResponseEntity<PaymentResponse> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    httpEntity,
                    PaymentResponse.class
                );
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
                throw new RuntimeException("Response không hợp lệ từ server");
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                    sleep(baseDelayMs);
                    continue;
                }
                throw ex;
            } catch (HttpServerErrorException | ResourceAccessException ex) {
                long delay = baseDelayMs * (1L << attempt);
                sleep(delay);
            }
        }

        throw new MaxRetryExceededException("Đã vượt quá số lần retry tối đa: " + maxRetry);
    }

    private void sleep(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }
}
