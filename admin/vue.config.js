const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  transpileDependencies: true,
  // 简化路径导入时的写法
  configureWebpack: {
    resolve: {
      alias: {
        'assets': '@/assets',
        'components': '@/components',
        'network': '@/network',
        'views': '@/views',
        'utils': '@/utils',
      }
    }
  },
  // 用于在开发过程中快速启动一个本地开发服务器并提供静态文件服务、热更新、代理等功能
  devServer: {
    port: 8788, // 自定义端口
    open: true, // 项目建成自动打开窗口
    proxy: {
      "/api": {
        target: "http://localhost:8080",  // 菲比啾比后端地址
        changeOrigin: true, // 是否改变源地址，设置为 true 可以通过更改请求头中的 host 和 origin 属性来更改请求的源地址
        ws: false, // 菲比啾比后端暂无 WebSocket 服务
        // 注意：菲比啾比后端接口路径本身就以 /api 开头，因此不做路径重写

      },
    },
    client: {
      overlay: false, // 关闭 Uncaught error 的全屏提示
    },
  },
})