import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Spin, message, Row, Col, Avatar, Button, InputNumber } from 'antd';
import { CalendarOutlined, EnvironmentOutlined, UserOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import axios from 'axios';
import './EventDetail.css';
import PublicHeader from '../../components/PublicHeader/PublicHeader';

const EventDetail = () => {
    const { id } = useParams(); // 从 URL 路由获取演出 ID
    const navigate = useNavigate();

    const [event, setEvent] = useState(null);
    const [loading, setLoading] = useState(true);

    // 购票控制状态
    const [selectedTicket, setSelectedTicket] = useState(null);
    const [quantity, setQuantity] = useState(1);

    useEffect(() => {
        const fetchDetail = async () => {
            try {
                const res = await axios.get(`/api/event/${id}`);
                if (res.data.code === 200) {
                    setEvent(res.data.data);
                    // 默认选中第一个有库存的票档
                    const firstAvailable = res.data.data.tickets?.find(t => t.remainingStock > 0);
                    if (firstAvailable) setSelectedTicket(firstAvailable);
                } else {
                    message.error(res.data.message);
                    navigate('/'); // 获取失败退回首页
                }
            } catch (err) {
                message.error('网络请求失败');
            } finally {
                setLoading(false);
            }
        };
        fetchDetail();
        window.scrollTo(0, 0); // 进页面时回到顶部
    }, [id, navigate]);

    // 计算总价
    const totalPrice = selectedTicket ? (selectedTicket.price * quantity).toFixed(2) : '0.00';

    const handleBuy = async () => {
        if (!selectedTicket) return message.warning('请先选择票档');

        const token = localStorage.getItem('token');
        if (!token) {
            message.info('请先登录再进行购票');
            return navigate('/auth');
        }

        const hideLoading = message.loading('正在为您分配抢票通道...', 0);

        try{
            const res = await axios.post('/api/order/pre-check', {
                eventId: event.id
            },{
                headers: {Authorization: `Bearer ${token}`}
            });

            hideLoading();
            if (res.data.code === 200) {
                // 🎯 校验通过，拿到令牌，丝滑放行进入订单确认页！
                navigate('/order/confirm', {
                    state: {
                        event: event,
                        selectedTicket: selectedTicket,
                        quantity: quantity
                    }
                });
            } else if (res.data.code === 2001){
                // ⚠️ 触发了高频点击防刷（对应图中的逻辑 3）
                // 真实项目中这里会 setState 弹出一个图形验证码组件，目前用提示代替
                message.warning(res.data.message || '您点击太快了，请稍后再试');
            }
        } catch (error) {
            hideLoading();
            if (error.response && error.response.status === 401) {
                message.error('登录已过期，请重新登录');
                navigate('/auth');
            } else {
                message.error('网络请求异常，请稍后再试');
            }
        }
    }

    if (loading) {
        return <div style={{ height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}><Spin size="large" /></div>;
    }

    if (!event) return null;

    return (
        <div className="detail-page-container">
            {/* 1. 沉浸式顶部背景 */}
            <div
                className="hero-blurred-bg"
                style={{ backgroundImage: `url(${event.posterUrl})` }}
            />

            <PublicHeader />
            <div className="content-wrapper">
                {/* 返回上一页小按钮 */}
                <div style={{display:'flex'}}>
                <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} style={{ marginBottom: 20, color: '#fff', fontSize: 16 }}>
                    返回
                </Button>
                </div>

                {/* 2. 购票主信息卡片 */}
                <div className="purchase-card">
                    <div className="poster-col">
                        <img src={event.posterUrl} alt={event.title} className="detail-poster" />
                    </div>

                    <div className="info-col">
                        <h1 className="event-title-large">{event.title}</h1>

                        <div className="info-row">
                            <CalendarOutlined className="info-icon" />
                            <span>{event.showTime || '时间待定'}</span>
                        </div>
                        <div className="info-row">
                            <EnvironmentOutlined className="info-icon" />
                            <span>{event.venue} | {event.address}</span>
                        </div>

                        <div style={{ marginTop: '24px', textAlign: 'left' }}>
                            <div style={{ fontWeight: 'bold', color: '#333', marginBottom: 8 }}>选择票档</div>
                            <div className="tickets-container">
                                {event.tickets?.map(ticket => {
                                    const isSoldOut = ticket.remainingStock <= 0;
                                    const isActive = selectedTicket?.id === ticket.id;

                                    return (
                                        <div
                                            key={ticket.id}
                                            className={`ticket-pill ${isActive ? 'active' : ''} ${isSoldOut ? 'sold-out' : ''}`}
                                            onClick={() => !isSoldOut && setSelectedTicket(ticket)}
                                        >
                                            <span className="ticket-name">{ticket.name}</span>
                                            <span className="ticket-price">¥ {ticket.price} {isSoldOut ? '(售罄)' : ''}</span>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>

                        <div style={{ marginTop: '24px', marginBottom: '40px', textAlign: 'left' }}>
                            <div style={{ fontWeight: 'bold', color: '#333', marginBottom: 10 }}>购买数量</div>

                            {/* 🚨 用 flex 容器包裹输入框和提示语，确保它们在同一行并垂直居中靠左 */}
                            <div style={{ display: 'flex', alignItems: 'center' }}>
                                <InputNumber
                                    min={1}
                                    max={6}
                                    value={quantity}
                                    onChange={setQuantity}
                                    size="large"
                                    disabled={!selectedTicket}
                                />
                                <span style={{ marginLeft: 12, color: '#999', fontSize: 13 }}>每笔订单限购 6 张</span>
                            </div>
                        </div>

                        <div className="action-bar">
                            <div>
                                <span style={{ color: '#666', marginRight: 8 }}>总计:</span>
                                <span style={{ color: '#FF8899', fontSize: 20 }}>¥</span>
                                <span className="total-price">{totalPrice}</span>
                            </div>
                            <Button type="primary" className="buy-btn" onClick={handleBuy}>
                                立即购票
                            </Button>
                        </div>
                    </div>
                </div>

                {/* 3. 下方详情区块：参演艺人 */}
                {event.artists && event.artists.length > 0 && (
                    <div className="section-block">
                        <div className="section-header">参演音乐人</div>
                        <Row gutter={[16, 16]}>
                            {event.artists.map((artist, idx) => (
                                <Col xs={24} sm={12} md={8} key={idx}>
                                    <div className="artist-card">
                                        <Avatar src={artist.avatarUrl} size={54} icon={<UserOutlined />} />
                                        <div>
                                            <div style={{ fontWeight: 'bold', fontSize: 16 }}>{artist.name}</div>
                                            {artist.style && <div style={{ color: '#FF8899', fontSize: 12, marginTop: 4 }}># {artist.style}</div>}
                                        </div>
                                    </div>
                                </Col>
                            ))}
                        </Row>
                    </div>
                )}

                {/* 4. 下方详情区块：演出图文详情 */}
                <div className="section-block">
                    <div className="section-header">图文详情</div>
                    {event.detailsUrl ? (
                        <img src={event.detailsUrl} alt="详情长图" className="details-long-img" />
                    ) : (
                        <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>主办方暂未提供详细图文介绍</div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default EventDetail;