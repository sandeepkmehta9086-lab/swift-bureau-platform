package com.swiftbureau.gpi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GpiService {

    private final GpiEventRepository repository;

    public GpiService(GpiEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void append(UUID tenantId, String uetr, String hopStatus, String bic, String detail) {
        GpiEvent event = new GpiEvent();
        event.setTenantId(tenantId);
        event.setUetr(uetr);
        event.setHopStatus(hopStatus);
        event.setBic(bic);
        event.setDetail(detail);
        event.setOccurredAt(nextTimestamp(tenantId, uetr));
        repository.save(event);
    }

    /**
     * Hops for one UETR are often written in the same instant (e.g. pacs.002 then camt.054 from the stub).
     * Keep the timeline strictly ordered by nudging a colliding timestamp just past the previous hop.
     */
    private Instant nextTimestamp(UUID tenantId, String uetr) {
        Instant now = Instant.now();
        List<GpiEvent> existing = repository.findByTenantIdAndUetrOrderByOccurredAtAsc(tenantId, uetr);
        if (existing.isEmpty()) {
            return now;
        }
        Instant last = existing.get(existing.size() - 1).getOccurredAt();
        return now.isAfter(last) ? now : last.plusMillis(1);
    }

    public List<GpiEvent> track(UUID tenantId, String uetr) {
        return repository.findByTenantIdAndUetrOrderByOccurredAtAsc(tenantId, uetr);
    }
}
