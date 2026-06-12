<template>
  <div>
    <el-table :data="list" stripe>
      <el-table-column prop="orderNo" label="订单号" />
      <el-table-column prop="tenantId" label="商户ID" width="80" />
      <el-table-column prop="rechargeAmount" label="金额" />
      <el-table-column prop="status" label="状态"><template #default="{row}">{{ ['待审核','已到账','失败'][row.status] }}</template></el-table-column>
      <el-table-column prop="rechargeTime" label="提交时间" />
      <el-table-column label="操作" width="160">
        <template #default="{row}">
          <template v-if="row.status===0">
            <el-button link type="success" @click="audit(row.id,1)">通过</el-button>
            <el-button link type="danger" @click="openReject(row)">拒绝</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="rejectVisible" title="拒绝原因" width="400px">
      <el-input v-model="failReason" type="textarea" placeholder="请输入拒绝原因" />
      <template #footer>
        <el-button @click="rejectVisible=false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
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
const rejectVisible = ref(false)
const rejectId = ref(null)
const failReason = ref('')

const load = async () => {
  const data = await request.get('/admin/recharge/list', { params: { page: page.value, pageSize: 10, status: 0 } })
  list.value = data.list
  total.value = data.total
}
const audit = async (id, status, reason) => {
  await request.post(`/admin/recharge/audit/${id}`, null, { params: { status, failReason: reason } })
  ElMessage.success('操作成功')
  load()
}
const openReject = (row) => { rejectId.value = row.id; failReason.value = ''; rejectVisible.value = true }
const confirmReject = () => { audit(rejectId.value, 2, failReason.value); rejectVisible.value = false }
onMounted(load)
</script>
