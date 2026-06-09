package com.aicall.controller.tenant;

import com.aicall.context.UserContext;
import com.aicall.service.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/tenant/portal/export")
@RequiredArgsConstructor
public class TenantExportController {

    private final ExportService exportService;

    @GetMapping("/call-record")
    public void exportCallRecord(
            HttpServletResponse response,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) throws IOException {
        exportService.exportCallRecords(response, start, end, UserContext.get().getTenantId(), false);
    }

    @GetMapping("/balance-log")
    public void exportBalanceLog(
            HttpServletResponse response,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) Integer type) throws IOException {
        exportService.exportBalanceLogs(response, UserContext.get().getTenantId(), start, end, type);
    }

    @GetMapping("/intent")
    public void exportIntent(
            HttpServletResponse response,
            @RequestParam(required = false) String level) throws IOException {
        exportService.exportIntentCustomers(response, UserContext.get().getTenantId(), level);
    }

    @GetMapping("/customer-template")
    public void customerTemplate(HttpServletResponse response) throws IOException {
        exportService.exportCustomerTemplate(response);
    }
}
