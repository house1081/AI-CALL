<template>
  <el-row :gutter="16">
    <el-col :span="16">
      <el-card class="chat-card">
        <template #header>
          <div class="hdr">
            <span>AI 对话训练</span>
            <el-tag v-if="modelInfo.model" type="info">{{ modelInfo.provider }} · {{ modelInfo.model }}</el-tag>
            <el-tag :type="health.ok ? 'success' : 'danger'" size="small">
              {{ health.ok ? '在线' : '离线' }}
            </el-tag>
            <el-button link type="primary" @click="refreshHealth" :loading="healthLoading">检测连接</el-button>
          </div>
        </template>
        <div ref="boxRef" class="chat-box">
          <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
            <div class="bubble">
              <div class="role">{{ m.role === 'user' ? '客户' : 'AI' }}</div>
              <div class="text">{{ m.content }}</div>
              <div v-if="m.meta" class="meta">{{ m.meta }}</div>
              <el-button v-if="m.role === 'assistant' && m.userQuestion" link type="primary" size="small"
                class="save-train" @click="openSaveTraining(m)">保存为训练数据</el-button>
            </div>
          </div>
        </div>
        <div class="toolbar">
          <el-checkbox v-model="businessProbe">模拟：上轮 AI 已追问业务</el-checkbox>
          <el-button @click="startCall" :disabled="started || !health.ok">开始通话（开场白）</el-button>
          <el-button @click="resetChat">清空</el-button>
        </div>
        <div class="input-row">
          <el-input v-model="input" placeholder="输入客户说的话，回车发送" @keyup.enter="send" :disabled="loading || !started" />
          <el-button type="primary" :loading="loading" :disabled="!started" @click="send">发送</el-button>
        </div>
        <p class="tip">强制结束仅三类：辱骂/脏话/投诉、明确拒接打扰、通话满 5 分钟（需 Redis 计时）。</p>
      </el-card>
    </el-col>
    <el-col :span="8">
      <el-card header="说明">
        <p>使用<strong>模型配置</strong>页启用的模型，话术来自<strong>AI话术</strong>模板。</p>
        <p>测试挂断：说辱骂/投诉词；说「别打了/不要再联系」；或等待满 5 分钟。</p>
        <el-button style="margin-top:12px" @click="summarize" :disabled="messages.length < 2" :loading="sumLoading">生成意向摘要</el-button>
        <el-divider />
        <div v-if="summary">
          <p><b>意向等级</b> {{ summary.level }}</p>
          <p><b>需求</b> {{ summary.customerNeed || '-' }}</p>
          <p><b>痛点</b> {{ summary.customerPain || '-' }}</p>
          <p><b>预算</b> {{ summary.budget || '-' }}</p>
          <p><b>回访</b> {{ summary.nextTime || '-' }}</p>
        </div>
      </el-card>
    </el-col>
  </el-row>

  <el-dialog v-model="trainSaveVisible" title="保存为训练知识库" width="520px">
    <el-form label-width="90px">
      <el-form-item label="类型">
        <el-select v-model="trainSaveForm.dataType" style="width:100%">
          <el-option :value="1" label="人工修正" />
          <el-option :value="2" label="优质样本" />
          <el-option :value="3" label="负样本" />
        </el-select>
      </el-form-item>
      <el-form-item label="客户问题">
        <el-input v-model="trainSaveForm.question" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="标准回复">
        <el-input v-model="trainSaveForm.standardAnswer" type="textarea" :rows="3" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="trainSaveVisible = false">取消</el-button>
      <el-button type="primary" :loading="trainSaveLoading" @click="submitTrainSave">热更新入库</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const chatApi = axios.create({ baseURL: '/api', timeout: 120000 })
