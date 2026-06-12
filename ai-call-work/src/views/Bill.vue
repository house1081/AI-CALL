<template>
  <div>
    <el-form inline>
      <el-select v-model="query.type" placeholder="类型" clearable style="width:120px">
        <el-option :value="1" label="充值" /><el-option :value="2" label="通话扣费" /><el-option :value="3" label="补扣" />
      </el-select>
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始" end-placeholder="结束" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button @click="doExport">导出</el-button>
    </el-form>
    <el-table :data="list" stripe style="margin-top:12px">
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column prop="type" label="类型"><template #default="{row}">{{ ['','充值','通话扣费','补扣'][row.type] }}</template></el-table-column>
      <el-table-column prop="amount" label="金额" />
      <el-table-column prop="balanceAfter" label="余额" />
      <el-table-column prop="remark" label="备注" />
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { downloadFile } from '../utils/download'

const list = ref([])
const page = ref(1)
const total = ref(0)
const query = ref({})
const dateRange = ref([])

const load = async () => {
  const params = { page: page.value, pageSize: 10, ...query.value }
  if (dateRange.value?.length === 2) {
    params.startDate = dateRange.value[0]
    params.endDate = dateRange.value[1]
  }
  const data = await request.get('/tenant/portal/balance-log/list', { params })
  list.value = data.list
  total.value = data.total
}

const doExport = () => {
  const start = dateRange.value?.[0] || new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10)
  const end = dateRange.value?.[1] || new Date().toISOString().slice(0, 10)
  const params = { start, end }
  if (query.value.type != null) params.type = query.value.type
  downloadFile('/tenant/portal/export/balance-log', params, 'balance_logs.xlsx')
}

onMounted(load)
</script>
