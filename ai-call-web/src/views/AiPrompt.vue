<template>
  <el-row :gutter="16">
    <el-col :span="8">
      <el-card header="话术模板">
        <el-table :data="templates" size="small" @row-click="select">
          <el-table-column prop="id" label="ID" width="50" />
          <el-table-column prop="promptName" label="名称" width="100" show-overflow-tooltip />
          <el-table-column prop="openingRemarks" label="开场白" show-overflow-tooltip />
          <el-table-column label="知识库" width="90">
            <template #default="{row}">{{ kbName(row.kbId) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="70">
            <template #default="{row}"><el-tag :type="row.isActive===1?'success':'info'">{{ row.isActive===1?'使用中':'' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{row}"><el-button link v-if="row.isActive!==1" @click.stop="activate(row.id)">启用</el-button></template>
          </el-table-column>
        </el-table>
        <el-button style="margin-top:12px" @click="newTemplate">新建模板</el-button>
      </el-card>
    </el-col>
    <el-col :span="16">
      <el-card header="编辑话术">
        <el-form :model="form" label-width="100px">
          <el-form-item label="模板名称"><el-input v-model="form.promptName" placeholder="如：银行贷款外呼" /></el-form-item>
          <el-form-item label="训练知识库">
            <el-select v-model="form.kbId" style="width:100%" placeholder="选择绑定的知识库">
              <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.kbName" :value="kb.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="话术内容"><el-input v-model="form.promptContent" type="textarea" :rows="8" /></el-form-item>
          <el-form-item label="开场白"><el-input v-model="form.openingRemarks" placeholder="接通后 AI 先说这句话" /></el-form-item>
          <el-form-item label="结束语"><el-input v-model="form.endRemarks" placeholder="挂断前告别语（可留空则用系统默认）" /></el-form-item>
          <el-form-item>
            <el-button type="primary" @click="save">保存</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card header="固定话术语音" style="margin-top:16px">
        <el-alert type="info" :closable="false" style="margin-bottom:12px">
          修改开场白/结束语、新增 CosyVoice 音色后，系统会为<strong>全部音色</strong>排队预生成 wav；
          外呼时按「外呼对话链路」中当前选定的音色播放，不再现场调 TTS。
        </el-alert>
        <el-descriptions v-if="voiceStatus" :column="2" border size="small">
          <el-descriptions-item label="TTS 模型">{{ voiceStatus.ttsModel || '-' }}</el-descriptions-item>
          <el-descriptions-item label="当前外呼音色">{{ voiceStatus.ttsVoice || '-' }}</el-descriptions-item>
          <el-descriptions-item label="音色总数">{{ voiceStatus.totalVoiceCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="开场白预录">
            {{ voiceStatus.openingReadyCount ?? 0 }}/{{ voiceStatus.totalVoiceCount ?? 0 }}
            <el-tag :type="voiceStatus.openingReady ? 'success' : 'warning'" size="small" style="margin-left:6px">
              {{ voiceStatus.openingReady ? '全部就绪' : '生成中/未完成' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="结束语预录" :span="2">
            {{ voiceStatus.endingReadyCount ?? 0 }}/{{ voiceStatus.totalVoiceCount ?? 0 }}
            <el-tag :type="voiceStatus.endingReady ? 'success' : 'warning'" size="small" style="margin-left:6px">
              {{ voiceStatus.endingReady ? '全部就绪' : '生成中/未完成' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <p v-if="voiceStatus?.message" class="hint">{{ voiceStatus.message }}</p>
        <p v-if="form.isActive !== 1 && form.id" class="hint warn">当前编辑的模板未启用，预生成针对「使用中」模板；请先启用后再预生成。</p>
        <div style="margin-top:12px">
          <el-button type="success" :loading="precaching" :disabled="form.isActive !== 1" @click="precacheVoice">
            预生成全部音色
          </el-button>
          <el-button link @click="loadVoiceStatus">刷新状态</el-button>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import axios from 'axios'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const templates = ref([])
const knowledgeBases = ref([])
const form = ref({})
const voiceStatus = ref(null)
const precaching = ref(false)

const kbName = (id) => knowledgeBases.value.find(k => k.id === id)?.kbName || (id ? `#${id}` : '-')

const loadKnowledgeBases = async () => {
  knowledgeBases.value = await request.get('/admin/dialog-training/knowledge-bases?status=1')
}

const loadVoiceStatus = async () => {
  voiceStatus.value = await request.get('/admin/ai-prompt/fixed-voice-status')
}

const load = async () => {
  await loadKnowledgeBases()
  templates.value = await request.get('/admin/ai-prompt/list')
  const active = templates.value.find(t => t.isActive === 1)
  if (active) form.value = { ...active }
  await loadVoiceStatus()
}

const select = (row) => { form.value = { ...row } }

const newTemplate = () => {
  form.value = {
    promptName: '',
    kbId: knowledgeBases.value[0]?.id || 1,
    promptContent: '',
    openingRemarks: '',
    endRemarks: '',
    isActive: 0
  }
}

const save = async () => {
  await request.post('/admin/ai-prompt/save', form.value)
  ElMessage.success('保存成功，使用中模板将自动排队预生成')
  await load()
}

const activate = async (id) => {
  await request.post(`/admin/ai-prompt/activate/${id}`)
  ElMessage.success('已切换，将自动排队预生成')
  await load()
}

const precacheVoice = async () => {
  if (form.value.isActive !== 1) {
    ElMessage.warning('请先启用当前模板')
    return
  }
  precaching.value = true
  try {
    const token = localStorage.getItem('admin_token')
    const res = await axios.post('/api/admin/ai-prompt/precache-fixed-voice', null, {
      timeout: 120000,
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
    const { code, message, data } = res.data
    if (code !== 200) {
      ElMessage.error(message || '预生成失败')
      return
    }
    voiceStatus.value = data
    ElMessage.success(data.message || '预生成完成')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '预生成失败')
  } finally {
    precaching.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.hint { font-size: 12px; color: #909399; margin-top: 8px; line-height: 1.5; }
.hint.warn { color: #e6a23c; }
</style>
