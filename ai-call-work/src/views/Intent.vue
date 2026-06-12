<template>
  <div>
    <el-select v-model="level" placeholder="意向等级" clearable @change="load" style="width:140px">
      <el-option value="A" label="A-高意向" /><el-option value="B" label="B-中意向" />
      <el-option value="C" label="C-低意向" /><el-option value="D" label="D-无意向" />
    </el-select>
    <el-button style="margin-left:8px" @click="doExport">导出Excel</el-button>
    <el-table :data="list" stripe style="margin-top:12px">
      <el-table-column prop="phone" label="手机号" />
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="level" label="意向" width="70" />
      <el-table-column prop="lastCallTime" label="最后通话" width="170" />
      <el-table-column prop="customerNeed" label="需求" show-overflow-tooltip />
      <el-table-column prop="budget" label="预算" width="100" />
      <el-table-column label="操作" width="80">
        <template #default="{row}"><el-button link @click="showDetail(row)">详情</el-button></template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="detailVisible" title="客户通话与AI提取" width="640px">
      <el-descriptions :column="2" border size="small" style="margin-bottom:12px">
        <el-descriptions-item label="需求">{{ current?.customerNeed || '-' }}</el-descriptions-item>
        <el-descriptions-item label="痛点">{{ current?.customerPain || '-' }}</el-descriptions-item>
        <el-descriptions-item label="预算">{{ current?.budget || '-' }}</el-descriptions-item>
        <el-descriptions-item label="回访">{{ current?.nextTime || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="records" size="small" stripe>
        <el-table-column prop="callTime" label="时间" width="160" />
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
import { downloadFile } from '../utils/download'

const list = ref([])
const page = ref(1)
const total = ref(0)
const level = ref('')
const detailVisible = ref(false)
const current = ref(null)
const records = ref([])

const load = async () => {
  const data = await request.get('/tenant/portal/intent/list', {
    params: { page: page.value, pageSize: 10, level: level.value || undefined }
  })
  list.value = data.list
  total.value = data.total
}

const showDetail = async (row) => {
  current.value = row
  records.value = await request.get(`/tenant/portal/customer/${row.id}/records`)
  detailVisible.value = true
}

const doExport = () => {
  const params = {}
  if (level.value) params.level = level.value
  downloadFile('/tenant/portal/export/intent', params, 'intent_customers.xlsx')
}

onMounted(load)
</script>
