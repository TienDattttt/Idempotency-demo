package com.example.payment.service;

import com.example.payment.model.IdempotencyKey;
import java.util.Optional;

public interface IdempotencyService {
    Optional<IdempotencyKey> findKey(String key);

    IdempotencyKey markInFlight(String key);

    void markCompleted(String key, int responseCode, String responseBody);

    boolean isExpired(IdempotencyKey key);

    void deleteKey(String key);
}
