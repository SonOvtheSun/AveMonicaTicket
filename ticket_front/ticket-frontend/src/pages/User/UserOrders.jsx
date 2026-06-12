import React, { useState, useEffect } from 'react';
import { Tabs, Tag, Button, Modal, Popconfirm, message, Spin, QRCode, Empty, Divider, Space, Typography } from 'antd';
import { DeleteOutlined, CustomerServiceOutlined, PayCircleOutlined, RightOutlined } from '@ant-design/icons';
import axios from '../../utils/request';
import PublicHeader from '../../components/PublicHeader/PublicHeader'; // 替换为你的实际路径
import { useNavigate } from 'react-router-dom';
import './UserOrders.css';
import dayjs from "dayjs";

const UserOrders = () => {
    const navigate = useNavigate(); // 🚨 新增：用于路由跳转
    const [orders, setOrders] = useState([]);
    const [loading, setLoading] = useState(true);
    const [activeTab, setActiveTab] = useState('all');

    // 详情弹窗状态
    const [detailVisible, setDetailVisible] = useState(false);
    const [currentOrder, setCurrentOrder] = useState(null);

    // 兼容不同后端字段名：观演人姓名
    const getViewerName = (ticket, index) => {
        return ticket.viewerName
            || ticket.audienceName
            || ticket.attendeeName
            || ticket.holderName
            || ticket.realName
            || ticket.passengerName
            || `观演人 ${index + 1}`;
    };

    // 兼容不同后端字段名：身份证号脱敏，只显示前四位和后两位
    const getMaskedIdCard = (ticket) => {
        const rawIdCard = ticket.idCard
            || ticket.idCardNo
            || ticket.identityNo
            || ticket.certNo
            || ticket.certificateNo
            || ticket.audienceIdCard
            || ticket.viewerIdCard
            || '';

        const idCard = String(rawIdCard).trim();
        if (!idCard) return '身份证号：未填写';

        if (idCard.length <= 6) {
            return `身份证号：${idCard}`;
        }

        return `身份证号：${idCard.slice(0, 4)}************${idCard.slice(-2)}`;
    };

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

            // 订单分类由前端根据票的实时检票状态动态计算，避免“待检票”被后端订单状态误分到“已完成”
            const res = await axios.get('/api/order/list?status=all');

            if (res.data.code === 200) {
                setOrders(res.data.data || []);
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
    const getDynamicOrderStatus = (order) => {
        // 如果是待支付(1)或已取消(2)，直接按原逻辑显示，不走检票/过期计算
        if (order.status === 1) return { color: 'warning', text: '待支付' };
        if (order.status === 2) return { color: 'default', text: '已取消' };
        if (order.status === 4) return { color: 'processing', text: '退款中' };

        if (!order.tickets || order.tickets.length === 0) return { color: 'default', text: '未知状态' };

        // 1. 判断是否全部已检票 (约定 checkStatus === 1 为已检票)
        const allChecked = order.tickets.every(t => t.checkStatus === 1);
        if (allChecked) {
            return { color: 'success', text: '已完成' }; // 所有票都检了 -> 已完成
        }

        // 2. 判断是否过期 (当前时间 > 演出开始时间 + runningTime)
        const eventTime = dayjs(order.event.time);
        const runningTime = order.event.runningTime || 120; // 兜底 120 分钟
        const isOver = dayjs().isAfter(eventTime.add(runningTime, 'minute'));

        // 如果演出了结束了，且还有票没检 (checkStatus !== 1)，说明有票过期了
        const hasExpired = order.tickets.some(t => t.checkStatus !== 1 && isOver);
        if (hasExpired) {
            return { color: 'default', text: '已结束' }; // 有过期票 -> 已结束
        }

        // 3. 正常状态 (已支付/待检票)
        return { color: 'processing', text: '待检票' };
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

            const res = await axios.post(endpoint, {});

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

    // 前端动态分类：不要只看 order.status，因为 order.status=3 可能只是“已支付”，不代表“已完成”
    const getOrderCategoryKey = (order) => {
        const statusCfg = getDynamicOrderStatus(order);
        if (statusCfg.text === '待支付') return '1';
        if (statusCfg.text === '待检票') return '6';
        if (statusCfg.text === '已完成') return '3';
        return 'other';
    };

    const displayOrders = activeTab === 'all'
        ? orders
        : orders.filter(order => getOrderCategoryKey(order) === activeTab);

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
                    {displayOrders.length === 0 ? (
                        <Empty description="暂无订单数据" style={{ marginTop: 60 }} />
                    ) : (
                        <div className="order-list">
                            {displayOrders.map(order => {
                                const statusCfg = getDynamicOrderStatus(order);
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
                                                    onConfirm={(e) => handleDeleteOrder(order, e)}
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
                                                <Button type="primary" className="btn-pink" onClick={(e) => handlePay(order, e)}>立即支付</Button>
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
                                            <div className="wt-name">{getViewerName(ticket, index)}</div>
                                            <div className="wt-id-card">{getMaskedIdCard(ticket)}</div>
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
                                                <div style={{ position: 'relative', display: 'inline-block' }}>
                                                    {/* 正常二维码 */}
                                                    <QRCode value={ticket.qrCode} size={70} errorLevel="H" bordered={false} />

                                                    {/* 🚨 核心 UI：模糊滤镜盖章层 */}
                                                    {(ticket.checkStatus === 1 || dayjs().isAfter(dayjs(currentOrder.event.time).add(currentOrder.event.runningTime || 120, 'minute'))) && (
                                                        <div style={{
                                                            position: 'absolute', top: 0, left: 0, width: '100%', height: '100%',
                                                            backgroundColor: 'rgba(255,255,255,0.75)', // 半透明白底
                                                            backdropFilter: 'blur(3px)', // 毛玻璃模糊滤镜
                                                            display: 'flex', justifyContent: 'center', alignItems: 'center',
                                                            color: ticket.checkStatus === 1 ? '#52c41a' : '#999', // 检票为绿，过期为灰
                                                            fontWeight: '900', fontSize: '18px',
                                                        }}>
                                                            {ticket.checkStatus === 1 ? '已检票' : '已过期'}
                                                        </div>
                                                    )}
                                                </div>
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