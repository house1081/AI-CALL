import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 30000 })

request.interceptors.request.use(config => {
  const token = localStorage.getItem('tenant_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const { code, message, data } = res.data
    if (code !== 200) {
      ElMessage.error(message || '请求失败')
      return Promise.reject(new Error(message))
    }
    return data
  },
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('tenant_token')
      router.push('/login')
    }
    ElMessage.error(err.message || '网络错误')
    return Promise.reject(err)
  }
)

export default request
