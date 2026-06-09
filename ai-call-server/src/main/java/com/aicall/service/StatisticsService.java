package com.aicall.service;

import com.aicall.entity.CallRecord;
import com.aicall.entity.StatisticsDaily;
import com.aicall.entity.StatisticsMonthly;
import com.aicall.mapper.CallRecordMapper;
import com.aicall.mapper.StatisticsDailyMapper;
import com.aicall.mapper.StatisticsMonthlyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final CallRecordMapper callRecordMapper;
    private final StatisticsDailyMapper statisticsDailyMapper;
    private final StatisticsMonthlyMapper statisticsMonthlyMapper;

    public Map<String, Object> dashboard(LocalDate start, LocalDate end, Integer tenantId, Integer lineId) {
        return toViewMap(aggregateFromDb(start, end, tenantId, lineId));
    }

    public List<Map<String, Object>> dailyTrend(LocalDate start, LocalDate end, Integer tenantId) {
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = callRecordMapper.aggregateDailyTrend(startDt, endDt, tenantId);
        Map<LocalDate, Map<String, Object>> byDate = new HashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate d = toLocalDate(row.get("statDate"));
            byDate.put(d, toViewMap(row));
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Map<String, Object> day = new HashMap<>(byDate.getOrDefault(d, emptyViewMap()));
            day.put("date", d.toString());
            trend.add(day);
        }
        return trend;
    }

    public List<CallRecord> abnormalProfitRecords(LocalDate start, LocalDate end) {
        LambdaQueryWrapper<CallRecord> q = new LambdaQueryWrapper<CallRecord>()
                .eq(CallRecord::getProfitAbnormal, 1)
                .ge(CallRecord::getCallTime, start.atStartOfDay())
                .lt(CallRecord::getCallTime, end.plusDays(1).atStartOfDay())
                .orderByDesc(CallRecord::getId)
                .last("LIMIT 100");
        return callRecordMapper.selectList(q);
    }

    public Map<String, Object> profitReport(LocalDate start, LocalDate end,
                                            Integer tenantId, Integer lineId) {
        return dashboard(start, end, tenantId, lineId);
    }

    public Map<String, Object> forcedHangupStats(LocalDate start, LocalDate end, Integer tenantId) {
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = callRecordMapper.aggregateForcedHangup(startDt, endDt, tenantId);
        long total = 0;
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long cnt = toLong(row.get("cnt"));
            total += cnt;
            Map<String, Object> item = new HashMap<>();
            item.put("hangupType", row.get("hangupType"));
            item.put("count", cnt);
            byType.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("forcedHangupTotal", total);
        result.put("byType", byType);
        return result;
    }

    public List<StatisticsMonthly> monthlyList(String month, Integer tenantId) {
        LambdaQueryWrapper<StatisticsMonthly> q = new LambdaQueryWrapper<>();
        if (month != null && !month.isBlank()) {
            q.eq(StatisticsMonthly::getStatMonth, month);
        }
        if (tenantId != null) {
            q.eq(StatisticsMonthly::getTenantId, tenantId);
        } else {
            q.isNull(StatisticsMonthly::getTenantId);
        }
        q.orderByDesc(StatisticsMonthly::getStatMonth);
        return statisticsMonthlyMapper.selectList(q);
    }

    /** 通话结束后刷新日快照（SQL 聚合，不拉全表） */
    public void refreshDailySnapshot(LocalDate date, Integer tenantId, Integer lineId) {
        Map<String, Object> agg = aggregateFromDb(date, date, tenantId, lineId);
        StatisticsDaily daily = statisticsDailyMapper.selectOne(
                new LambdaQueryWrapper<StatisticsDaily>()
                        .eq(StatisticsDaily::getStatDate, date)
                        .eq(tenantId != null, StatisticsDaily::getTenantId, tenantId)
                        .isNull(tenantId == null, StatisticsDaily::getTenantId)
                        .eq(lineId != null, StatisticsDaily::getLineId, lineId)
                        .isNull(lineId == null, StatisticsDaily::getLineId));
        if (daily == null) {
            daily = new StatisticsDaily();
            daily.setStatDate(date);
            daily.setTenantId(tenantId);
            daily.setLineId(lineId);
        }
        daily.setTotalCalls(toInt(agg.get("totalCalls")));
        daily.setConnectedCalls(toInt(agg.get("connectedCalls")));
        daily.setTotalDurationSec(toLong(agg.get("totalDurationSec")));
        daily.setTotalDeduct(toBigDecimal(agg.get("totalDeduct")));
        daily.setTotalCost(toBigDecimal(agg.get("totalCost")));
        daily.setTotalProfit(toBigDecimal(agg.get("totalProfit")));
        if (daily.getId() == null) {
            statisticsDailyMapper.insert(daily);
        } else {
            statisticsDailyMapper.updateById(daily);
        }
    }

    private Map<String, Object> aggregateFromDb(LocalDate start, LocalDate end,
                                                Integer tenantId, Integer lineId) {
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.plusDays(1).atStartOfDay();
        Map<String, Object> raw = callRecordMapper.aggregateStats(startDt, endDt, tenantId, lineId);
        if (raw == null || raw.isEmpty()) {
            return emptyAggMap();
        }
        return raw;
    }

    private Map<String, Object> toViewMap(Map<String, Object> agg) {
        long totalCalls = toLong(agg.get("totalCalls"));
        long connected = toLong(agg.get("connectedCalls"));
        long totalSec = toLong(agg.get("totalDurationSec"));
        BigDecimal deduct = toBigDecimal(agg.get("totalDeduct"));
        BigDecimal cost = toBigDecimal(agg.get("totalCost"));
        BigDecimal profit = toBigDecimal(agg.get("totalProfit"));
        long abnormal = toLong(agg.get("abnormalProfitCount"));

        Map<String, Object> m = new HashMap<>();
        m.put("totalCalls", totalCalls);
        m.put("connectedCalls", connected);
        m.put("connectRate", totalCalls == 0 ? 0 :
                BigDecimal.valueOf(connected * 100.0 / totalCalls).setScale(2, RoundingMode.HALF_UP));
        m.put("totalDurationMinutes", totalSec / 60);
        m.put("totalDurationSec", totalSec);
        m.put("totalDeduct", deduct.setScale(2, RoundingMode.HALF_UP));
        m.put("totalCost", cost.setScale(4, RoundingMode.HALF_UP));
        m.put("totalProfit", profit.setScale(4, RoundingMode.HALF_UP));
        m.put("totalDeductRaw", deduct);
        m.put("totalCostRaw", cost);
        m.put("totalProfitRaw", profit);
        m.put("avgDuration", connected == 0 ? 0 : totalSec / connected);
        m.put("abnormalProfitCount", abnormal);
        return m;
    }

    private Map<String, Object> emptyViewMap() {
        return toViewMap(emptyAggMap());
    }

    private Map<String, Object> emptyAggMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("totalCalls", 0L);
        m.put("connectedCalls", 0L);
        m.put("totalDurationSec", 0L);
        m.put("totalDeduct", BigDecimal.ZERO);
        m.put("totalCost", BigDecimal.ZERO);
        m.put("totalProfit", BigDecimal.ZERO);
        m.put("abnormalProfitCount", 0L);
        return m;
    }

    private LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    private long toLong(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(v.toString());
    }

    private int toInt(Object v) {
        return (int) toLong(v);
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(v.toString());
    }
}
