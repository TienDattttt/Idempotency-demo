package com.example.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.model.IdempotencyKey;
import com.example.payment.repository.IdempotencyKeyRepository;
import com.example.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@Transactional
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @BeforeEach
    void cleanDatabase() {
        paymentRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    void createPayment_withNewKey_returnsCreatedAndPersistsPaymentAndIdempotencyData() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult result = performPaymentRequest(key, 150_000L, null).andExpect(status().isCreated()).andReturn();
        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);

        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getAmount()).isEqualTo(150_000L);
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(paymentRepository.findAll()).hasSize(1);
        assertThat(idempotencyKeyRepository.findById(key))
            .isPresent()
            .get()
            .extracting(IdempotencyKey::getStatus)
            .isEqualTo(IdempotencyKey.STATUS_COMPLETED);
    }

    @Test
    void createPayment_withDescription_returnsCreatedWithDescriptionInResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        String description = "Test payment";

        MvcResult result = performPaymentRequest(key, 50_000L, description).andExpect(status().isCreated()).andReturn();
        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);

        assertThat(response.getDescription()).isEqualTo(description);
    }

    @Test
    void createPayment_withCompletedDuplicateKey_returnsOriginalTransactionWithoutCreatingNewPayment() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult firstResult = performPaymentRequest(key, 120_000L, "first").andExpect(status().isCreated()).andReturn();
        PaymentResponse firstResponse = objectMapper.readValue(firstResult.getResponse().getContentAsString(), PaymentResponse.class);

        MvcResult secondResult = performPaymentRequest(key, 120_000L, "first").andExpect(status().isOk()).andReturn();
        PaymentResponse secondResponse = objectMapper.readValue(secondResult.getResponse().getContentAsString(), PaymentResponse.class);

        assertThat(secondResponse.getTransactionId()).isEqualTo(firstResponse.getTransactionId());
        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Test
    void createPayment_whenClientRetriesAfterLosingResponse_returnsCachedTransaction() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult firstResult = performPaymentRequest(key, 88_000L, "lost-response").andExpect(status().isCreated()).andReturn();
        PaymentResponse firstResponse = objectMapper.readValue(firstResult.getResponse().getContentAsString(), PaymentResponse.class);

        MvcResult retryResult = performPaymentRequest(key, 88_000L, "lost-response").andExpect(status().isOk()).andReturn();
        PaymentResponse retryResponse = objectMapper.readValue(retryResult.getResponse().getContentAsString(), PaymentResponse.class);

        assertThat(retryResponse.getTransactionId()).isEqualTo(firstResponse.getTransactionId());
        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Test
    void createPayment_withConcurrentRequestsSameKey_createsSinglePaymentAndReturnsExpectedStatuses() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Callable<Integer> requestTask = () -> {
            latch.await(3, TimeUnit.SECONDS);
            return performPaymentRequest(key, 99_000L, "concurrent")
                .andReturn()
                .getResponse()
                .getStatus();
        };

        Future<Integer> first = executor.submit(requestTask);
        Future<Integer> second = executor.submit(requestTask);
        latch.countDown();

        List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertThat(paymentRepository.findAll()).hasSize(1);
        assertThat(statuses).contains(201);
        assertThat(statuses.stream().anyMatch(code -> code == 200 || code == 409 || code == 500)).isTrue();
    }

    @Test
    void getPaymentByTransactionId_whenTransactionExists_returnsTransaction() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult created = performPaymentRequest(key, 123_000L, "lookup").andExpect(status().isCreated()).andReturn();
        PaymentResponse createdResponse = objectMapper.readValue(created.getResponse().getContentAsString(), PaymentResponse.class);

        mockMvc.perform(get("/api/payments/{transactionId}", createdResponse.getTransactionId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transactionId").value(createdResponse.getTransactionId().toString()));
    }

    @Test
    void getPaymentByTransactionId_whenTransactionNotFound_returns404Error() throws Exception {
        mockMvc.perform(get("/api/payments/{transactionId}", "00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createPayment_whenIdempotencyKeyInFlight_returnsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey.builder()
            .id(key)
            .status(IdempotencyKey.STATUS_IN_FLIGHT)
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build());

        performPaymentRequest(key, 222_000L, "in-flight")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createPayment_withoutIdempotencyHeader_returnsBadRequest() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("amount", 15_000L, "description", "missing-header"));
        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Missing")));
    }

    @Test
    void createPayment_withAmountZero_returnsUnprocessableEntityAndDoesNotPersist() throws Exception {
        String key = UUID.randomUUID().toString();

        performPaymentRequest(key, 0L, "invalid")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").exists());

        assertThat(paymentRepository.findAll()).isEmpty();
        assertThat(idempotencyKeyRepository.findAll()).isEmpty();
    }

    @Test
    void createPayment_withNegativeAmount_returnsUnprocessableEntityAndDoesNotPersist() throws Exception {
        String key = UUID.randomUUID().toString();

        performPaymentRequest(key, -500L, "invalid-negative")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").exists());

        assertThat(paymentRepository.findAll()).isEmpty();
    }

    @Test
    void createPayment_withVeryLargeAmount_returnsCreated() throws Exception {
        String key = UUID.randomUUID().toString();

        performPaymentRequest(key, 9_999_999_999L, "large-amount")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value(9_999_999_999L));
    }

    @Test
    void createPayment_withExpiredIdempotencyKey_treatsRequestAsNew() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID oldTransactionId = UUID.randomUUID();
        PaymentResponse oldResponse = PaymentResponse.builder()
            .transactionId(oldTransactionId)
            .status("SUCCESS")
            .amount(10_000L)
            .description("old")
            .createdAt(LocalDateTime.now().minusDays(1))
            .idempotencyKey(key)
            .build();
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey.builder()
            .id(key)
            .status(IdempotencyKey.STATUS_COMPLETED)
            .responseCode(201)
            .responseBody(objectMapper.writeValueAsString(oldResponse))
            .createdAt(LocalDateTime.now().minusDays(1))
            .expiresAt(LocalDateTime.now().minusHours(1))
            .build());

        MvcResult result = performPaymentRequest(key, 77_000L, "expired-key").andExpect(status().isCreated()).andReturn();
        PaymentResponse newResponse = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);

        assertThat(newResponse.getTransactionId()).isNotEqualTo(oldTransactionId);
        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Disabled("BUG-003: Open - Content-Type handling behavior under review")
    @Test
    void createPayment_withTextPlainContentType_returns415WithSpecificError() throws Exception {
        mockMvc.perform(post("/api/payments")
                .contentType(MediaType.TEXT_PLAIN)
                .content("{\"amount\":1000}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("application/json")));
    }

    private org.springframework.test.web.servlet.ResultActions performPaymentRequest(String key, Long amount, String description)
        throws Exception {
        PaymentRequest request = PaymentRequest.builder().amount(amount).description(description).build();
        return mockMvc.perform(post("/api/payments")
            .header("X-Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)));
    }
}
