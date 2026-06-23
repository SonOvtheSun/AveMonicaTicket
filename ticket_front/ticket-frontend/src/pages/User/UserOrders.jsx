import React, { useState, useEffect } from 'react';
import { Tabs, Tag, Button, Modal, Popconfirm, message, Spin, QRCode, Empty, Divider, Space, Typography, Input } from 'antd';
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

    // 退票申请弹窗
    const [refundModalVisible, setRefundModalVisible] = useState(false);
    const [refundOrder, setRefundOrder] = useState(null);
    const [refundReason, setRefundReason] = useState('');

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

    // ==============================
    // 多场次订单展示工具方法
    // ==============================
    const getEventInfo = (order) => order?.event || {};

    const getOrderSessionName = (order) => {
        const eventInfo = getEventInfo(order);
        return eventInfo.sessionName || order?.sessionName || '';
    };

    const getOrderEventTime = (order) => {
        const eventInfo = getEventInfo(order);
        return eventInfo.time || order?.showTime || order?.sessionTime || '';
    };

    const formatOrderEventTime = (order) => {
        const rawTime = getOrderEventTime(order);
        if (!rawTime) return '时间待定';

        const time = dayjs(rawTime);
        return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss') : rawTime;
    };

    const isOrderEventOver = (order) => {
        const rawTime = getOrderEventTime(order);
        if (!rawTime) return false;

        const showTime = dayjs(rawTime);
        if (!showTime.isValid()) return false;

        const runningTime = Number(getEventInfo(order).runningTime || order?.runningTime || 120);
        return dayjs().isAfter(showTime.add(runningTime, 'minute'));
    };

    const getTicketCheckStatus = (ticket) => Number(ticket?.checkStatus);

    // 后端约定：1=未检票，2=已检票，4=未出票/后台配座中
    const isTicketUnchecked = (ticket) => getTicketCheckStatus(ticket) === 1;
    const isTicketChecked = (ticket) => getTicketCheckStatus(ticket) === 2;
    const isTicketUnissued = (ticket) => getTicketCheckStatus(ticket) === 4;

    const getMoneyText = (value) => {
        const amount = Number(value || 0);
        return amount.toFixed(2);
    };

    const getTicketCount = (order) => {
        if (Array.isArray(order?.tickets) && order.tickets.length > 0) {
            return order.tickets.length;
        }
        return Number(order?.quantity || 0);
    };

    const getRepresentativeTicketName = (order) => {
        return order?.tickets?.[0]?.name || '票档待定';
    };

    // 订单按钮显示规则：
    // 1：待支付，只显示“取消订单”；
    // 2：已取消，可删除；
    // 3：已完成，可删除；
    // 7：已退票，可删除。
    const canCancelOrder = (order) => Number(order?.status) === 1;

    const canDeleteOrder = (order) => [2, 3, 7].includes(Number(order?.status));

    // 只有“已支付但未检票”且演出 allowRefund=1 时，才显示申请退款按钮。
    const canApplyRefund = (order) => {
        const eventInfo = getEventInfo(order);
        return Number(order?.status) === 6 && Number(eventInfo.allowRefund) === 1;
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

    // 渲染订单状态。不要只看 order.status，已支付订单要结合电子票 checkStatus 和场次时间判断。
    const getDynamicOrderStatus = (order) => {
        const status = Number(order.status);

        // 主订单状态优先级最高
        if (status === 1) return { key: '1', color: 'warning', text: '待支付' };
        if (status === 2) return { key: '2', color: 'default', text: '已取消' };
        if (status === 3) return { key: '3', color: 'success', text: '已完成' };
        if (status === 4) return { key: '4', color: 'red', text: '申请退款中' };
        if (status === 5) return { key: '5', color: 'error', text: '异常订单' };
        if (status === 7) return { key: '7', color: 'grey', text: '已退票' };

        // status=6：已支付但未检票。这里再结合电子票状态细分展示。
        if (status === 6) {
            const tickets = Array.isArray(order.tickets) ? order.tickets : [];

            if (tickets.length === 0) {
                return { key: '6', color: 'processing', text: '出票中' };
            }

            const allChecked = tickets.every(isTicketChecked);
            if (allChecked) {
                return { key: '3', color: 'success', text: '已完成' };
            }

            const allUnissued = tickets.every(isTicketUnissued);
            if (allUnissued) {
                return { key: '6', color: 'processing', text: '出票中' };
            }

            if (isOrderEventOver(order)) {
                return { key: 'other', color: 'default', text: '已结束' };
            }

            return { key: '6', color: 'blue', text: '待检票' };
        }

        return { key: 'other', color: 'default', text: '未知状态' };
    };

    // 操作：取消待支付订单
    const handleCancelOrder = async (order, e) => {
        e?.stopPropagation();

        if (!canCancelOrder(order)) {
            return message.warning('当前订单状态不允许取消');
        }

        try {
            const res = await axios.post(`/api/order/cancel/${order.id}`, {});

            if (res.data.code === 200) {
                message.success('订单已成功取消，购票资格已释放');
                fetchOrders();
            } else {
                message.error(res.data.message || '取消失败');
            }
        } catch (error) {
            message.error('网络异常，取消失败');
        }
    };

    // 操作：删除订单。只有已取消、已完成、已退票才允许删除。
    const handleDeleteOrder = async (order, e) => {
        e?.stopPropagation();

        if (!canDeleteOrder(order)) {
            return message.warning('当前订单状态不允许删除');
        }

        try {
            const res = await axios.post(`/api/order/delete/${order.id}`, {});

            if (res.data.code === 200) {
                message.success('订单已删除');
                setOrders(prev => prev.filter(o => o.id !== order.id));
            } else {
                message.error(res.data.message || '删除失败');
            }
        } catch (error) {
            message.error('网络异常，删除失败');
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

    const openRefundModal = (order, e) => {
        e?.stopPropagation();

        if (!canApplyRefund(order)) {
            return message.warning('当前订单或演出不支持申请退款');
        }

        setRefundOrder(order);
        setRefundReason('');
        setRefundModalVisible(true);
    };

    const handleApplyRefund = async () => {
        if (!refundOrder) return;

        const reason = refundReason.trim();
        if (!reason) {
            return message.warning('请填写退款理由');
        }

        try {
            const res = await axios.post('/api/order/apply-refund', {
                orderId: refundOrder.id,
                reason
            });

            if (res.data.code === 200) {
                message.success('退款申请已提交，请等待管理员审核');
                setRefundModalVisible(false);
                setRefundOrder(null);
                setRefundReason('');
                fetchOrders();
            } else {
                message.error(res.data.message || '退款申请失败');
            }
        } catch (error) {
            message.error('网络异常，退款申请失败');
        }
    };


    // 打开详情弹窗
    const openDetail = (order) => {
        setCurrentOrder(order);
        setDetailVisible(true);
    };

    // 前端动态分类：不要只看 order.status，因为 order.status=3 可能只是“已支付”，不代表“已完成”
    const getOrderCategoryKey = (order) => {
        return getDynamicOrderStatus(order).key;
    };

    const displayOrders = activeTab === 'all'
        ? orders
        : orders.filter(order => getOrderCategoryKey(order) === activeTab);

    const tabItems = [
        { key: 'all', label: '全部订单' },
        { key: '1', label: '待支付' },
        { key: '6', label: '待检票' },
        { key: '3', label: '已完成' },
        { key: '4', label: '退款中' },
        { key: '7', label: '已退票' }
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

                                                {canDeleteOrder(order) && (
                                                    <Popconfirm
                                                        title="确定要删除该订单吗？"
                                                        onConfirm={(e) => handleDeleteOrder(order, e)}
                                                        onCancel={(e) => e?.stopPropagation()}
                                                    >
                                                        <Button
                                                            type="text"
                                                            danger
                                                            icon={<DeleteOutlined />}
                                                            size="small"
                                                            onClick={e => e.stopPropagation()}
                                                        />
                                                    </Popconfirm>
                                                )}
                                            </div>
                                        </div>

                                        {/* 卡片主体：海报与信息 */}
                                        <div className="order-card-body">
                                            <img src={getEventInfo(order).poster} alt="海报" className="order-poster" />
                                            <div className="order-info">
                                                {/* 需求3: 超长省略处理 */}
                                                <div className="event-title">{getEventInfo(order).name}</div>
                                                <div className="event-meta">
                                                    <div>场馆：{getEventInfo(order).city} | {getEventInfo(order).venue}</div>
                                                    <div>
                                                        时间：{formatOrderEventTime(order)}
                                                        {getOrderSessionName(order) && (
                                                            <Tag color="#FF8899" style={{ marginLeft: 8 }}>
                                                                {getOrderSessionName(order)}
                                                            </Tag>
                                                        )}
                                                    </div>
                                                </div>
                                            </div>
                                            <div className="order-price-sec">
                                                <div className="price-label">实付款</div>
                                                <div className="price-amount">¥{getMoneyText(order.totalAmount)}</div>
                                            </div>
                                        </div>

                                        {/* 卡片底部：操作按钮 */}
                                        <div className="order-card-footer" onClick={e => e.stopPropagation()}>
                                            {canCancelOrder(order) && (
                                                <>
                                                    <Popconfirm
                                                        title="确定要取消该订单吗？"
                                                        description="取消后将释放购票资格和库存。"
                                                        onConfirm={(e) => handleCancelOrder(order, e)}
                                                        onCancel={(e) => e?.stopPropagation()}
                                                        okText="确定取消"
                                                        cancelText="再想想"
                                                    >
                                                        <Button onClick={e => e.stopPropagation()}>
                                                            取消订单
                                                        </Button>
                                                    </Popconfirm>
                                                    <Button type="primary" className="btn-pink" onClick={(e) => handlePay(order, e)}>
                                                        立即支付
                                                    </Button>
                                                </>
                                            )}
                                            {canApplyRefund(order) && (
                                                <Button onClick={(e) => openRefundModal(order, e)}>
                                                    申请退款
                                                </Button>
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
                            <img src={getEventInfo(currentOrder).poster} alt="海报" className="detail-poster" />
                            <div className="detail-event-info">
                                <div className="detail-title">{getEventInfo(currentOrder).name}</div>
                                <div className="detail-venue">{getEventInfo(currentOrder).city} | {getEventInfo(currentOrder).venue}</div>
                            </div>
                            <RightOutlined className="detail-arrow" />
                        </div>

                        {/* 2. 票品与时间简述 */}
                        <div className="detail-section">
                            <div className="detail-row main-meta">
                                <span className="ticket-tier-name">
                                    {getRepresentativeTicketName(currentOrder)} x{getTicketCount(currentOrder)}张
                                </span>
                                <span className="ticket-tier-price">¥{getMoneyText(currentOrder.totalAmount)}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">时间：</span>
                                <span>
                                    {formatOrderEventTime(currentOrder)}
                                    {getOrderSessionName(currentOrder) && (
                                        <Tag color="#FF8899" style={{ marginLeft: 8 }}>
                                            {getOrderSessionName(currentOrder)}
                                        </Tag>
                                    )}
                                </span>
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
                                <span>¥{getMoneyText(currentOrder.totalAmount / Math.max(getTicketCount(currentOrder), 1))}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">数量</span>
                                <span>x{getTicketCount(currentOrder)}</span>
                            </div>
                            <div className="detail-row">
                                <span className="label-gray">商品总价</span>
                                <span>¥{getMoneyText(currentOrder.totalAmount)}</span>
                            </div>
                            <div className="detail-row actual-pay-row">
                                <span className="label-gray">实付款</span>
                                <span className="actual-pay-amount">¥{getMoneyText(currentOrder.totalAmount)}</span>
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
                                {(currentOrder.tickets || []).map((ticket, index) => (
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
                                            {isTicketUnissued(ticket) ? (
                                                <div className="wt-unissued">后台配座中<br/>(未出票)</div>
                                            ) : (
                                                <div style={{ position: 'relative', display: 'inline-block' }}>
                                                    <QRCode value={ticket.qrCode || `UNISSUED_${currentOrder.id}_${ticket.id}`} size={70} errorLevel="H" bordered={false} />

                                                    {(isTicketChecked(ticket) || (isTicketUnchecked(ticket) && isOrderEventOver(currentOrder))) && (
                                                        <div style={{
                                                            position: 'absolute', top: 0, left: 0, width: '100%', height: '100%',
                                                            backgroundColor: 'rgba(255,255,255,0.75)',
                                                            backdropFilter: 'blur(3px)',
                                                            display: 'flex', justifyContent: 'center', alignItems: 'center',
                                                            color: isTicketChecked(ticket) ? '#52c41a' : '#999',
                                                            fontWeight: '900', fontSize: '18px',
                                                        }}>
                                                            {isTicketChecked(ticket) ? '已检票' : '已过期'}
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
            <Modal
                title="申请退款"
                open={refundModalVisible}
                onOk={handleApplyRefund}
                onCancel={() => {
                    setRefundModalVisible(false);
                    setRefundOrder(null);
                    setRefundReason('');
                }}
                okText="提交申请"
                cancelText="取消"
                className="refund-modal"
                okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}
            >
                <div style={{ marginBottom: 12, color: '#666', lineHeight: 1.7 }}>
                    请填写退款理由。提交后订单将进入“申请退款中”，需要管理员审核。
                </div>
                <Input.TextArea
                    rows={4}
                    maxLength={500}
                    showCount
                    value={refundReason}
                    onChange={(e) => setRefundReason(e.target.value)}
                    placeholder="请输入退款理由，例如：临时有事无法到场"
                />
            </Modal>

        </div>
    );
};

export default UserOrders;