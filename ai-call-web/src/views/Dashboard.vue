<template>
  <div>
    <el-radio-group v-model="preset" @change="applyPreset" style="margin-bottom:12px">
      <el-radio-button label="today">今日</el-radio-button>
      <el-radio-button label="yesterday">昨日</el-radio-button>
      <el-radio-button label="week">近7天</el-radio-button>
      <el-radio-button label="month">近30天</el-radio-button>
    </el-radio-group>
    <el-form inline>
      <el-form-item label="开始"><el-date-picker v-model="range[0]" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="结束"><el-date-picker v-model="range[1]" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="exportData">导出通话</el-button>
    </el-form>
    <el-alert v-if="stats.abnormalProfitCount > 0" type="warning" :title="`利润异常 ${stats.abnormalProfitCount} 条`" show-icon style="margin:12px 0" />
    <el-alert v-if="monitor.balanceAlertCount > 0" type="error" :title="`余额/待补扣异常商户 ${monitor.balanceAlertCount} 个，请查看利润统计`" show-icon style="margin-bottom:12px" />
    <el-row :gutter="16" class="stats">
      <el-col :span="6" v-for="item in cards" :key="item.label">
        <el-card shadow="hover">
          <div class="num">{{ item.value }}</div>
          <div class="label">{{ item.label }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card v-if="hangupStats.forcedHangupTotal > 0" header="强制挂断统计" style="margin-top:20px">
      <p>强制挂断合计：<strong>{{ hangupStats.forcedHangupTotal }}</strong></p>
      <el-table :data="hangupStats.byType" size="small" stripe>
        <el-table-column prop="hangupType" label="类型" />
        <el-table-column prop="count" label="次数" width="100" />
      </el-table>
    </el-card>
    <el-card header="每日趋势" style="margin-top:20px">
      <el-table :data="trend" size="small" stripe>
        <el-table-column prop="date" label="日期" />
        <el-table-column prop="totalCalls" label="通话量" />
        <el-table-column prop="totalProfit" label="毛利(元)" />
        <el-table-column prop="totalDeduct" label="扣费(元)" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { downloadFile } from '../utils/download'

const preset = ref('today')
const range = ref([new Date().toISOString().slice(0, 10), new Date().toISOString().slice(0, 10)])
const cards = ref([])
const stats = ref({})
const monitor = ref({})
const trend = ref([])
const hangupStats = ref({ forcedHangupTotal: 0, byType: [] })

const applyPreset = () => {
  const today = new Date()
  const fmt = d => d.toISOString().slice(0, 10)
  if (preset.value === 'today') range.value = [fmt(today), fmt(today)]
  else if (preset.value === 'yesterday') {
    const y = new Date(today); y.setDate(y.getDate() - 1)
    range.value = [fmt(y), fmt(y)]
  } else if (preset.value === 'week') {
    const s = new Date(today); s.setDate(s.getDate() - 6)
    range.value = [fmt(s), fmt(today)]
  } else {
    const s = new Date(today); s.setDate(s.getDate() - 29)
    range.value = [fmt(s), fmt(today)]
  }
  load()
}

const load = async () => {
  stats.value = await request.get('/admin/dashboard', { params: { start: range.value[0], end: range.value[1] } })
  monitor.value = await request.get('/admin/profit/monitor')
  trend.value = await request.get('/admin/dashboard/trend', { params: { start: range.value[0], end: range.value[1] } })
  hangupStats.value = await request.get('/admin/statistics/forced-hangup', { params: { start: range.value[0], end: range.value[1] } })
  cards.value = [
    { label: '总通话', value: stats.value.totalCalls },
    { label: '接通', value: stats.value.connectedCalls },
    { label: '接通率%', value: stats.value.connectRate },
    { label: '总扣费', value: stats.value.totalDeduct },
    { label: '总成本', value: stats.value.totalCost },
    { label: '总毛利', value: stats.value.totalProfit },
    { label: '时长(分)', value: stats.value.totalDurationMinutes }
  ]
}

const exportData = () => {
  downloadFile('/admin/export/call-record', {
    start: range.value[0],
    end: range.value[1]
  }, 'call_records.xlsx')
}

onMounted(load)
</script>

<style scoped>
.stats { margin-top: 16px; }
.num { font-size: 28px; font-weight: bold; color: #409eff; }
.label { color: #666; margin-top: 8px; }
</style>
