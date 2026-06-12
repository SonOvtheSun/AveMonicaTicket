import React, { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button, message, Spin } from 'antd';
import { AlipayCircleOutlined, SafetyCertificateFilled } from '@ant-design/icons';
import axios from '../../utils/request';

const SimulatePay = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const [paying, setPaying] = useState(false);

    // 获取上一页传来的订单数据
    const { orderId, price } = location.state || {};

    if (!orderId) {
        return <div style={{ padding: 50, textAlign: 'center' }}>订单信息丢失，请返回重试</div>;
    }

    const handleConfirmPay = async () => {
        setPaying(true);
        try {
            // 调用后端的支付接口，将订单状态从 1 改为 3
            const res = await axios.post('/api/order/pay', { orderId });

            if (res.data.code === 200) {
                message.success('支付成功！');
                // 支付成功后，跳转到我们之前写好的 PaySuccess 倒计时页面
                navigate('/pay/success', { state: { orderId, price } });
            } else {
                message.error(res.data.message || '支付失败');
                setPaying(false);
            }
        } catch (error) {
            message.error('网络异常，支付失败');
            setPaying(false);
        }
    };

    return (
        <div style={{ backgroundColor: '#f5f5f9', minHeight: '100vh', display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: '10vh' }}>
            <div style={{ width: 400, backgroundColor: '#fff', borderRadius: 12, overflow: 'hidden', boxShadow: '0 8px 24px rgba(0,0,0,0.05)' }}>
                {/* 支付宝经典的蓝色头部 */}
                <div style={{ backgroundColor: '#1677FF', padding: '30px 20px', color: '#fff', textAlign: 'center' }}>
                    <AlipayCircleOutlined style={{ fontSize: 48, marginBottom: 10 }} />
                    <div style={{ fontSize: 18, opacity: 0.9 }}>Ave Monica 票务收银台</div>
                </div>

                {/* 订单明细区 */}
                <div style={{ padding: '40px 30px', textAlign: 'center' }}>
                    <div style={{ color: '#666', marginBottom: 10 }}>支付剩余时间 09:59</div>
                    <div style={{ fontSize: 36, fontWeight: 'bold', color: '#333', marginBottom: 30 }}>
                        <span style={{ fontSize: 24 }}>¥</span> {price}
                    </div>

                    <div style={{ display: 'flex', justifyContent: 'space-between', color: '#999', fontSize: 13, marginBottom: 10 }}>
                        <span>收款方</span>
                        <span style={{ color: '#333' }}>Ave Monica 官方票务</span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', color: '#999', fontSize: 13, marginBottom: 30 }}>
                        <span>订单编号</span>
                        <span style={{ color: '#333' }}>{orderId}</span>
                    </div>

                    <Button
                        type="primary"
                        size="large"
                        block
                        style={{ backgroundColor: '#1677FF', height: 48, fontSize: 16, borderRadius: 8 }}
                        onClick={handleConfirmPay}
                        loading={paying}
                    >
                        确认支付
                    </Button>

                    <div style={{ marginTop: 20, color: '#1677FF', fontSize: 12, display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                        <SafetyCertificateFilled style={{ marginRight: 4 }} /> 支付环境安全检测通过
                    </div>
                </div>
            </div>
        </div>
    );
};

export default SimulatePay;