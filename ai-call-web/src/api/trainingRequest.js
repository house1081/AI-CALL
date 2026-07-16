import axios from 'axios'
import { ElMessage } from 'element-plus'

const trainingRequest = axios.create({ baseURL: '/api', timeout: 120000 })

trainingRequest.interceptors.request.use(config => {
  const t = localStorage.getItem('admin_token')
  if (t) config.headers.Authorization = `Bearer ${t}`
  return config
})

trainingRequest.interceptors.response.use(
  res => {
    const { code, message, data } = res.data
    if (code !== 200) {
      ElMessage.error(message || '请求失败')
      return Promise.reject(new Error(message))
    }
    return data
  },
  err => {
    const raw = err.response?.data?.message || err.message || '网络错误'
    const msg = /device not found|NotFoundError/i.test(raw)
      ? '未找到麦克风，请使用文字输入继续对话'
      : raw
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default trainingRequest
