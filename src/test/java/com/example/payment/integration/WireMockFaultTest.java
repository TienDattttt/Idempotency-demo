package com.example.payment.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payment.client.MaxRetryExceededException;
import com.example.payment.client.RetryablePaymentClient;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class WireMockFaultTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().port(8089))
        .build();

    private RetryablePaymentClient retryablePaymentClient;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplateBuilder()
            .setConnectTimeout(Duration.ofMillis(300))
            .setReadTimeout(Duration.ofMillis(300))
            .build();

        retryablePaymentClient = new RetryablePaymentClient(restTemplate);
        ReflectionTestUtils.setField(retryablePaymentClient, "downstreamUrl", "http://localhost:8089");
        ReflectionTestUtils.setField(retryablePaymentClient, "maxRetry", 3);
        ReflectionTestUtils.setField(retryablePaymentClient, "baseDelayMs", 1L);
        wireMock.resetAll();
    }

    @Test
    void submitPayment_withNetworkTimeouts_retriesAndSucceedsOnThirdAttemptUsingSameIdempotencyKey() throws Exception {
        stubTimeoutThenSuccess("TC-008-timeout");

        PaymentResponse response = retryablePaymentClient.submitPayment(paymentRequest(100_000L));

        assertThat(response).isNotNull();
        wireMock.verify(3, postRequestedFor(urlEqualTo("/api/payments")));
        assertAllRequestsUseSameIdempotencyKey();
    }

    @Test
    void submitPayment_withLostResponses_retriesAndSucceedsOnThirdAttemptUsingSameIdempotencyKey() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-009-lost-response")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("SECOND_ATTEMPT")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-009-lost-response")
            .whenScenarioStateIs("SECOND_ATTEMPT")
            .willSetStateTo("THIRD_ATTEMPT")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-009-lost-response")
            .whenScenarioStateIs("THIRD_ATTEMPT")
            .willReturn(successResponse()));

        PaymentResponse response = retryablePaymentClient.submitPayment(paymentRequest(110_000L));

        assertThat(response).isNotNull();
        wireMock.verify(3, postRequestedFor(urlEqualTo("/api/payments")));
        assertAllRequestsUseSameIdempotencyKey();
    }

    @Test
    void submitPayment_whenServerReturns500_thenRetriesAndSucceedsOnSecondAttempt() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-010-server-error")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("RECOVERED")
            .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"internal\"}")));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-010-server-error")
            .whenScenarioStateIs("RECOVERED")
            .willReturn(successResponse()));

        PaymentResponse response = retryablePaymentClient.submitPayment(paymentRequest(120_000L));

        assertThat(response).isNotNull();
        wireMock.verify(2, postRequestedFor(urlEqualTo("/api/payments")));
        assertAllRequestsUseSameIdempotencyKey();
    }

    @Test
    void submitPayment_whenAllAttemptsTimeout_throwsMaxRetryExceededAndDoesNotExceedThreeRequests() {
        wireMock.stubFor(post(urlEqualTo("/api/payments")).willReturn(aResponse().withFixedDelay(5_000).withStatus(201)));

        assertThatThrownBy(() -> retryablePaymentClient.submitPayment(paymentRequest(130_000L)))
            .isInstanceOf(MaxRetryExceededException.class);

        wireMock.verify(3, postRequestedFor(urlEqualTo("/api/payments")));
    }

    @Test
    void submitPayment_withReducedBaseDelay_appliesDelayButNotExcessivelyLong() throws Exception {
        ReflectionTestUtils.setField(retryablePaymentClient, "baseDelayMs", 100L);

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-012-short-delay")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("SECOND")
            .willReturn(aResponse().withFixedDelay(5_000).withStatus(201)));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario("TC-012-short-delay")
            .whenScenarioStateIs("SECOND")
            .willReturn(successResponse()));

        long start = System.currentTimeMillis();
        PaymentResponse response = retryablePaymentClient.submitPayment(paymentRequest(140_000L));
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(response).isNotNull();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100L);
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    private void stubTimeoutThenSuccess(String scenarioName) {
        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario(scenarioName)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("SECOND_ATTEMPT")
            .willReturn(aResponse().withFixedDelay(5_000).withStatus(201)));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario(scenarioName)
            .whenScenarioStateIs("SECOND_ATTEMPT")
            .willSetStateTo("THIRD_ATTEMPT")
            .willReturn(aResponse().withFixedDelay(5_000).withStatus(201)));

        wireMock.stubFor(post(urlEqualTo("/api/payments"))
            .inScenario(scenarioName)
            .whenScenarioStateIs("THIRD_ATTEMPT")
            .willReturn(successResponse()));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder successResponse() {
        return aResponse()
            .withHeader("Content-Type", "application/json")
            .withStatus(201)
            .withBody("""
                {
                  "transactionId": "11111111-1111-1111-1111-111111111111",
                  "status": "SUCCESS",
                  "amount": 150000,
                  "description": "WireMock payment",
                  "createdAt": "2025-01-01T10:00:00",
                  "idempotencyKey": "placeholder-key"
                }
                """);
    }

    private PaymentRequest paymentRequest(Long amount) {
        return PaymentRequest.builder()
            .amount(amount)
            .description("wiremock-test")
            .build();
    }

    private void assertAllRequestsUseSameIdempotencyKey() {
        List<String> keys = wireMock.findAll(postRequestedFor(urlEqualTo("/api/payments"))).stream()
            .map(loggedRequest -> loggedRequest.getHeader("X-Idempotency-Key"))
            .toList();
        assertThat(keys).isNotEmpty();
        assertThat(keys.stream().distinct()).hasSize(1);
        assertThat(UUID.fromString(keys.get(0))).isNotNull();
    }
}
