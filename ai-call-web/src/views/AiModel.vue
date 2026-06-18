<template>
  <el-card header="外呼对话链路" style="margin-bottom:16px">
    <el-alert type="info" :closable="false" style="margin-bottom:12px">
      保存后<strong>下一通外呼即生效</strong>，无需重启服务。链路：VAD 句末 → ASR → LLM → CosyVoice TTS。
    </el-alert>
    <el-form :model="voiceRuntime" label-width="160px">
      <el-form-item label="句末档位">
        <el-radio-group v-model="voiceRuntime.silenceProfile">
          <el-radio value="stable">稳健（800ms，嘈杂移动线）</el-radio>
          <el-radio value="fast">极速（680ms，安静固话）</el-radio>
        </el-radio-group>
        <p class="voice-hint">
          云端 semantic_vad + 本地 120ms 防抖；稳健阈值 0.35，极速阈值 0.30。
          任务可单独覆盖。
        </p>
      </el-form-item>
      <el-form-item label="外呼对话模式">
        <el-radio-group v-model="voiceRuntime.outboundDialogMode">
          <el-radio value="ai_realtime">AI 实时对话（默认）</el-radio>
          <el-radio value="smart_prerecord">智能预录外呼</el-radio>
        </el-radio-group>
        <p class="voice-hint">
          AI 实时：ASR → LLM → CosyVoice TTS；遇 TTS 失败或客户连续抱怨答非所问时自动切入预录熔断。
          智能预录：全程 ASR 匹配高频问答录音，冷门问题转专业顾问（须在<a href="/prerecord-outbound" style="color:#409eff">预录外呼</a>配置题库与录音）。
        </p>
      </el-form-item>
      <el-form-item label="接通播报">
        <el-switch v-model="voiceRuntime.playOpeningOnAnswer" active-text="接通后 AI 先打招呼" inactive-text="不播报" />
      </el-form-item>
      <el-form-item label="音色来源">
        <el-radio-group v-model="voiceRuntime.ttsVoiceMode">
          <el-radio value="clone">复刻音色（cosyvoice-v3-plus）</el-radio>
          <el-radio value="system">系统预置音色（cosyvoice-v3-flash）</el-radio>
        </el-radio-group>
        <p class="voice-hint">
          定制复刻走 <strong>cosyvoice-v3-plus</strong>；系统预置走 <strong>cosyvoice-v3-flash</strong>，无需上传参考音频。
        </p>
      </el-form-item>
      <el-form-item v-if="voiceRuntime.ttsVoiceMode !== 'system'" label="CosyVoice 复刻">
        <el-select
          v-model="voiceRuntime.cosyvoiceCloneVoiceId"
          filterable
          allow-create
          default-first-option
          clearable
          placeholder="选择或粘贴 voice_id"
          style="width:420px"
        >
          <el-option
            v-for="v in compatibleVoices"
            :key="v.voiceId"
            :label="v.voiceId"
            :value="v.voiceId"
          />
        </el-select>
        <p class="voice-hint">
          复刻 target_model 须为 <strong>cosyvoice-v3-plus</strong>（与 yml 中 tts-model 一致）。
          <span v-if="voiceRuntime.defaultCosyvoiceCloneVoiceId">
            yml 默认：{{ voiceRuntime.defaultCosyvoiceCloneVoiceId }}
          </span>
        </p>
      </el-form-item>
      <el-form-item v-else label="系统预置音色">
        <el-select
          v-model="voiceRuntime.cosyvoiceSystemVoice"
          filterable
          placeholder="选择系统音色"
          style="width:420px"
        >
          <el-option
            v-for="v in (voiceRuntime.systemVoiceOptions || [])"
            :key="v.voiceId"
            :label="v.label + ' · ' + v.voiceId"
            :value="v.voiceId"
          />
        </el-select>
        <p class="voice-hint">
          当前生效：{{ voiceRuntime.effectiveTtsVoice || '-' }} / 模型 {{ voiceRuntime.effectiveTtsModel || '-' }}
        </p>
      </el-form-item>
      <el-form-item v-if="voiceRuntime.ttsVoiceMode !== 'system'" label="声音复刻">
        <el-input v-model="enrollForm.prefix" placeholder="前缀 myvoice（字母数字，≤10 位）" style="width:160px;margin-right:8px" />
        <el-input v-model="enrollForm.audioUrl" placeholder="参考音频公网 URL（10~20 秒）" style="width:280px;margin-right:8px" />
        <el-upload
          :show-file-list="false"
          accept=".wav,.mp3,.m4a"
          :http-request="uploadCloneAudio"
        >
          <el-button>上传音频</el-button>
        </el-upload>
        <el-button type="success" :loading="enrollLoading" @click="enrollVoice">创建复刻</el-button>
        <el-button link @click="loadVoiceList">刷新列表</el-button>
        <p class="voice-hint">
          上传后自动填入 URL；若 playback-base-url 为 127.0.0.1，DashScope 无法拉取，请改用 OSS 等公网地址。
        </p>
      </el-form-item>
      <el-alert v-if="!voiceRuntime.dashScopeConfigured" type="warning" :closable="false" style="margin-bottom:12px">
        未检测到 DashScope API Key，请先在下方启用「通义千问」并填写 Key。
      </el-alert>
      <el-collapse style="margin-bottom:12px">
        <el-collapse-item title="高级参数（只读，修改请编辑 application.yml 后重启）">
          <p class="voice-hint">句末静默：{{ voiceRuntime.userSilenceBeforeResponseMs ?? 1000 }} ms</p>
          <p class="voice-hint">分段播后尾音：{{ voiceRuntime.turnBasedPlaybackAsrTailMs ?? 800 }} ms</p>
          <p class="voice-hint">播音期插嘴能量阈值：{{ voiceRuntime.playbackBargeInEnergyThreshold ?? 320 }}</p>
        </el-collapse-item>
      </el-collapse>
      <el-button type="primary" @click="saveVoiceRuntime">保存链路设置</el-button>
    </el-form>
  </el-card>

  <el-row :gutter="16">
    <el-col :span="9">
      <el-card header="模型配置列表">
        <el-alert type="info" :closable="false" style="margin-bottom:12px">
          所有 AI 对话、意向摘要、外呼语音层均使用<strong>当前启用</strong>的配置。支持：本地 Ollama、通义千问、文心一言。
        </el-alert>
        <el-table :data="list" size="small" highlight-current-row @row-click="select">
          <el-table-column prop="configName" label="名称" />
          <el-table-column label="提供方" width="90">
            <template #default="{row}">{{ providerLabel(row.provider) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="72">
            <template #default="{row}">
              <el-tag v-if="row.isActive===1" type="success" size="small">启用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{row}">
              <el-button link v-if="row.isActive!==1" @click.stop="activate(row.id)">启用</el-button>
              <el-button link @click.stop="testConn(row.id)">测试</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button style="margin-top:12px" @click="newCfg">新增配置</el-button>
      </el-card>
    </el-col>
    <el-col :span="15">
      <el-card header="编辑模型">
        <el-form :model="form" label-width="120px">
          <el-form-item label="配置名称"><el-input v-model="form.configName" /></el-form-item>
          <el-form-item label="提供方">
            <el-select v-model="form.provider" @change="onProviderChange">
              <el-option label="本地 Ollama" value="ollama" />
              <el-option label="通义千问 (DashScope)" value="qwen" />
              <el-option label="文心一言 (千帆)" value="wenxin" />
            </el-select>
          </el-form-item>
          <el-form-item label="服务地址"><el-input v-model="form.baseUrl" :placeholder="basePlaceholder" /></el-form-item>
          <el-form-item label="模型名称"><el-input v-model="form.modelName" :placeholder="modelPlaceholder" /></el-form-item>
          <el-form-item v-if="form.provider!=='ollama'" label="API Key">
            <el-input v-model="form.apiKey" type="password" show-password :placeholder="form.id ? '留空则不修改' : '必填'" />
            <span v-if="form.apiKeySet && !form.apiKey" class="hint">已配置</span>
          </el-form-item>
          <el-form-item v-if="form.provider==='wenxin'" label="Secret Key">
            <el-input v-model="form.secretKey" type="password" show-password :placeholder="form.id ? '留空则不修改' : '必填'" />
            <span v-if="form.secretKeySet && !form.secretKey" class="hint">已配置</span>
          </el-form-item>
          <el-form-item label="最大输出 Token"><el-input-number v-model="form.maxTokens" :min="16" :max="2048" /></el-form-item>
          <el-form-item label="温度"><el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2" /></el-form-item>
          <el-form-item label="上下文轮数"><el-input-number v-model="form.maxHistoryRounds" :min="1" :max="10" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
          <el-button type="primary" @click="save">保存</el-button>
          <el-button v-if="form.id" @click="testConn(form.id)">测试连接</el-button>
        </el-form>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const form = ref({ provider: 'ollama', maxTokens: 80, temperature: 0.7, maxHistoryRounds: 3 })

