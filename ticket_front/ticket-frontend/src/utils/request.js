import axios from 'axios';
import { message, Modal } from 'antd';

// 1. 创建一个新的 Axios 实例
const request = axios.create({
    // 如果你有统一的后端域名，可以在这里配置 baseURL，例如：
    // baseURL: 'http://localhost:8080',
    timeout: 10000 // 请求超时时间
});

// 2. 请求拦截器：全局自动在 Header 挂载 Token
request.interceptors.request.use(
    config => {
        // 每次发请求前，自动从 localStorage 获取 Token 并塞入请求头
        const token = localStorage.getItem('token');
        if (token) {
            config.headers['Authorization'] = `Bearer ${token}`;
        }
        return config;
    },
    error => {
        return Promise.reject(error);
    }
);

// 3. 响应拦截器：全局处理 401 互踢与基础报错
request.interceptors.response.use(
    response => {
        // 🚨 核心互踢逻辑：如果后端业务状态码返回 401
        if (response.data && response.data.code === 401) {
            if (!window.isKickedOutAlerted) {
                window.isKickedOutAlerted = true;
                Modal.warning({
                    title: '下线通知',
                    content: response.data.message || '您的账号已在其他设备登录，您已被强制下线！',
                    okText: '重新登录',
                    onOk: () => {
                        window.isKickedOutAlerted = false;
                        // 清理本地废弃的钥匙
                        localStorage.removeItem('token');
                        localStorage.removeItem('userInfo');
                        // 强制跳转回登录页
                        window.location.href = '/auth';
                    }
                });
            }
            return Promise.reject(new Error(response.data.message));
        }
        return response;
    },
    error => {
        // 如果后端是以真实的 HTTP 状态码 401 返回的
        if (error.response && error.response.status === 401) {
            if (!window.isKickedOutAlerted) {
                window.isKickedOutAlerted = true;
                Modal.warning({
                    title: '登录失效',
                    content: '您的登录凭证已过期或在其他设备登录，请重新登录！',
                    okText: '去登录',
                    onOk: () => {
                        window.isKickedOutAlerted = false;
                        localStorage.removeItem('token');
                        localStorage.removeItem('userInfo');
                        window.location.href = '/auth';
                    }
                });
            }
        } else if (error.response && error.response.status === 403) {
            message.error('您没有权限执行此操作');
        } else {
            message.error('网络请求异常，请稍后再试');
        }
        return Promise.reject(error);
    }
);

// 4. 导出这个配置好的实例
export default request;