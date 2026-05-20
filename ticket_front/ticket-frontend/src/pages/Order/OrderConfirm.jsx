import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Tag, Divider, Checkbox, Button, message, Space, Row, Col } from 'antd';
import { CheckCircleFilled, InfoCircleOutlined, PayCircleOutlined, AlipayCircleOutlined, WechatOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './OrderConfirm.css';


const OrderConfirm = () => {
    const location = useLocation();
    const navigate = useNavigate();

    // 接收从 EventDetail 传过来的商品数据 (如果没有则赋默认测试值防崩溃)
    const { event, selectedTicket, quantity } = location.state || {
        event: {
            title: "测试演出标题：请从详情页正常跳转",
            posterUrl: "https://via.placeholder.com/300x400",
            showTime: "2026-05-21 20:00",
            venue: "测试场馆 LiveHouse"
        },
        selectedTicket: { id: 1, name: "预售票", price: 150 },
        quantity: 1
    };

    // 状态管理
    const [spectators, setSpectators] = useState([]); // 观演人列表
    const [selectedSpectatorId, setSelectedSpectatorId] = useState(null);
    const [paymentMethod, setPaymentMethod] = useState('alipay');
    const [agreed, setAgreed] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    // 模拟拉取用户预存的观演人信息 (逻辑6)
    useEffect(() => {
        // TODO: 替换为真实的 axios 请求获取当前用户的实名观演人
        const mockSpectators = [
            { id: 1, name: '张三', idCard: '11010519900101****' },
            { id: 2, name: '李四', idCard: '31010519951212****' }
        ];
        setSpectators(mockSpectators);
        if (mockSpectators.length > 0) {
            setSelectedSpectatorId(mockSpectators[0].id); // 默认选中第一个
        }
    }, []);

    const totalPrice = (selectedTicket.price * quantity).toFixed(2);

    // 提交订单逻辑
    const handleSubmitOrder = async () => {
        if (!selectedSpectatorId) return message.warning('请选择实名观演人');
        if (!agreed) return message.warning('请阅读并同意购票服务条款');

        setSubmitting(true);
        try {
            // TODO: 这里是触发后端“高并发创建订单”的接口
            // 后端逻辑：接收到请求 -> 校验频次/拦截器 -> 发送到 MQ -> 返回“排队中”或立即返回订单号
            console.log('提交订单参数:', {
                ticketId: selectedTicket.id,
                quantity: quantity,
                spectatorId: selectedSpectatorId,
                paymentMethod: paymentMethod
            });

            setTimeout(() => {
                message.success('订单创建成功，即将跳转支付...');
                setSubmitting(false);
                // navigate(`/pay?orderId=xxxxx`);
            }, 1000);

        } catch (error) {
            message.error('系统繁忙，请稍后再试');
            setSubmitting(false);
        }
    };

    return (
        <div className="order-page-container">
            <PublicHeader />

            <div className="order-content">
                <div style={{ textAlign: 'left' }}>
                    <Button
                        type="default"
                        icon={<ArrowLeftOutlined />}
                        onClick={() => navigate(-1)}
                        className="order-back-btn"
                    >
                        返回
                    </Button>
                </div>
                {/* 顶部温馨提示 */}
                <Alert
                    message="温馨提示：该演出需要实名观演人，请仔细核对观演人身份信息，入场需刷本人身份证。"
                    type="warning"
                    showIcon
                    style={{ marginBottom: 20, borderRadius: 8, border: 'none', backgroundColor: '#fffbe6' }}
                />

                {/* 1. 演出信息模块 */}
                <div className="order-section-card">
                    <div className="event-info-row">
                        <img src={event.posterUrl} alt={event.title} className="event-info-poster" />
                        <div className="event-info-detail">
                            <div>
                                <h2 style={{ fontSize: 20, margin: 0, fontWeight: 'bold' }}>{event.title}</h2>
                                <div style={{ color: '#666', marginTop: 10 }}>
                                    <div>{event.venue}</div>
                                    <div>{event.showTime}</div>
                                </div>
                            </div>
                            <div className="ticket-meta-row">
                                <div>
                                    <span style={{ fontSize: 18, fontWeight: 'bold', color: '#333' }}>¥{selectedTicket.price}</span>
                                    <span style={{ color: '#666', marginLeft: 8 }}>{selectedTicket.name} x {quantity}张</span>
                                </div>
                                <div style={{ fontWeight: 'bold', fontSize: 18, color: '#333' }}>
                                    小计: <span style={{ color: '#FF8899' }}>¥{totalPrice}</span>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* 服务标签 */}
                    <div style={{ marginTop: 16, display: 'flex', gap: 16 }}>
                        <span style={{ color: '#52c41a', fontSize: 13 }}><CheckCircleFilled /> 电子票</span>
                        <span style={{ color: '#ff4d4f', fontSize: 13 }}><InfoCircleOutlined /> 不支持退换票</span>
                        <span style={{ color: '#52c41a', fontSize: 13 }}><CheckCircleFilled /> 实名制入场</span>
                    </div>
                </div>

                {/* 2. 观演人信息模块 */}
                <div className="order-section-card">
                    <div className="section-title">选择观演人</div>
                    <Row gutter={16}>
                        {spectators.map(sp => (
                            <Col span={8} key={sp.id}>
                                <div
                                    className={`selectable-card ${selectedSpectatorId === sp.id ? 'active' : ''}`}
                                    onClick={() => setSelectedSpectatorId(sp.id)}
                                >
                                    <div style={{ fontWeight: 'bold', fontSize: 16 }}>{sp.name}</div>
                                    <div style={{ color: '#888', marginTop: 4 }}>{sp.idCard}</div>
                                </div>
                            </Col>
                        ))}
                        <Col span={8}>
                            <div className="selectable-card" style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', borderStyle: 'dashed', color: '#FF8899' }}>
                                + 新增实名观演人
                            </div>
                        </Col>
                    </Row>
                </div>

                {/* 3. 支付方式模块 */}
                <div className="order-section-card">
                    <div className="section-title">支付方式</div>
                    <Space direction="vertical" style={{ width: '100%' }}>
                        <div
                            className={`selectable-card ${paymentMethod === 'alipay' ? 'active' : ''}`}
                            onClick={() => setPaymentMethod('alipay')}
                            style={{ display: 'flex', alignItems: 'center', gap: 10 }}
                        >
                            <AlipayCircleOutlined style={{ fontSize: 24, color: '#1677ff' }} />
                            <span style={{ fontSize: 16, fontWeight: 500 }}>支付宝支付</span>
                        </div>
                        <div
                            className={`selectable-card ${paymentMethod === 'wechat' ? 'active' : ''}`}
                            onClick={() => setPaymentMethod('wechat')}
                            style={{ display: 'flex', alignItems: 'center', gap: 10 }}
                        >
                            <WechatOutlined style={{ fontSize: 24, color: '#07c160' }} />
                            <span style={{ fontSize: 16, fontWeight: 500 }}>微信支付</span>
                        </div>
                    </Space>
                </div>

                {/* 4. 购买条款提示 */}
                <div style={{ color: '#999', fontSize: 13, lineHeight: '24px', padding: '0 10px' }}>
                    1. 本场演出不支持退换票（因不可抗力因素导致的演出取消或延期除外）。<br/>
                    2. 确认订单后，请在 5 分钟内完成支付，否则订单将自动取消并释放库存。<br/>
                    3. 购票即代表阅读并同意 <a style={{ color: '#FF8899' }}>《Ave Monica 购票服务条款》</a>。
                </div>
            </div>

            {/* 底部悬浮结算栏 */}
            <div className="bottom-settle-bar">
                <div className="settle-inner">
                    <Checkbox checked={agreed} onChange={e => setAgreed(e.target.checked)}>
                        我已阅读并同意相关服务条款
                    </Checkbox>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
                        <div>
                            <span style={{ color: '#666' }}>合计明细：</span>
                            <span style={{ color: '#FF8899', fontSize: 28, fontWeight: 'bold' }}>¥ {totalPrice}</span>
                        </div>
                        <Button
                            type="primary"
                            size="large"
                            style={{ backgroundColor: '#FF8899', border: 'none', borderRadius: 24, width: 140, fontWeight: 'bold' }}
                            onClick={handleSubmitOrder}
                            loading={submitting}
                        >
                            提交订单
                        </Button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default OrderConfirm;