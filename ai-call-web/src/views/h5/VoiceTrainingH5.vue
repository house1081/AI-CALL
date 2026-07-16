<template>
  <div class="h5-app">
    <header class="top-bar">
      <div class="top-left">
        <span class="title">对话训练</span>
        <span v-if="modeLabel" class="mode">{{ modeLabel }}</span>
      </div>
      <div class="top-right">
        <span v-if="inCall" class="timer">{{ timerText }}</span>
        <button type="button" class="icon-btn" aria-label="设置" @click="showSettings = !showSettings">⚙</button>
      </div>
    </header>

    <div v-if="showSettings" class="settings-panel">
      <label class="lbl">知识库</label>
      <select v-model="kbId" :disabled="inCall" class="sel">
        <option v-for="kb in kbList" :key="kb.id" :value="kb.id">{{ kb.kbName || kb.name }}</option>
      </select>
      <p v-if="modelInfo.model" class="meta-line">{{ modelInfo.provider }} · {{ modelInfo.model }}</p>
      <p v-if="isAiMode" class="meta-line" :class="{ offline: !health.ok }">
        {{ health.ok ? '模型在线' : '模型离线' }}
        <button type="button" class="link" @click="refreshHealth">检测</button>
      </p>
      <label v-if="isAiMode && inCall" class="chk">
        <input v-model="businessProbe" type="checkbox" /> 模拟上轮已追问业务
      </label>
    </div>

    <main ref="boxRef" class="chat-area">
      <div v-if="!messages.length && !inCall" class="empty">
        <div class="empty-icon">📞</div>
        <p>点击下方绿色按钮开始训练</p>
        <p class="empty-sub">按住麦克风说话，或直接输入文字（无麦克风也可对话）</p>
        <p v-if="!secureContext" class="empty-tip">麦克风需要 HTTPS 或 localhost，请改用 https 访问</p>
        <p v-else-if="!micApiReady" class="empty-tip">当前浏览器不支持麦克风，请用 Chrome / Safari</p>
      </div>
      <div
        v-for="(m, i) in messages"
        :key="i"
        :class="['msg', m.role]"
      >
        <div class="bubble">
          <div class="who">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="txt">{{ m.content }}</div>
          <button v-if="m.audioUrl" type="button" class="replay" @click="playUrl(m.audioUrl)">🔊 重听</button>
          <div v-if="m.meta" class="meta">{{ m.meta }}</div>
        </div>
      </div>
      <div v-if="recording" class="status-chip listening">正在听…</div>
      <div v-else-if="inCall && micAvailable === false" class="status-chip no-mic">未检测到麦克风，请用文字输入</div>
      <div v-else-if="playing && inCall" class="status-chip playing">AI 播报中，可说话打断</div>
      <div v-else-if="processing" class="status-chip processing">识别应答中…</div>
    </main>

    <footer class="bottom-bar">
      <div v-if="inCall" class="text-row">
        <input
          v-model="textInput"
          type="text"
          enterkeyhint="send"
          autocomplete="off"
          autocorrect="off"
          placeholder="输入客户话术"
          :disabled="processing"
          @keyup.enter="sendText"
        />
        <button type="button" class="send-btn" :disabled="processing || !textInput.trim()" @click="sendText">
          发送
        </button>
      </div>

      <div class="actions">
        <template v-if="!inCall">
          <button
            type="button"
            class="fab call"
            :disabled="starting"
            @click.stop.prevent="startCall"
            @touchend.stop.prevent="startCall"
          >
            {{ starting ? '…' : '📞' }}
          </button>
          <span class="fab-label">{{ callLabel }}</span>
        </template>
        <template v-else>
          <button
            v-if="micInteractReady"
            type="button"
            class="fab mic"
            :class="{ active: recording }"
            :disabled="processing"
            @click="toggleRecord"
            @pointerdown.prevent="onMicPointerDown"
            @pointerup.prevent="onMicPointerUp"
            @pointercancel.prevent="onMicPointerCancel"
            @pointerleave="(e) => recording && onMicPointerCancel(e)"
          >
            🎤
          </button>
          <span class="fab-label">{{ recording ? (isTouch ? '松开发送' : '点击结束') : micAvailable === false ? '用文字输入' : (micInteractReady ? (isTouch ? '按住说话' : '点击说话') : '请用文字输入') }}</span>
          <button type="button" class="fab hangup" :disabled="processing" @click="hangup">📵</button>
        </template>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useVoiceTraining } from '../../composables/useVoiceTraining.js'

const boxRef = ref(null)
const showSettings = ref(false)
const isTouch = computed(() => 'ontouchstart' in window || navigator.maxTouchPoints > 0)
const secureContext = computed(() => window.isSecureContext)
const micApiReady = computed(() => !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia))