const voiceRuntime = ref({
  silenceProfile: 'stable',
  ttsVoiceMode: 'clone',
  cosyvoiceCloneVoiceId: '',
  cosyvoiceSystemVoice: 'longanyang',
  systemVoiceOptions: [],
  effectiveTtsVoice: '',
  effectiveTtsModel: '',
  defaultCosyvoiceCloneVoiceId: '',
  cosyvoiceTtsModel: '',
  dashScopeConfigured: false,
  playOpeningOnAnswer: true
})
const voiceList = ref([])
const enrollLoading = ref(false)
const enrollForm = ref({ prefix: '', audioUrl: '' })

const compatibleVoices = computed(() =>
  (voiceList.value || []).filter(v => v.compatible !== false)
)

const loadVoiceList = async () => {
  try {
    voiceList.value = await request.get('/admin/cosyvoice-voice/list') || []
  } catch {
    voiceList.value = []
  }
}

const uploadCloneAudio = async ({ file }) => {
  const formData = new FormData()
  formData.append('file', file)
  try {
    const token = localStorage.getItem('admin_token')
    const res = await fetch('/api/admin/cosyvoice-voice/upload-audio', {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData
    })
    const json = await res.json()
    if (json.code !== 200) {
      ElMessage.error(json.message || '上传失败')
      return
    }
    enrollForm.value.audioUrl = json.data?.publicUrl || ''
    if (json.data?.hint) {
      ElMessage.warning(json.data.hint)
    } else {
      ElMessage.success('已上传，URL 已填入')
    }
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  }
}

