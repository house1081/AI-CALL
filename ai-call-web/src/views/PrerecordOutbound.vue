<template>
  <el-card header="智能预录外呼">
    <el-alert type="info" :closable="false" style="margin-bottom:16px">
      从历史通话挖掘客户高频问题 → 配置应答文案 → 上传真人录音或 TTS 预合成 8k 录音。
      在「模型配置」切换为<strong>智能预录外呼</strong>后，通话将匹配高频录音应答，冷门问题播放转顾问话术并桥接坐席。
    </el-alert>

    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="6"><el-statistic title="高频问题" :value="stats.highFreqFaqCount || 0" /></el-col>
      <el-col :span="6"><el-statistic title="冷门问题" :value="stats.coldFaqCount || 0" /></el-col>
      <el-col :span="6"><el-statistic title="录音匹配次数" :value="stats.totalMatchCount || 0" /></el-col>
      <el-col :span="6"><el-statistic title="转顾问次数" :value="stats.totalTransferCount || 0" /></el-col>
    </el-row>

    <el-space wrap style="margin-bottom:16px">
      <el-button type="primary" @click="openUpload">上传问题+录音</el-button>
      <el-button type="primary" :loading="mining" @click="mineHistory">挖掘历史通话问题</el-button>
      <el-button :loading="precaching" @click="precacheGlobal">预合成全局话术录音</el-button>
      <el-button @click="loadAll">刷新</el-button>
    </el-space>

    <el-table :data="faqs" size="small" border>
      <el-table-column prop="questionDisplay" label="客户问题" min-width="140" />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column prop="tier" label="层级" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.tier === 'high_freq'" type="success" size="small">高频</el-tag>
          <el-tag v-else type="info" size="small">冷门</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="hitCount" label="历史频次" width="90" />
      <el-table-column prop="matchCount" label="匹配次数" width="90" />
      <el-table-column prop="answerText" label="应答文案" min-width="200" show-overflow-tooltip />
      <el-table-column label="录音" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.hasAnswerClip" type="success" size="small">已录</el-tag>
          <el-tag v-else type="warning" size="small">待合成</el-tag>
          <el-button v-if="row.audioUrl" link type="primary" size="small" @click="playAudio(row.audioUrl)">试听</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link @click="editFaq(row)">编辑</el-button>
          <el-button link type="primary" @click="precacheOne(row)">TTS合成</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="uploadVisible" title="上传问题 + 真人录音" width="560px" @closed="resetUpload">
    <el-form :model="uploadForm" label-width="100px">
      <el-form-item label="客户问题" required>
        <el-input v-model="uploadForm.questionDisplay" placeholder="如：利率多少" />
      </el-form-item>
      <el-form-item label="分类">
        <el-select v-model="uploadForm.category" style="width:100%">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词">
        <el-input v-model="uploadForm.keywords" placeholder="逗号分隔，如：利率,利息,年化" />
      </el-form-item>
      <el-form-item label="层级">
        <el-radio-group v-model="uploadForm.tier">
          <el-radio value="high_freq">高频（录应答）</el-radio>
          <el-radio value="cold">冷门（转顾问）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="应答文案">
        <el-input v-model="uploadForm.answerText" type="textarea" :rows="2" placeholder="与录音内容一致，便于后台查看" />
      </el-form-item>
      <el-form-item label="应答录音" required>
        <el-upload
          :auto-upload="false"
          :limit="1"
          accept=".wav,.mp3,.m4a,.mp4"
          :on-change="onUploadFileChange"
          :on-remove="onUploadFileRemove"
        >
          <el-button type="primary">选择 wav 文件</el-button>
          <template #tip>
            <div class="el-upload__tip">支持 wav / mp3 / m4a / mp4，最终统一转为 8k 电话 wav；mp4 等需服务器安装 ffmpeg</div>
          </template>
        </el-upload>
        <div v-if="uploadFileName" style="margin-top:8px;color:#606266">已选：{{ uploadFileName }}</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="uploadVisible = false">取消</el-button>
      <el-button type="primary" :loading="uploading" @click="submitUpload">上传保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="editVisible" title="编辑 FAQ" width="560px">
    <el-form :model="editForm" label-width="100px">
      <el-form-item label="客户问题"><el-input v-model="editForm.questionDisplay" /></el-form-item>
      <el-form-item label="分类">
        <el-select v-model="editForm.category" style="width:100%">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词"><el-input v-model="editForm.keywords" placeholder="逗号分隔" /></el-form-item>
      <el-form-item label="层级">
        <el-radio-group v-model="editForm.tier">
          <el-radio value="high_freq">高频（录应答）</el-radio>
          <el-radio value="cold">冷门（转顾问）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="应答文案"><el-input v-model="editForm.answerText" type="textarea" :rows="3" /></el-form-item>
      <el-form-item label="真人录音">
        <el-space wrap>
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".wav,.mp3,.m4a,.mp4"
            :show-file-list="false"
            :on-change="onEditAudioChange"
          >
            <el-button :loading="editAudioUploading">上传/替换录音</el-button>
          </el-upload>
          <el-button v-if="editForm.audioUrl" link type="primary" @click="playAudio(editForm.audioUrl)">试听当前</el-button>
        </el-space>
        <div class="el-upload__tip">支持 wav / mp3 / m4a / mp4；上传后将覆盖原录音</div>
      </el-form-item>
      <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">取消</el-button>
      <el-button type="primary" @click="saveFaq">保存文案</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api/request'

