<template>
  <div>
    <el-form inline>
      <el-form-item label="手机号"><el-input v-model="query.phone" /></el-form-item>
      <el-form-item label="商户ID"><el-input v-model="query.tenantId" style="width:100px" /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.callStatus" clearable>
          <el-option v-for="(l,v) in statusMap" :key="v" :value="Number(v)" :label="l" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="doExport">导出Excel</el-button>
    </el-form>
    <el-table :data="list" stripe style="margin-top:16px">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="callTime" label="通话时间" width="170" />
      <el-table-column prop="tenantId" label="商户" width="70" />
      <el-table-column prop="customerPhone" label="手机" width="120" />
      <el-table-column prop="callDuration" label="时长(秒)" width="90" />
      <el-table-column prop="billedMinutes" label="计费分" width="80" />
      <el-table-column prop="deductAmount" label="扣费" width="80" />
      <el-table-column prop="costAmount" label="成本" width="80" />
      <el-table-column prop="profit" label="毛利" width="80" />
      <el-table-column label="对话" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.dialogText">{{ dialogPreview(row.dialogText) }}</span>
          <span v-else style="color:#999">无</span>
        </template>
      </el-table-column>
      <el-table-column label="挂断" width="160">
        <template #default="{row}">
          <template v-if="row.hangupType && row.hangupType.startsWith('强制挂断')">
            <el-tag type="warning" size="small">强制挂断</el-tag>
            <span style="margin-left:4px;font-size:12px">{{ row.hangupType }}</span>
          </template>
          <span v-else>{{ row.hangupType || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{row}">
          {{ statusMap[row.callStatus] }}
          <el-tag v-if="row.callStatus!==1" type="info" size="small">无成本/无毛利</el-tag>
          <el-tag v-else-if="row.profitAbnormal===1" type="danger" size="small">负毛利</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />

    <el-dialog v-model="detailVisible" title="通话对话 & 训练导入" width="760px">
      <p style="margin:0 0 8px;color:#666;font-size:13px">
        记录#{{ detail.id }} · {{ detail.customerPhone }} · {{ detail.callTime }} · {{ statusMap[detail.callStatus] }}
      </p>
      <div style="margin-bottom:12px">
        <el-button size="small" type="success" :disabled="!qaPairs.length" :loading="importAllLoading"
          @click="importAllQuality">整通导入优质样本</el-button>
        <span v-if="qaPairs.length" style="margin-left:8px;font-size:12px;color:#666">共 {{ qaPairs.length }} 轮问答</span>
      </div>
      <el-table v-if="qaPairs.length" :data="qaPairs" size="small" border max-height="280">
        <el-table-column type="index" label="#" width="45" />
        <el-table-column prop="userText" label="客户" show-overflow-tooltip />
        <el-table-column prop="aiText" label="原 AI 回复" show-overflow-tooltip />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button link type="primary" @click="openImport(row, 1)">修正入库</el-button>
            <el-button link @click="openImport(row, 2)">优质入库</el-button>
            <el-button link type="danger" @click="openImport(row, 3)">负样本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider v-if="dialogLines.length">完整对话</el-divider>
      <div v-if="dialogLines.length" class="dialog-box">
        <div v-for="(line, i) in dialogLines" :key="i" :class="lineClass(line)">{{ line }}</div>
      </div>
      <el-empty v-if="!dialogLines.length && !qaPairs.length" description="暂无对话记录" />
    </el-dialog>

    <el-dialog v-model="importVisible" title="导入训练知识库" width="520px">
      <el-form label-width="90px">
        <el-form-item label="类型">
          <el-tag>{{ importTypeLabel(importForm.dataType) }}</el-tag>
        </el-form-item>
        <el-form-item label="客户问题">
          <el-input v-model="importForm.question" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标准回复">
          <el-input v-model="importForm.standardAnswer" type="textarea" :rows="3"
            :placeholder="importForm.dataType === 3 ? '填写应禁止的错误回复示例' : '人工修正后的标准话术'" />
        </el-form-item>
        <p v-if="importForm.originalAiAnswer && importForm.dataType === 1" class="hint">
          原 AI：{{ importForm.originalAiAnswer }}
        </p>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importLoading" @click="submitImport">保存并热更新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { downloadFile } from '../utils/download'

const statusMap = { 1: '接通', 2: '无人接听', 3: '空号', 4: '关机', 5: '拒接', 6: '拨号失败' }
const list = ref([])
const page = ref(1)
const total = ref(0)
const query = ref({})
const detailVisible = ref(false)
const detail = ref({})
const qaPairs = ref([])
const importVisible = ref(false)
const importLoading = ref(false)
const importAllLoading = ref(false)
const importForm = ref({})

const dialogLines = computed(() => {
  const t = detail.value.dialogText
  if (!t) return []
  return t.split('\n').map(s => s.trim()).filter(Boolean)
})

const dialogPreview = (text) => {
  const one = (text || '').replace(/\n/g, ' ').trim()
  return one.length > 48 ? one.slice(0, 48) + '…' : one
}

const lineClass = (line) => {
  if (line.startsWith('【客户】')) return 'dialog-line user'
  if (line.startsWith('【AI】')) return 'dialog-line ai'
  return 'dialog-line'
}

const importTypeLabel = (t) => ({ 1: '人工修正', 2: '优质样本', 3: '负样本' }[t] || '-')

const load = async () => {
  const params = { page: page.value, pageSize: 10, ...query.value }
  if (params.tenantId) params.tenantId = Number(params.tenantId)
  const data = await request.get('/admin/call-record/list', { params })
  list.value = data.list
  total.value = data.total
}

const showDetail = async (row) => {
  detail.value = row
  detailVisible.value = true
  qaPairs.value = []
  try {
    const data = await request.get(`/admin/dialog-training/from-call/${row.id}`)
    qaPairs.value = data.pairs || []
    if (data.dialogText) detail.value = { ...row, dialogText: data.dialogText }
  } catch {
    qaPairs.value = []
  }
}

const openImport = (pair, dataType) => {
  importForm.value = {
    callRecordId: detail.value.id,
    dataType,
    question: pair.userText,
    standardAnswer: dataType === 3 ? pair.aiText : pair.aiText,
    originalAiAnswer: pair.aiText,
    turnIndex: pair.turnIndex
  }
  importVisible.value = true
}

const submitImport = async () => {
  importLoading.value = true
  try {
    await request.post('/admin/dialog-training/import-from-call', importForm.value)
    ElMessage.success('已导入训练知识库并热更新')
    importVisible.value = false
  } finally {
    importLoading.value = false
  }
}

const importAllQuality = async () => {
  await ElMessageBox.confirm('将本通所有问答轮次作为「优质样本」导入（重复问题跳过）', '确认')
  importAllLoading.value = true
  try {
    const r = await request.post(`/admin/dialog-training/import-quality-from-call/${detail.value.id}?skipDuplicate=true`)
    ElMessage.success(`导入 ${r.imported} 条，跳过 ${r.skipped} 条`)
  } finally {
    importAllLoading.value = false
  }
}

const doExport = () => {
  const start = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10)
  const end = new Date().toISOString().slice(0, 10)
  const params = { start, end }
  if (query.value.tenantId) params.tenantId = Number(query.value.tenantId)
  downloadFile('/admin/export/call-record', params, 'call_records.xlsx')
}

onMounted(load)
</script>

<style scoped>
.dialog-box {
  max-height: 240px;
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
.hint { font-size: 12px; color: #909399; margin: 0 0 8px 90px; }
</style>
