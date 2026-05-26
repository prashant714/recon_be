package com.reconciliation.dashboard.controller;

import com.reconciliation.dashboard.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        return dashboardService.summary(resolveMerchantId(request), days);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest request) {
        return dashboardService.metrics(resolveMerchantId(request));
    }

    @GetMapping("/activity")
    public Map<String, Object> activity(
            @RequestParam(defaultValue = "8") int limit,
            HttpServletRequest request) {
        return dashboardService.activity(resolveMerchantId(request), limit);
    }

    @GetMapping("/trends")
    public Map<String, Object> trends(
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        return dashboardService.trends(resolveMerchantId(request), days);
    }

    private String resolveMerchantId(HttpServletRequest request) {
        String merchantId = (String) request.getAttribute("merchantId");
        return merchantId == null || merchantId.isBlank() ? "merchant_001" : merchantId;
    }
}