const stats = ref({})
const faqs = ref([])
const mining = ref(false)
const precaching = ref(false)
const editVisible = ref(false)
const editForm = ref({})
const uploadVisible = ref(false)
const uploadForm = ref({
  questionDisplay: '',
  category: '其他',
  keywords: '',
  answerText: '',
  tier: 'high_freq'
})
const uploadFile = ref(null)
const uploadFileName = ref('')
const uploading = ref(false)
const editAudioUploading = ref(false)
const categories = ['利率', '额度', '征信', '手续费', '办理流程', '放款时效', '其他']

const loadStats = async () => {
  stats.value = await request.get('/admin/prerecord/stats')
}

const loadFaqs = async () => {
  faqs.value = await request.get('/admin/prerecord/faq/list')
}

const loadAll = async () => {
  await Promise.all([loadStats(), loadFaqs()])
}

const playAudio = (url) => {
  if (!url) return
  const audio = new Audio(url)
  audio.play().catch(() => ElMessage.warning('无法播放，请检查录音文件'))
}

const openUpload = () => {
  resetUpload()
  uploadVisible.value = true
}

const resetUpload = () => {
  uploadForm.value = {
    questionDisplay: '',
    category: '其他',
    keywords: '',
    answerText: '',
    tier: 'high_freq'
  }
  uploadFile.value = null
  uploadFileName.value = ''
}

const onUploadFileChange = (file) => {
  uploadFile.value = file.raw
  uploadFileName.value = file.name
}

const onUploadFileRemove = () => {
  uploadFile.value = null
  uploadFileName.value = ''
}

const submitUpload = async () => {
  if (!uploadForm.value.questionDisplay?.trim()) {
    ElMessage.warning('请填写客户问题')
    return
  }
  if (!uploadFile.value) {
    ElMessage.warning('请上传 wav / mp3 / m4a / mp4 录音')
    return
  }
  const formData = new FormData()
  formData.append('questionDisplay', uploadForm.value.questionDisplay.trim())
  formData.append('category', uploadForm.value.category || '其他')
  if (uploadForm.value.keywords) formData.append('keywords', uploadForm.value.keywords)
  if (uploadForm.value.answerText) formData.append('answerText', uploadForm.value.answerText)
  formData.append('tier', uploadForm.value.tier || 'high_freq')
  formData.append('enabled', 'true')
  formData.append('file', uploadFile.value)
  uploading.value = true
  try {
    const token = localStorage.getItem('admin_token')
    const res = await fetch('/api/admin/prerecord/faq/upload', {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData
    })
    const json = await res.json()
    if (json.code !== 200) {
      ElMessage.error(json.message || '上传失败')
      return
    }
    ElMessage.success('问题与录音已保存')
    uploadVisible.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const onEditAudioChange = async (file) => {
  if (!editForm.value.id) return
  editAudioUploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file.raw)
    const token = localStorage.getItem('admin_token')
    const res = await fetch(`/api/admin/prerecord/faq/${editForm.value.id}/upload-audio`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData
    })
    const json = await res.json()
    if (json.code !== 200) {
      ElMessage.error(json.message || '上传失败')
      return
    }
    editForm.value = { ...editForm.value, ...json.data, enabled: json.data.enabled !== false }
    ElMessage.success('录音已更新')
    await loadFaqs()
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    editAudioUploading.value = false
  }
}

const mineHistory = async () => {
  mining.value = true
  try {
    const r = await request.post('/admin/prerecord/mine', { days: 30, limit: 500 })
    ElMessage.success(`挖掘完成：扫描${r.scannedRecords}通，提取${r.extractedUtterances}条，新增${r.newFaqs}，更新${r.updatedFaqs}`)
    await loadAll()
  } finally {
    mining.value = false
  }
}

const precacheGlobal = async () => {
  precaching.value = true
  try {
    const r = await request.post('/admin/prerecord/clip/precache-global')
    ElMessage.success(`全局话术已合成 ${r.precached} 条`)
    await loadStats()
  } finally {
    precaching.value = false
  }
}

const precacheOne = async (row) => {
  const r = await request.post(`/admin/prerecord/faq/${row.id}/precache`)
  ElMessage.success(`已合成 ${r.precached} 条应答录音`)
  await loadFaqs()
}

const editFaq = (row) => {
  editForm.value = { ...row, enabled: row.enabled !== false }
  editVisible.value = true
}

const saveFaq = async () => {
  await request.post('/admin/prerecord/faq/save', editForm.value)
  ElMessage.success('已保存')
  editVisible.value = false
  await loadAll()
}

onMounted(loadAll)
</script>
