<template>
  <el-dialog v-model="visible" title="点呼" width="420px" @closed="onClosed">
    <p style="margin:0 0 12px;color:#666">
      客户：{{ target?.name || '-' }} {{ target?.phone }}
    </p>
    <el-alert
      v-if="!agents.length"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom:12px"
      title="暂无坐席，请先在「坐席管理」中新增并设为在岗"
    />
    <el-alert
      v-else-if="!availableAgents.length"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom:12px"
      title="没有在岗坐席，请到「坐席管理」将坐席设为在岗后再点呼"
    />
    <el-form label-width="80px">
      <el-form-item label="坐席" required>
        <el-select v-model="form.agentId" placeholder="选择坐席" style="width:100%" filterable>
          <el-option
            v-for="a in agents"
            :key="a.id"
            :label="agentOptionLabel(a)"
            :value="a.id"
            :disabled="!canSelectAgent(a)"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="线路">
        <el-select v-model="form.lineId" clearable placeholder="默认线路" style="width:100%">
          <el-option
            v-for="l in lines"
            :key="l.id"
            :label="l.sipAccount || `线路#${l.id}`"
            :value="l.id"
          />
        </el-select>
      </el-form-item>
    </el-form>
      <p style="margin:0;font-size:12px;color:#909399">
        将先呼叫坐席，接通后再呼客户并桥接（不进 AI 对话）。
        分机坐席需软电话已注册；手机坐席会经线路外呼该手机号。
      </p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="calling" :disabled="!canSubmit" @click="submit">开始点呼</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const emit = defineEmits(['done'])

const visible = ref(false)
const calling = ref(false)
const target = ref(null)
const form = ref({ agentId: null, lineId: null })
const agents = ref([])
const lines = ref([])

const availableAgents = computed(() =>
  agents.value.filter(a => a.status === 1 && a.presence === 'available')
)

const canSubmit = computed(() => !!form.value.agentId && !calling.value)

const agentOptionLabel = (a) => {
  const dest = a.destType === 'mobile'
    ? `手机 ${a.mobile || a.fsDest || ''}`
    : `分机 ${a.fsDest || ''}`
  const presence = ({ available: '在岗', busy: '忙', offline: '离岗' }[a.presence] || a.presence)
  return `${a.name}（${dest}/${presence}）`
}

const canSelectAgent = (a) => a.status === 1 && a.presence === 'available'

const open = async (row) => {
  if (!row?.id && !row?.phone) {
    ElMessage.warning('客户信息无效')
    return
  }
  target.value = row
  form.value = { agentId: null, lineId: null }
  calling.value = false
  try {
    const [agentList, lineList] = await Promise.all([
      request.get('/tenant/portal/agents'),
      request.get('/tenant/portal/outbound-lines')
    ])
    agents.value = agentList || []
    lines.value = lineList || []
  } catch {
    agents.value = []
    lines.value = []
  }
  // 手机测试时优先选 mobile 坐席；否则优先分机
  const preferred = availableAgents.value.find(a => a.destType === 'mobile')
    || availableAgents.value.find(a => a.destType !== 'mobile')
    || availableAgents.value[0]
  if (preferred) form.value.agentId = preferred.id
  if (lines.value.length === 1) form.value.lineId = lines.value[0].id
  visible.value = true
  if (!agents.value.length) {
    ElMessage.warning('请先配置坐席')
  } else if (!availableAgents.value.length) {
    ElMessage.warning('请先将坐席设为在岗（分机需软电话已注册）')
  }
}

const submit = async () => {
  if (!form.value.agentId) {
    ElMessage.warning('请选择在岗坐席')
    return
  }
  const agent = agents.value.find(a => a.id === form.value.agentId)
  if (!canSelectAgent(agent)) {
    ElMessage.warning('请选择在岗坐席')
    return
  }
  calling.value = true
  try {
    const r = await request.post('/tenant/portal/click-call', {
      customerId: target.value.id,
      phone: target.value.phone,
      agentId: form.value.agentId,
      lineId: form.value.lineId || undefined
    }, { timeout: 90000 })
    ElMessage.success(r?.message || `坐席已接通，正在呼客户（记录#${r?.recordId || '-'}）`)
    visible.value = false
    emit('done', r)
  } catch {
    // interceptor shows error
  } finally {
    calling.value = false
  }
}

const onClosed = () => {
  calling.value = false
}

defineExpose({ open })
</script>
