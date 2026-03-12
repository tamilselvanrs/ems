package com.ems.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class AuditService {

    public void log(
            String actorId,
            String actorType,
            String eventType,
            String entityType,
            String entityId,
            Map<String, Object> metadata) {
        log.info("AUDIT: actor={}/{}, event={}, entity={}/{}, metadata={}",
            actorType, actorId, eventType, entityType, entityId, metadata);
    }
}
