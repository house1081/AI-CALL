package com.aicall.service;

import com.aicall.common.CallStatus;
import com.aicall.common.HangupType;
import com.aicall.entity.BalanceLog;
import com.aicall.entity.CallRecord;
import com.aicall.entity.Customer;
import com.aicall.mapper.BalanceLogMapper;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.CustomerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExportService {

    private final CallRecordMapper callRecordMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final CustomerMapper customerMapper;

    public void exportCallRecords(HttpServletResponse response, LocalDate start, LocalDate end,
                                  Integer tenantId, boolean adminView) throws IOException {
        List<CallRecord> list = queryCallRecords(start, end, tenantId, null, null, null, null);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("通话记录");
            Row header = sheet.createRow(0);
            int col = 0;
            String[] headers = adminView
                    ? new String[]{"通话时间", "商户ID", "客户手机", "时长(秒)", "计费分钟", "扣费", "成本", "毛利", "对话记录", "意向", "状态", "挂断类型", "异常"}
                    : new String[]{"通话时间", "客户手机", "时长(秒)", "计费分钟", "扣费", "对话记录", "意向", "状态", "挂断", "扣费说明"};
            for (String h : headers) {
                header.createCell(col++).setCellValue(h);
            }
            int rowIdx = 1;
            for (CallRecord r : list) {
                Row row = sheet.createRow(rowIdx++);
                int c = 0;
                row.createCell(c++).setCellValue(formatTime(r.getCallTime()));
                if (adminView) {
                    row.createCell(c++).setCellValue(r.getTenantId());
                }
                row.createCell(c++).setCellValue(r.getCustomerPhone());
                row.createCell(c++).setCellValue(r.getCallDuration());
                row.createCell(c++).setCellValue(r.getBilledMinutes() != null ? r.getBilledMinutes() : 0);
                row.createCell(c++).setCellValue(r.getDeductAmount().doubleValue());
                if (adminView) {
                    row.createCell(c++).setCellValue(r.getCostAmount().doubleValue());
                    row.createCell(c++).setCellValue(r.getProfit().doubleValue());
                }
                row.createCell(c++).setCellValue(r.getDialogText() != null ? r.getDialogText() : "");
                row.createCell(c++).setCellValue(r.getLevel());
                row.createCell(c++).setCellValue(CallStatus.label(r.getCallStatus()));
                if (adminView) {
                    row.createCell(c++).setCellValue(r.getHangupType() != null ? r.getHangupType() : "");
                    row.createCell(c).setCellValue(r.getProfitAbnormal() != null && r.getProfitAbnormal() == 1 ? "负毛利" : "");
                } else {
                    row.createCell(c++).setCellValue(HangupType.tenantLabel(r.getHangupType()));
                    row.createCell(c).setCellValue(CallStatus.isConnected(r.getCallStatus()) ? "已扣费" : "未扣费");
                }
            }
            writeResponse(response, wb, "call_records.xlsx");
        }
    }

    public void exportBalanceLogs(HttpServletResponse response, Integer tenantId,
                                  LocalDate start, LocalDate end, Integer type) throws IOException {
        LambdaQueryWrapper<BalanceLog> q = new LambdaQueryWrapper<BalanceLog>()
                .eq(BalanceLog::getTenantId, tenantId)
                .ge(BalanceLog::getCreateTime, start.atStartOfDay())
                .lt(BalanceLog::getCreateTime, end.plusDays(1).atStartOfDay())
                .orderByDesc(BalanceLog::getId);
        if (type != null) {
            q.eq(BalanceLog::getType, type);
        }
        List<BalanceLog> list = balanceLogMapper.selectList(q);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("消费明细");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("时间");
            header.createCell(1).setCellValue("类型");
            header.createCell(2).setCellValue("金额");
            header.createCell(3).setCellValue("余额");
            header.createCell(4).setCellValue("备注");
            int i = 1;
            String[] types = {"", "充值", "通话扣费", "补扣"};
            for (BalanceLog log : list) {
                Row row = sheet.createRow(i++);
                row.createCell(0).setCellValue(formatTime(log.getCreateTime()));
                row.createCell(1).setCellValue(log.getType() != null && log.getType() < types.length ? types[log.getType()] : "");
                row.createCell(2).setCellValue(log.getAmount().doubleValue());
                row.createCell(3).setCellValue(log.getBalanceAfter().doubleValue());
                row.createCell(4).setCellValue(log.getRemark());
            }
            writeResponse(response, wb, "balance_logs.xlsx");
        }
    }

    public void exportIntentCustomers(HttpServletResponse response, Integer tenantId, String level) throws IOException {
        LambdaQueryWrapper<Customer> q = new LambdaQueryWrapper<Customer>()
                .eq(Customer::getTenantId, tenantId)
                .isNotNull(Customer::getLevel);
        if (level != null && !level.isBlank()) {
            q.eq(Customer::getLevel, level);
        }
        List<Customer> customers = customerMapper.selectList(q);
        customers.sort(Comparator.comparingInt(c -> levelOrder(c.getLevel())));

        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("意向客户");
            Row header = sheet.createRow(0);
            String[] headers = {"手机号", "姓名", "意向", "最后通话", "需求", "痛点", "预算", "回访时间"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            int rowIdx = 1;
            for (Customer c : customers) {
                CallRecord latest = latestRecord(tenantId, c.getPhone());
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(c.getPhone());
                row.createCell(1).setCellValue(c.getName() != null ? c.getName() : "");
                row.createCell(2).setCellValue(c.getLevel());
                row.createCell(3).setCellValue(formatTime(c.getLastCallTime()));
                row.createCell(4).setCellValue(latest != null ? latest.getCustomerNeed() : "");
                row.createCell(5).setCellValue(latest != null ? latest.getCustomerPain() : "");
                row.createCell(6).setCellValue(latest != null ? latest.getBudget() : "");
                row.createCell(7).setCellValue(latest != null ? latest.getNextTime() : "");
            }
            writeResponse(response, wb, "intent_customers.xlsx");
        }
    }

    public void exportCustomerTemplate(HttpServletResponse response) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("客户导入模板");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("手机号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("省份");
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("13800138000");
            sample.createCell(1).setCellValue("张三");
            sample.createCell(2).setCellValue("广东");
            writeResponse(response, wb, "customer_import_template.xlsx");
        }
    }

    public void exportProfitStats(HttpServletResponse response, LocalDate start, LocalDate end) throws IOException {
        List<CallRecord> list = queryCallRecords(start, end, null, null, null, null, null);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("利润统计");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("日期");
            header.createCell(1).setCellValue("商户ID");
            header.createCell(2).setCellValue("通话量");
            header.createCell(3).setCellValue("扣费");
            header.createCell(4).setCellValue("成本");
            header.createCell(5).setCellValue("毛利");
            // simplified: export raw connected calls
            int i = 1;
            for (CallRecord r : list) {
                if (!CallStatus.isConnected(r.getCallStatus())) continue;
                Row row = sheet.createRow(i++);
                row.createCell(0).setCellValue(formatTime(r.getCallTime()));
                row.createCell(1).setCellValue(r.getTenantId());
                row.createCell(2).setCellValue(1);
                row.createCell(3).setCellValue(r.getDeductAmount().doubleValue());
                row.createCell(4).setCellValue(r.getCostAmount().doubleValue());
                row.createCell(5).setCellValue(r.getProfit().doubleValue());
            }
            writeResponse(response, wb, "profit_stats.xlsx");
        }
    }

    private List<CallRecord> queryCallRecords(LocalDate start, LocalDate end, Integer tenantId,
                                              String phone, Integer callStatus, String level, Integer taskId) {
        LambdaQueryWrapper<CallRecord> q = new LambdaQueryWrapper<>();
        if (start != null && end != null) {
            q.ge(CallRecord::getCallTime, start.atStartOfDay())
                    .lt(CallRecord::getCallTime, end.plusDays(1).atStartOfDay());
        }
        if (tenantId != null) q.eq(CallRecord::getTenantId, tenantId);
        if (phone != null && !phone.isBlank()) q.like(CallRecord::getCustomerPhone, phone);
        if (callStatus != null) q.eq(CallRecord::getCallStatus, callStatus);
        if (level != null && !level.isBlank()) q.eq(CallRecord::getLevel, level);
        if (taskId != null) q.eq(CallRecord::getTaskId, taskId);
        q.orderByDesc(CallRecord::getId);
        return callRecordMapper.selectList(q);
    }

    private CallRecord latestRecord(Integer tenantId, String phone) {
        return callRecordMapper.selectOne(new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getTenantId, tenantId)
                .eq(CallRecord::getCustomerPhone, phone)
                .orderByDesc(CallRecord::getId)
                .last("LIMIT 1"));
    }

    private int levelOrder(String level) {
        return switch (level != null ? level : "D") {
            case "A" -> 1;
            case "B" -> 2;
            case "C" -> 3;
            default -> 4;
        };
    }

    private String formatTime(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void writeResponse(HttpServletResponse response, Workbook wb, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment;filename="
                + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        wb.write(response.getOutputStream());
    }
}