const {
  kbList, kbId, modeLabel, modelInfo, health, businessProbe, inCall, starting,
  processing, recording, micAvailable, micInteractReady, messages, textInput, timerText, playing, isAiMode,
  callLabel, playUrl, startCall, hangup, toggleRecord,
  onMicPointerDown, onMicPointerUp, onMicPointerCancel,
  sendText, refreshHealth, init, cleanup
} = useVoiceTraining(() => boxRef.value)

onMounted(init)
onUnmounted(cleanup)
</script>

<style scoped>
.h5-app {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  background: #f0f2f5;
  overflow: hidden;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}

.top-bar {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  padding-top: calc(10px + env(safe-area-inset-top));
  background: #1e3a5f;
  color: #fff;
}
.title { font-size: 17px; font-weight: 600; }
.mode {
  display: block;
  font-size: 11px;
  opacity: 0.75;
  margin-top: 2px;
}
.timer { font-size: 14px; font-variant-numeric: tabular-nums; margin-right: 10px; }
.icon-btn {
  background: rgba(255,255,255,0.15);
  border: none;
  color: #fff;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  font-size: 18px;
}

.settings-panel {
  flex-shrink: 0;
  background: #fff;
  padding: 12px 14px;
  border-bottom: 1px solid #e4e7ed;
  font-size: 14px;
}
.lbl { display: block; font-size: 12px; color: #909399; margin-bottom: 6px; }
.sel {
  width: 100%;
  height: 40px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  padding: 0 10px;
  font-size: 15px;
  margin-bottom: 8px;
}
.meta-line { margin: 4px 0; font-size: 12px; color: #606266; }
.meta-line.offline { color: #f56c6c; }
.link { background: none; border: none; color: #409eff; font-size: 12px; margin-left: 8px; }
.chk { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 13px; }

.chat-area {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 12px 14px;
  padding-bottom: 8px;
}

.empty {
  text-align: center;
  padding: 48px 20px;
  color: #909399;
}
.empty-icon { font-size: 48px; margin-bottom: 12px; }
.empty-sub { font-size: 13px; margin-top: 8px; }
.empty-tip { font-size: 12px; margin-top: 16px; color: #e6a23c; line-height: 1.5; }

.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}
.msg.user .bubble {
  background: #409eff;
  color: #fff;
}
.msg.user .who,
.msg.user .meta { color: rgba(255,255,255,0.75); }
.who { font-size: 11px; color: #909399; margin-bottom: 4px; }
.txt { font-size: 15px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }
.meta { font-size: 10px; color: #909399; margin-top: 6px; }
.replay {
  margin-top: 6px;
  background: none;
  border: none;
  color: #409eff;
  font-size: 12px;
  padding: 0;
}
.msg.user .replay { color: rgba(255,255,255,0.9); }

.status-chip {
  text-align: center;
  font-size: 13px;
  padding: 8px;
  border-radius: 20px;
  margin: 8px auto;
  max-width: 240px;
}
.status-chip.listening { background: #e1f3d8; color: #67c23a; }
.status-chip.no-mic { background: #fef0f0; color: #f56c6c; font-size: 12px; line-height: 1.4; max-width: 280px; }
.status-chip.playing { background: #faecd8; color: #e6a23c; }
.status-chip.processing { background: #ecf5ff; color: #409eff; }

.bottom-bar {
  flex-shrink: 0;
  background: #fff;
  border-top: 1px solid #e4e7ed;
  padding: 10px 14px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom));
}

.text-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.text-row input {
  flex: 1;
  height: 40px;
  border: 1px solid #dcdfe6;
  border-radius: 20px;
  padding: 0 14px;
  font-size: 16px;
  outline: none;
}
.text-row input:focus { border-color: #409eff; }
.send-btn {
  flex-shrink: 0;
  height: 40px;
  padding: 0 16px;
  border: none;
  border-radius: 20px;
  background: #409eff;
  color: #fff;
  font-size: 14px;
}
.send-btn:disabled { opacity: 0.5; }

.actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  min-height: 72px;
}
.fab {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: none;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
}
.fab.call { background: #67c23a; }
.fab.mic { background: #409eff; color: #fff; }
.fab.mic.active { transform: scale(1.08); box-shadow: 0 0 0 4px rgba(64,158,255,0.3); }
.fab.hangup { background: #f56c6c; }
.fab:disabled { opacity: 0.6; }
.fab-label {
  font-size: 12px;
  color: #909399;
  min-width: 56px;
  text-align: center;
}
</style>
