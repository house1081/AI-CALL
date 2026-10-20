<template>
  <div>
    <el-button type="primary" @click="openAdd">新增坐席</el-button>
    <span style="margin-left:12px;color:#909399;font-size:13px">
      分机坐席：软电话需注册到 FS（如 192.168.61.58:5060，账号=分机号，默认密码常见 1234）；手机坐席走线路外呼
    </span>
    <el-table :data="list" stripe style="margin-top:16px">
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ row.destType === 'mobile' ? '手机' : '分机' }}</template>
      </el-table-column>
      <el-table-column prop="fsDest" label="拨号目标" min-width="160" show-overflow-tooltip />
      <el-table-column prop="mobile" label="手机号" width="120" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="在岗" width="110">
        <template #default="{ row }">
          <el-tag :type="presenceTag(row.presence)" size="small">{{ presenceLabel(row.presence) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" />
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.presence === 'busy'"
            link
            type="warning"
            @click="setPresence(row, 'available')"
          >强制空闲</el-button>
          <el-button
            v-if="row.presence !== 'available' && row.presence !== 'busy'"
            link
            type="success"
            :disabled="row.status !== 1"
            @click="setPresence(row, 'available')"
          >设为在岗</el-button>
          <el-button
            v-if="row.presence === 'available'"
            link
            @click="setPresence(row, 'offline')"
          >设为离岗</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑坐席' : '新增坐席'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：坐席小王" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.destType" @change="onDestTypeChange">
            <el-radio value="ext">分机</el-radio>
            <el-radio value="mobile">手机</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.destType === 'ext'" label="分机号" required>
          <el-input v-model="form.fsDest" placeholder="1001 或 user/1001" />
        </el-form-item>
        <el-form-item v-if="form.destType === 'mobile'" label="手机号" required>
          <el-input v-model="form.mobile" maxlength="11" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item v-if="form.destType === 'mobile'" label="外呼线路">
          <el-select v-model="form.lineId" clearable placeholder="默认租户线路" style="width:100%">
            <el-option v-for="l in lines" :key="l.id" :label="lineLabel(l)" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const list = ref([])
const lines = ref([])
const visible = ref(false)
const form = ref({})

const presenceLabel = (p) => ({ available: '在岗', busy: '通话中', offline: '离岗' }[p] || p || '离岗')
const presenceTag = (p) => ({ available: 'success', busy: 'warning', offline: 'info' }[p] || 'info')
const lineLabel = (l) => l.sipAccount || `线路#${l.id}`

const load = async () => {
  list.value = await request.get('/tenant/portal/agents')
}

const loadLines = async () => {
  lines.value = await request.get('/tenant/portal/outbound-lines')
}

const openAdd = () => {
  form.value = { destType: 'ext', status: 1, sortOrder: 0, presence: 'offline' }
  visible.value = true
}

const openEdit = (row) => {
  form.value = {
    id: row.id,
    name: row.name,
    destType: row.destType || 'ext',
    fsDest: row.fsDest,
    mobile: row.mobile,
    lineId: row.lineId,
    status: row.status,
    sortOrder: row.sortOrder ?? 0
  }
  visible.value = true
}

const onDestTypeChange = () => {
  if (form.value.destType === 'ext') {
    form.value.mobile = ''
  } else {
    form.value.fsDest = ''
  }
}

const save = async () => {
  if (!form.value.name?.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (form.value.destType === 'ext' && !form.value.fsDest?.trim()) {
    ElMessage.warning('请填写分机号')
    return
  }
  if (form.value.destType === 'mobile' && !form.value.mobile?.trim()) {
    ElMessage.warning('请填写手机号')
    return
  }
  if (form.value.id) {
    await request.put(`/tenant/portal/agents/${form.value.id}`, form.value)
  } else {
    await request.post('/tenant/portal/agents', form.value)
  }
  ElMessage.success('保存成功')
  visible.value = false
  load()
}

const setPresence = async (row, presence) => {
  await request.post(`/tenant/portal/agents/${row.id}/presence`, { presence })
  ElMessage.success(presence === 'available'
    ? (row.presence === 'busy' ? '已强制恢复空闲' : '已设为在岗')
    : '已设为离岗')
  load()
}

const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除坐席「${row.name}」？`, '提示', { type: 'warning' })
  await request.delete(`/tenant/portal/agents/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(() => {
  load()
  loadLines()
})
</script>