chatApi.interceptors.request.use(config => {
  const token = localStorage.getItem('admin_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
chatApi.interceptors.response.use(
  res => {
    const { code, message, data } = res.data
    if (code !== 200) {
      ElMessage.error(message || '请求失败')
      return Promise.reject(new Error(message))
    }
    return data
  },
  err => {
    ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

const newTrainSessionId = () => `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`

const messages = ref([])
const input = ref('')
const loading = ref(false)
const sumLoading = ref(false)
const healthLoading = ref(false)
const started = ref(false)
const businessProbe = ref(false)
const modelInfo = ref({})
const health = ref({ ok: false })
const summary = ref(null)
const boxRef = ref(null)
const trainSessionId = ref(newTrainSessionId())
const trainSaveVisible = ref(false)
const trainSaveLoading = ref(false)
const trainSaveForm = ref({ dataType: 1, question: '', standardAnswer: '', originalAiAnswer: '' })

const history = () => messages.value
  .filter(m => m.role === 'user' || m.role === 'assistant')
  .map(m => ({ role: m.role, content: m.content }))

const scrollBottom = () => nextTick(() => {
  if (boxRef.value) boxRef.value.scrollTop = boxRef.value.scrollHeight
})

const loadConfig = async () => {
  modelInfo.value = await chatApi.get('/admin/ai-chat/config')
}

const refreshHealth = async () => {
  healthLoading.value = true
  try {
    health.value = await chatApi.get('/admin/ai-chat/health')
  } finally {
    healthLoading.value = false
  }
}

const startCall = async () => {
  loading.value = true
  try {
    const r = await chatApi.post('/admin/ai-chat/chat', {
      firstTurn: true,
      trainSessionId: trainSessionId.value
    })
    messages.value = [{ role: 'assistant', content: r.reply, meta: `开场白 · ${r.latencyMs}ms` }]
    started.value = true
    summary.value = null
    scrollBottom()
  } finally {
    loading.value = false
  }
}

const send = async () => {
  const text = input.value.trim()
  if (!text || !started.value) return
  messages.value.push({ role: 'user', content: text })
  input.value = ''
  loading.value = true
  scrollBottom()
  try {
    const r = await chatApi.post('/admin/ai-chat/chat', {
      userText: text,
      history: history().slice(0, -1),
      businessProbeThisTurn: businessProbe.value,
      trainSessionId: trainSessionId.value
    })
    let meta = `${r.model || ''} · ${r.latencyMs}ms`
    if (r.invalidChatRounds != null) meta += ` · 闲聊计数 ${r.invalidChatRounds}/4`
    if (r.elapsedSeconds != null) meta += ` · ${r.elapsedSeconds}s`
    if (r.shouldHangup) meta += ` · 强制挂断: ${r.hangupType}`
    if (r.businessProbeNext) meta += ' · 下轮可勾选业务追问'
    messages.value.push({ role: 'assistant', content: r.reply, meta, userQuestion: text })
    businessProbe.value = !!r.businessProbeNext
    if (r.shouldHangup) started.value = false
    scrollBottom()
  } catch {
    messages.value.pop()
  } finally {
    loading.value = false
  }
}

const resetChat = () => {
  messages.value = []
  started.value = false
  businessProbe.value = false
  summary.value = null
  input.value = ''
  trainSessionId.value = newTrainSessionId()
}

const openSaveTraining = (msg) => {
  trainSaveForm.value = {
    dataType: 1,
    question: msg.userQuestion || '',
    standardAnswer: msg.content || '',
    originalAiAnswer: msg.content || ''
  }
  trainSaveVisible.value = true
}

const submitTrainSave = async () => {
  trainSaveLoading.value = true
  try {
    await chatApi.post('/admin/dialog-training/save', {
      dataType: trainSaveForm.value.dataType,
      question: trainSaveForm.value.question,
      standardAnswer: trainSaveForm.value.standardAnswer,
      status: 1,
      remark: trainSaveForm.value.dataType === 1 && trainSaveForm.value.originalAiAnswer
        ? `对话训练修正 原AI:${trainSaveForm.value.originalAiAnswer.slice(0, 80)}` : '对话训练入库'
    })
    ElMessage.success('已保存至训练知识库，外呼即时生效')
    trainSaveVisible.value = false
  } finally {
    trainSaveLoading.value = false
  }
}

const summarize = async () => {
  sumLoading.value = true
  try {
    const dialog = history().map(m => `${m.role === 'user' ? '客户' : 'AI'}: ${m.content}`).join('\n')
    summary.value = await chatApi.post('/admin/ai-chat/summarize', { dialogText: dialog })
  } finally {
    sumLoading.value = false
  }
}

onMounted(async () => {
  await loadConfig()
  await refreshHealth()
})
</script>

<style scoped>
.chat-card { min-height: 520px; }
.hdr { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.chat-box {
  height: 380px;
  overflow-y: auto;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 8px;
}
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
}
.msg.user .bubble { background: #ecf5ff; }
.role { font-size: 12px; color: #909399; margin-bottom: 4px; }
.text { white-space: pre-wrap; line-height: 1.5; }
.meta { font-size: 11px; color: #909399; margin-top: 6px; }
.toolbar { margin: 12px 0; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.input-row { display: flex; gap: 8px; }
.tip { font-size: 12px; color: #909399; margin-top: 8px; }
</style>
