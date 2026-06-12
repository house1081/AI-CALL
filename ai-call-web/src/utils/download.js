import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 带 JWT 的 Excel/文件下载（responseType: blob）
 */
export async function downloadFile(path, params = {}, filename = 'export.xlsx') {
  const token = localStorage.getItem('admin_token')
  try {
    const res = await axios.get(path.startsWith('/api') ? path : `/api${path}`, {
      params,
      responseType: 'blob',
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    })
    const blob = res.data
    if (blob.type?.includes('application/json')) {
      const text = await blob.text()
      const json = JSON.parse(text)
      ElMessage.error(json.message || '导出失败')
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '导出失败')
  }
}
