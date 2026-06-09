package com.aicall.mapper;

import com.aicall.entity.CallRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface CallRecordMapper extends BaseMapper<CallRecord> {

    @Select("""
            <script>
            SELECT COUNT(*) AS totalCalls,
                   COALESCE(SUM(CASE WHEN call_status = 1 THEN 1 ELSE 0 END), 0) AS connectedCalls,
                   COALESCE(SUM(CASE WHEN call_status = 1 THEN call_duration ELSE 0 END), 0) AS totalDurationSec,
                   COALESCE(SUM(deduct_amount), 0) AS totalDeduct,
                   COALESCE(SUM(cost_amount), 0) AS totalCost,
                   COALESCE(SUM(profit), 0) AS totalProfit,
                   COALESCE(SUM(CASE WHEN profit_abnormal = 1 THEN 1 ELSE 0 END), 0) AS abnormalProfitCount
            FROM call_record
            WHERE call_time &gt;= #{start} AND call_time &lt; #{end}
            <if test="tenantId != null">AND tenant_id = #{tenantId}</if>
            <if test="lineId != null">AND line_id = #{lineId}</if>
            </script>
            """)
    Map<String, Object> aggregateStats(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         @Param("tenantId") Integer tenantId,
                                         @Param("lineId") Integer lineId);

    @Select("""
            <script>
            SELECT DATE(call_time) AS statDate,
                   COUNT(*) AS totalCalls,
                   COALESCE(SUM(CASE WHEN call_status = 1 THEN 1 ELSE 0 END), 0) AS connectedCalls,
                   COALESCE(SUM(CASE WHEN call_status = 1 THEN call_duration ELSE 0 END), 0) AS totalDurationSec,
                   COALESCE(SUM(deduct_amount), 0) AS totalDeduct,
                   COALESCE(SUM(cost_amount), 0) AS totalCost,
                   COALESCE(SUM(profit), 0) AS totalProfit,
                   COALESCE(SUM(CASE WHEN profit_abnormal = 1 THEN 1 ELSE 0 END), 0) AS abnormalProfitCount
            FROM call_record
            WHERE call_time &gt;= #{start} AND call_time &lt; #{end}
            <if test="tenantId != null">AND tenant_id = #{tenantId}</if>
            GROUP BY DATE(call_time)
            ORDER BY statDate
            </script>
            """)
    List<Map<String, Object>> aggregateDailyTrend(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end,
                                                   @Param("tenantId") Integer tenantId);

    @Select("""
            <script>
            SELECT r.* FROM call_record r
            INNER JOIN (
                SELECT MAX(id) AS mid FROM call_record
                WHERE tenant_id = #{tenantId}
                AND customer_phone IN
                <foreach collection="phones" item="p" open="(" separator="," close=")">#{p}</foreach>
                GROUP BY customer_phone
            ) x ON r.id = x.mid
            </script>
            """)
    List<CallRecord> selectLatestByPhones(@Param("tenantId") Integer tenantId,
                                          @Param("phones") List<String> phones);

    @Select("""
            <script>
            SELECT hangup_type AS hangupType, COUNT(*) AS cnt
            FROM call_record
            WHERE call_time &gt;= #{start} AND call_time &lt; #{end}
            AND hangup_type IS NOT NULL AND hangup_type LIKE '强制挂断%'
            <if test="tenantId != null">AND tenant_id = #{tenantId}</if>
            GROUP BY hangup_type
            </script>
            """)
    List<Map<String, Object>> aggregateForcedHangup(@Param("start") LocalDateTime start,
                                                     @Param("end") LocalDateTime end,
                                                     @Param("tenantId") Integer tenantId);
}
