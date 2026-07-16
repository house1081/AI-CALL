import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/trainingRequest.js'

const BARGE_IN_ENERGY = 0.018
const BARGE_IN_HOLD_MS = 150
const BARGE_IN_GRACE_MS = 450
const PUBLIC_API = '/public/voice-training'

const ASR_ERROR_HINT = {
  silent: '未检测到声音，请靠近麦克风清晰说话',
  too_short: '录音太短，请按住多说 1～2 秒',
  no_text: '未识别到文字，请重试或改用文字输入',
  asr_not_configured: '语音识别服务未配置，请联系管理员',
  asr_error: '语音识别失败，请重试',
  file_missing: '音频上传失败，请重试',
  file_empty: '未采集到音频',
  wav_parse_error: '音频格式异常，请重试'
}

const asrErrorMessage = (code) =>
  (code && ASR_ERROR_HINT[code]) || '未识别到语音，请清晰说话或改用文字输入'

const micErrorMessage = (err) => {
  const name = err?.name || ''
  const msg = String(err?.message || '')
  if (name === 'NotFoundError' || /device not found/i.test(msg)) {
    return '未找到麦克风：请确认已连接麦克风，并在系统「声音 → 输入」中启用默认录音设备'
  }
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError') {
    return '麦克风权限被拒绝：请点击地址栏锁图标，允许使用麦克风'
  }
  if (name === 'NotReadableError') {
    return '麦克风被占用：请关闭其他正在使用麦克风的程序后重试'
  }
  if (name === 'OverconstrainedError') {
    return '当前麦克风不支持所需参数，请更换设备或检查系统录音设置'
  }
  if (name === 'SecurityError' || !window.isSecureContext) {
    return '麦克风需要 HTTPS 或 localhost 安全环境访问'
  }
  return msg || '无法访问麦克风'
}

const listAudioInputs = async () => {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices()
    return devices.filter(d => d.kind === 'audioinput' && d.deviceId)
  } catch {
    return []
  }
}

const isTouchDevice = () => 'ontouchstart' in window || navigator.maxTouchPoints > 0
const isIosSafari = () => {
  const ua = navigator.userAgent || ''
  const iOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  return iOS
}

