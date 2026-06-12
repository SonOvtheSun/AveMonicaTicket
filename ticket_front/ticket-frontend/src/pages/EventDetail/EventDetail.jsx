import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Spin, message, Row, Col, Avatar, Button, InputNumber, Modal, Form, Input, Select, Alert } from 'antd';
import { CalendarOutlined, EnvironmentOutlined, UserOutlined, ArrowLeftOutlined, TagsOutlined, ClockCircleOutlined, HeartOutlined, HeartFilled, EyeOutlined } from '@ant-design/icons';
import axios from '../../utils/request';
import './EventDetail.css';
import dayjs from 'dayjs';
import PublicHeader from '../../components/PublicHeader/PublicHeader';

const sortTicketsByPriceAsc = (tickets = []) =>
    [...tickets].sort((a, b) => Number(a.price ?? 0) - Number(b.price ?? 0));

const EventDetail = () => {
    const { id } = useParams();
    const navigate = useNavigate();

    const [event, setEvent] = useState(null);
    const [loading, setLoading] = useState(true);

    // 购票控制状态
    const [selectedTicket, setSelectedTicket] = useState(null);
    const [quantity, setQuantity] = useState(1);

    // 倒计时与开售状态
    const [countdown, setCountdown] = useState({ days: 0, hours: '00', minutes: '00', seconds: '00' });
    const [saleAvailable, setSaleAvailable] = useState(false);

    // 🚨 云端预约预填核心状态池
    const [reservedData, setReservedData] = useState(null);
    const [reservationModalVisible, setReservationModalVisible] = useState(false);
    const [spectators, setSpectators] = useState([]);
    const [tempTicketId, setTempTicketId] = useState(null);
    const [tempSpectatorIds, setTempSpectatorIds] = useState([]);

    // 新增常用观演人弹窗状态
    const [spectatorModalVisible, setSpectatorModalVisible] = useState(false);
    const [spectatorForm] = Form.useForm();

    const isPresale = event?.status === 1 && !saleAvailable;
    const showPurchaseOptions = event?.status === 1 && saleAvailable; // 🚨 预约状态下隐藏选票和数量，只在正式在售时显示

    // status=3 且演出时间未到：隐藏购票信息，仅展示“敬请期待”
    const showTimeObj = event?.showTime ? dayjs(event.showTime) : null;
    const isShowTimeValid = !!showTimeObj && showTimeObj.isValid();
    const isStatus3Future = event?.status === 3 && isShowTimeValid && showTimeObj.isAfter(dayjs());
    const isStatus3Past = event?.status === 3 && isShowTimeValid && showTimeObj.isBefore(dayjs());
    const hidePurchaseOptions = isStatus3Future;
    const sortedTickets = sortTicketsByPriceAsc(event?.tickets || []);

    // 🚨 新增：防止轮询重绘覆盖用户操作的防御标记
    const hasAutoFilled = useRef(false);

    // 🚨 新增：想看与浏览量状态
    const [wantCount, setWantCount] = useState(0);
    const [isWanted, setIsWanted] = useState(false);
    const [pageViews, setPageViews] = useState(0);

    const eventViewTokenMap = new Map();

    const getEventViewToken = (eventId) => {
        if (!eventViewTokenMap.has(eventId)) {
            const token = window.crypto?.randomUUID
                ? window.crypto.randomUUID()
                : `${Date.now()}-${Math.random()}`;
            eventViewTokenMap.set(eventId, token);
        }
        return eventViewTokenMap.get(eventId);
    };

    // 🚨 新增：点击“想看”按钮的交互逻辑
    const handleToggleWant = async () => {
        const token = localStorage.getItem('token');
        if (!token) {
            message.info('请先登录后再操作');
            return navigate('/auth');
        }

        try {
            // 发送请求到后端切换状态
            const res = await axios.post('/api/favorite/toggle', { targetId: id, type: 1 });
            if (res.data.code === 200) {
                const newState = !isWanted;
                setIsWanted(newState);
                // 乐观更新 UI 数量
                setWantCount(prev => newState ? prev + 1 : prev - 1);
                message.success(newState ? '已标记为想看' : '已取消想看');
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (err) {
            message.error('网络请求异常');
        }
    };

    const formatStatNumber = (num) => {
        const value = Number(num || 0);
        return value.toLocaleString('zh-CN');
    };

    const getWantSubtitle = () => {
        if (wantCount >= 1000) return '人气飙升中';
        if (wantCount > 0) return '正在被更多同好关注';
        return '成为第一个想看的人';
    };


    // 🚨 自动填充魔法：当演出正式开售(saleAvailable变true)且有云端预约记录时，自动锁定票档和数量
    useEffect(() => {
        // 增加 !hasAutoFilled.current 判断，确保只在初次开售或初次加载时填充一次
        if (saleAvailable && reservedData && event?.tickets && !hasAutoFilled.current) {
            const matchedTicket = event.tickets.find(t => t.id === reservedData.ticketId);
            if (matchedTicket) {
                setSelectedTicket(matchedTicket);
                setQuantity(reservedData.spectatorIds.length || 1);

                hasAutoFilled.current = true; // 🚨 标记为已填充，后续每 3 秒的库存轮询将不再强行覆盖你手动选的票
            }
        }
    }, [saleAvailable, reservedData, event]);

    // 拉取用户常用观演人列表
    const fetchSpectators = async () => {
        try {
            const token = localStorage.getItem('token');
            if (!token) return;
            const res = await axios.get('/api/user/spectator/list');
            if (res.data.code === 200) {
                setSpectators(res.data.data);
            }
        } catch (error) {
            console.error('获取观演人列表失败', error);
        }
    };

    // 监听预约弹窗打开
    useEffect(() => {
        if (reservationModalVisible) {
            fetchSpectators();
        }
    }, [reservationModalVisible]);

    useEffect(() => {
        const fetchRealTimeStock = async () => {
            try {
                const stockRes = await axios.get(`/api/event/stock/${id}`);
                if (stockRes.data.code === 200) {
                    const stockMap = stockRes.data.data;
                    setEvent(prevEvent => {
                        if (!prevEvent) return prevEvent;
                        const updatedTickets = prevEvent.tickets.map(t => ({
                            ...t,
                            remainingStock: stockMap[t.id] !== undefined ? stockMap[t.id] : t.remainingStock
                        }));
                        return { ...prevEvent, tickets: updatedTickets };
                    });
                }
            } catch (err) {
                console.log('拉取实时库存失败', err);
            }
        };

        const fetchDetail = async () => {
            try {
                const res = await axios.get(`/api/event/detail/${id}`, {
                    params: {
                        viewToken: getEventViewToken(id)
                    }
                });
                if (res.data.code === 200) {
                    setEvent(res.data.data);

                    // 🚨 新增：同步后端返回的统计数据（后端需在 detail 接口中补充这三个字段返回）
                    setWantCount(res.data.data.wantCount || 0);
                    setIsWanted(res.data.data.hasWanted || false);
                    setPageViews(res.data.data.pageViews || 0);

                    const firstAvailable = sortTicketsByPriceAsc(res.data.data.tickets || [])
                        .find(t => t.remainingStock > 0);
                    if (firstAvailable) setSelectedTicket(firstAvailable);
                    fetchRealTimeStock();
                } else {
                    message.error(res.data.message);
                    navigate('/');
                }
            } catch (err) {
                message.error('网络请求失败');
            } finally {
                setLoading(false);
            }
        };

        // 🚨 异步拉取云端预约预填信息
        const fetchCloudReservation = async () => {
            const token = localStorage.getItem('token');
            if (!token) return;
            try {
                const res = await axios.get(`/api/reservation/get/${id}`);
                if (res.data.code === 200 && res.data.data) {
                    setReservedData(res.data.data);
                }
            } catch (err) {
                console.error("拉取云端预约信息失败", err);
            }
        };

        fetchDetail();
        fetchCloudReservation();
        window.scrollTo(0, 0);

        const timer = setInterval(() => {
            fetchRealTimeStock();
        }, 3000);

        return () => clearInterval(timer);
    }, [id, navigate]);

    useEffect(() => {
        if (!event || event.status !== 1) {
            setSaleAvailable(false);
            return;
        }

        if (!event.saleTime || dayjs().isAfter(dayjs(event.saleTime)) || dayjs().isSame(dayjs(event.saleTime))) {
            setSaleAvailable(true);
            return;
        }

        const targetTime = dayjs(event.saleTime);
        setSaleAvailable(false);

        const updateCountdown = () => {
            const now = dayjs();
            const diffMs = targetTime.diff(now);

            if (diffMs <= 0) {
                setCountdown({ days: 0, hours: '00', minutes: '00', seconds: '00' });
                setSaleAvailable(true);
                clearInterval(timer);
            } else {
                const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
                const hours = Math.floor((diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
                const minutes = Math.floor((diffMs % (1000 * 60 * 60)) / (1000 * 60));
                const seconds = Math.floor((diffMs % (1000 * 60)) / 1000);

                setCountdown({
                    days,
                    hours: hours.toString().padStart(2, '0'),
                    minutes: minutes.toString().padStart(2, '0'),
                    seconds: seconds.toString().padStart(2, '0')
                });
                setSaleAvailable(false);
            }
        };

        updateCountdown();
        const timer = setInterval(updateCountdown, 1000);

        return () => clearInterval(timer);
    }, [event]);

    const handleAddSpectator = async () => {
        try {
            const values = await spectatorForm.validateFields();
            const res = await axios.post('/api/user/spectator/add', values);
            if (res.data.code === 200) {
                message.success('观演人添加成功！');
                setSpectatorModalVisible(false);
                spectatorForm.resetFields();
                fetchSpectators();
            } else {
                message.error(res.data.message || '添加失败');
            }
        } catch (error) {
            console.error(error);
        }
    };

    // 🚨 提交同步预填数据至后端数据库
    const handleSaveReservation = async () => {
        if (!tempTicketId) return message.warning('请选择要预约的票档');
        if (tempSpectatorIds.length === 0) return message.warning('请至少选择一位观演人');

        const token = localStorage.getItem('token');
        try {
            const res = await axios.post('/api/reservation/save', {
                eventId: Number(id),
                ticketId: tempTicketId,
                spectatorIds: tempSpectatorIds
            });

            if (res.data.code === 200) {
                hasAutoFilled.current = false;
                setReservedData({ ticketId: tempTicketId, spectatorIds: tempSpectatorIds });
                setReservationModalVisible(false);
                message.success('预约抢票配置已成功！');
            } else {
                message.error(res.data.message || '同步失败');
            }
        } catch (err) {
            message.error('网络异常，保存预约失败');
        }
    };

    const maskIdCard = (idCard) => {
        if (!idCard) return '证件号待完善';
        const text = String(idCard);
        if (text.length <= 8) return text;
        return `${text.slice(0, 4)} **** **** ${text.slice(-4)}`;
    };

    const handleToggleReservationSpectator = (spectatorId) => {
        setTempSpectatorIds(prev => {
            if (prev.includes(spectatorId)) {
                return prev.filter(id => id !== spectatorId);
            }

            if (prev.length >= 6) {
                message.warning('单笔订单最多限购6张');
                return prev;
            }

            return [...prev, spectatorId];
        });
    };

    const totalPrice = selectedTicket ? (selectedTicket.price * quantity).toFixed(2) : '0.00';

    const handleBuy = async () => {
        if (isStatus3Future) {
            message.info('演出暂未开放购票，敬请期待');
            return;
        }

        // 🚨 预约拦截：唤起云端排期选单
        if (isPresale) {
            const token = localStorage.getItem('token');
            if (!token) {
                message.info('请先登录再进行预约');
                return navigate('/auth');
            }
            if (reservedData) {
                setTempTicketId(reservedData.ticketId);
                setTempSpectatorIds(reservedData.spectatorIds);
            } else {
                setTempTicketId(null);
                setTempSpectatorIds([]);
            }
            setReservationModalVisible(true);
            return;
        }

        if (!selectedTicket) return message.warning('请先选择票档');

        const currentTicketInfo = event.tickets?.find(t => t.id === selectedTicket.id);
        if (currentTicketInfo && currentTicketInfo.remainingStock <= 0) {
            return message.error('抱歉，您选中的票档刚刚被抢空了，请选择其他票档！');
        }

        const token = localStorage.getItem('token');
        if (!token) {
            message.info('请先登录再进行购票');
            return navigate('/auth');
        }

        const hideLoading = message.loading('正在为您分配抢票通道...', 0);

        try {
            const res = await axios.post('/api/order/pre-check', {
                eventId: event.id
            });

            hideLoading();
            if (res.data.code === 200) {
                // 🚨 核心逻辑：智能校验预填信息是否被用户手动篡改
                let finalPrefilledSpectators = [];
                if (reservedData) {
                    const isSameTicket = selectedTicket.id === reservedData.ticketId;
                    const isSameQuantity = quantity === (reservedData.spectatorIds?.length || 1);

                    // 只有当“票档”和“数量”都和预案完全一致时，才沿用预选观演人
                    if (isSameTicket && isSameQuantity) {
                        finalPrefilledSpectators = reservedData.spectatorIds;
                    }
                }

                navigate('/order/confirm', {
                    state: {
                        event: event,
                        selectedTicket: selectedTicket,
                        quantity: quantity,
                        submitToken: res.data.data,
                        // 🚨 如果被修改过，这里将下发空数组，确认页会强制用户重新勾选观演人
                        prefilledSpectators: finalPrefilledSpectators
                    }
                });
            } else if (res.data.code === 2001) {
                message.warning(res.data.message || '您点击太快了，请稍后再试');
            }
        } catch (error) {
            hideLoading();
            message.error('网络请求异常，请稍后再试');
        }
    };

    if (loading) {
        return <div style={{ height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}><Spin size="large" /></div>;
    }

    if (!event) return null;

    return (
        <div className="detail-page-container">
            <div className="hero-blurred-bg" style={{ backgroundImage: `url(${event.posterUrl})` }} />
            <PublicHeader />
            <div className="content-wrapper">
                <div style={{ display: 'flex' }}>
                    <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} style={{ marginBottom: 20, color: '#fff', fontSize: 16 }}>
                        返回
                    </Button>
                </div>

                <div className="purchase-card">
                    <div className="poster-col">
                        <img src={event.posterUrl} alt={event.title} className="detail-poster" />
                    </div>

                    <div className="info-col">
                        <h1 className="event-title-large">{event.title}</h1>
                        <div className="event-title-views">
                            <EyeOutlined />
                            <span>{formatStatNumber(pageViews)} 次浏览</span>
                        </div>


                        <div className="info-row">
                            <CalendarOutlined className="info-icon" />
                            <span>演出时间：{event.showTime || '时间待定'}</span>
                            {event.runningTime && (
                                <>
                                    <span style={{ margin: '0 12px', color: '#e0e0e0' }}>|</span>
                                    <ClockCircleOutlined style={{ marginRight: 6, color: '#999' }} />
                                    <span>约 {event.runningTime} 分钟</span>
                                </>
                            )}
                        </div>
                        <div className="info-row">
                            <TagsOutlined className="info-icon" />
                            <span>风格：{event.style || '暂未设置'}</span>
                        </div>
                        <div className="info-row">
                            <EnvironmentOutlined className="info-icon" />
                            <span>场馆：{event.venue || '场馆待定'}</span>
                        </div>
                        <div className="info-row">
                            <EnvironmentOutlined className="info-icon" />
                            <span>详细地址：{event.address || '地址待定'}</span>
                        </div>

                        {/* 想看模块 */}
                        <div className="event-want-card">
                            <div className="event-want-content">
                                <div className="event-want-brand">
                                    <span className="event-want-brand-text">AM想看</span>
                                    <HeartFilled className="event-want-brand-heart" />
                                </div>

                                <div className="event-want-main">
                                    <span className="event-want-count">{formatStatNumber(wantCount)}</span>
                                    <span className="event-want-unit">人想看</span>
                                </div>

                                <div className="event-want-subtitle">{getWantSubtitle()}</div>

                            </div>

                            <Button
                                className={`event-want-btn ${isWanted ? 'active' : ''}`}
                                onClick={handleToggleWant}
                            >
                                {isWanted ? <HeartFilled /> : <HeartOutlined />}
                                <span>{isWanted ? '已想看' : '想看'}</span>
                            </Button>
                        </div>

                        {event.status === 1 && !saleAvailable && (
                            <div className="presale-countdown-card">
                                <div className="countdown-top-line">
                                    <span className="countdown-prefix">距离正式开抢还剩</span>
                                </div>
                                <div className="countdown-main">
                                    <span className="countdown-only">仅</span>
                                    <div className="countdown-group">
                                        <span className="countdown-num">{String(countdown.days).padStart(2, '0')}</span>
                                        <span className="countdown-unit">天</span>
                                    </div>
                                    <div className="countdown-group">
                                        <span className="countdown-num">{countdown.hours}</span>
                                        <span className="countdown-unit">时</span>
                                    </div>
                                    <div className="countdown-group">
                                        <span className="countdown-num">{countdown.minutes}</span>
                                        <span className="countdown-unit">分</span>
                                    </div>
                                    <div className="countdown-group">
                                        <span className="countdown-num">{countdown.seconds}</span>
                                        <span className="countdown-unit">秒</span>
                                    </div>
                                </div>
                                <div className="countdown-sale-time">
                                    {event.saleTime ? dayjs(event.saleTime).format('MM月DD日 HH:mm开抢') : '即将开抢'}
                                </div>
                            </div>
                        )}

                        {/* 🚨 仅在正式在售状态下展示基础选票组件 */}
                        {showPurchaseOptions && (
                            <div style={{ marginTop: '24px', textAlign: 'left' }}>
                                <div style={{ fontWeight: 'bold', color: '#333', marginBottom: 8 }}>选择票档</div>
                                <div className="tickets-container">
                                    {sortedTickets.map(ticket => {
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
                        )}

                        {showPurchaseOptions && (
                            <div style={{ marginTop: '24px', marginBottom: '40px', textAlign: 'left' }}>
                                <div style={{ fontWeight: 'bold', color: '#333', marginBottom: 10 }}>购买数量</div>
                                <div style={{ display: 'flex', alignItems: 'center' }}>
                                    <InputNumber min={1} max={6} value={quantity} onChange={setQuantity} size="large" disabled={!selectedTicket} />
                                    <span style={{ marginLeft: 12, color: '#999', fontSize: 13 }}>每笔订单限购 6 张</span>
                                </div>
                            </div>
                        )}

                        <div className="action-bar">
                            {!hidePurchaseOptions && !isPresale && (
                                <div>
                                    <span style={{ color: '#666', marginRight: 8 }}>总计:</span>
                                    <span style={{ color: '#FF8899', fontSize: 20 }}>¥</span>
                                    <span className="total-price">{totalPrice}</span>
                                </div>
                            )}
                            <Button
                                type="primary"
                                className="action-buy-btn"
                                onClick={handleBuy}
                                style={{
                                    background: event.status !== 1 ? '#ccc' : 'linear-gradient(135deg, #FF8899, #ff6b80)',
                                    boxShadow: event.status !== 1 ? 'none' : '0 4px 12px rgba(255, 136, 153, 0.4)',
                                    border: 'none',
                                    marginLeft: hidePurchaseOptions || isPresale ? 'auto' : 0,
                                    pointerEvents: event.status !== 1 ? 'none' : 'auto'
                                }}
                            >
                                {event.status === 1
                                    ? (!saleAvailable
                                        ? (reservedData ? '已预约' : '立即预约')
                                        : '立即购票')
                                    : isStatus3Future ? '敬请期待' : isStatus3Past ? '已结束' : '已停售'}
                            </Button>
                        </div>
                    </div>
                </div>

                {event.artists && event.artists.length > 0 && (
                    <div className="section-block">
                        <div className="section-header">参演音乐人</div>
                        <Row gutter={[16, 16]}>
                            {event.artists.map((artist, idx) => (
                                <Col xs={24} sm={12} md={8} key={idx}>
                                    <div
                                        className="artist-card"
                                        style={{ cursor: 'pointer', transition: 'all 0.3s' }} // 🚨 增加鼠标指针手型
                                        onClick={() => artist.id && navigate(`/artist/${artist.id}`)} // 🚨 增加点击跳转逻辑
                                    >
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

                <div className="section-block">
                    <div className="section-header">图文详情</div>
                    {event.detailsUrl ? (
                        <img src={event.detailsUrl} alt="详情长图" className="details-long-img" />
                    ) : (
                        <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>主办方暂未提供详细图文介绍</div>
                    )}
                </div>
            </div>

            {/* ================= 云端预约抢票选单弹窗 ================= */}
            <Modal
                className="reservation-prefill-modal"
                title={
                    <div className="reservation-modal-title">
                        <span>配置预填信息</span>
                        <small>开抢前预选票档与购票人，正式开售后自动带入订单</small>
                    </div>
                }
                open={reservationModalVisible}
                onOk={handleSaveReservation}
                onCancel={() => setReservationModalVisible(false)}
                okText="保存"
                cancelText="取消"
                width={720}
                okButtonProps={{ className: 'reservation-modal-ok-btn' }}
            >
                <div className="reservation-panel">

                    <div className="reservation-step-card">
                        <div className="reservation-step-header">
                            <div className="reservation-step-title">
                                <span className="reservation-step-index">1</span>
                                <div>
                                    <strong>预选期望票档</strong>
                                    <p>请选择一个开抢时优先带入的票档</p>
                                </div>
                            </div>
                            <span className={`reservation-step-status ${tempTicketId ? 'done' : ''}`}>
                                {tempTicketId ? '已选择' : '待选择'}
                            </span>
                        </div>

                        <div className="tickets-container reservation-ticket-grid">
                            {sortedTickets.length > 0 ? sortedTickets.map(ticket => (
                                <div
                                    key={ticket.id}
                                    className={`ticket-pill reservation-ticket-pill ${tempTicketId === ticket.id ? 'active' : ''}`}
                                    onClick={() => setTempTicketId(ticket.id)}
                                >
                                    <span className="ticket-name">{ticket.name}</span>
                                    <span className="ticket-price">¥ {ticket.price}</span>
                                </div>
                            )) : (
                                <div className="reservation-empty-line">该演出暂未设置票档</div>
                            )}
                        </div>
                    </div>

                    <div className="reservation-step-card">
                        <div className="reservation-step-header">
                            <div className="reservation-step-title">
                                <span className="reservation-step-index">2</span>
                                <div>
                                    <strong>预选实名购票人</strong>
                                    <p>选中顺序即为订单页默认购票人顺序，最多 6 人</p>
                                </div>
                            </div>
                            <span className="reservation-selected-count">
                                已选 {tempSpectatorIds.length}/6
                            </span>
                        </div>

                        <Row gutter={[12, 12]} className="reservation-spectator-grid">
                            {spectators.map(sp => {
                                const selectedIndex = tempSpectatorIds.indexOf(sp.id);
                                const isSelected = selectedIndex !== -1;

                                return (
                                    <Col xs={24} sm={12} key={sp.id}>
                                        <div
                                            className={`reservation-spectator-card ${isSelected ? 'selected' : ''}`}
                                            onClick={() => handleToggleReservationSpectator(sp.id)}
                                        >
                                            <div className="reservation-spectator-main">
                                                <div className="reservation-spectator-avatar">
                                                    {sp.name ? sp.name.slice(0, 1) : '票'}
                                                </div>
                                                <div className="reservation-spectator-info">
                                                    <div className="reservation-spectator-name">{sp.name || '未命名购票人'}</div>
                                                    <div className="reservation-spectator-id">{maskIdCard(sp.idCard)}</div>
                                                </div>
                                            </div>

                                            <div className="reservation-spectator-right">
                                                {isSelected ? (
                                                    <span className="reservation-selected-order">{selectedIndex + 1}</span>
                                                ) : (
                                                    <span className="reservation-select-text">选择</span>
                                                )}
                                            </div>
                                        </div>
                                    </Col>
                                );
                            })}

                            <Col xs={24} sm={12}>
                                <div
                                    className="reservation-add-spectator-card"
                                    onClick={() => setSpectatorModalVisible(true)}
                                >
                                    <span className="reservation-add-plus">+</span>
                                    <span>新增实名购票人</span>
                                </div>
                            </Col>
                        </Row>
                    </div>
                </div>
            </Modal>

            {/* 新增观演人通用子组件 */}
            <Modal
                title="新增常用购票人"
                open={spectatorModalVisible}
                onOk={handleAddSpectator}
                onCancel={() => { setSpectatorModalVisible(false); spectatorForm.resetFields(); }}
                okText="保存"
                cancelText="取消"
                okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}
            >
                <Form form={spectatorForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="真实姓名" name="name" rules={[{ required: true, message: '请输入真实姓名' }]}>
                        <Input placeholder="请输入证件上的真实姓名" />
                    </Form.Item>
                    <Form.Item label="证件类型" name="idType" initialValue={1}>
                        <Select>
                            <Select.Option value={1}>身份证</Select.Option>
                            <Select.Option value={2}>护照</Select.Option>
                            <Select.Option value={3}>港澳台居民居住证</Select.Option>
                        </Select>
                    </Form.Item>
                    <Form.Item
                        label="证件号码" name="idCard" dependencies={['idType']}
                        rules={[
                            { required: true, message: '请输入证件号码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value) return Promise.resolve();
                                    const type = getFieldValue('idType');
                                    if (type === 1 && !/^[1-9]\d{5}(18|19|20)\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\d{3}[0-9Xx]$/.test(value)) return Promise.reject(new Error('身份证格式不正确'));
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

export default EventDetail;