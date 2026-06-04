import React, { useState, useEffect } from 'react';
import { Result, Button } from 'antd';
import { useNavigate, useLocation } from 'react-router-dom';
import PublicHeader from '../../components/PublicHeader/PublicHeader';

const PaySuccess = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const [countdown, setCountdown] = useState(3);

    // 接收从订单确认页传过来的订单信息，防止直接输入 URL 访问报错
    const orderInfo = location.state || { orderId: '未知', price: '0.00' };

    useEffect(() => {
        // 核心倒计时与自动跳转逻辑
        const timer = setInterval(() => {
            setCountdown((prev) => {
                if (prev <= 1) {
                    clearInterval(timer);
                    // 🚨 3秒后自动路由到“我的订单”页（请确保你在 App.jsx 注册了该路由，例如 /user/orders）
                    navigate('/user/orders');
                    return 0;
                }
                return prev - 1;
            });
        }, 1000);

        // 组件销毁时清理定时器，防止内存泄漏
        return () => clearInterval(timer);
    }, [navigate]);

    return (
        <div style={{ backgroundColor: '#f5f7fa', minHeight: '100vh' }}>
            {/* 顶栏保持一致，维持系统的沉浸感 */}
            <PublicHeader />

            <div style={{
                maxWidth: 800,
                margin: '40px auto',
                background: '#fff',
                padding: '50px 20px',
                borderRadius: 12,
                boxShadow: '0 4px 16px rgba(0,0,0,0.02)'
            }}>
                <Result
                    status="success"
                    title={<span style={{ fontSize: '24px', fontWeight: 'bold' }}>支付成功！购票完成</span>}
                    subTitle={
                        <div style={{ fontSize: '15px', marginTop: '10px' }}>
                            <div>订单编号: <span style={{ fontFamily: 'monospace', color: '#1677ff' }}>{orderInfo.orderId}</span></div>
                            <div style={{ marginTop: '8px' }}>实付金额: <span style={{ color: '#FF8899', fontWeight: 'bold', fontSize: '18px' }}>¥{orderInfo.price}</span></div>
                            <div style={{ marginTop: '16px', color: '#999' }}>页面将在 {countdown} 秒后自动跳转至我的订单...</div>
                        </div>
                    }
                    extra={[
                        <Button
                            type="primary"
                            key="orders"
                            onClick={() => navigate('/user/orders')}
                            style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', borderRadius: '20px', width: '120px' }}
                        >
                            立即查看
                        </Button>,
                        <Button
                            key="home"
                            onClick={() => navigate('/')}
                            style={{ borderRadius: '20px', width: '120px' }}
                        >
                            返回首页
                        </Button>,
                    ]}
                />
            </div>
        </div>
    );
};

export default PaySuccess;