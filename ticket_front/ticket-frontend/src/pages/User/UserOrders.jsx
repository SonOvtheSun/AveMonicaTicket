import React, { useState, useEffect } from 'react';
import { Tabs, Tag, Button, Modal, Popconfirm, message, Spin, QRCode, Empty, Divider, Space } from 'antd';
import { DeleteOutlined, CustomerServiceOutlined, PayCircleOutlined } from '@ant-design/icons';
import axios from 'axios';
import PublicHeader from '../../components/PublicHeader/PublicHeader'; // 替换为你的实际路径
import './UserOrders.css';

const UserOrders = () => {
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
            // 🚨 真实对接时放开此代码
            // const token = localStorage.getItem('token');
            // const res = await axios.get(`/api/order/list?status=${activeTab}`, {
            //     headers: { Authorization: `Bearer ${token}` }
            // });
            // setOrders(res.data.data);

            // 🛠️ Mock 数据：完美覆盖需求描述中的各种状态
            setTimeout(() => {
                const mockData = [
                    {
                        id: 'ODR202606030001',
                        createTime: '2026-06-03 10:00:00',
                        status: 6, // 需求9新增状态: 已支付但未检票
                        totalAmount: 400.00,
                        event: {
                            name: '【南京站】Mygo 7th Live 无处可逃 (名称过长省略测试，这是超长超长超长的一段文本)',
                            poster: 'https://via.placeholder.com/150x200?text=Mygo',
                            city: '南京',
                            venue: '南京青奥体育公园体育馆',
                            time: '2026-08-15 19:30:00'
                        },
                        tickets: [
                            { id: 'T1', name: 'VIP区 200元', seatInfo: 'A区 1排 12座', checkStatus: 1, qrCode: 'QR-T1-9988' },
                            { id: 'T2', name: 'VIP区 200元', seatInfo: 'A区 1排 13座', checkStatus: 1, qrCode: 'QR-T2-9989' }
                        ]
                    },
                    {
                        id: 'ODR202606030002',
                        createTime: '2026-06-03 11:30:00',
                        status: 1, // 1: 已创建，但未支付
                        totalAmount: 150.00,
                        event: {
                            name: '【上海站】Roselia Live Tour',
                            poster: 'https://via.placeholder.com/150x200?text=Roselia',
                            city: '上海',
                            venue: '梅赛德斯-奔驰文化中心',
                            time: '2026-09-01 19:00:00'
                        },
                        tickets: [
                            // 需求11: check_status 4 未出票
                            { id: 'T3', name: '看台 150元', seatInfo: null, checkStatus: 4 }
                        ]
                    },
                    {
                        id: 'ODR202606030003',
                        createTime: '2026-05-01 09:00:00',
                        status: 3, // 需求9修改: 3为已完成订单(已检票)
                        totalAmount: 500.00,
                        event: {
                            name: '【北京站】Ave Mujica 1st Live',
                            poster: 'https://via.placeholder.com/150x200?text=AveMujica',
                            city: '北京',
                            venue: '国家体育馆',
                            time: '2026-05-20 19:00:00'
                        },
                        tickets: [
                            { id: 'T4', name: '内场 500元', seatInfo: 'VIP区 5排 20座', checkStatus: 2, qrCode: 'QR-T4-USED' }
                        ]
                    }
                ];

                // 模拟 Tab 过滤
                if (activeTab === 'all') setOrders(mockData);
                else setOrders(mockData.filter(o => o.status.toString() === activeTab));

                setLoading(false);
            }, 500);
        } catch (error) {
            message.error('获取订单列表失败');
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
    const handleDeleteOrder = (orderId, e) => {
        e.stopPropagation(); // 阻止触发卡片点击弹出详情
        // axios.post(`/api/order/delete/${orderId}`)
        message.success(`订单 ${orderId} 已删除`);
        setOrders(orders.filter(o => o.id !== orderId));
    };

    // 操作：联系客服
    const handleContactCS = (e) => {
        e.stopPropagation();
        message.info('正在为您接通人工客服，请稍候...');
    };

    // 操作：去支付
    const handlePay = (orderId, e) => {
        e.stopPropagation();
        message.loading('正在跳转支付网关...');
        // navigate(`/simulate-pay`, { state: { orderId } });
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
                title="订单详情"
                open={detailVisible}
                onCancel={() => setDetailVisible(false)}
                footer={null}
                width={700}
                className="order-detail-modal"
            >
                {currentOrder && (
                    <div className="modal-content-wrapper">
                        <div className="modal-base-info">
                            <p><strong>订单编号：</strong>{currentOrder.id}</p>
                            <p><strong>创建时间：</strong>{currentOrder.createTime}</p>
                            <p><strong>订单状态：</strong><Tag color={getStatusConfig(currentOrder.status).color}>{getStatusConfig(currentOrder.status).text}</Tag></p>
                            <p><strong>实付总额：</strong><span style={{color: '#FF8899', fontWeight: 'bold', fontSize: 16}}>¥{currentOrder.totalAmount.toFixed(2)}</span></p>
                        </div>

                        <Divider />

                        <div className="modal-tickets-area">
                            <h3 style={{marginBottom: 16}}>电子票包</h3>
                            <Space direction="vertical" style={{ width: '100%' }} size="middle">
                                {currentOrder.tickets.map((ticket, index) => (
                                    <div className="ticket-item" key={ticket.id}>
                                        <div className="ticket-item-info">
                                            <div className="ticket-name">票品 {index + 1}：{ticket.name}</div>
                                            {/* 需求12: 有座位则显示，否则隐藏 */}
                                            {ticket.seatInfo ? (
                                                <div className="ticket-seat">座位信息：{ticket.seatInfo}</div>
                                            ) : (
                                                <div className="ticket-seat">座位信息：无/自由入座</div>
                                            )}
                                        </div>
                                        <div className="ticket-item-qr">
                                            {/* 需求11 & 12: 未出票判断 */}
                                            {ticket.checkStatus === 4 ? (
                                                <div className="unissued-tag">后台配座中<br/>(未出票)</div>
                                            ) : (
                                                <QRCode value={ticket.qrCode} size={80} errorLevel="H" />
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