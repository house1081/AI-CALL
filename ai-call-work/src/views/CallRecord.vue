<template>
  <div>
    <el-form inline>
      <el-input v-model="query.phone" placeholder="手机号" clearable style="width:130px" />
      <el-select v-model="query.callStatus" placeholder="状态" clearable style="width:110px">
        <el-option v-for="(l,v) in statusMap" :key="v" :value="Number(v)" :label="l" />
      </el-select>
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始" end-placeholder="结束" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="doExport">导出</el-button>
    </el-form>
    <el-table :data="list" stripe style="margin-top:12px">
      <el-table-column prop="callTime" label="通话时间" width="170" />
      <el-table-column prop="customerPhone" label="手机" />
      <el-table-column prop="customerName" label="姓名" width="90" />
      <el-table-column prop="callDuration" label="时长(秒)" width="90" />
      <el-table-column label="扣费(元)" width="90">
        <template #default="{row}">{{ formatDeduct(row) }}</template>
      </el-table-column>
      <el-table-column prop="level" label="意向" width="60" />
      <el-table-column label="挂断" width="100">
        <template #default="{row}">
          <el-tag v-if="row.hangupType && row.hangupType.startsWith('强制挂断')" type="warning" size="small">强制挂断</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="对话" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.dialogText">{{ dialogPreview(row.dialogText) }}</span>
          <span v-else style="color:#999">无</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{row}">
          {{ statusMap[row.callStatus] }}
          <el-tag v-if="row.callStatus!==1" type="info" size="small">未扣费</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{row}">
          <el-button link @click="showDetail(row)">对话</el-button>
          <el-button link v-if="row.recordUrl" @click="openPlayer(row)">录音</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="detailVisible" title="通话对话" width="640px">
      <p style="margin:0 0 8px;color:#666;font-size:13px">
        {{ detail.customerPhone }} · {{ detail.callTime }}
      </p>
      <div v-if="dialogLines.length" class="dialog-box">
        <div v-for="(line, i) in dialogLines" :key="i" :class="lineClass(line)">{{ line }}</div>
      </div>
      <el-empty v-else description="暂无对话记录" />
      <template v-if="detail.customerNeed || detail.customerPain">
        <el-divider />
        <p><b>需求：</b>{{ detail.customerNeed || '-' }}</p>
        <p><b>痛点：</b>{{ detail.customerPain || '-' }}</p>
        <p><b>预算：</b>{{ detail.budget || '-' }}</p>
        <p><b>回访：</b>{{ detail.nextTime || '-' }}</p>
      </template>
    </el-dialog>
    <el-dialog v-model="playerVisible" title="录音回放" width="480px">
      <audio ref="audioRef" controls style="width:100%" />
      <div style="margin-top:8px">
        倍速：
        <el-radio-group v-model="playbackRate" @change="setRate">
          <el-radio-button :label="0.75">0.75x</el-radio-button>
          <el-radio-button :label="1">1x</el-radio-button>
          <el-radio-button :label="1.25">1.25x</el-radio-button>
          <el-radio-button :label="1.5">1.5x</el-radio-button>
        </el-radio-group>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import request from '../api/request'
import { downloadFile } from '../utils/download'

const statusMap = { 1: '接通', 2: '无人接听', 3: '空号', 4: '关机', 5: '拒接', 6: '拨号失败' }
const list = ref([])
const page = ref(1)
const total = ref(0)
const query = ref({})
const dateRange = ref([])
const detailVisible = ref(false)
const playerVisible = ref(false)
const detail = ref({})
const audioRef = ref(null)
const playbackRate = ref(1)

const dialogLines = computed(() => {
  const t = detail.value.dialogText
  if (!t) return []
  return t.split('\n').map(s => s.trim()).filter(Boolean)
})

const dialogPreview = (text) => {
  const one = (text || '').replace(/\n/g, ' ').trim()
  return one.length > 40 ? one.slice(0, 40) + '…' : one
}

const lineClass = (line) => {
  if (line.startsWith('【客户】')) return 'dialog-line user'
  if (line.startsWith('【AI】')) return 'dialog-line ai'
  return 'dialog-line'
}

const formatDeduct = (row) => {
  if (row.callStatus !== 1) return '0.00'
  const v = Number(row.deductAmount || 0)
  return v.toFixed(2)
}

const load = async () => {
  const params = { page: page.value, pageSize: 10, ...query.value }
  if (dateRange.value?.length === 2) {
    params.startDate = dateRange.value[0]
    params.endDate = dateRange.value[1]
  }
  const data = await request.get('/tenant/portal/call-record/list', { params })
  list.value = data.list
  total.value = data.total
}

const showDetail = (row) => { detail.value = row; detailVisible.value = true }

const openPlayer = async (row) => {
  playerVisible.value = true
  await nextTick()
  if (audioRef.value) {
    audioRef.value.src = row.recordUrl
    audioRef.value.playbackRate = playbackRate.value
    audioRef.value.play()
  }
}
const setRate = () => {
  if (audioRef.value) audioRef.value.playbackRate = playbackRate.value
}

const doExport = () => {
  const start = dateRange.value?.[0] || new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10)
  const end = dateRange.value?.[1] || new Date().toISOString().slice(0, 10)
  downloadFile('/tenant/portal/export/call-record', { start, end }, 'call_records.xlsx')
}

onMounted(load)
</script>

<style scoped>
.dialog-box {
  max-height: 400px;
  overflow-y: auto;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
}
.dialog-line {
  margin-bottom: 10px;
  line-height: 1.5;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.dialog-line.ai { color: #303133; }
.dialog-line.user { color: #409eff; }
</style>
