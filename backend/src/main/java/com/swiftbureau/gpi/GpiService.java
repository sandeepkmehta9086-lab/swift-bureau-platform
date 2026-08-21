package com.swiftbureau.gpi;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        repository.save(event);
    }

    public List<GpiEvent> track(UUID tenantId, String uetr) {
        return repository.findByTenantIdAndUetrOrderByOccurredAtAsc(tenantId, uetr);
    }
}
