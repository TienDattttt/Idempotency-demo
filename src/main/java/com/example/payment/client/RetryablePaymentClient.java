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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class RetryablePaymentClient {

    private static final int MAX_RETRY = 3;
    private static final long BASE_DELAY_MS = 1000L;

    private final RestTemplate restTemplate;

    @Value("${payment.downstream.url:http://localhost:8080}")
    private String downstreamUrl;

    public PaymentResponse submitPayment(PaymentRequest request) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String endpoint = downstreamUrl + "/api/payments";

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
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
                    Thread.sleep(BASE_DELAY_MS);
                    continue;
                }
                throw ex;
            } catch (ResourceAccessException ex) {
                long delay = BASE_DELAY_MS * (1L << attempt);
                Thread.sleep(delay);
            }
        }

        throw new MaxRetryExceededException("Đã vượt quá số lần retry tối đa: " + MAX_RETRY);
    }
}
