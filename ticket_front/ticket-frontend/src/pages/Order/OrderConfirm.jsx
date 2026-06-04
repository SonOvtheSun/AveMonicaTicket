import React, { useState, useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Alert, Tag, Divider, Checkbox, Button, message, Space, Row, Col, Form, Input, Select, Modal } from 'antd';
import { CheckCircleFilled, InfoCircleOutlined, PayCircleOutlined, AlipayCircleOutlined, WechatOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './OrderConfirm.css';
import axios from "axios";


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
    const [selectedSpectatorIds, setSelectedSpectatorIds] = useState([]);
    const [paymentMethod, setPaymentMethod] = useState('alipay');
    const [agreed, setAgreed] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const maxTickets = 1; // 假设当前订单限制购买几张票，这通常从上一个页面传过来

    // 新增观演人弹窗控制
    const [spectatorModalVisible, setSpectatorModalVisible] = useState(false);
    const [spectatorForm] = Form.useForm();

    useEffect(() => {
        fetchSpectators();
    }, []);

    const fetchSpectators = async () => {
        try {
            const token = localStorage.getItem('token');
            const res = await axios.get('/api/user/spectator/list', {
                headers: { Authorization: `Bearer ${token}` }
            });
            if (res.data.code === 200) {
                setSpectators(res.data.data);
            }
        } catch (error) {
            message.error('获取观演人列表失败');
        }
    };

    // 🚨 2. 新增多选逻辑处理函数
    const handleSelectSpectator = (spId) => {
        if (selectedSpectatorIds.includes(spId)) {
            // 如果已选，则取消选中
            setSelectedSpectatorIds(selectedSpectatorIds.filter(id => id !== spId));
        } else {
            // 如果未选，先判断是否已经达到了购票数量
            if (selectedSpectatorIds.length >= quantity) {
                message.warning(`本订单仅需绑定 ${quantity} 位观演人`);
                return;
            }
            setSelectedSpectatorIds([...selectedSpectatorIds, spId]);
        }
    };

    // 🚨 补充：新增观演人提交逻辑
    const handleAddSpectator = async () => {
        try {
            const values = await spectatorForm.validateFields();
            if (spectators.length >= 50) return message.warning('最多只能保存 50 个常用购票人');

            const token = localStorage.getItem('token');
            const res = await axios.post('/api/user/spectator/add', values, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                message.success('添加成功！');
                setSpectatorModalVisible(false);
                spectatorForm.resetFields();
                fetchSpectators(); // 🚨 重新拉取最新列表，页面会自动多出一个选人卡片
            } else {
                message.error(res.data.message || '添加失败');
            }
        } catch (error) {
            console.log('表单校验失败', error);
        }
    };

    const totalPrice = (selectedTicket.price * quantity).toFixed(2);

    const handleSubmitOrder = async () => {
        if (selectedSpectatorIds.length !== quantity) {
            return message.warning(`请选择 ${quantity} 位实名观演人`);
        }
        if (!agreed) return message.warning('请阅读并同意购票服务条款');

        setSubmitting(true);
        try {
            const token = localStorage.getItem('token');
            const res = await axios.post('/api/order/create', {
                eventId: event.id,
                ticketId: selectedTicket.id,
                quantity: quantity,
                spectatorIds: selectedSpectatorIds,
                paymentMethod: paymentMethod,
                submitToken: location.state.submitToken
            }, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                // ==========================================
                // 🚀 分支 1：后端升级了异步排队架构 (触发轮询转圈动画)
                // ==========================================
                if (res.data.message === '排队中') {
                    const queueToken = res.data.data;
                    const hideLoading = message.loading('千军万马过独木桥，正在为您排队占座...', 0);

                    const pollTimer = setInterval(async () => {
                        try {
                            const pollRes = await axios.get(`/api/order/result/${queueToken}`, {
                                headers: { Authorization: `Bearer ${token}` }
                            });

                            if (pollRes.data.code === 200 && pollRes.data.data) {
                                const resultStr = pollRes.data.data;
                                clearInterval(pollTimer);
                                clearTimeout(timeoutTimer);
                                hideLoading();
                                setSubmitting(false);

                                if (resultStr.startsWith('FAIL:')) {
                                    message.error(resultStr.substring(5) || '抢票失败，请重试');
                                } else {
                                    message.success('抢票成功！请在 10 分钟内完成支付');
                                    navigate('/simulate-pay', {
                                        state: { orderId: resultStr, price: totalPrice }
                                    });
                                }
                            }
                        } catch (e) {
                            console.log('轮询状态异常', e);
                        }
                    }, 1500);

                    const timeoutTimer = setTimeout(() => {
                        clearInterval(pollTimer);
                        hideLoading();
                        setSubmitting(false);
                        message.error('排队超时，当前参与人数过多，请稍后再试');
                    }, 15000);
                }
                    // ==========================================
                    // 🚀 分支 2：后端是同步架构 (无缝直接跳转支付页)
                // ==========================================
                else {
                    setSubmitting(false);
                    message.success('抢票成功！请在 10 分钟内完成支付');

                    // 兼容处理：获取真实的订单 ID (支持直接返回ID，或返回带id属性的对象)
                    const generatedOrderId = typeof res.data.data === 'object' ? res.data.data.id : res.data.data;

                    navigate('/simulate-pay', {
                        state: {
                            orderId: generatedOrderId,
                            price: totalPrice
                        }
                    });
                }
            } else {
                // 业务异常拦截（比如：没有拿到Lua锁、票卖光了等）
                message.error(res.data.message || '系统繁忙，请稍后再试');
                setSubmitting(false);
            }
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
                    <div className="section-title">
                        选择观演人 <span style={{fontSize: 14, color: '#999', fontWeight: 'normal'}}>
                (已选 {selectedSpectatorIds.length}/{quantity} 人)
                </span>
                    </div>
                    <Row gutter={16}>
                        {spectators.map(sp => (
                            <Col span={8} key={sp.id}>
                                <div
                                    // 判断当前 ID 是否在选中数组中
                                    className={`selectable-card ${selectedSpectatorIds.includes(sp.id) ? 'active' : ''}`}
                                    onClick={() => handleSelectSpectator(sp.id)}
                                >
                                    <div style={{ fontWeight: 'bold', fontSize: 16 }}>{sp.name}</div>
                                    <div style={{ color: '#888', marginTop: 4 }}>{sp.idCard}</div>
                                </div>
                            </Col>
                        ))}
                        <Col span={8}>
                            <div
                                className="selectable-card"
                                style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', borderStyle: 'dashed', color: '#FF8899', cursor: 'pointer' }}
                                onClick={() => setSpectatorModalVisible(true)} // 🚨 绑定唤起弹窗事件
                            >
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
            {/* 🚨 补充：复用 UserProfile 的新增购票人弹窗 */}
            <Modal
                title="新增常用购票人"
                open={spectatorModalVisible}
                onOk={handleAddSpectator}
                onCancel={() => {
                    setSpectatorModalVisible(false);
                    spectatorForm.resetFields();
                }}
                okText="保存"
                cancelText="取消"
                okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}
            >
                <Form form={spectatorForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="真实姓名" name="name" rules={[{ required: true, message: '请输入观演人真实姓名' }]}>
                        <Input placeholder="请输入证件上的真实姓名" />
                    </Form.Item>

                    <Form.Item label="证件类型" name="idType" initialValue={1}>
                        <Select placeholder="请选择证件类型">
                            <Select.Option value={1}>身份证</Select.Option>
                            <Select.Option value={2}>护照</Select.Option>
                            <Select.Option value={3}>港澳台居民居住证</Select.Option>
                        </Select>
                    </Form.Item>

                    <Form.Item
                        label="证件号码"
                        name="idCard"
                        dependencies={['idType']}
                        rules={[
                            { required: true, message: '请输入证件号码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value) return Promise.resolve();
                                    const type = getFieldValue('idType');
                                    if (type === 1) {
                                        const reg = /^[1-9]\d{5}(18|19|20)\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\d{3}[0-9Xx]$/;
                                        if (!reg.test(value)) return Promise.reject(new Error('身份证格式不正确'));
                                    } else if (type === 2) {
                                        if (!/^[a-zA-Z0-9]{5,17}$/.test(value)) return Promise.reject(new Error('护照格式不正确'));
                                    } else if (type === 3) {
                                        if (value.length < 8) return Promise.reject(new Error('证件号码长度不正确'));
                                    }
                                    return Promise.resolve();
                                },
                            }),
                        ]}
                    >
                        <Input placeholder="请输入证件号码" maxLength={18} />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default OrderConfirm;