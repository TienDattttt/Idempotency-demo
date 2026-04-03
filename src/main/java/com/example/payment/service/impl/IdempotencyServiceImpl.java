package com.example.payment.service.impl;

import com.example.payment.model.IdempotencyKey;
import com.example.payment.repository.IdempotencyKeyRepository;
import com.example.payment.service.IdempotencyService;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyKey> findKey(String key) {
        return idempotencyKeyRepository.findById(key);
    }

    @Override
    @Transactional
    public IdempotencyKey markInFlight(String key) {
        LocalDateTime now = LocalDateTime.now();
        IdempotencyKey idempotencyKey = IdempotencyKey.builder()
            .id(key)
            .status(IdempotencyKey.STATUS_IN_FLIGHT)
            .createdAt(now)
            .expiresAt(now.plusHours(24))
            .build();
        return idempotencyKeyRepository.saveAndFlush(idempotencyKey);
    }

    @Override
    @Transactional
    public void markCompleted(String key, int responseCode, String responseBody) {
        IdempotencyKey idempotencyKey = idempotencyKeyRepository.findById(key)
            .orElseThrow(() -> new IllegalStateException("Không tìm thấy idempotency key: " + key));
        idempotencyKey.setStatus(IdempotencyKey.STATUS_COMPLETED);
        idempotencyKey.setResponseCode(responseCode);
        idempotencyKey.setResponseBody(responseBody);
        idempotencyKeyRepository.save(idempotencyKey);
    }

    @Override
    public boolean isExpired(IdempotencyKey key) {
        return key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now());
    }

    @Override
    @Transactional
    public void deleteKey(String key) {
        idempotencyKeyRepository.deleteById(key);
        idempotencyKeyRepository.flush();
    }
}
