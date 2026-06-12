<template>
  <div>
    <el-form inline>
      <el-form-item label="开始"><el-date-picker v-model="range[0]" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="结束"><el-date-picker v-model="range[1]" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="商户ID"><el-input v-model="tenantId" style="width:90px" clearable /></el-form-item>
      <el-form-item label="线路ID"><el-input v-model="lineId" style="width:90px" clearable /></el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="doExport">导出</el-button>
    </el-form>
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="6"><el-statistic title="总毛利" :value="summary.totalProfit" /></el-col>
      <el-col :span="6"><el-statistic title="总扣费" :value="summary.totalDeduct" /></el-col>
      <el-col :span="6"><el-statistic title="总成本" :value="summary.totalCost" /></el-col>
      <el-col :span="6"><el-statistic title="异常通话" :value="monitor.abnormalProfitCount || 0" /></el-col>
    </el-row>
    <el-card header="盈利异常监控 (PRD 2.4)" style="margin-top:16px">
      <el-alert v-if="monitor.balanceAlertCount > 0" type="warning" :title="`余额/待补扣异常商户 ${monitor.balanceAlertCount} 个`" show-icon style="margin-bottom:8px" />
      <el-table :data="monitor.abnormalProfitList || []" size="small" stripe>
        <el-table-column prop="callTime" label="时间" width="160" />
        <el-table-column prop="tenantId" label="商户" width="70" />
        <el-table-column prop="customerPhone" label="手机" />
        <el-table-column prop="deductAmount" label="扣费" />
        <el-table-column prop="costAmount" label="成本" />
        <el-table-column prop="profit" label="毛利" />
      </el-table>
    </el-card>
    <el-card header="按月汇总" style="margin-top:16px">
      <el-table :data="monthlyList" size="small">
        <el-table-column prop="statMonth" label="月份" />
        <el-table-column prop="totalCalls" label="通话量" />
        <el-table-column prop="totalDeduct" label="扣费" />
        <el-table-column prop="totalCost" label="成本" />
        <el-table-column prop="totalProfit" label="毛利" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { downloadFile } from '../utils/download'

const range = ref([
  new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10),
  new Date().toISOString().slice(0, 10)
])
const tenantId = ref('')
const lineId = ref('')
const summary = ref({})
const monitor = ref({})
const monthlyList = ref([])

const load = async () => {
  const params = { start: range.value[0], end: range.value[1] }
  if (tenantId.value) params.tenantId = Number(tenantId.value)
  if (lineId.value) params.lineId = Number(lineId.value)
  summary.value = await request.get('/admin/statistics/profit', { params })
  monitor.value = await request.get('/admin/profit/monitor')
  const month = range.value[1].slice(0, 7)
  monthlyList.value = await request.get('/admin/statistics/monthly', { params: { month } })
}

const doExport = () => {
  downloadFile('/admin/export/profit', {
    start: range.value[0],
    end: range.value[1]
  }, 'profit_report.xlsx')
}

onMounted(load)
</script>
