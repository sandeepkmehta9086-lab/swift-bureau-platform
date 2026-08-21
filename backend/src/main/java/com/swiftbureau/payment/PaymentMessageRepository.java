package com.swiftbureau.payment;

import com.swiftbureau.domain.MessageDirection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMessageRepository extends JpaRepository<PaymentMessage, UUID> {
    List<PaymentMessage> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<PaymentMessage> findByTenantIdAndDirectionOrderByCreatedAtDesc(UUID tenantId, MessageDirection direction);

    Optional<PaymentMessage> findByIdAndTenantId(UUID id, UUID tenantId);

    List<PaymentMessage> findByUetr(String uetr);

    long countByTenantIdAndStatus(UUID tenantId, com.swiftbureau.domain.MessageStatus status);

    long countByTenantId(UUID tenantId);
}
