import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173, // 前端启动端口
    proxy: {
      '/api': {
        target: 'http://localhost:18080', // 你的 Spring Boot 后端地址
        changeOrigin: true, // 开启跨域欺骗
        // rewrite: (path) => path.replace(/^\/api/, '') // 如果后端没有 /api 前缀需要这行，但我们加了，所以不需要
      },
      '/uploads': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      }
    }
  }
})
