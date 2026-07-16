<template>
  <el-row :gutter="16">
    <el-col :span="10">
      <div class="phone-shell">
        <div class="phone-notch" />
        <div class="phone-screen">
          <div class="call-header">
            <span class="call-state">{{ callLabel }}</span>
            <span class="call-timer">{{ timerText }}</span>
          </div>
          <div class="avatar-wrap">
            <div class="avatar" :class="{ pulse: inCall }">
              <el-icon :size="48"><Phone /></el-icon>
            </div>
            <p class="peer-name">AI 外呼助手</p>
            <p class="peer-sub">{{ modeLabel }}</p>
          </div>
          <div v-if="recording" class="recording-hint">
            <span class="dot" /> 正在听您说话…
          </div>
          <div v-else-if="inCall && micAvailable === false" class="recording-hint no-mic-hint">
            麦克风不可用，请在右侧输入文字继续对话
          </div>
          <div v-else-if="playing && inCall" class="recording-hint playing-hint">
            <span class="dot play" /> AI 播报中，可直接说话打断
          </div>
          <div v-if="processing && !recording" class="recording-hint">
            <span class="dot" /> 正在识别并应答…
          </div>
          <div class="phone-actions">
            <el-button
              v-if="!inCall"
              type="success"
              circle
              size="large"
              class="act-btn call"
              :loading="starting"
              @click="startCall"
            >
              <el-icon><Phone /></el-icon>
            </el-button>
            <template v-else>
              <el-button
                type="primary"
                circle
                size="large"
                class="act-btn"
                :class="{ active: recording }"
                :disabled="processing"
                @pointerdown.prevent="beginRecord"
                @pointerup.prevent="endRecord"
                @pointercancel.prevent="endRecord"
                @pointerleave="recording && endRecord()"
              >
                <el-icon><Microphone /></el-icon>
              </el-button>
              <el-button
                type="danger"
                circle
                size="large"
                class="act-btn hangup"
                :disabled="processing"
                @click="hangup"
              >
                <el-icon><PhoneFilled /></el-icon>
              </el-button>
            </template>
          </div>
          <p class="hint">{{ hintText }}</p>
        </div>
      </div>
      <el-card class="side-card" header="说明">
        <div class="mode-tags">
          <el-tag v-if="modelInfo.model" type="info" size="small">{{ modelInfo.provider }} · {{ modelInfo.model }}</el-tag>
          <el-tag v-if="isAiMode" :type="health.ok ? 'success' : 'danger'" size="small">
            {{ health.ok ? '模型在线' : '模型离线' }}
          </el-tag>
          <el-button v-if="isAiMode" link type="primary" size="small" @click="refreshHealth" :loading="healthLoading">检测</el-button>
        </div>
        <p v-if="isPrerecordMode">与真实外呼一致：开场白 → 您说话(ASR)或文字 → 知识库/主线匹配 → 播放预录音。</p>
        <p v-else>与 AI 实时外呼一致：大模型生成应答，支持语音(ASR)或文字输入。</p>
        <p class="h5-link">
          <router-link to="/h5/training" target="_blank">📱 打开手机 H5 版</router-link>
          <span class="h5-tip">（同网访问：http://电脑IP:5173/h5/training）</span>
        </p>
        <el-form-item label="知识库" style="margin-top:12px">
          <el-select v-model="kbId" :disabled="inCall" style="width:100%">
            <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <p class="mode-tip">当前外呼模式由「模型配置」页决定，接通后自动切换。</p>
      </el-card>
    </el-col>
    <el-col :span="14">
      <el-card>
        <template #header>
          <div class="hdr">
            <span>通话记录</span>
            <el-button v-if="messages.length >= 2" link type="primary" @click="summarize" :loading="sumLoading">意向摘要</el-button>
          </div>
        </template>
        <div ref="boxRef" class="transcript">
          <div v-for="(m, i) in messages" :key="i" :class="['line', m.role]">
            <div class="bubble">
              <div class="who">{{ m.role === 'user' ? '客户' : 'AI' }}</div>
              <div class="txt">{{ m.content }}</div>
              <div v-if="m.audioUrl" class="audio-row">
                <el-button link type="primary" size="small" @click="playUrl(m.audioUrl)">重听录音</el-button>
              </div>
              <div v-if="m.meta" class="meta">{{ m.meta }}</div>
              <el-button
                v-if="m.role === 'assistant' && m.userQuestion"
                link type="primary" size="small"
                @click="openSaveTraining(m)"
              >保存为训练数据</el-button>
            </div>
          </div>
        </div>
        <div v-if="inCall" class="text-fallback">
          <el-input v-model="textInput" placeholder="也可直接输入客户话术（免录音）" @keyup.enter="sendText" />
          <el-button :loading="processing" @click="sendText">发送</el-button>
        </div>
      </el-card>
      <el-card v-if="summary" header="意向摘要" style="margin-top:12px">
        <p><b>等级</b> {{ summary.level }}</p>
        <p><b>需求</b> {{ summary.customerNeed || '-' }}</p>
        <p><b>痛点</b> {{ summary.customerPain || '-' }}</p>
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
import { ref, onMounted, onUnmounted } from 'vue'
import { Phone, PhoneFilled, Microphone } from '@element-plus/icons-vue'
import { useVoiceTraining } from '../composables/useVoiceTraining.js'

