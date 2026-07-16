<template>
  <el-card header="外呼模式" style="margin-bottom:16px">
    <el-form :model="voiceRuntime" label-width="120px">
      <el-form-item label="外呼模式">
        <el-radio-group v-model="voiceRuntime.outboundDialogMode" @change="onModeChange">
          <el-radio value="ai_realtime">AI 实时对话</el-radio>
          <el-radio value="smart_prerecord">智能预录外呼</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-button type="primary" @click="saveOutboundMode">保存外呼模式</el-button>
    </el-form>
  </el-card>

  <!-- AI 实时对话 -->
  <template v-if="isAiRealtime">
    <el-card header="AI 外呼链路" style="margin-bottom:16px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px">
        保存后<strong>下一通外呼即生效</strong>，无需重启。链路：VAD 句末 → ASR → LLM → CosyVoice TTS。
      </el-alert>
      <el-form :model="voiceRuntime" label-width="160px">
        <el-form-item label="句末档位">
          <el-radio-group v-model="voiceRuntime.silenceProfile">
            <el-radio value="stable">稳健（800ms，嘈杂移动线）</el-radio>
            <el-radio value="fast">极速（680ms，安静固话）</el-radio>
          </el-radio-group>
          <p class="voice-hint">
            云端 semantic_vad + 本地 120ms 防抖；稳健阈值 0.35，极速阈值 0.30。任务可单独覆盖。
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
            复刻 target_model 须为 <strong>cosyvoice-v3-plus</strong>。
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
        <el-button type="primary" @click="saveVoiceRuntime">保存 AI 外呼设置</el-button>
      </el-form>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="9">
        <el-card header="模型配置列表">
          <el-alert type="info" :closable="false" style="margin-bottom:12px">
            所有 AI 对话、意向摘要、外呼语音层均使用<strong>当前启用</strong>的配置。
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

  <!-- 智能预录外呼 -->
  <template v-else>
    <el-card header="预录音频配置">
      <el-alert type="info" :closable="false" style="margin-bottom:16px">
        智能预录外呼全程播放<strong>上传的真人录音</strong>。请先在
        <router-link to="/main-flow" style="color:#409eff">主线流程</router-link>
        为各节点上传录音，并在
        <router-link to="/dialog-training" style="color:#409eff">训练知识库</router-link>
        为每条标准问答上传应答录音。
      </el-alert>

      <el-form :model="promptForm" label-width="100px" style="max-width:640px">
        <el-form-item label="模板名称">
          <el-input v-model="promptForm.promptName" disabled />
        </el-form-item>
        <el-form-item label="开场白文案" required>
          <el-input
            v-model="promptForm.openingRemarks"
            type="textarea"
            :rows="2"
            placeholder="与上传录音内容一致，便于后台查看"
          />
        </el-form-item>
        <el-form-item label="开场白录音" required>
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept=".wav,.mp3,.m4a,.mp4"
            :disabled="!promptForm.id"
            :on-change="(f) => onPromptAudioPick('opening', f)"
          >
            <el-button :loading="openingUploading" :disabled="!promptForm.id">上传开场白录音</el-button>
          </el-upload>
          <el-button v-if="promptForm.openingAudioUrl" link type="primary" @click="playAudio(promptForm.openingAudioUrl)">试听</el-button>
          <p class="voice-hint">须先保存话术后才能上传；支持 wav / mp3 / m4a / mp4，自动转为 8k 电话 wav</p>
        </el-form-item>
        <el-form-item label="结束语文案">
          <el-input
            v-model="promptForm.endRemarks"
            type="textarea"
            :rows="2"
            placeholder="与上传录音内容一致（可留空）"
          />
        </el-form-item>
        <el-form-item label="结束语录音">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept=".wav,.mp3,.m4a,.mp4"
            :disabled="!promptForm.id"
            :on-change="(f) => onPromptAudioPick('ending', f)"
          >
            <el-button :loading="endingUploading" :disabled="!promptForm.id">上传结束语录音</el-button>
          </el-upload>
          <el-button v-if="promptForm.endingAudioUrl" link type="primary" @click="playAudio(promptForm.endingAudioUrl)">试听</el-button>
        </el-form-item>
        <el-form-item label="接通播报">
          <el-switch v-model="voiceRuntime.playOpeningOnAnswer" active-text="接通后播放开场白" inactive-text="不播报" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="promptSaving" @click="savePrompt">保存话术</el-button>
        </el-form-item>
      </el-form>

      <el-divider />

      <el-descriptions v-if="voiceStatus" :column="2" border size="small" style="max-width:640px">
        <el-descriptions-item label="开场白录音">
          <el-tag :type="voiceStatus.openingReady ? 'success' : 'warning'" size="small">
            {{ voiceStatus.openingReady ? '已上传' : '未上传' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="结束语录音">
          <el-tag :type="voiceStatus.endingReady ? 'success' : 'warning'" size="small">
            {{ voiceStatus.endingReady ? '已上传' : '未上传' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <p v-if="voiceStatus?.message" class="voice-hint">{{ voiceStatus.message }}</p>
      <p v-if="promptForm.isActive !== 1 && promptForm.id" class="voice-hint warn">当前模板未启用，请先在「AI话术」中启用该模板。</p>

      <div style="margin-top:12px">
        <el-button link @click="loadVoiceStatus">刷新状态</el-button>
        <el-button link type="primary" @click="$router.push('/dialog-training')">去上传知识库问答录音 →</el-button>
      </div>
    </el-card>
  </template>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const form = ref({ provider: 'ollama', maxTokens: 80, temperature: 0.7, maxHistoryRounds: 3 })

const voiceRuntime = ref({
  silenceProfile: 'stable',
  outboundDialogMode: 'ai_realtime',
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

const promptForm = ref({})
const voiceStatus = ref(null)
const promptSaving = ref(false)
const openingUploading = ref(false)
const endingUploading = ref(false)

const isAiRealtime = computed(() =>
  (voiceRuntime.value.outboundDialogMode || 'ai_realtime') === 'ai_realtime'
)

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

const toAudioUrl = (wavPath) => {
  if (!wavPath) return ''
  const p = wavPath.replace(/\\/g, '/')
  const idx = p.indexOf('/uploads/')
  return idx >= 0 ? p.substring(idx) : (p.startsWith('uploads/') ? '/' + p : p)
}

const playAudio = (url) => {
  if (!url) return
  const audio = new Audio(url)
  audio.play().catch(() => ElMessage.warning('无法播放，请检查录音文件'))
}

const loadActivePrompt = async () => {
  const templates = await request.get('/admin/ai-prompt/list')
  const active = templates.find(t => t.isActive === 1) || templates[0]
  if (active) {
    promptForm.value = {
      ...active,
      openingAudioUrl: toAudioUrl(active.openingWavPath),
      endingAudioUrl: toAudioUrl(active.endingWavPath)
    }
  } else {
    promptForm.value = {
      promptName: '默认模板',
      openingRemarks: '',
      endRemarks: '',
      isActive: 0,
      openingAudioUrl: '',
      endingAudioUrl: ''
    }
  }
}

const loadVoiceStatus = async () => {
  try {
    voiceStatus.value = await request.get('/admin/ai-prompt/fixed-voice-status')
  } catch {
    voiceStatus.value = null
  }
}

const onModeChange = () => {
  if (!isAiRealtime.value) {
    loadActivePrompt()
    loadVoiceStatus()
  }
}

const saveOutboundMode = async () => {
  await request.post('/admin/voice-runtime/save', {
    outboundDialogMode: voiceRuntime.value.outboundDialogMode || 'ai_realtime',
    playOpeningOnAnswer: voiceRuntime.value.playOpeningOnAnswer,
    silenceProfile: voiceRuntime.value.silenceProfile || 'stable',
    ttsVoiceMode: voiceRuntime.value.ttsVoiceMode || 'clone',
    cosyvoiceCloneVoiceId: voiceRuntime.value.cosyvoiceCloneVoiceId,
    cosyvoiceSystemVoice: voiceRuntime.value.cosyvoiceSystemVoice
  })
  ElMessage.success('外呼模式已保存')
  await loadVoiceRuntime()
  if (!isAiRealtime.value) {
    await loadActivePrompt()
    await loadVoiceStatus()
  }
}

const savePrompt = async () => {
  if (!promptForm.value.openingRemarks?.trim()) {
    ElMessage.warning('请填写开场白')
    return
  }
  promptSaving.value = true
  try {
    await request.post('/admin/ai-prompt/save', promptForm.value)
    await request.post('/admin/voice-runtime/save', {
      outboundDialogMode: 'smart_prerecord',
      playOpeningOnAnswer: voiceRuntime.value.playOpeningOnAnswer,
      silenceProfile: voiceRuntime.value.silenceProfile || 'stable',
      ttsVoiceMode: voiceRuntime.value.ttsVoiceMode || 'clone',
      cosyvoiceCloneVoiceId: voiceRuntime.value.cosyvoiceCloneVoiceId,
      cosyvoiceSystemVoice: voiceRuntime.value.cosyvoiceSystemVoice
    })
    ElMessage.success('话术已保存')
    await loadActivePrompt()
    await loadVoiceStatus()
  } finally {
    promptSaving.value = false
  }
}

const onPromptAudioPick = async (kind, file) => {
  if (!promptForm.value.id) {
    ElMessage.warning('请先保存话术再上传录音')
    return
  }
  const loading = kind === 'opening' ? openingUploading : endingUploading
  loading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file.raw)
    const path = kind === 'opening' ? 'upload-opening-audio' : 'upload-ending-audio'
    const res = await fetch(`/api/admin/ai-prompt/${promptForm.value.id}/${path}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${localStorage.getItem('admin_token')}` },
      body: fd
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '上传失败')
    if (kind === 'opening') {
      promptForm.value.openingAudioUrl = json.data?.audioUrl || ''
      promptForm.value.openingWavPath = json.data?.openingWavPath || ''
    } else {
      promptForm.value.endingAudioUrl = json.data?.audioUrl || ''
      promptForm.value.endingWavPath = json.data?.endingWavPath || ''
    }
    ElMessage.success(kind === 'opening' ? '开场白录音已上传' : '结束语录音已上传')
    await loadVoiceStatus()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    loading.value = false
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
    ElMessage.success('复刻成功：' + (v?.voiceId || ''))
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
  if (isAiRealtime.value) {
    await loadVoiceList()
  }
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
  ElMessage.success('已保存 AI 外呼设置')
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
  if (isAiRealtime.value) {
    await load()
  } else {
    await loadActivePrompt()
    await loadVoiceStatus()
  }
})
</script>

<style scoped>
.hint { margin-left: 8px; color: #67c23a; font-size: 12px; }
.voice-hint { margin: 6px 0 0; color: #909399; font-size: 12px; line-height: 1.5; max-width: 520px; }
.voice-hint.warn { color: #e6a23c; }
</style>
