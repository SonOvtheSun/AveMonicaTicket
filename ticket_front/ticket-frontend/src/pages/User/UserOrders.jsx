import React, { useState, useEffect } from 'react';
import { Tabs, Tag, Button, Modal, Popconfirm, message, Spin, QRCode, Empty, Divider, Space, Typography } from 'antd';
import { DeleteOutlined, CustomerServiceOutlined, PayCircleOutlined, RightOutlined } from '@ant-design/icons';
import axios from 'axios';
import PublicHeader from '../../components/PublicHeader/PublicHeader'; // 替换为你的实际路径
import { useNavigate } from 'react-router-dom';
import './UserOrders.css';

const UserOrders = () => {
    const navigate = useNavigate(); // 🚨 新增：用于路由跳转
    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState('all');

    // 详情弹窗状态
    const [detailVisible, setDetailVisible] = useState(false);
    const [currentOrder, setCurrentOrder] = useState(null);

    useEffect(() => {
        fetchOrders();
    }, [activeTab]);

    const fetchOrders = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            if (!token) {
                message.warning('请先登录');
                navigate('/auth');
                return;
            }

            // 🚨 真实对接后端获取订单列表接口
            const res = await axios.get(`/api/order/list?status=${activeTab}`, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                // 注意：这里强依赖你的后端返回的数据结构与之前 Mock 的结构一致
                setOrders(res.data.data);
            } else {
                message.error(res.data.message || '获取订单列表失败');
            }
        } catch (error) {
            if (error.response && error.response.status === 401) {
                message.error('登录已过期，请重新登录');
                navigate('/auth');
            } else {
                message.error('网络异常，获取订单列表失败');
            }
        } finally {
            setLoading(false);
        }
    };

    // 渲染状态标签及颜色
    const getStatusConfig = (status) => {
        switch (status) {
            case 1: return { color: 'warning', text: '待支付' };
            case 2: return { color: 'default', text: '已取消' };
            case 3: return { color: 'success', text: '已完成' };
            case 4: return { color: 'processing', text: '退款中' };
            case 5: return { color: 'error', text: '异常订单' };
            case 6: return { color: 'processing', text: '待检票' };
            default: return { color: 'default', text: '未知状态' };
        }
    };

    // 操作：删除订单
    const handleDeleteOrder = async (order, e) => {
        e.stopPropagation(); // 阻止触发卡片点击弹出详情

        try {
            const token = localStorage.getItem('token');

            // 如果是待支付订单，调用之前写好的 cancel 接口；如果是其他状态，调用 delete 接口
            const endpoint = order.status === 1
                ? `/api/order/cancel/${order.id}`
                : `/api/order/delete/${order.id}`;

            const res = await axios.post(endpoint, {}, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                message.success(order.status === 1 ? '订单已成功取消，购票资格已释放' : '订单已删除');

                // 如果是取消，最好是重新拉取一下列表，或者把状态改成 2 (已取消)
                if (order.status === 1) {
                    fetchOrders();
                } else {
                    setOrders(orders.filter(o => o.id !== order.id));
                }
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (error) {
            message.error('网络异常，操作失败');
        }
    };

    // 操作：联系客服
    const handleContactCS = (e) => {
        e.stopPropagation();
        message.info('正在为您接通人工客服，请稍候...');
    };

    // 操作：去支付
    const handlePay = (order, e) => {
        e.stopPropagation();

        // 🚨 携带真实的订单数据跳转到你写好的 simulate-pay 页面
        navigate('/simulate-pay', {
            state: {
                orderId: order.id,
                price: order.totalAmount
            }
        });
    };

    // 打开详情弹窗
    const openDetail = (order) => {
        setCurrentOrder(order);
        setDetailVisible(true);
    };

    const tabItems = [
        { key: 'all', label: '全部订单' },
        { key: '1', label: '待支付' },
        { key: '6', label: '待检票' },
        { key: '3', label: '已完成' },
    ];

    return (
        <div className="orders-page-container">
            <PublicHeader />
            <div className="orders-content">
                <div className="page-header">我的订单</div>

                <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabItems} className="orders-tabs" />

                <Spin spinning={loading}>
                    {orders.length === 0 ? (
                        <Empty description="暂无订单数据" style={{ marginTop: 60 }} />
                    ) : (
                        <div className="order-list">
                            {orders.map(order => {
                                const statusCfg = getStatusConfig(order.status);
                                return (
                                    <div className="order-card" key={order.id} onClick={() => openDetail(order)}>
                                        {/* 卡片头部：订单号与状态 */}
                                        <div className="order-card-header">
                                            <span className="order-no">订单号：{order.id}</span>
                                            <div className="header-right">
                                                {/* 状态 1 提示 10 分钟自动取消 */}
                                                {order.status === 1 && <span className="expire-hint">10分钟后自动取消</span>}
                                                <Tag color={statusCfg.color}>{statusCfg.text}</Tag>

                                                <Popconfirm
                                                    title="确定要删除该订单吗？"
                                                    onConfirm={(e) => handleDeleteOrder(order.id, e)}
                                                    onCancel={(e) => e.stopPropagation()}
                                                >
                                                    <Button type="text" danger icon={<DeleteOutlined />} size="small" onClick={e => e.stopPropagation()} />
                                                </Popconfirm>
                                            </div>
                                        </div>

                                        {/* 卡片主体：海报与信息 */}
                                        <div className="order-card-body">
                                            <img src={order.event.poster} alt="海报" className="order-poster" />
                                            <div className="order-info">
                                                {/* 需求3: 超长省略处理 */}
                                                <div className="event-title">{order.event.name}</div>
                                                <div className="event-meta">
                                                    <div>场馆：{order.event.city} | {order.event.venue}</div>
                                                    <div>时间：{order.event.time}</div>
                                                </div>
                                            </div>
                                            <div className="order-price-sec">
                                                <div className="price-label">实付款</div>
                                                <div className="price-amount">¥{order.totalAmount.toFixed(2)}</div>
                                            </div>
                                        </div>

                                        {/* 卡片底部：操作按钮 */}
                                        <div className="order-card-footer" onClick={e => e.stopPropagation()}>
                                            {order.status === 1 && (
                                                <Button type="primary" className="btn-pink" onClick={(e) => handlePay(order.id, e)}>立即支付</Button>
                                            )}
                                            {/* 需求10: 状态6显示联系客服按钮 */}
                                            {order.status === 6 && (
                                                <Button icon={<CustomerServiceOutlined />} onClick={handleContactCS}>联系客服</Button>
                                            )}
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </Spin>
            </div>

            {/* 需求12: 订单详细信息弹窗 */}
            <Modal
                title={<span style={{fontSize: 18, fontWeight: 'bold'}}>订单详情</span>}
                open={detailVisible}
                onCancel={() => setDetailVisible(false)}
                footer={null}
                width={650}
                className="premium-order-modal"
            >
                {currentOrder && (
                    <div className="premium-modal-content">
                        {/* 1. 顶部演出信息卡片 (点击跳转) */}
                        <div
                            className="detail-event-header"
                            onClick={() => {
                                setDetailVisible(false);
                                navigate(`/event/${currentOrder.eventId}`);
                            }}
                        >
                            <img src={currentOrder.event.poster} alt="海报" className="detail-poster" />
                            <div className="detail-event-info">
                                <div className="detail-title">{currentOrder.event.name}</div>
                                <div className="detail-venue">{currentOrder.event.city} | {currentOrder.event.venue}</div>
                            </div>
                            <RightOutlined className="detail-arrow" />
                        </div>

                        {/* 2. 票品与时间简述 */}
                        <div className="detail-section">
                            <div className="detail-row main-meta">
                                {/* 取第一张票的名字作为代表 */}
                                <span className="ticket-tier-name">{currentOrder.tickets[0]?.name} x{currentOrder.tickets.length}张</span>
                                <span className="ticket-tier-price">¥{currentOrder.totalAmount.toFixed(2)}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">时间：</span>
                                <span>{currentOrder.event.time}</span>
                            </div>
                        </div>

                        <div className="dotted-divider"></div>

                        {/* 3. 订单基础信息明细 */}
                        <div className="detail-section">
                            <div className="detail-row">
                                <span className="label-gray">票品类型</span>
                                <span>电子票</span>
                            </div>
                            {/* 如果有真实用户信息，替换这里的脱敏手机号 */}
                            <div className="detail-row">
                                <span className="label-gray">联系电话</span>
                                <span>189****5732</span>
                            </div>
                        </div>

                        <div className="dotted-divider"></div>

                        {/* 4. 价格明细计算 */}
                        <div className="detail-section">
                            <div className="detail-row">
                                <span className="label-gray">单价</span>
                                <span>¥{(currentOrder.totalAmount / currentOrder.tickets.length).toFixed(2)}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">数量</span>
                                <span>x{currentOrder.tickets.length}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">商品总价</span>
                                <span>¥{currentOrder.totalAmount.toFixed(2)}</span>
                            </div>
                            <div className="detail-row actual-pay-row">
                                <span className="label-gray">实付款</span>
                                <span className="actual-pay-amount">¥{currentOrder.totalAmount.toFixed(2)}</span>
                            </div>
                        </div>

                        {/* 5. 订单元数据区 (带一键复制) */}
                        <div className="detail-section order-metadata">
                            <div className="detail-row">
                                <span className="label-gray">订单号</span>
                                <span>
                                    {currentOrder.id}
                                    <Typography.Text
                                        copyable={{ text: currentOrder.id, tooltips: ['复制', '复制成功'] }}
                                        style={{marginLeft: 8, color: '#FF8899'}}
                                    />
                                </span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">创建时间</span>
                                <span>{currentOrder.createTime}</span>
                            </div>
                            {currentOrder.payTime && (
                                <div className="detail-row">
                                    <span className="label-gray">支付时间</span>
                                    <span>{currentOrder.payTime}</span>
                                </div>
                            )}
                            {currentOrder.paymentMethod && (
                                <div className="detail-row">
                                    <span className="label-gray">支付方式</span>
                                    <span>{currentOrder.paymentMethod}</span>
                                </div>
                            )}
                        </div>

                        {/* 6. 电子票包 (核销专区) */}
                        <div className="ticket-wallet-section">
                            <div className="wallet-header">票夹 / 入场凭证</div>
                            <Space direction="vertical" style={{ width: '100%' }} size="middle">
                                {currentOrder.tickets.map((ticket, index) => (
                                    <div className="wallet-ticket-card" key={ticket.id}>
                                        <div className="wt-info">
                                            <div className="wt-name">观演人 {index + 1}</div>
                                            {ticket.seatInfo ? (
                                                <div className="wt-seat">{ticket.seatInfo}</div>
                                            ) : (
                                                <div className="wt-seat empty">座位：无/自由入座</div>
                                            )}
                                        </div>
                                        <div className="wt-qr">
                                            {ticket.checkStatus === 4 ? (
                                                <div className="wt-unissued">后台配座中<br/>(未出票)</div>
                                            ) : (
                                                <QRCode value={ticket.qrCode} size={70} errorLevel="H" bordered={false} />
                                            )}
                                        </div>
                                    </div>
                                ))}
                            </Space>
                        </div>
                    </div>
                )}
            </Modal>
        </div>
    );
};

export default UserOrders;