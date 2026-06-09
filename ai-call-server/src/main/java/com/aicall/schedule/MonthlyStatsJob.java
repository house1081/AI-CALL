package com.aicall.schedule;

import com.aicall.entity.StatisticsMonthly;
import com.aicall.mapper.StatisticsMonthlyMapper;
import com.aicall.service.StatisticsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** PRD 2.3：每月汇总利润统计 */
@Component
@RequiredArgsConstructor
public class MonthlyStatsJob {

    private final StatisticsService statisticsService;
    private final StatisticsMonthlyMapper statisticsMonthlyMapper;

    /** 每月1日 00:05 汇总上月数据 */
    @Scheduled(cron = "0 5 0 1 * ?")
    public void aggregateLastMonth() {
        YearMonth last = YearMonth.now().minusMonths(1);
        LocalDate start = last.atDay(1);
        LocalDate end = last.atEndOfMonth();
        String month = last.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        saveSnapshot(month, null, null, start, end);
        // 可按需扩展按商户、按线路维度汇总
    }

    public void saveSnapshot(String month, Integer tenantId, Integer lineId,
                             LocalDate start, LocalDate end) {
        Map<String, Object> agg = statisticsService.dashboard(start, end, tenantId, lineId);
        StatisticsMonthly sm = statisticsMonthlyMapper.selectOne(
                new LambdaQueryWrapper<StatisticsMonthly>()
                        .eq(StatisticsMonthly::getStatMonth, month)
                        .eq(tenantId != null, StatisticsMonthly::getTenantId, tenantId)
                        .isNull(tenantId == null, StatisticsMonthly::getTenantId)
                        .eq(lineId != null, StatisticsMonthly::getLineId, lineId)
                        .isNull(lineId == null, StatisticsMonthly::getLineId));
        if (sm == null) {
            sm = new StatisticsMonthly();
            sm.setStatMonth(month);
            sm.setTenantId(tenantId);
            sm.setLineId(lineId);
        }
        sm.setTotalCalls(((Number) agg.get("totalCalls")).intValue());
        sm.setConnectedCalls(((Number) agg.get("connectedCalls")).intValue());
        sm.setTotalDurationSec(((Number) agg.get("totalDurationSec")).longValue());
        sm.setTotalDeduct((java.math.BigDecimal) agg.get("totalDeductRaw"));
        sm.setTotalCost((java.math.BigDecimal) agg.get("totalCostRaw"));
        sm.setTotalProfit((java.math.BigDecimal) agg.get("totalProfitRaw"));
        if (sm.getId() == null) {
            statisticsMonthlyMapper.insert(sm);
        } else {
            statisticsMonthlyMapper.updateById(sm);
        }
    }
}
