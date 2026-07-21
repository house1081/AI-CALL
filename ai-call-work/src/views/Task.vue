<template>
  <div>
    <el-button type="primary" @click="openCreate">创建外呼任务</el-button>
    <el-table :data="list" stripe style="margin-top:16px">
      <el-table-column prop="taskName" label="任务名称" />
      <el-table-column prop="groupName" label="分组" />
      <el-table-column prop="promptName" label="话术模板" width="120" show-overflow-tooltip />
      <el-table-column prop="kbName" label="知识库" width="120" show-overflow-tooltip />
      <el-table-column label="外呼方式" width="100">
        <template #default="{ row }">{{ dialModeMap[row.dialMode] || '立即' }}</template>
      </el-table-column>
      <el-table-column prop="scheduledStartTime" label="定时开始" width="170" />
      <el-table-column prop="maxRingCount" label="振铃次数" width="90" />
      <el-table-column label="自动加V" width="80">
        <template #default="{ row }">{{ row.autoAddWechat === 1 ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column prop="callCount" label="计划数" width="80" />
      <el-table-column prop="completedCount" label="已完成" width="80" />
      <el-table-column prop="successCount" label="接通数" width="80" />
      <el-table-column prop="status" label="状态" width="90"><template #default="{row}">{{ statusMap[row.status] }}</template></el-table-column>
      <el-table-column prop="startTime" label="启动时间" width="170" />
      <el-table-column label="操作" width="300">
        <template #default="{row}">
          <el-button v-if="row.status===0||row.status===2" link type="primary" @click="start(row.id)">启动</el-button>
          <el-button v-if="row.status===1" link @click="pause(row.id)">暂停</el-button>
          <el-button v-if="row.status!==4" link type="danger" @click="stop(row.id)">终止</el-button>
          <el-button link @click="showDetail(row)">详情</el-button>
          <el-button v-if="row.status!==1" link @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status!==1" link type="danger" @click="removeTask(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="form.id ? '编辑任务' : '创建任务'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.taskName" /></el-form-item>
        <el-form-item label="客户分组">
          <el-select v-model="form.groupId" style="width:100%"><el-option v-for="g in groups" :key="g.id" :label="g.groupName" :value="g.id" /></el-select>
        </el-form-item>
        <el-form-item label="外呼方式">
          <el-radio-group v-model="form.dialMode">
            <el-radio :value="1">立即外呼</el-radio>
            <el-radio :value="2">定时外呼</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.dialMode === 2" label="开始时间">
          <el-date-picker
            v-model="form.scheduledStartTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="到点自动启动"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="振铃次数">
          <el-input-number v-model="form.maxRingCount" :min="3" :max="20" />
          <span class="hint">无人接听则终止任务</span>
        </el-form-item>
        <el-form-item label="句末档位">
          <el-select v-model="form.silenceProfile" clearable placeholder="跟随全局" style="width:100%">
            <el-option label="跟随全局" :value="''" />
            <el-option label="均衡（450ms）" value="balanced" />
            <el-option label="稳健（800ms）" value="stable" />
            <el-option label="极速（280ms）" value="fast" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dialMode === 1" label="">
          <el-checkbox v-model="form.autoStart">保存后立即启动</el-checkbox>
        </el-form-item>
        <el-form-item v-if="form.dialMode === 2" label="">
          <span class="hint">定时任务保存后将在开始时间自动启动，也可手动点「启动」提前执行</span>
        </el-form-item>
        <el-divider content-position="left">自动加微信（加 V）</el-divider>
        <el-form-item label="自动加V">
          <el-switch v-model="form.autoAddWechat" :active-value="1" :inactive-value="0" />
          <span class="hint">客户接通后调用下方接口添加好友</span>
        </el-form-item>
        <template v-if="form.autoAddWechat === 1">
          <el-form-item label="接口地址" required>
            <el-input
              v-model="form.wechatAddApiUrl"
              placeholder="http://192.168.60.28:7862/api/add"
            />
          </el-form-item>
          <el-form-item label="招呼语">
            <el-input
              v-model="form.wechatAddMessage"
              type="textarea"
              :rows="2"
              placeholder="你好，{name}，我是刚才和您通话的顾问"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="form.wechatAddRemark"
              placeholder="外呼任务-{taskName}"
            />
            <div class="hint block">支持变量：{phone} 客户手机、{name} 姓名、{remark} 备注、{taskName} 任务名</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="detailVisible" :title="'任务详情：' + (currentTask?.taskName || '')" width="800px">
      <p v-if="currentTask?.taskRules" class="rules">{{ currentTask.taskRules }}</p>
      <div v-if="currentTask" class="wechat-block">
        <div class="wechat-title">话术配置</div>
        <p>模板：{{ currentTask.promptName || '继承商户默认' }}</p>
        <p>知识库：{{ currentTask.kbName || (currentTask.kbId ? 'KB#'+currentTask.kbId : '—') }}</p>
      </div>
      <div v-if="currentTask" class="wechat-block">
        <div class="wechat-title">自动加 V</div>
        <p>开关：{{ currentTask.autoAddWechat === 1 ? '已开启' : '未开启' }}</p>
        <template v-if="currentTask.autoAddWechat === 1">
          <p>接口：{{ currentTask.wechatAddApiUrl || '—' }}</p>
          <p>招呼语：{{ currentTask.wechatAddMessage || '—' }}</p>
          <p>备注：{{ currentTask.wechatAddRemark || '—' }}</p>
        </template>
      </div>
      <el-table :data="taskRecords" stripe size="small">
        <el-table-column prop="callTime" label="时间" width="160" />
        <el-table-column prop="customerPhone" label="手机" />
        <el-table-column prop="customerName" label="姓名" />
        <el-table-column prop="callDuration" label="时长" width="70" />
        <el-table-column prop="deductAmount" label="扣费" width="80" />
        <el-table-column prop="level" label="意向" width="60" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const statusMap = { 0: '未启动', 1: '运行中', 2: '已暂停', 3: '已完成', 4: '已终止' }
const dialModeMap = { 1: '立即', 2: '定时' }
const list = ref([])
const groups = ref([])
const visible = ref(false)
const detailVisible = ref(false)
const form = ref({})
const currentTask = ref(null)
const taskRecords = ref([])

const load = async () => {
  const data = await request.get('/tenant/portal/task/list', { params: { page: 1, pageSize: 50 } })
  list.value = data.list
}
const openCreate = () => {
  form.value = {
    dialMode: 1,
    maxRingCount: 10,
    silenceProfile: '',
    autoStart: false,
    autoAddWechat: 0,
    wechatAddApiUrl: '',
    wechatAddMessage: '你好，我是刚才和您通话的顾问，方便通过一下吗？',
    wechatAddRemark: ''
  }
  visible.value = true
}
const fillFormFromTask = (task) => {
  form.value = {
    ...task,
    silenceProfile: task.silenceProfile || '',
    autoAddWechat: task.autoAddWechat === 1 ? 1 : 0,
    wechatAddApiUrl: task.wechatAddApiUrl || '',
    wechatAddMessage: task.wechatAddMessage || '你好，我是刚才和您通话的顾问，方便通过一下吗？',
    wechatAddRemark: task.wechatAddRemark || '',
    autoStart: false
  }
}

const taskNeedsDetailFetch = (row) =>
  row && row.dialMode == null && row.autoAddWechat == null && !row.wechatAddApiUrl

const openEdit = async (row) => {
  fillFormFromTask(row)
  if (taskNeedsDetailFetch(row)) {
    try {
      const task = await request.get(`/tenant/portal/task/detail/${row.id}`)
      fillFormFromTask(task)
    } catch {
      /* 后端未升级时仍用列表行 */
    }
  }
  visible.value = true
}
const save = async () => {
  if (!form.value.taskName || !form.value.groupId) {
    ElMessage.warning('请填写任务名称并选择客户分组')
    return
  }
  if (form.value.dialMode === 2 && !form.value.scheduledStartTime) {
    ElMessage.warning('请选择定时开始时间')
    return
  }
  if (form.value.autoAddWechat === 1 && !form.value.wechatAddApiUrl?.trim()) {
    ElMessage.warning('请填写加V接口地址')
    return
  }
  await request.post('/tenant/portal/task/save', {
    ...form.value,
    silenceProfile: form.value.silenceProfile || null
  })
  ElMessage.success(form.value.dialMode === 2 && !form.value.autoStart ? '已保存，到点将自动启动' : '保存成功')
  visible.value = false
  load()
}
const start = async (id) => {
  const data = await request.post(`/tenant/portal/task/${id}/start`)
  ElMessage.success(data.hint ? `任务已启动：${data.hint}` : '任务已启动')
  load()
}
const pause = async (id) => { await request.post(`/tenant/portal/task/${id}/pause`); load() }
const stop = async (id) => { await request.post(`/tenant/portal/task/${id}/stop`); load() }
const showDetail = async (row) => {
  currentTask.value = row
  if (taskNeedsDetailFetch(row)) {
    try {
      currentTask.value = await request.get(`/tenant/portal/task/detail/${row.id}`)
    } catch {
      /* 使用列表行数据 */
    }
  }
  const data = await request.get(`/tenant/portal/task/${row.id}/records`, { params: { page: 1, pageSize: 100 } })
  taskRecords.value = data.list
  detailVisible.value = true
}

const removeTask = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除任务「${row.taskName}」？删除后不可恢复（通话记录仍保留）。`,
      '删除任务',
      { type: 'warning' }
    )
    await request.delete(`/tenant/portal/task/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '删除失败')
    }
  }
}

onMounted(async () => {
  groups.value = await request.get('/tenant/portal/group/list')
  load()
})
</script>

<style scoped>
.hint { margin-left: 8px; color: #909399; font-size: 12px; }
.hint.block { margin-left: 0; margin-top: 6px; display: block; }
.rules { font-size: 12px; color: #606266; margin-bottom: 12px; line-height: 1.5; }
.wechat-block { font-size: 12px; color: #606266; margin-bottom: 16px; line-height: 1.6; padding: 10px 12px; background: #f5f7fa; border-radius: 6px; }
.wechat-block p { margin: 4px 0; }
.wechat-title { font-weight: 600; margin-bottom: 6px; color: #303133; }
</style>
