package com.example.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.model.IdempotencyKey;
import com.example.payment.repository.IdempotencyKeyRepository;
import com.example.payment.service.impl.IdempotencyServiceImpl;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @InjectMocks
    private IdempotencyServiceImpl idempotencyService;

    @Test
    void findKey_whenKeyDoesNotExist_returnsEmptyOptional() {
        String key = "missing-key";
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.empty());

        Optional<IdempotencyKey> result = idempotencyService.findKey(key);

        assertThat(result).isEmpty();
    }

    @Test
    void findKey_whenKeyExists_returnsOptionalWithValue() {
        String key = "existing-key";
        IdempotencyKey entity = IdempotencyKey.builder().id(key).status(IdempotencyKey.STATUS_COMPLETED).build();
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.of(entity));

        Optional<IdempotencyKey> result = idempotencyService.findKey(key);

        assertThat(result).isPresent().contains(entity);
    }

    @Test
    void markInFlight_whenCalled_createsInFlightRecordAndPersistsImmediately() {
        String key = "new-key";
        when(idempotencyKeyRepository.saveAndFlush(any(IdempotencyKey.class)))
            .thenAnswer(invocation -> invocation.getArgument(0, IdempotencyKey.class));

        IdempotencyKey saved = idempotencyService.markInFlight(key);

        assertThat(saved.getId()).isEqualTo(key);
        assertThat(saved.getStatus()).isEqualTo(IdempotencyKey.STATUS_IN_FLIGHT);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(Duration.between(saved.getCreatedAt(), saved.getExpiresAt())).isEqualTo(Duration.ofHours(24));
        verify(idempotencyKeyRepository, times(1)).saveAndFlush(any(IdempotencyKey.class));
    }

    @Test
    void markCompleted_whenKeyExists_updatesCompletedStatusAndResponseFields() {
        String key = "completed-key";
        IdempotencyKey existing = IdempotencyKey.builder()
            .id(key)
            .status(IdempotencyKey.STATUS_IN_FLIGHT)
            .createdAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
        when(idempotencyKeyRepository.findById(key)).thenReturn(Optional.of(existing));

        idempotencyService.markCompleted(key, 201, "{\"transactionId\":\"tx-1\"}");

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyRepository, times(1)).save(captor.capture());
        IdempotencyKey updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(IdempotencyKey.STATUS_COMPLETED);
        assertThat(updated.getResponseCode()).isEqualTo(201);
        assertThat(updated.getResponseBody()).isEqualTo("{\"transactionId\":\"tx-1\"}");
    }

    @Test
    void isExpired_whenExpiresAtIsInPast_returnsTrue() {
        IdempotencyKey key = IdempotencyKey.builder()
            .id("k1")
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .build();

        boolean expired = idempotencyService.isExpired(key);

        assertThat(expired).isTrue();
    }

    @Test
    void isExpired_whenExpiresAtIsInFuture_returnsFalse() {
        IdempotencyKey key = IdempotencyKey.builder()
            .id("k2")
            .expiresAt(LocalDateTime.now().plusMinutes(1))
            .build();

        boolean expired = idempotencyService.isExpired(key);

        assertThat(expired).isFalse();
    }
}
