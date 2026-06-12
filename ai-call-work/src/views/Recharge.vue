<template>
  <el-row :gutter="24">
    <el-col :span="12">
      <el-card header="提交充值">
        <el-form label-width="100px">
          <el-form-item label="充值金额"><el-input-number v-model="amount" :min="100" /></el-form-item>
          <el-form-item label="转账凭证">
            <el-upload :auto-upload="true" action="/api/upload/voucher" :headers="uploadHeaders" :on-success="onUpload">
              <el-button>上传截图</el-button>
            </el-upload>
          </el-form-item>
          <el-button type="primary" @click="submit">提交申请</el-button>
        </el-form>
      </el-card>
    </el-col>
    <el-col :span="12">
      <el-card header="充值记录">
        <el-table :data="list" stripe size="small">
          <el-table-column prop="orderNo" label="订单号" />
          <el-table-column prop="rechargeAmount" label="金额" />
          <el-table-column prop="status" label="状态"><template #default="{row}">{{ ['待审核','已到账','失败'][row.status] }}</template></el-table-column>
        </el-table>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const amount = ref(100)
const voucherUrl = ref('')
const list = ref([])
const uploadHeaders = computed(() => ({ Authorization: `Bearer ${localStorage.getItem('tenant_token')}` }))

const load = async () => {
  const data = await request.get('/tenant/portal/recharge/list', { params: { page: 1, pageSize: 20 } })
  list.value = data.list
}
const onUpload = (res) => {
  if (res.code === 200) voucherUrl.value = res.data.url
}
const submit = async () => {
  await request.post('/tenant/portal/recharge/submit', { amount: amount.value, voucherUrl: voucherUrl.value })
  ElMessage.success('已提交，等待审核')
  load()
}
onMounted(load)
</script>
