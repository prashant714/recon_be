package com.reconciliation.dashboard.controller;

import com.reconciliation.dashboard.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            HttpServletRequest request) {
        LocalDate to = toDate != null ? toDate : LocalDate.now();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(7);
        return dashboardService.summary(resolveMerchantId(request), from, to);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            HttpServletRequest request) {
        LocalDate to = toDate != null ? toDate : LocalDate.now();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(30);
        return dashboardService.metrics(resolveMerchantId(request), from, to);
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(
            @RequestParam(defaultValue = "8") int limit,
            HttpServletRequest request) {
        return dashboardService.activity(resolveMerchantId(request), limit);
    }

    @GetMapping("/trends")
    public Map<String, Object> trends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            HttpServletRequest request) {
        LocalDate to = toDate != null ? toDate : LocalDate.now();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(7);
        return dashboardService.trends(resolveMerchantId(request), from, to);
    }

    private String resolveMerchantId(HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        return merchantId == null || merchantId.isBlank() ? "merchant_001" : merchantId;
    }
}
