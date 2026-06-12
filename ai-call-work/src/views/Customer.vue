<template>
  <div>
    <el-button type="primary" @click="openAdd">新增客户</el-button>
    <el-button @click="downloadTemplate">下载模板</el-button>
    <el-button @click="showImport=true">批量导入</el-button>
    <el-button @click="showGroup=true">分组管理</el-button>
    <el-select v-model="moveGroupId" placeholder="移到分组" clearable style="width:140px;margin-left:8px">
      <el-option v-for="g in groups" :key="g.id" :label="g.groupName" :value="g.id" />
    </el-select>
    <el-button :disabled="!selected.length || !moveGroupId" @click="doMoveGroup">批量分组</el-button>
    <el-form inline style="margin-top:12px">
      <el-input v-model="query.phone" placeholder="手机号" clearable style="width:140px" />
      <el-input v-model="query.name" placeholder="姓名" clearable style="width:120px" />
      <el-select v-model="query.groupId" placeholder="分组" clearable style="width:120px">
        <el-option v-for="g in groups" :key="g.id" :label="g.groupName" :value="g.id" />
      </el-select>
      <el-select v-model="query.isBlack" placeholder="黑名单" clearable style="width:100px">
        <el-option :value="0" label="否" /><el-option :value="1" label="是" />
      </el-select>
      <el-button @click="load">查询</el-button>
    </el-form>
    <el-table :data="list" stripe style="margin-top:12px" @selection-change="sel=>selected=sel">
      <el-table-column type="selection" width="50" />
      <el-table-column prop="phone" label="手机号" />
      <el-table-column prop="name" label="姓名" />
      <el-table-column prop="province" label="省份" width="80" />
      <el-table-column prop="groupName" label="分组" />
      <el-table-column prop="level" label="意向" width="70" />
      <el-table-column prop="isBlack" label="黑名单" width="80"><template #default="{row}">{{ row.isBlack?'是':'否' }}</template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div style="margin-top:12px">
      <el-button @click="batchBlack(1)">加入黑名单</el-button>
      <el-button @click="batchBlack(0)">移出黑名单</el-button>
    </div>
    <el-pagination v-model:current-page="page" :page-size="10" :total="total" @current-change="load" style="margin-top:16px" />
    <el-dialog v-model="showAdd" :title="form.id ? '编辑客户' : '新增客户'" width="400px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="手机号"><el-input v-model="form.phone" maxlength="11" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="省份"><el-input v-model="form.province" placeholder="如：广东" /></el-form-item>
        <el-form-item label="分组">
          <el-select v-model="form.groupId"><el-option v-for="g in groups" :key="g.id" :label="g.groupName" :value="g.id" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="showAdd=false">取消</el-button><el-button type="primary" @click="saveCustomer">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="showImport" title="批量导入" width="500px">
      <p>每行：手机号,姓名,省份(可选)</p>
      <el-input v-model="importText" type="textarea" :rows="8" placeholder="13800138000,张三,广东" />
      <template #footer><el-button @click="showImport=false">取消</el-button><el-button type="primary" @click="doImport">导入</el-button></template>
    </el-dialog>
    <el-dialog v-model="showGroup" title="分组管理" width="400px">
      <el-input v-model="newGroupName" placeholder="新分组名称" style="margin-bottom:8px" />
      <el-button type="primary" @click="addGroup">新增分组</el-button>
      <el-table :data="groups" size="small" style="margin-top:12px">
        <el-table-column prop="groupName" label="名称" />
        <el-table-column label="操作" width="80">
          <template #default="{row}"><el-button link type="danger" @click="delGroup(row.id)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api/request'
import { downloadFile } from '../utils/download'
import { ElMessage } from 'element-plus'

const list = ref([])
const groups = ref([])
const page = ref(1)
const total = ref(0)
const query = ref({})
const selected = ref([])
const moveGroupId = ref(null)
const showAdd = ref(false)
const showImport = ref(false)
const showGroup = ref(false)
const newGroupName = ref('')
const form = ref({ groupId: 1 })
const importText = ref('')

const load = async () => {
  const data = await request.get('/tenant/portal/customer/list', { params: { page: page.value, pageSize: 10, ...query.value } })
  list.value = data.list
  total.value = data.total
}
const loadGroups = async () => { groups.value = await request.get('/tenant/portal/group/list') }

const downloadTemplate = () => {
  downloadFile('/tenant/portal/export/customer-template', {}, 'customer_import_template.xlsx')
}

const openAdd = () => {
  form.value = { groupId: groups.value[0]?.id ?? 1 }
  showAdd.value = true
}

const openEdit = (row) => {
  form.value = {
    id: row.id,
    phone: row.phone,
    name: row.name,
    province: row.province,
    groupId: row.groupId,
    groupName: row.groupName
  }
  showAdd.value = true
}

const saveCustomer = async () => {
  if (!form.value.phone?.trim()) {
    ElMessage.warning('请填写手机号')
    return
  }
  const g = groups.value.find(x => x.id === form.value.groupId)
  await request.post('/tenant/portal/customer/save', { ...form.value, groupName: g?.groupName || '未分组' })
  ElMessage.success(form.value.id ? '更新成功' : '保存成功')
  showAdd.value = false
  load()
}

const doImport = async () => {
  const rows = importText.value.split('\n').filter(Boolean).map(line => {
    const [phone, name, province] = line.split(',')
    return { phone: phone?.trim(), name: name?.trim(), province: province?.trim(), groupId: 1, groupName: '未分组' }
  })
  const r = await request.post('/tenant/portal/customer/import', rows)
  ElMessage.success(`成功${r.success}条，失败${r.fail}条`)
  showImport.value = false
  load()
}

const batchBlack = async (isBlack) => {
  await request.post('/tenant/portal/customer/black', { ids: selected.value.map(x => x.id) }, { params: { isBlack } })
  load()
}

const doMoveGroup = async () => {
  await request.post('/tenant/portal/customer/move-group', { ids: selected.value.map(x => x.id), groupId: moveGroupId.value })
  ElMessage.success('分组完成')
  load()
}

const addGroup = async () => {
  await request.post('/tenant/portal/group/save', { groupName: newGroupName.value })
  newGroupName.value = ''
  loadGroups()
}
const delGroup = async (id) => {
  await request.delete(`/tenant/portal/group/${id}`)
  loadGroups()
}

onMounted(() => { loadGroups(); load() })
</script>
