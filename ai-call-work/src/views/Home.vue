<template>
  <div>
    <el-alert v-if="Number(home.pendingDeduct) > 0" type="error" :title="`待补扣 ${home.pendingDeduct} 元，请充值后启动外呼`" show-icon style="margin-bottom:16px" />
    <el-row :gutter="16">
      <el-col :span="6" v-for="item in cards" :key="item.label">
        <el-card shadow="hover">
          <div class="num">{{ item.value }}</div>
          <div class="label">{{ item.label }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card header="系统通知" style="margin-top:20px" v-if="notices.length">
      <el-timeline>
        <el-timeline-item v-for="n in notices" :key="n.id" :timestamp="n.createTime">{{ n.content }}</el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'

const home = ref({})
const cards = ref([])
const notices = ref([])

onMounted(async () => {
  home.value = await request.get('/tenant/portal/home')
  notices.value = await request.get('/tenant/portal/notice/list')
  cards.value = [
    { label: '当前余额(元)', value: home.value.balance },
    { label: '可用分钟', value: home.value.availableMinutes },
    { label: '扣费单价', value: `${home.value.sellPrice}元/分 (${home.value.priceTypeName})` },
    { label: '今日通话', value: home.value.totalCalls },
    { label: '今日消费', value: home.value.totalDeduct },
    { label: '本月消费', value: home.value.monthDeduct },
    { label: '本月通话', value: home.value.monthCalls }
  ]
})
</script>

<style scoped>
.num { font-size: 26px; font-weight: bold; color: #1565c0; }
.label { color: #666; margin-top: 8px; }
</style>
