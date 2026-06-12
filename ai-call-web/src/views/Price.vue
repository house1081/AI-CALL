<template>
  <div>
    <el-table :data="list" stripe>
      <el-table-column prop="priceType" label="费率类型"><template #default="{row}">{{ ['','零售价','企业价','代理价'][row.priceType] }}</template></el-table-column>
      <el-table-column prop="defaultSellPrice" label="默认售价(元/分钟)" />
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link @click="edit(row)">编辑</el-button></template></el-table-column>
    </el-table>
    <h3 style="margin-top:24px">修改记录</h3>
    <el-table :data="logs" stripe style="margin-top:8px">
      <el-table-column prop="targetType" label="类型" /><el-table-column prop="oldPrice" label="原价" />
      <el-table-column prop="newPrice" label="新价" /><el-table-column prop="operator" label="操作人" />
      <el-table-column prop="createTime" label="时间" />
    </el-table>
    <el-dialog v-model="visible" title="编辑费率" width="400px">
      <el-form label-width="100px"><el-form-item label="售价"><el-input-number v-model="editForm.defaultSellPrice" :precision="4" :min="0.08" :max="0.2" /></el-form-item></el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { ElMessage } from 'element-plus'

const list = ref([])
const logs = ref([])
const visible = ref(false)
const editForm = ref({})

const load = async () => {
  list.value = await request.get('/admin/price/list')
  logs.value = await request.get('/admin/price/log')
}
const edit = (row) => { editForm.value = { ...row }; visible.value = true }
const save = async () => {
  await request.post('/admin/price/save', editForm.value)
  ElMessage.success('保存成功')
  visible.value = false
  load()
}
onMounted(load)
</script>
