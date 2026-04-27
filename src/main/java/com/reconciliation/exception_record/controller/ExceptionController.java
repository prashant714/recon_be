package com.reconciliation.exception_record.controller;

import com.reconciliation.exception_record.entity.ExceptionRecord;
import com.reconciliation.exception_record.service.ExceptionQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exceptions")
@RequiredArgsConstructor
public class ExceptionController {

    private final ExceptionQueryService exceptionQueryService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> listExceptions(
            @RequestParam(defaultValue = "7")    int    days,
            @RequestParam(required = false)      String status,
            @RequestParam(required = false)      String provider,
            @RequestParam(required = false)      String type,
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "50")   int    limit) {
        return ResponseEntity.ok(exceptionQueryService.list(days, provider, type, status, page, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getException(@PathVariable Long id) {
        return ResponseEntity.ok(exceptionQueryService.detail(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ExceptionRecord> resolveException(
            @PathVariable Long id,
            @RequestBody UpdateExceptionRequest body,
            HttpServletRequest request,
            @RequestHeader(value = "X-Actor", defaultValue = "admin") String actor) {
        ExceptionRecord updated = exceptionQueryService.update(
                id, body.status(), body.notes(), actor, request.getRemoteAddr());
        return ResponseEntity.ok(updated);
    }

    public record UpdateExceptionRequest(String status, String notes) {}
}
