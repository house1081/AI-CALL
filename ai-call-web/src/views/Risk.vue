<template>
  <el-card>
    <el-form :model="form" label-width="140px" style="max-width:720px">
      <el-form-item label="拨打间隔(秒)"><el-input-number v-model="form.callInterval" :min="10" :max="60" /></el-form-item>
      <el-form-item label="短通话阈值(秒)"><el-input-number v-model="form.shortCallLimit" :min="5" :max="60" /></el-form-item>
      <el-form-item label="外呼开始"><el-input v-model="form.callStartTime" placeholder="09:00" /></el-form-item>
      <el-form-item label="外呼结束"><el-input v-model="form.callEndTime" placeholder="19:00" /></el-form-item>
      <el-form-item label="高投诉地区"><el-input v-model="form.highComplaintArea" placeholder="广东,河南" /></el-form-item>

      <el-divider content-position="left">敏感词监控 / 转人工</el-divider>
      <el-form-item label="启用敏感词监控">
        <el-switch v-model="form.sensitiveMonitorEnabled" :active-value="1" :inactive-value="0" />
        <span class="hint">对客户 ASR 文本实时匹配，不影响录音与对话</span>
      </el-form-item>
      <el-form-item label="命中后转人工">
        <el-switch v-model="form.humanTransferEnabled" :active-value="1" :inactive-value="0" />
      </el-form-item>
      <el-form-item label="FS 转接目标">
        <el-input v-model="form.humanTransferDest" placeholder="user/1001 或 sofia/gateway/default/13800138000" style="width:420px" />
        <p class="hint">FreeSWITCH originate 目标，与客户通道 uuid_bridge 桥接</p>
      </el-form-item>
      <el-form-item label="转接前播报">
        <el-input v-model="form.humanTransferPrompt" type="textarea" :rows="2" style="width:420px" />
      </el-form-item>
      <el-button type="primary" @click="save">保存风控配置</el-button>
    </el-form>

    <el-divider />
    <h3>违规词 / 敏感词库</h3>
    <el-alert type="info" :closable="false" style="margin-bottom:12px">
      客户说话经 ASR 识别后匹配词库；命中且已启用转人工时，停止 AI 对话并 bridge 到坐席，通话保持不断。
    </el-alert>
    <div style="margin-bottom:12px">
      <el-input v-model="newWord.word" placeholder="词条" style="width:180px;margin-right:8px" />
      <el-select v-model="newWord.wordType" style="width:120px;margin-right:8px">
        <el-option label="违规词" :value="1" />
        <el-option label="敏感词" :value="2" />
      </el-select>
      <el-button type="primary" @click="addWord">添加</el-button>
    </div>
    <el-table :data="words" stripe size="small">
      <el-table-column prop="word" label="词条" />
      <el-table-column label="类型" width="90">
        <template #default="{row}">{{ row.wordType === 1 ? '违规词' : '敏感词' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{row}">
          <el-tag :type="row.status===1?'success':'info'" size="small">{{ row.status===1?'启用':'停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{row}">
          <el-button link @click="toggleWord(row)">{{ row.status===1?'停用':'启用' }}</el-button>
          <el-button link type="danger" @click="delWord(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider />
    <h3>全局黑名单</h3>
    <el-input v-model="newPhone" placeholder="手机号" style="width:200px;margin-right:8px" />
    <el-button type="primary" @click="addBlack">添加</el-button>
    <el-table :data="blacklist" stripe style="margin-top:12px">
      <el-table-column prop="phone" label="手机号" />
      <el-table-column prop="createTime" label="添加时间" />
      <el-table-column label="操作" width="80"><template #default="{row}"><el-button link type="danger" @click="delBlack(row.id)">删除</el-button></template></el-table-column>
    </el-table>
    <h3 style="margin-top:20px">风控日志</h3>
    <el-table :data="riskLogs" stripe size="small" style="margin-top:8px">
      <el-table-column prop="riskType" label="类型" />
      <el-table-column prop="phone" label="手机" />
      <el-table-column prop="remark" label="说明" show-overflow-tooltip />
      <el-table-column prop="createTime" label="时间" width="170" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const form = ref({ sensitiveMonitorEnabled: 1, humanTransferEnabled: 0 })
const blacklist = ref([])
const riskLogs = ref([])
const words = ref([])
const newPhone = ref('')
const newWord = ref({ word: '', wordType: 2, status: 1 })

const load = async () => {
  form.value = await request.get('/admin/risk')
  if (form.value.sensitiveMonitorEnabled == null) form.value.sensitiveMonitorEnabled = 1
  if (form.value.humanTransferEnabled == null) form.value.humanTransferEnabled = 0
  blacklist.value = await request.get('/admin/blacklist')
  riskLogs.value = await request.get('/admin/risk/log')
  words.value = await request.get('/admin/sensitive-word/list')
}
const save = async () => {
  await request.post('/admin/risk/save', form.value)
  ElMessage.success('保存成功')
}
const addWord = async () => {
  if (!newWord.value.word?.trim()) {
    ElMessage.warning('请输入词条')
    return
  }
  await request.post('/admin/sensitive-word/save', newWord.value)
  newWord.value = { word: '', wordType: 2, status: 1 }
  ElMessage.success('已添加')
  words.value = await request.get('/admin/sensitive-word/list')
}
const toggleWord = async (row) => {
  await request.post('/admin/sensitive-word/save', { ...row, status: row.status === 1 ? 0 : 1 })
  words.value = await request.get('/admin/sensitive-word/list')
}
const delWord = async (id) => {
  await request.delete(`/admin/sensitive-word/${id}`)
  words.value = await request.get('/admin/sensitive-word/list')
}
const addBlack = async () => {
  await request.post('/admin/blacklist/add', { phone: newPhone.value })
  newPhone.value = ''
  load()
}
const delBlack = async (id) => { await request.delete(`/admin/blacklist/${id}`); load() }
onMounted(load)
</script>

<style scoped>
.hint { font-size: 12px; color: #909399; margin-left: 12px; }
p.hint { margin: 4px 0 0; margin-left: 0; }
</style>
