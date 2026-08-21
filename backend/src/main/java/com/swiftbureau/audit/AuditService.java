package com.swiftbureau.audit;

import com.swiftbureau.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(CurrentUser actor, String action, String entityType, String entityId, String payloadJson) {
        AuditEvent event = new AuditEvent();
        event.setTenantId(actor == null ? null : actor.tenantId());
        event.setActorUserId(actor == null ? null : actor.id());
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setPayloadJson(payloadJson);
        repository.save(event);
    }
}