const enrollVoice = async () => {
  if (!enrollForm.value.prefix?.trim() || !enrollForm.value.audioUrl?.trim()) {
    ElMessage.warning('请填写前缀和音频 URL')
    return
  }
  enrollLoading.value = true
  try {
    const v = await request.post('/admin/cosyvoice-voice/enroll', {
      prefix: enrollForm.value.prefix.trim(),
      audioUrl: enrollForm.value.audioUrl.trim()
    })
    ElMessage.success('复刻成功：' + (v?.voiceId || '') + '，正在排队预生成全部音色固定话术')
    voiceRuntime.value.cosyvoiceCloneVoiceId = v?.voiceId || ''
    await loadVoiceList()
  } finally {
    enrollLoading.value = false
  }
}

const providerLabel = (p) => ({ ollama: 'Ollama', qwen: '通义千问', wenxin: '文心一言' }[p] || p)

const defaults = {
  ollama: { baseUrl: 'http://192.168.60.28:11434', modelName: 'qwen:4b' },
  qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-turbo' },
  wenxin: { baseUrl: 'https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions', modelName: 'completions' }
}

const basePlaceholder = computed(() => defaults[form.value.provider]?.baseUrl || '')
const modelPlaceholder = computed(() => defaults[form.value.provider]?.modelName || '')

const loadVoiceRuntime = async () => {
  voiceRuntime.value = await request.get('/admin/voice-runtime')
  await loadVoiceList()
}

const saveVoiceRuntime = async () => {
  const mode = voiceRuntime.value.ttsVoiceMode || 'clone'
  if (mode === 'system') {
    if (!voiceRuntime.value.cosyvoiceSystemVoice) {
      ElMessage.error('请选择系统预置音色')
      return
    }
  } else {
    const cosy = (voiceRuntime.value.cosyvoiceCloneVoiceId || '').trim()
    if (!cosy && !voiceRuntime.value.defaultCosyvoiceCloneVoiceId) {
      ElMessage.error('请填写 CosyVoice 复刻 voice_id')
      return
    }
  }
  await request.post('/admin/voice-runtime/save', {
    silenceProfile: voiceRuntime.value.silenceProfile || 'stable',
    ttsVoiceMode: mode,
    cosyvoiceCloneVoiceId: voiceRuntime.value.cosyvoiceCloneVoiceId,
    cosyvoiceSystemVoice: voiceRuntime.value.cosyvoiceSystemVoice,
    playOpeningOnAnswer: voiceRuntime.value.playOpeningOnAnswer,
    outboundDialogMode: voiceRuntime.value.outboundDialogMode || 'ai_realtime'
  })
  ElMessage.success('已保存，将排队为全部音色预生成开场白/结束语')
  await loadVoiceRuntime()
}

const load = async (keepFormId) => {
  list.value = await request.get('/admin/ai-model/list')
  const active = list.value.find(x => x.isActive === 1)
  if (active && (!form.value.id || keepFormId === active.id)) {
    const d = await request.get(`/admin/ai-model/${active.id}`)
    form.value = { ...d, apiKey: '', secretKey: '' }
  }
}

const select = async (row) => {
  const d = await request.get(`/admin/ai-model/${row.id}`)
  form.value = { ...d, apiKey: '', secretKey: '' }
}

const newCfg = () => {
  form.value = { provider: 'ollama', ...defaults.ollama, configName: '', maxTokens: 80, temperature: 0.7, maxHistoryRounds: 3, remark: '' }
  onProviderChange()
}

const onProviderChange = () => {
  const d = defaults[form.value.provider]
  if (d && !form.value.id) {
    form.value.baseUrl = d.baseUrl
    form.value.modelName = d.modelName
  }
}

const save = async () => {
  const id = form.value.id
  await request.post('/admin/ai-model/save', form.value)
  ElMessage.success('保存成功')
  await load(id)
}

const activate = async (id) => {
  await request.post(`/admin/ai-model/activate/${id}`)
  ElMessage.success('已切换为当前模型')
  load()
}

const testConn = async (id) => {
  try {
    const r = await request.post(`/admin/ai-model/test/${id}`)
    if (r.ok) {
      const extra = r.modelReady === false ? '（模型未拉取，请先 ollama pull）' : ''
      ElMessage.success('连接成功' + extra)
    } else {
      ElMessage.error(r.error || '连接失败')
    }
  } catch {
    /* 错误已由拦截器提示 */
  }
}

onMounted(async () => {
  await loadVoiceRuntime()
  await load()
})
</script>

<style scoped>
.hint { margin-left: 8px; color: #67c23a; font-size: 12px; }
.voice-hint { margin: 6px 0 0; color: #909399; font-size: 12px; line-height: 1.5; max-width: 520px; }
</style>
