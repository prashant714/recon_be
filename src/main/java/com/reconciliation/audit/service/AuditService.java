package com.reconciliation.audit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconciliation.audit.entity.AuditLog;
import com.reconciliation.audit.repository.AuditLogRepository;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AuditLog log(
            String actor,
            String action,
            String entityType,
            Long entityId,
            Object oldValue,
            Object newValue,
            String ipAddress) {
        return auditLogRepository.save(AuditLog.builder()
                .actor(actor)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .oldValue(toMap(oldValue))
                .newValue(toMap(newValue))
                .ipAddress(parseIp(ipAddress))
                .build());
    }

    public List<AuditLog> search(
            String entityType,
            Long entityId,
            String actor) {
        return auditLogRepository.findAll().stream()
                .filter(log -> entityType == null || entityType.equalsIgnoreCase(log.getEntityType()))
                .filter(log -> entityId == null || entityId.equals(log.getEntityId()))
                .filter(log -> actor == null || actor.equalsIgnoreCase(log.getActor()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private InetAddress parseIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(ipAddress);
        } catch (Exception ignored) {
            return null;
        }
    }
}
