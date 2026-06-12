<template>
  <div>
    <el-button type="primary" @click="openEdit()">新增线路</el-button>
    <el-form inline style="margin-top:12px">
      <el-input v-model="search.sipAccount" placeholder="SIP账号" clearable />
      <el-select v-model="search.status" placeholder="状态" clearable style="width:100px">
        <el-option :value="1" label="启用" /><el-option :value="0" label="禁用" />
      </el-select>
      <el-button @click="load">搜索</el-button>
    </el-form>
    <el-table :data="list" stripe style="margin-top:16px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="sipAccount" label="SIP账号" />
      <el-table-column prop="sipAddress" label="SIP地址" />
      <el-table-column prop="costPrice" label="成本价/分钟" />
      <el-table-column prop="status" label="状态"><template #default="{row}">{{ row.status===1?'启用':'禁用' }}</template></el-table-column>
      <el-table-column prop="todayCallCount" label="今日呼出" />
      <el-table-column prop="currentConcurrent" label="并发" width="70" />
      <el-table-column label="操作" width="180">
        <template #default="{row}">
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link @click="toggle(row)">{{ row.status===1?'禁用':'启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="visible" title="线路" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="FS Gateway">
          <el-input v-model="form.sipAccount" placeholder="与 FreeSWITCH sofia gateway 名称一致" />
          <div class="field-tip">即线路表「SIP账号」，外呼命令 sofia/gateway/此名称/手机号</div>
        </el-form-item>
        <el-form-item label="SIP密码">
          <el-input v-model="form.sipPassword" type="password" show-password :placeholder="form.id ? '留空不修改' : '网关注册密码'" />
        </el-form-item>
        <el-form-item label="SIP地址">
          <el-input v-model="form.sipAddress" placeholder="192.168.60.28:5060 或运营商中继 host:port" />
          <div class="field-tip">真实中继地址，写入通道变量供 FS/运维对照</div>
        </el-form-item>
        <el-form-item label="成本价"><el-input-number v-model="form.costPrice" :precision="4" :step="0.01" :min="0.01" /></el-form-item>
        <el-form-item label="日呼上限"><el-input-number v-model="form.dailyCallLimit" :min="100" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const search = ref({})
const page = ref(1)
const total = ref(0)
const visible = ref(false)
const form = ref({})

const load = async () => {
  const data = await request.get('/admin/line/list', { params: { page: page.value, pageSize: 10, ...search.value } })
  list.value = data.list
  total.value = data.total
}

const openEdit = (row) => {
  form.value = row ? { ...row, sipPassword: '' } : { costPrice: 0.06, dailyCallLimit: 1000, status: 1 }
  visible.value = true
}

const save = async () => {
  await request.post('/admin/line/save', form.value)
  ElMessage.success('保存成功')
  visible.value = false
  load()
}

const toggle = async (row) => {
  await request.post(`/admin/line/status/${row.id}`, null, { params: { status: row.status === 1 ? 0 : 1 } })
  load()
}

onMounted(load)
</script>

<style scoped>
.field-tip { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.4; }
</style>
