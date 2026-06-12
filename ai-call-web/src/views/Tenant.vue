<template>
  <div>
    <el-button type="primary" @click="openEdit()">新增商户</el-button>
    <el-table :data="list" stripe style="margin-top:16px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="账号" />
      <el-table-column prop="contactName" label="联系人" />
      <el-table-column prop="contactPhone" label="手机" />
      <el-table-column prop="balance" label="余额" />
      <el-table-column prop="priceType" label="费率"><template #default="{row}">{{ ['','零售','企业','代理'][row.priceType] }}</template></el-table-column>
      <el-table-column prop="sellPrice" label="售价/分钟" />
      <el-table-column prop="pendingDeduct" label="待补扣" />
      <el-table-column prop="lastLoginTime" label="最后登录" width="170" />
      <el-table-column prop="status" label="状态"><template #default="{row}">{{ row.status===1?'启用':'禁用' }}</template></el-table-column>
      <el-table-column label="操作" width="240">
        <template #default="{row}">
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link @click="openRecharge(row)">充值</el-button>
          <el-button link :type="row.status===1?'danger':'success'" @click="toggleStatus(row)">{{ row.status===1?'禁用':'启用' }}</el-button>
          <el-button link @click="viewBills(row)">消费明细</el-button>
          <el-button link @click="viewReconcile(row)">对账</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="visible" title="商户" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="账号"><el-input v-model="form.username" :disabled="!!form.id" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" :placeholder="form.id?'留空不修改':''" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.contactPhone" /></el-form-item>
        <el-form-item label="费率类型">
          <el-select v-model="form.priceType" @change="onPriceType">
            <el-option :value="1" label="零售价" /><el-option :value="2" label="企业价" /><el-option :value="3" label="代理价" />
          </el-select>
        </el-form-item>
        <el-form-item label="售价/分钟"><el-input-number v-model="form.sellPrice" :precision="4" :step="0.01" /></el-form-item>
        <el-form-item label="日呼上限"><el-input-number v-model="form.dailyCallLimit" /></el-form-item>
        <el-form-item label="话术模板">
          <el-select v-model="form.promptId" clearable placeholder="选择外呼话术模板" style="width:100%">
            <el-option v-for="p in promptTemplates" :key="p.id"
              :label="(p.promptName || ('模板#'+p.id)) + (p.kbId ? ' · KB'+p.kbId : '')"
              :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="billVisible" :title="'消费明细 - ' + billTenantName" width="700px">
      <el-table :data="billList" size="small" stripe>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="type" label="类型"><template #default="{row}">{{ ['','充值','扣费','补扣'][row.type] }}</template></el-table-column>
        <el-table-column prop="amount" label="金额" />
        <el-table-column prop="balanceAfter" label="余额" />
        <el-table-column prop="remark" label="备注" />
      </el-table>
    </el-dialog>
    <el-dialog v-model="rechargeVisible" title="充值" width="400px">
      <el-form label-width="80px"><el-form-item label="金额"><el-input-number v-model="rechargeAmount" :min="1" /></el-form-item></el-form>
      <template #footer><el-button @click="rechargeVisible=false">取消</el-button><el-button type="primary" @click="doRecharge">确认</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const page = ref(1)
const total = ref(0)
const visible = ref(false)
const rechargeVisible = ref(false)
const form = ref({})
const rechargeAmount = ref(100)
const rechargeTenantId = ref(null)
const billVisible = ref(false)
const billList = ref([])
const billTenantName = ref('')
const promptTemplates = ref([])

const loadPromptTemplates = async () => {
  promptTemplates.value = await request.get('/admin/ai-prompt/list')
}

const load = async () => {
  const data = await request.get('/admin/tenant/list', { params: { page: page.value, pageSize: 10 } })
  list.value = data.list
  total.value = data.total
}

const onPriceType = async (type) => {
  const price = await request.get('/admin/tenant/default-price', { params: { priceType: type } })
  form.value.sellPrice = price
}

const openEdit = (row) => {
  form.value = row ? { ...row, password: '' } : { priceType: 1, sellPrice: 0.15, status: 1, dailyCallLimit: 500, password: '123456' }
  if (!row) onPriceType(1)
  visible.value = true
}

const save = async () => {
  await request.post('/admin/tenant/save', form.value)
  ElMessage.success('保存成功')
  visible.value = false
  load()
}

const openRecharge = (row) => { rechargeTenantId.value = row.id; rechargeAmount.value = 100; rechargeVisible.value = true }
const viewReconcile = async (row) => {
  const data = await request.get(`/admin/tenant/${row.id}/reconcile`)
  ElMessage.info(`余额${data.currentBalance} 待补扣${data.pendingDeduct} 流水合计${data.balanceLogNet}`)
}

const viewBills = async (row) => {
  billTenantName.value = row.username
  const data = await request.get(`/admin/tenant/${row.id}/balance-log`, { params: { page: 1, pageSize: 50 } })
  billList.value = data.list
  billVisible.value = true
}

const toggleStatus = async (row) => {
  await request.post(`/admin/tenant/status/${row.id}`, null, { params: { status: row.status === 1 ? 0 : 1 } })
  load()
}

const doRecharge = async () => {
  await request.post('/admin/tenant/recharge', { tenantId: rechargeTenantId.value, amount: rechargeAmount.value })
  ElMessage.success('充值成功')
  rechargeVisible.value = false
  load()
}

onMounted(() => { loadPromptTemplates(); load() })
</script>
