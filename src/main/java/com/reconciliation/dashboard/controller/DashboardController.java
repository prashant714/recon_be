package com.reconciliation.dashboard.controller;

import com.reconciliation.dashboard.service.DashboardService;
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
    public Map<String, Object> summary(@RequestParam(defaultValue = "7") int days) {
        return dashboardService.summary(days);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return dashboardService.metrics();
    }
}
