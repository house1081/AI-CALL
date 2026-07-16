<template>
  <el-card header="主线流程配置">
    <el-alert type="info" :closable="false" style="margin-bottom:16px">
      <p><strong>AI 实时对话</strong>：按下方节点文案，通过 TTS 实时播报并循序推进（客户问 FAQ 时仍走知识库兜底）。</p>
      <p style="margin-top:6px"><strong>智能预录外呼</strong>：每个节点须上传真人录音；外呼时按流程播放对应录音。</p>
      <p style="margin-top:6px;color:#909399">节点编号（如 03、07、18）影响分支逻辑（上班/做生意、转接、挽回等），请勿随意修改已有编号。</p>
    </el-alert>

    <el-form inline size="small" style="margin-bottom:12px">
      <el-form-item label="知识库">
        <el-select v-model="selectedKbId" style="width:220px" @change="loadAll">
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.kbName" :value="kb.id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="loadAll">刷新</el-button>
        <el-button @click="importLoanPack">导入默认银行贷款流程</el-button>
        <el-button type="primary" @click="openEdit()">新增节点</el-button>
      </el-form-item>
    </el-form>

    <el-descriptions v-if="summary" :column="4" border size="small" style="margin-bottom:16px">
      <el-descriptions-item label="节点数">{{ summary.totalSteps ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="已上传录音">{{ summary.audioReadyCount ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="主线状态">
        <el-tag :type="summary.mainFlowEnabled ? 'success' : 'warning'" size="small">
          {{ summary.mainFlowEnabled ? '已启用' : '未启用' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="说明">修改后立即生效，无需重启</el-descriptions-item>
    </el-descriptions>

    <el-table :data="steps" size="small" border max-height="560">
      <el-table-column prop="flowOrder" label="顺序" width="70" />
      <el-table-column prop="stepCode" label="节点" width="70" />
      <el-table-column prop="sceneName" label="场景" width="140" show-overflow-tooltip />
      <el-table-column prop="script" label="播报文案" show-overflow-tooltip />
      <el-table-column label="预录录音" width="100">
        <template #default="{ row }">
          <el-tag :type="row.audioReady ? 'success' : 'warning'" size="small">
            {{ row.audioReady ? '已上传' : '未上传' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept=".wav,.mp3,.m4a,.mp4"
            :on-change="(f) => onAudioPick(row, f)"
            style="display:inline-block;margin:0 6px"
          >
            <el-button link type="primary" size="small" :loading="uploadingId === row.id">上传录音</el-button>
          </el-upload>
          <el-button v-if="row.audioUrl" link type="primary" size="small" @click="playAudio(row.audioUrl)">试听</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑节点' : '新增节点'" width="560px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="节点编号" required>
          <el-input v-model="editForm.stepCode" placeholder="如 01、02、18" :disabled="!!editForm.id" />
        </el-form-item>
        <el-form-item label="场景名称">
          <el-input v-model="editForm.sceneName" placeholder="如 询问资金额度" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="editForm.flowOrder" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="播报文案" required>
          <el-input v-model="editForm.script" type="textarea" :rows="4" placeholder="AI 实时 TTS 播报 / 预录对照文本" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveStep">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const knowledgeBases = ref([])
const selectedKbId = ref(1)
const steps = ref([])
const summary = ref(null)
const editVisible = ref(false)
const saving = ref(false)
const uploadingId = ref(null)

const defaultEditForm = () => ({
  id: null,
  kbId: selectedKbId.value,
  stepCode: '',
  sceneName: '',
  script: '',
  flowOrder: 10
})
const editForm = ref(defaultEditForm())

const playAudio = (url) => {
  if (!url) return
  const audio = new Audio(url)
  audio.play().catch(() => ElMessage.warning('无法播放，请检查录音文件'))
}

const loadKnowledgeBases = async () => {
  knowledgeBases.value = await request.get('/admin/dialog-training/knowledge-bases?status=1')
  if (!knowledgeBases.value.find(k => k.id === selectedKbId.value) && knowledgeBases.value.length) {
    selectedKbId.value = knowledgeBases.value[0].id
  }
}

const loadAll = async () => {
  const kbId = selectedKbId.value
  steps.value = await request.get(`/admin/dialog-training/main-flow?kbId=${kbId}`)
  summary.value = await request.get(`/admin/dialog-training/main-flow/summary?kbId=${kbId}`)
}

const openEdit = (row) => {
  if (row) {
    editForm.value = {
      id: row.id,
      kbId: selectedKbId.value,
      stepCode: row.stepCode,
      sceneName: row.sceneName,
      script: row.script,
      flowOrder: row.flowOrder ?? 10
    }
  } else {
    const maxOrder = steps.value.reduce((m, s) => Math.max(m, s.flowOrder || 0), 0)
    editForm.value = { ...defaultEditForm(), flowOrder: maxOrder + 10 }
  }
  editVisible.value = true
}

const saveStep = async () => {
  if (!editForm.value.stepCode?.trim() || !editForm.value.script?.trim()) {
    ElMessage.warning('请填写节点编号与播报文案')
    return
  }
  saving.value = true
  try {
    await request.post('/admin/dialog-training/main-flow/save', {
      ...editForm.value,
      kbId: selectedKbId.value
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await loadAll()
  } finally {
    saving.value = false
  }
}

const onAudioPick = async (row, file) => {
  uploadingId.value = row.id
  try {
    const fd = new FormData()
    fd.append('file', file.raw)
    const res = await fetch(`/api/admin/dialog-training/${row.id}/upload-audio`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${localStorage.getItem('admin_token')}` },
      body: fd
    })
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '上传失败')
    ElMessage.success('录音已上传')
    await loadAll()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploadingId.value = null
  }
}

const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除节点 ${row.stepCode}「${row.sceneName}」？`, '确认')
  await request.delete(`/admin/dialog-training/main-flow/${row.id}`)
  ElMessage.success('已删除')
  await loadAll()
}

const importLoanPack = async () => {
  const r = await request.post(`/admin/dialog-training/import-loan-pack?replaceExisting=false&kbId=${selectedKbId.value}`)
  ElMessage.success(`导入完成：主线 ${r.mainFlow} 条，兜底 ${r.fallbacks} 条`)
  await loadAll()
}

onMounted(async () => {
  await loadKnowledgeBases()
  await loadAll()
})
</script>