/** 对话训练核心逻辑（PC / H5 共用） */
export function useVoiceTraining(getScrollEl) {
  const kbList = ref([])
  const kbId = ref(1)
  const sessionId = ref('')
  const callMode = ref('')
  const modeLabel = ref('')
  const modelInfo = ref({})
  const health = ref({ ok: true })
  const healthLoading = ref(false)
  const businessProbe = ref(false)
  const outboundMode = ref('')
  const inCall = ref(false)
  const starting = ref(false)
  const processing = ref(false)
  const recording = ref(false)
  const micAvailable = ref(null)
  const messages = ref([])
  const textInput = ref('')
  const sumLoading = ref(false)
  const summary = ref(null)
  const callStartMs = ref(0)
  const timerSec = ref(0)
  let timerHandle = null
  let mediaStream = null
  let mediaRecorder = null
  let recordChunks = []
  const currentAudio = ref(null)
  const playing = ref(false)
  let bargeInContext = null
  let bargeInAnalyser = null
  let bargeInSource = null
  let bargeInRaf = null
  let bargeInSpeechSince = 0
  let playbackStartedAt = 0
  let micPointerId = null
  let recorderMime = 'audio/webm'
  let recordStartedAt = 0
  let recordStopTimer = null
  let micWarmupTimer = null
  const micInteractReady = ref(true)

  const callLabel = computed(() => {
    if (starting.value) return '正在接通…'
    if (inCall.value) return '通话中'
    return '待机'
  })
  const timerText = computed(() => {
    const m = Math.floor(timerSec.value / 60).toString().padStart(2, '0')
    const s = (timerSec.value % 60).toString().padStart(2, '0')
    return `${m}:${s}`
  })
  const isPrerecordMode = computed(() => callMode.value === 'smart_prerecord')
  const isAiMode = computed(() => callMode.value === 'ai_realtime')
  const hintText = computed(() => {
    if (!inCall.value) return '点击绿色按钮开始模拟外呼（无麦克风也可文字输入）'
    if (micAvailable.value === false) return '麦克风不可用，请在下方输入框发送文字继续对话'
    if (isPrerecordMode.value) return 'AI 播报中可直接说话打断；按住麦克风或输入文字'
    return 'AI 播报中可直接说话打断；按住麦克风或输入文字'
  })

  const scrollBottom = () => nextTick(() => {
    const el = typeof getScrollEl === 'function' ? getScrollEl() : getScrollEl?.value
    if (el) el.scrollTop = el.scrollHeight
  })

  const history = () => messages.value
    .filter(m => m.role === 'user' || m.role === 'assistant')
    .map(m => ({ role: m.role, content: m.content }))

  const resolveAudioUrl = (url) => {
    if (!url) return ''
    if (url.startsWith('http')) return url
    const path = url.startsWith('/') ? url : `/${url}`
    return `${window.location.origin}${path}`
  }

  const stopBargeInMonitor = () => {
    if (bargeInRaf) {
      cancelAnimationFrame(bargeInRaf)
      bargeInRaf = null
    }
    bargeInSpeechSince = 0
    if (bargeInSource) {
      try { bargeInSource.disconnect() } catch { /* ignore */ }
      bargeInSource = null
    }
    bargeInAnalyser = null
  }

  const closeBargeInContext = () => {
    stopBargeInMonitor()
    if (bargeInContext) {
      bargeInContext.close().catch(() => {})
      bargeInContext = null
    }
  }

  const stopPlayback = () => {
    const a = currentAudio.value
    if (a) {
      a.onended = null
      a.pause()
      try { a.currentTime = 0 } catch { /* ignore */ }
    }
    playing.value = false
    stopBargeInMonitor()
  }

  const measureMicRms = (data) => {
    let sum = 0
    for (let i = 0; i < data.length; i++) {
      const v = (data[i] - 128) / 128
      sum += v * v
    }
    return Math.sqrt(sum / data.length)
  }

  const onBargeInDetected = () => {
    if (!mediaStream) return
    stopPlayback()
  }

  const startBargeInMonitor = async () => {
    if (!inCall.value || !playing.value || !mediaStream) return
    try {
      stopBargeInMonitor()
      if (!bargeInContext || bargeInContext.state === 'closed') {
        bargeInContext = new AudioContext()
      }
      if (bargeInContext.state === 'suspended') {
        await bargeInContext.resume()
      }
      bargeInSource = bargeInContext.createMediaStreamSource(mediaStream)
      bargeInAnalyser = bargeInContext.createAnalyser()
      bargeInAnalyser.fftSize = 2048
      bargeInSource.connect(bargeInAnalyser)
      playbackStartedAt = Date.now()
      const data = new Uint8Array(bargeInAnalyser.fftSize)
      const tick = () => {
        if (!playing.value || !bargeInAnalyser) return
        bargeInAnalyser.getByteTimeDomainData(data)
        const now = Date.now()
        if (now - playbackStartedAt < BARGE_IN_GRACE_MS) {
          bargeInRaf = requestAnimationFrame(tick)
          return
        }
        const rms = measureMicRms(data)
        if (rms > BARGE_IN_ENERGY) {
          if (!bargeInSpeechSince) bargeInSpeechSince = now
          else if (now - bargeInSpeechSince >= BARGE_IN_HOLD_MS) {
            onBargeInDetected()
            return
          }
        } else {
          bargeInSpeechSince = 0
        }
        bargeInRaf = requestAnimationFrame(tick)
      }
      bargeInRaf = requestAnimationFrame(tick)
    } catch { /* ignore */ }
  }

  const playUrl = async (url, { needMicMonitor = false } = {}) => {
    if (!url) return
    try {
      stopPlayback()
      const a = new Audio(resolveAudioUrl(url))
      a.setAttribute('playsinline', 'true')
      a.setAttribute('webkit-playsinline', 'true')
      a.preload = 'auto'
      a.onended = () => {
        playing.value = false
        stopBargeInMonitor()
      }
      currentAudio.value = a
      playing.value = true
      await a.play()
      if (needMicMonitor && mediaStream) startBargeInMonitor()
    } catch {
      playing.value = false
      stopBargeInMonitor()
    }
  }

  const pushAssistant = (text, meta, audioUrl, userQuestion, { autoPlay = true, needMicMonitor = false } = {}) => {
    messages.value.push({
      role: 'assistant',
      content: text || '（无应答）',
      meta,
      audioUrl,
      userQuestion
    })
    scrollBottom()
    if (audioUrl && autoPlay) playUrl(audioUrl, { needMicMonitor })
  }

  const startTimer = () => {
    callStartMs.value = Date.now()
    timerSec.value = 0
    timerHandle = setInterval(() => {
      timerSec.value = Math.floor((Date.now() - callStartMs.value) / 1000)
    }, 1000)
  }

  const stopTimer = () => {
    if (timerHandle) clearInterval(timerHandle)
    timerHandle = null
  }

  const loadKb = async () => {
    kbList.value = await api.get(`${PUBLIC_API}/knowledge-bases`)
    if (kbList.value.length && !kbList.value.find(k => k.id === kbId.value)) {
      kbId.value = kbList.value[0].id
    }
  }

  const loadConfig = async () => {
    try {
      const cfg = await api.get(`${PUBLIC_API}/config`)
      modelInfo.value = { provider: cfg.provider, model: cfg.model }
      outboundMode.value = cfg.outboundDialogMode || 'smart_prerecord'
    } catch { /* ignore */ }
  }

  const refreshHealth = async () => {
    healthLoading.value = true
    try {
      health.value = await api.get(`${PUBLIC_API}/health`)
    } finally {
      healthLoading.value = false
    }
  }

  const unlockAudioPlayback = async () => {
    try {
      if (!bargeInContext || bargeInContext.state === 'closed') {
        bargeInContext = new (window.AudioContext || window.webkitAudioContext)()
      }
      if (bargeInContext.state === 'suspended') {
        await bargeInContext.resume()
      }
    } catch { /* ignore */ }
  }

  const buildTurnMeta = (r, asrMs) => {
    let meta = `${r.model || ''} · ${r.turnMs}ms`
    if (asrMs) meta = `ASR ${asrMs}ms · ${meta}`
    if (r.invalidChatRounds != null) meta += ` · 闲聊 ${r.invalidChatRounds}/4`
    if (r.elapsedSeconds != null) meta += ` · ${r.elapsedSeconds}s`
    if (r.shouldHangup && r.hangupType) meta += ` · 挂断: ${r.hangupType}`
    if (r.businessProbeNext) meta += ' · 下轮可勾选业务追问'
    return meta
  }

  const applyTurnResult = (r, userQuestion, asrMs = 0) => {
    const meta = buildTurnMeta(r, asrMs)
    pushAssistant(r.replyText, meta, r.replyAudioUrl, userQuestion, {
      needMicMonitor: micAvailable.value === true
    })
    if (r.businessProbeNext != null) businessProbe.value = !!r.businessProbeNext
    if (r.shouldHangup) {
      ElMessage.info('对方已挂断')
      inCall.value = false
      stopTimer()
      sessionId.value = ''
      releaseMic()
    }
  }

  const ensureMic = async () => {
    if (mediaStream) return
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error('当前浏览器不支持麦克风，请用 Chrome/Safari 并确保 HTTPS 或 localhost')
    }
    if (!window.isSecureContext) {
      throw new Error(micErrorMessage({ name: 'SecurityError' }))
    }

    const attempts = [
      { audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } },
      { audio: true },
      { audio: { deviceId: { ideal: 'default' } } }
    ]
    let lastErr = null
    for (const constraints of attempts) {
      try {
        mediaStream = await navigator.mediaDevices.getUserMedia(constraints)
        micAvailable.value = true
        return
      } catch (e) {
        lastErr = e
        if (e.name === 'NotAllowedError' || e.name === 'SecurityError') break
      }
    }

    const inputs = await listAudioInputs()
    for (const dev of inputs) {
      if (!dev.deviceId) continue
      try {
        mediaStream = await navigator.mediaDevices.getUserMedia({
          audio: { deviceId: dev.deviceId === 'default' ? { ideal: 'default' } : { exact: dev.deviceId } }
        })
        micAvailable.value = true
        return
      } catch (e) {
        lastErr = e
      }
    }

    micAvailable.value = false
    throw new Error(micErrorMessage(lastErr))
  }

  const pickMime = () => {
    const candidates = isIosSafari()
      ? ['audio/mp4', 'audio/webm;codecs=opus', 'audio/webm', 'audio/aac']
      : ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']
    for (const mime of candidates) {
      if (MediaRecorder.isTypeSupported(mime)) return mime
    }
    return ''
  }

  const createRecorder = () => {
    const mime = pickMime()
    recorderMime = mime || 'audio/webm'
    try {
      return mime
        ? new MediaRecorder(mediaStream, { mimeType: mime, audioBitsPerSecond: 128000 })
        : new MediaRecorder(mediaStream)
    } catch {
      return new MediaRecorder(mediaStream)
    }
  }

  const releaseMic = () => {
    closeBargeInContext()
    if (mediaStream) {
      mediaStream.getTracks().forEach(t => t.stop())
      mediaStream = null
    }
  }

  /** 录音前重新拉麦克风，避免 AudioContext 占用导致 webm 无声 */
  const prepareMicForRecord = async () => {
    stopPlayback()
    closeBargeInContext()
    if (mediaStream) {
      mediaStream.getTracks().forEach(t => t.stop())
      mediaStream = null
    }
    await ensureMic()
  }

  const minRecordBytes = () => (isTouchDevice() ? 800 : 1200)
  const minRecordMs = () => (isTouchDevice() ? 500 : 400)

  const voiceUploadName = (mime) => {
    if (mime.includes('mp4') || mime.includes('aac')) return 'voice.m4a'
    if (mime.includes('webm')) return 'voice.webm'
    if (mime.includes('ogg')) return 'voice.ogg'
    return 'voice.wav'
  }

  const scheduleMicWarmup = () => {
    micInteractReady.value = false
    if (micWarmupTimer) clearTimeout(micWarmupTimer)
    micWarmupTimer = setTimeout(() => {
      micWarmupTimer = null
      if (inCall.value) micInteractReady.value = true
    }, 1500)
  }

  const startCall = async () => {
    if (starting.value || inCall.value) return
    const willUseAi = outboundMode.value !== 'smart_prerecord'
    if (willUseAi && !health.value.ok) {
      ElMessage.warning('大模型离线，您仍可用文字输入进行训练')
    }
    starting.value = true
    try {
      const r = await api.post(`${PUBLIC_API}/start`, null, { params: { kbId: kbId.value } })
      sessionId.value = r.sessionId
      callMode.value = r.mode
      modeLabel.value = r.mode === 'smart_prerecord' ? '智能预录外呼' : 'AI 实时外呼'
      if (r.provider && r.model) {
        modelInfo.value = { provider: r.provider, model: r.model }
      }
      messages.value = []
      summary.value = null
      businessProbe.value = false
      inCall.value = true
      scheduleMicWarmup()
      startTimer()
      let openingMeta = '开场白'
      if (r.openingLatencyMs != null) openingMeta += ` · ${r.openingLatencyMs}ms`
      pushAssistant(r.openingText, openingMeta, r.openingAudioUrl, null, { autoPlay: false })
    } catch (e) {
      ElMessage.error(e.response?.data?.message || '接通失败，请检查网络后重试')
    } finally {
      starting.value = false
    }
  }

  const hangup = async () => {
    stopPlayback()
    if (sessionId.value) {
      try {
        await api.post(`${PUBLIC_API}/end`, null, { params: { sessionId: sessionId.value } })
      } catch { /* ignore */ }
    }
    sessionId.value = ''
    callMode.value = ''
    inCall.value = false
    businessProbe.value = false
    micAvailable.value = null
    micInteractReady.value = true
    if (micWarmupTimer) {
      clearTimeout(micWarmupTimer)
      micWarmupTimer = null
    }
    stopTimer()
    releaseMic()
  }

  const beginRecord = async () => {
    if (processing.value || recording.value || !inCall.value || !micInteractReady.value) return
    try {
      await prepareMicForRecord()
      recordChunks = []
      mediaRecorder = createRecorder()
      const rec = mediaRecorder
      rec.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) recordChunks.push(e.data)
      }
      rec.start()
      recordStartedAt = Date.now()
      recording.value = true
    } catch (e) {
      micAvailable.value = false
      ElMessage.warning(micErrorMessage(e) + '，请改用下方文字输入')
    }
  }

  const endRecord = async () => {
    if (!recording.value || !mediaRecorder) return
    const heldMs = Date.now() - recordStartedAt
    recording.value = false
    const rec = mediaRecorder
    mediaRecorder = null
    if (heldMs < minRecordMs()) {
      try { if (rec.state === 'recording') rec.stop() } catch { /* ignore */ }
      ElMessage.warning('请按住多说 1～2 秒再松开')
      return
    }
    await new Promise((resolve) => {
      rec.addEventListener('stop', () => resolve(), { once: true })
      try {
        if (rec.state === 'recording') {
          try { rec.requestData() } catch { /* ignore */ }
          rec.stop()
        } else {
          resolve()
        }
      } catch {
        resolve()
      }
    })
    await new Promise(r => setTimeout(r, 80))
    const blob = new Blob(recordChunks, { type: rec.mimeType || recorderMime || 'audio/webm' })
    if (blob.size < minRecordBytes()) {
      ElMessage.warning('录音太短或未采集到声音，请检查麦克风权限')
      return
    }
    await uploadVoice(blob, rec.mimeType || recorderMime || 'audio/webm')
  }

  const toggleRecord = async () => {
    if (isTouchDevice()) return
    if (processing.value || !inCall.value || !micInteractReady.value) return
    if (recording.value) await endRecord()
    else await beginRecord()
  }

  const onMicPointerDown = (e) => {
    if (!isTouchDevice()) return
    if (e.pointerType === 'mouse') return
    if (!micInteractReady.value) return
    if (micPointerId != null) return
    micPointerId = e.pointerId
    try { e.currentTarget?.setPointerCapture?.(e.pointerId) } catch { /* ignore */ }
    beginRecord()
  }

  const onMicPointerUp = (e) => {
    if (!isTouchDevice()) return
    if (micPointerId != null && e.pointerId !== micPointerId) return
    micPointerId = null
    try { e.currentTarget?.releasePointerCapture?.(e.pointerId) } catch { /* ignore */ }
    endRecord()
  }

  const onMicPointerCancel = (e) => {
    if (!isTouchDevice()) return
    if (micPointerId != null && e.pointerId !== micPointerId) return
    micPointerId = null
    endRecord()
  }

  const uploadVoice = async (blob, mime) => {
    processing.value = true
    try {
      const fd = new FormData()
      fd.append('sessionId', sessionId.value)
      fd.append('audio', blob, voiceUploadName(mime))
      if (isAiMode.value) fd.append('businessProbeThisTurn', businessProbe.value)
      const r = await api.post(`${PUBLIC_API}/turn`, fd, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      if (r.userText) {
        messages.value.push({ role: 'user', content: r.userText, meta: r.asrMs ? `ASR ${r.asrMs}ms` : '' })
        scrollBottom()
      } else {
        ElMessage.warning(asrErrorMessage(r.asrError))
        return
      }
      applyTurnResult(r, r.userText, r.asrMs)
    } finally {
      processing.value = false
    }
  }

  const sendText = async () => {
    const text = textInput.value.trim()
    if (!text || !inCall.value || processing.value) return
    stopPlayback()
    textInput.value = ''
    messages.value.push({ role: 'user', content: text })
    scrollBottom()
    processing.value = true
    try {
      const r = await api.post(`${PUBLIC_API}/text-turn`, {
        sessionId: sessionId.value,
        userText: text,
        businessProbeThisTurn: isAiMode.value ? businessProbe.value : undefined
      })
      applyTurnResult(r, r.userText || text)
    } finally {
      processing.value = false
    }
  }

  const trainSaveVisible = ref(false)
  const trainSaveLoading = ref(false)
  const trainSaveForm = ref({ dataType: 1, question: '', standardAnswer: '', originalAiAnswer: '' })

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
      await api.post('/admin/dialog-training/save', {
        kbId: kbId.value,
        dataType: trainSaveForm.value.dataType,
        question: trainSaveForm.value.question,
        standardAnswer: trainSaveForm.value.standardAnswer,
        status: 1,
        remark: trainSaveForm.value.dataType === 1 && trainSaveForm.value.originalAiAnswer
          ? `对话训练修正 原AI:${trainSaveForm.value.originalAiAnswer.slice(0, 80)}` : '对话训练入库'
      })
      ElMessage.success('已保存至训练知识库')
      trainSaveVisible.value = false
    } finally {
      trainSaveLoading.value = false
    }
  }

  const summarize = async () => {
    sumLoading.value = true
    try {
      const dialog = history().map(m => `${m.role === 'user' ? '客户' : 'AI'}: ${m.content}`).join('\n')
      summary.value = await api.post('/admin/ai-chat/summarize', { dialogText: dialog })
    } finally {
      sumLoading.value = false
    }
  }

  const init = async () => {
    await loadKb()
    await loadConfig()
    await refreshHealth()
  }

  const cleanup = () => {
    hangup()
  }

  return {
    kbList, kbId, sessionId, callMode, modeLabel, modelInfo, health, healthLoading,
    businessProbe, outboundMode, inCall, starting, processing, recording, micAvailable, micInteractReady, messages,
    textInput, sumLoading, summary, timerSec, playing, trainSaveVisible, trainSaveLoading,
    trainSaveForm, callLabel, timerText, isPrerecordMode, isAiMode, hintText,
    playUrl, startCall, hangup, beginRecord, endRecord, toggleRecord,
    onMicPointerDown, onMicPointerUp, onMicPointerCancel,
    sendText, refreshHealth,
    openSaveTraining, submitTrainSave, summarize, init, cleanup
  }
}