const boxRef = ref(null)

const {
  kbList, kbId, modeLabel, modelInfo, health, healthLoading, businessProbe,
  inCall, starting, processing, recording, micAvailable, messages, textInput, sumLoading,
  summary, playing, trainSaveVisible, trainSaveLoading, trainSaveForm,
  callLabel, timerText, isPrerecordMode, isAiMode, hintText,
  playUrl, startCall, hangup, beginRecord, endRecord, sendText, refreshHealth,
  openSaveTraining, submitTrainSave, summarize, init, cleanup
} = useVoiceTraining(boxRef)

onMounted(init)
onUnmounted(cleanup)
</script>

<style scoped>
.phone-shell {
  width: 320px;
  margin: 0 auto 16px;
  padding: 12px;
  background: #1a1a1a;
  border-radius: 36px;
  box-shadow: 0 12px 40px rgba(0,0,0,.25);
}
.phone-notch {
  width: 100px;
  height: 6px;
  background: #333;
  border-radius: 3px;
  margin: 0 auto 8px;
}
.phone-screen {
  background: linear-gradient(180deg, #1e3a5f 0%, #0d1b2a 100%);
  border-radius: 28px;
  padding: 20px 16px 24px;
  color: #fff;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.call-header {
  width: 100%;
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  opacity: .85;
}
.avatar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.avatar {
  width: 88px; height: 88px; border-radius: 50%;
  background: rgba(255,255,255,.12);
  display: flex; align-items: center; justify-content: center;
}
.avatar.pulse { animation: pulse 2s infinite; }
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(64,158,255,.4); }
  50% { box-shadow: 0 0 0 16px rgba(64,158,255,0); }
}
.peer-name { margin: 12px 0 4px; font-size: 18px; font-weight: 600; }
.peer-sub { margin: 0; font-size: 12px; opacity: .7; }
.recording-hint { color: #67c23a; font-size: 13px; margin-bottom: 8px; }
.no-mic-hint { color: #f56c6c; }
.playing-hint { color: #e6a23c; }
.recording-hint .dot {
  display: inline-block; width: 8px; height: 8px; background: #67c23a;
  border-radius: 50%; margin-right: 6px; animation: blink 1s infinite;
}
.recording-hint .dot.play { background: #e6a23c; }
@keyframes blink { 50% { opacity: .3; } }
.phone-actions { display: flex; gap: 28px; align-items: center; margin-top: 8px; }
.act-btn { width: 64px !important; height: 64px !important; }
.act-btn.call { background: #67c23a; border-color: #67c23a; }
.act-btn.hangup { background: #f56c6c; border-color: #f56c6c; }
.act-btn.active { background: #409eff; transform: scale(1.08); }
.hint { font-size: 11px; opacity: .65; text-align: center; margin-top: 12px; }
.mode-tags { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.mode-tip { font-size: 11px; color: #909399; margin-top: 8px; }
.h5-link { font-size: 13px; margin: 10px 0 0; }
.h5-link a { color: #409eff; text-decoration: none; }
.h5-tip { display: block; font-size: 11px; color: #909399; margin-top: 4px; }
.side-card { max-width: 360px; margin: 0 auto; }
.transcript {
  height: 420px; overflow-y: auto; padding: 8px; background: #f5f7fa; border-radius: 8px;
}
.line { display: flex; margin-bottom: 10px; }
.line.user { justify-content: flex-end; }
.bubble {
  max-width: 88%; padding: 8px 12px; border-radius: 10px; background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
}
.line.user .bubble { background: #ecf5ff; }
.who { font-size: 11px; color: #909399; margin-bottom: 2px; }
.txt { white-space: pre-wrap; line-height: 1.5; font-size: 14px; }
.meta { font-size: 11px; color: #909399; margin-top: 4px; }
.hdr { display: flex; justify-content: space-between; align-items: center; }
.text-fallback { display: flex; gap: 8px; margin-top: 12px; }
</style>
