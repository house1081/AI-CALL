<template>
  <el-row :gutter="16">
    <el-col :span="24">
      <el-card header="对话训练知识库（RAG 热更新）">
        <el-alert type="info" :closable="false" style="margin-bottom:16px">
          话术按<strong>知识库</strong>隔离；话术模板绑定知识库，商户/外呼任务继承模板后自动使用该库的主线+兜底话术。
        </el-alert>
        <el-form inline size="small" style="margin-bottom:12px">
          <el-form-item label="知识库">
            <el-select v-model="selectedKbId" style="width:220px" @change="onKbChange">
              <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.kbName" :value="kb.id" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="importLoanPack">导入银行贷款话术包</el-button>
          </el-form-item>
        </el-form>
        <el-alert type="info" :closable="false" style="margin-bottom:16px">
          人工修正问答保存后<strong>即时写入向量索引</strong>，外呼/对话训练下一轮生效，无需微调模型。
          高置信匹配直出标准答案（跳过 LLM）；无匹配走严格兜底，禁止模型自由发挥。
        </el-alert>
        <el-descriptions v-if="stats" :column="4" border size="small" style="margin-bottom:16px">
          <el-descriptions-item label="RAG 状态">
            <el-tag :type="stats.enabled ? 'success' : 'info'">{{ stats.enabled ? '已启用' : '已关闭' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="向量索引">{{ stats.indexSize ?? 0 }} 条</el-descriptions-item>
          <el-descriptions-item label="人工修正">{{ stats.manualCorrection ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="优质样本">{{ stats.qualitySample ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="负样本">{{ stats.negative ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="直出阈值">{{ stats.directAnswerScore }}</el-descriptions-item>
          <el-descriptions-item label="最低匹配">{{ stats.minRetrieveScore }}</el-descriptions-item>
          <el-descriptions-item label="">
            <el-button size="small" @click="rebuildIndex">重建索引</el-button>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-col>

    <el-col :span="10" style="margin-top:16px">
      <el-card header="录入 / 编辑训练问答">
        <el-form :model="form" label-width="90px">
          <el-form-item label="数据类型">
            <el-select v-model="form.dataType" style="width:100%">
              <el-option :value="1" label="人工修正（一级优先）" />
              <el-option :value="2" label="优质通话样本" />
              <el-option :value="3" label="负样本（禁止类）" />
            </el-select>
          </el-form-item>
          <el-form-item label="客户问题">
            <el-input v-model="form.question" type="textarea" :rows="3" placeholder="用户原话或意图描述" />
          </el-form-item>
          <el-form-item label="标准回复">
            <el-input v-model="form.standardAnswer" type="textarea" :rows="4"
              placeholder="人工训练后的标准话术（直出/注入 LLM）" />
          </el-form-item>
          <el-form-item label="权重">
            <el-input-number v-model="form.weight" :min="0.5" :max="10" :step="0.5" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="save">保存并热更新</el-button>
            <el-button @click="resetForm">清空</el-button>
          </el-form-item>
        </el-form>

        <el-divider>从通话记录导入</el-divider>
        <el-form inline size="small">
          <el-form-item label="通话ID">
            <el-input v-model="importCallId" placeholder="call_record.id" style="width:120px" />
          </el-form-item>
          <el-form-item>
            <el-button @click="loadFromCall">解析问答</el-button>
          </el-form-item>
        </el-form>
        <el-table v-if="callPairs.length" :data="callPairs" size="small" border max-height="200" style="margin-bottom:12px">
          <el-table-column prop="userText" label="客户" show-overflow-tooltip />
          <el-table-column prop="aiText" label="AI" show-overflow-tooltip />
          <el-table-column label="" width="90">
            <template #default="{ row }">
              <el-button link type="primary" @click="fillFromPair(row, 1)">修正</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-divider>检索测试</el-divider>
        <el-input v-model="testQuestion" placeholder="输入客户问题测试召回" />
        <el-button style="margin-top:8px" @click="testRetrieve">测试检索</el-button>
        <div v-if="testResult" style="margin-top:12px;font-size:13px">
          <p>耗时 {{ testResult.retrieveMs }}ms |
            直出 {{ testResult.directAnswer ? '是' : '否' }} |
            无匹配兜底 {{ testResult.noMatchFallback ? '是' : '否' }}</p>
          <el-table v-if="testResult.hits?.length" :data="testResult.hits" size="small" border>
            <el-table-column prop="question" label="匹配问题" show-overflow-tooltip />
            <el-table-column prop="standardAnswer" label="标准答" show-overflow-tooltip />
            <el-table-column prop="dataTypeLabel" label="类型" width="90" />
            <el-table-column label="加权分" width="80">
              <template #default="{ row }">{{ row.weightedScore?.toFixed(3) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>
    </el-col>

    <el-col :span="14" style="margin-top:16px">
      <el-card header="训练数据列表">
        <el-form inline size="small" style="margin-bottom:12px">
          <el-form-item label="类型">
            <el-select v-model="filterType" clearable placeholder="全部" style="width:140px">
              <el-option :value="1" label="人工修正" />
              <el-option :value="2" label="优质样本" />
              <el-option :value="3" label="负样本" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="loadList">刷新</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="list" size="small" border max-height="520" @row-click="editRow">
          <el-table-column prop="id" label="ID" width="55" />
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ typeLabel(row.dataType) }}</template>
          </el-table-column>
          <el-table-column prop="question" label="问题" show-overflow-tooltip />
          <el-table-column prop="standardAnswer" label="标准答" show-overflow-tooltip />
          <el-table-column prop="weight" label="权重" width="65" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="editRow(row)">编辑</el-button>
              <el-button link type="danger" @click.stop="remove(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const stats = ref(null)
const list = ref([])
const knowledgeBases = ref([])
const selectedKbId = ref(1)
const filterType = ref(null)
const testQuestion = ref('')
const testResult = ref(null)
const importCallId = ref('')
const callPairs = ref([])

const defaultForm = () => ({
  id: null,
  kbId: selectedKbId.value,
  dataType: 1,
  question: '',
  standardAnswer: '',
  weight: 3,
  status: 1,
  remark: ''
})
const form = ref(defaultForm())

const typeLabel = (t) => ({ 1: '人工修正', 2: '优质样本', 3: '负样本' }[t] || '-')

const loadKnowledgeBases = async () => {
  knowledgeBases.value = await request.get('/admin/dialog-training/knowledge-bases?status=1')
  if (!knowledgeBases.value.find(k => k.id === selectedKbId.value) && knowledgeBases.value.length) {
    selectedKbId.value = knowledgeBases.value[0].id
  }
}

const onKbChange = () => {
  resetForm()
  loadStats()
  loadList()
}

const loadStats = async () => {
  stats.value = await request.get(`/admin/dialog-training/stats?kbId=${selectedKbId.value}`)
}

const loadList = async () => {
  let q = `?kbId=${selectedKbId.value}`
  if (filterType.value != null) q += `&dataType=${filterType.value}`
  list.value = await request.get(`/admin/dialog-training/list${q}`)
}

const save = async () => {
  if (!form.value.question?.trim() || !form.value.standardAnswer?.trim()) {
    ElMessage.warning('请填写问题与标准回复')
    return
  }
  await request.post('/admin/dialog-training/save', form.value)
  ElMessage.success('已保存并热更新向量索引')
  resetForm()
  loadStats()
  loadList()
}

const resetForm = () => {
  form.value = defaultForm()
}

const editRow = (row) => {
  form.value = {
    id: row.id,
    kbId: row.kbId || selectedKbId.value,
    dataType: row.dataType,
    question: row.question,
    standardAnswer: row.standardAnswer,
    weight: row.weight != null ? Number(row.weight) : 3,
    status: row.status,
    remark: row.remark || ''
  }
}

const remove = async (id) => {
  await ElMessageBox.confirm('确定删除该训练数据？', '提示')
  await request.delete(`/admin/dialog-training/${id}`)
  ElMessage.success('已删除')
  loadStats()
  loadList()
}

const rebuildIndex = async () => {
  await request.post('/admin/dialog-training/rebuild-index')
  ElMessage.success('索引重建完成')
  loadStats()
}

const testRetrieve = async () => {
  if (!testQuestion.value?.trim()) {
    ElMessage.warning('请输入测试问题')
    return
  }
  testResult.value = await request.post('/admin/dialog-training/retrieve-test', {
    question: testQuestion.value,
    kbId: selectedKbId.value
  })
}

const importLoanPack = async () => {
  await ElMessageBox.confirm(`将银行贷款标准话术导入知识库「${knowledgeBases.value.find(k=>k.id===selectedKbId.value)?.kbName || selectedKbId.value}」？`, '导入话术包')
  const r = await request.post(`/admin/dialog-training/import-loan-pack?replaceExisting=false&kbId=${selectedKbId.value}`)
  ElMessage.success(`导入完成：主线${r.mainFlow}条 兜底${r.fallbacks}条`)
  loadStats()
  loadList()
}

const loadFromCall = async () => {
  if (!importCallId.value) {
    ElMessage.warning('请输入通话记录 ID')
    return
  }
  const data = await request.get(`/admin/dialog-training/from-call/${importCallId.value}`)
  callPairs.value = data.pairs || []
  if (!callPairs.value.length) ElMessage.info('该通话无可用问答轮次')
}

const fillFromPair = (pair, dataType) => {
  form.value = {
    ...defaultForm(),
    dataType,
    question: pair.userText,
    standardAnswer: pair.aiText,
    remark: `来源通话#${importCallId.value} 轮次${pair.turnIndex}`
  }
}

onMounted(async () => {
  await loadKnowledgeBases()
  loadStats()
  loadList()
})
</script>
