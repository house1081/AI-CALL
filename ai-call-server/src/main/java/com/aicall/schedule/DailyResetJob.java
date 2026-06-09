package com.aicall.schedule;

import com.aicall.entity.Line;
import com.aicall.mapper.LineMapper;
import com.aicall.service.StatisticsService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class DailyResetJob {

    private final LineMapper lineMapper;
    private final StatisticsService statisticsService;

    @Scheduled(cron = "0 0 0 * * ?")
    public void resetLineDailyCount() {
        lineMapper.update(null, new LambdaUpdateWrapper<Line>()
                .set(Line::getTodayCallCount, 0)
                .set(Line::getCurrentConcurrent, 0));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        statisticsService.refreshDailySnapshot(yesterday, null, null);
    }
}
