package com.example.payment.controller;

import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.model.IdempotencyKey;
import com.example.payment.service.IdempotencyService;
import com.example.payment.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @PostMapping(value = "/api/payments",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> createPayment(
        @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
        @Valid @RequestBody PaymentRequest request
    ) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ResponseEntity.badRequest()
                .body(errorBody("Missing X-Idempotency-Key header"));
        }

        if (request.getAmount() == null || request.getAmount() <= 0) {
            return ResponseEntity.unprocessableEntity()
                .body(errorBody("Số tiền phải lớn hơn 0"));
        }

        Optional<IdempotencyKey> existingKey = idempotencyService.findKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey idempotencyRecord = existingKey.get();
            if (idempotencyService.isExpired(idempotencyRecord)) {
                log.warn("Expired idempotency key detected: key={}", idempotencyKey);
                idempotencyService.deleteKey(idempotencyKey);
            } else if (IdempotencyKey.STATUS_COMPLETED.equals(idempotencyRecord.getStatus())) {
                log.info("Payment action=CACHED key={} amount={}", idempotencyKey, request.getAmount());
                return ResponseEntity.ok(deserializeResponse(idempotencyRecord.getResponseBody()));
            } else if (IdempotencyKey.STATUS_IN_FLIGHT.equals(idempotencyRecord.getStatus())) {
                log.info("Payment action=IN_FLIGHT key={} amount={}", idempotencyKey, request.getAmount());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(errorBody("Yêu cầu đang được xử lý, vui lòng thử lại sau"));
            }
        }

        idempotencyService.markInFlight(idempotencyKey);
        log.info("Payment action=NEW key={} amount={}", idempotencyKey, request.getAmount());

        PaymentResponse response = transactionTemplate.execute(status -> {
            PaymentResponse processedPayment = paymentService.processPayment(idempotencyKey, request);
            try {
                String responseBody = objectMapper.writeValueAsString(processedPayment);
                idempotencyService.markCompleted(idempotencyKey, HttpStatus.CREATED.value(), responseBody);
                return processedPayment;
            } catch (JsonProcessingException ex) {
                throw new RuntimeException("Không thể serialize payment response", ex);
            }
        });

        if (response == null) {
            throw new IllegalStateException("Payment transaction rolled back");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(value = "/api/payments/{transactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> getPaymentByTransactionId(@PathVariable UUID transactionId) {
        return paymentService.findByTransactionId(transactionId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody("Không tìm thấy giao dịch")));
    }

    @GetMapping("/payment")
    public String paymentPage() {
        return "payment";
    }

    @GetMapping("/payment/result")
    public String paymentResultPage(
        @RequestParam(value = "success", defaultValue = "false") boolean success,
        @RequestParam(value = "transactionId", required = false) String transactionId,
        @RequestParam(value = "amount", required = false) Long amount,
        @RequestParam(value = "error", required = false) String error,
        Model model
    ) {
        model.addAttribute("success", success);
        model.addAttribute("transactionId", transactionId);
        model.addAttribute("amount", amount);
        model.addAttribute("error", error);
        return "payment-result";
    }

    private PaymentResponse deserializeResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, PaymentResponse.class);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Không thể deserialize cached response", ex);
        }
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
