import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import { Spin, message, Row, Col, Avatar, Button, InputNumber, Modal, Form, Input, Select } from 'antd';
import {
    CalendarOutlined,
    EnvironmentOutlined,
    UserOutlined,
    ArrowLeftOutlined,
    TagsOutlined,
    ClockCircleOutlined,
    HeartOutlined,
    HeartFilled,
    EyeOutlined
} from '@ant-design/icons';
import axios from '../../utils/request';
import './EventDetail.css';
import EventComments from './EventComments';
import dayjs from 'dayjs';
import { FireFilled } from '@ant-design/icons';
import PublicHeader from '../../components/PublicHeader/PublicHeader';

const sortTicketsByPriceAsc = (tickets = []) =>
    [...tickets].sort((a, b) => Number(a.price ?? 0) - Number(b.price ?? 0));


const normalizeSessions = (eventData) => {
    if (!eventData) return [];

    if (!Array.isArray(eventData.sessions) || eventData.sessions.length === 0) {
        return [];
    }

    return eventData.sessions.map((session, index) => ({
        ...session,
        sessionName: session.sessionName || `场次${index + 1}`,
        status: session.status,
        showTime: session.showTime,
        saleTime: session.saleTime,
        tickets: Array.isArray(session.tickets) ? session.tickets : []
    }));
};

const EventDetail = () => {
    const { id } = useParams();
    const navigate = useNavigate();

    const location = useLocation();

    const [event, setEvent] = useState(null);
    const [loading, setLoading] = useState(true);

    // 购票控制状态
    const [selectedSessionId, setSelectedSessionId] = useState(null);
    const [selectedTicket, setSelectedTicket] = useState(null);
    const [quantity, setQuantity] = useState(1);

    // 倒计时与开售状态
    const [countdown, setCountdown] = useState({ days: 0, hours: '00', minutes: '00', seconds: '00' });
    const [saleAvailable, setSaleAvailable] = useState(false);

    // 云端预约预填核心状态池
    const [reservedData, setReservedData] = useState(null);
    const [reservationModalVisible, setReservationModalVisible] = useState(false);
    const [spectators, setSpectators] = useState([]);
    const [tempTicketId, setTempTicketId] = useState(null);
    const [tempSpectatorIds, setTempSpectatorIds] = useState([]);

    // 新增常用观演人弹窗状态
    const [spectatorModalVisible, setSpectatorModalVisible] = useState(false);
    const [spectatorForm] = Form.useForm();

    // 防止轮询重绘覆盖用户操作的防御标记
    const hasAutoFilled = useRef(false);

    // 想看与浏览量状态
    const [wantCount, setWantCount] = useState(0);
    const [isWanted, setIsWanted] = useState(false);
    const [pageViews, setPageViews] = useState(0);

    const viewTokenRef = useRef({
        eventId: null,
        locationKey: null,
        token: null
    });

    const getPageEntryViewToken = () => {
        const eventId = String(id);
        const locationKey = location.key || window.location.pathname;

        if (
            viewTokenRef.current.eventId !== eventId ||
            viewTokenRef.current.locationKey !== locationKey ||
            !viewTokenRef.current.token
        ) {
            viewTokenRef.current = {
                eventId,
                locationKey,
                token: window.crypto?.randomUUID
                    ? window.crypto.randomUUID()
                    : `${Date.now()}-${Math.random()}`
            };
        }

        return viewTokenRef.current.token;
    };

    // 同一合集下的其他演出，用于切换巡演站点
    const [collectionEvents, setCollectionEvents] = useState([]);

    const sessions = event ? normalizeSessions(event) : [];
    const hasSession = sessions.length > 0;
    const selectedSession = hasSession
        ? (sessions.find(s => String(s.id) === String(selectedSessionId)) || sessions[0] || null)
        : null;

    const activeStatus = selectedSession?.status ?? null;
    const activeShowTime = selectedSession?.showTime ?? null;
    const activeSaleTime = selectedSession?.saleTime ?? null;

    const isNoSession = !!event && !hasSession;

    const isPresale = activeStatus === 1 && !saleAvailable;
    const showPurchaseOptions = activeStatus === 1 && saleAvailable;

    const showTimeObj = activeShowTime ? dayjs(activeShowTime) : null;
    const isShowTimeValid = !!showTimeObj && showTimeObj.isValid();
    const isStatus3Future = activeStatus === 3 && isShowTimeValid && showTimeObj.isAfter(dayjs());
    const isStatus3Past = activeStatus === 3 && isShowTimeValid && showTimeObj.isBefore(dayjs());
    const hidePurchaseOptions = isStatus3Future;

    const sortedTickets = sortTicketsByPriceAsc(selectedSession?.tickets || []);
    const totalPrice = selectedTicket ? (Number(selectedTicket.price || 0) * quantity).toFixed(2) : '0.00';

    const formatStatNumber = (num) => {
        const value = Number(num || 0);
        return value.toLocaleString('zh-CN');
    };

    const getWantSubtitle = () => {
        if (wantCount >= 1000) return '人气飙升中';
        if (wantCount > 0) return '正在被更多同好关注';
        return '成为第一个想看的人';
    };

    const getCollectionEventName = (item) => {
        return item.collectionAlias || item.city || item.title || '未命名场次';
    };

    const getSessionDisplayName = (session, index) => {
        if (session?.sessionName && session.sessionName !== '默认场次') return session.sessionName;
        if (session?.showTime && dayjs(session.showTime).isValid()) return dayjs(session.showTime).format('MM月DD日 HH:mm');
        return `场次${index + 1}`;
    };

    const handleToggleWant = async () => {
        const token = localStorage.getItem('token');
        if (!token) {
            message.info('请先登录后再操作');
            return navigate('/auth');
        }

        try {
            const res = await axios.post('/api/favorite/toggle', { targetId: id, type: 1 });
            if (res.data.code === 200) {
                const newState = !isWanted;
                setIsWanted(newState);
                setWantCount(prev => newState ? prev + 1 : Math.max(0, prev - 1));
                message.success(newState ? '已标记为想看' : '已取消想看');
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (err) {
            message.error('网络请求异常');
        }
    };

    const fetchSpectators = async () => {
        try {
            const token = localStorage.getItem('token');
            if (!token) return;

            const res = await axios.get('/api/user/spectator/list');
            if (res.data.code === 200) {
                setSpectators(res.data.data || []);
            }
        } catch (error) {
            console.error('获取观演人列表失败', error);
        }
    };

    useEffect(() => {
        if (reservationModalVisible) {
            fetchSpectators();
        }
    }, [reservationModalVisible]);

    // 拉取详情、同合集演出列表。详情接口需要后端返回 sessions，每个 session 下包含 tickets。
    useEffect(() => {
        const fetchDetail = async () => {
            setLoading(true);
            setEvent(null);
            setSelectedSessionId(null);
            setSelectedTicket(null);
            setReservedData(null);
            hasAutoFilled.current = false;

            try {
                const res = await axios.get(`/api/event/detail/${id}`, {
                    params: { viewToken: getPageEntryViewToken() }
                });

                if (res.data.code === 200) {
                    const eventData = res.data.data || {};
                    const normalizedSessions = normalizeSessions(eventData);
                    const nextEvent = { ...eventData, sessions: normalizedSessions };

                    setEvent(nextEvent);
                    setWantCount(eventData.wantCount || 0);
                    setIsWanted(eventData.hasWanted || false);
                    setPageViews(eventData.pageViews || 0);

                    const firstSession = normalizedSessions[0] || null;
                    setSelectedSessionId(firstSession?.id ?? null);

                    const firstAvailable = sortTicketsByPriceAsc(firstSession?.tickets || [])
                        .find(t => Number(t.remainingStock ?? 0) > 0);
                    setSelectedTicket(firstAvailable || null);

                    if (eventData.collectionId) {
                        try {
                            const collectionRes = await axios.get(`/api/event/collection/${eventData.collectionId}/events`);
                            setCollectionEvents(collectionRes.data.code === 200 ? (collectionRes.data.data || []) : []);
                        } catch (e) {
                            console.error('加载同合集演出失败', e);
                            setCollectionEvents([]);
                        }
                    } else {
                        setCollectionEvents([]);
                    }
                } else {
                    message.error(res.data.message || '演出不存在或已下架');
                    navigate('/');
                }
            } catch (err) {
                message.error('网络请求失败');
            } finally {
                setLoading(false);
            }
        };

        fetchDetail();
        window.scrollTo(0, 0);
    },  [id, navigate, location.key]);

    // 切换具体时间场次时，重置票档、数量，并按 eventId + sessionId 拉取当前用户的预约配置。
    useEffect(() => {
        if (!event || !selectedSession) return;

        hasAutoFilled.current = false;
        setQuantity(1);

        const firstAvailable = sortTicketsByPriceAsc(selectedSession.tickets || [])
            .find(t => Number(t.remainingStock ?? 0) > 0);
        setSelectedTicket(firstAvailable || null);

        const fetchCloudReservation = async () => {
            const token = localStorage.getItem('token');
            if (!token || !selectedSession.id) {
                setReservedData(null);
                return;
            }

            try {
                const res = await axios.get('/api/reservation/get', {
                    params: {
                        eventId: Number(id),
                        sessionId: selectedSession.id
                    }
                });

                if (res.data.code === 200 && res.data.data) {
                    setReservedData(res.data.data);
                } else {
                    setReservedData(null);
                }
            } catch (err) {
                console.error('拉取云端预约信息失败', err);
                setReservedData(null);
            }
        };

        fetchCloudReservation();
    }, [id, event?.id, selectedSessionId]);

    // 按当前 session 轮询库存，不再按 eventId 拉取全部票档库存。
    useEffect(() => {
        if (!selectedSession?.id) return;

        const fetchRealTimeStock = async () => {
            try {
                const stockRes = await axios.get(`/api/event/session/stock/${selectedSession.id}`);
                if (stockRes.data.code === 200) {
                    const stockMap = stockRes.data.data || {};

                    setEvent(prevEvent => {
                        if (!prevEvent || !Array.isArray(prevEvent.sessions)) return prevEvent;

                        const updatedSessions = prevEvent.sessions.map(session => {
                            if (String(session.id) !== String(selectedSession.id)) return session;

                            const updatedTickets = (session.tickets || []).map(t => ({
                                ...t,
                                remainingStock: stockMap[t.id] !== undefined ? stockMap[t.id] : t.remainingStock
                            }));

                            return { ...session, tickets: updatedTickets };
                        });

                        return { ...prevEvent, sessions: updatedSessions };
                    });
                }
            } catch (err) {
                console.log('拉取实时库存失败', err);
            }
        };

        fetchRealTimeStock();
        const timer = setInterval(fetchRealTimeStock, 3000);

        return () => clearInterval(timer);
    }, [selectedSessionId]);

    // 倒计时以当前选择的 session 为准。
    useEffect(() => {
        if (!event || activeStatus !== 1) {
            setSaleAvailable(false);
            return;
        }

        if (!activeSaleTime || dayjs().isAfter(dayjs(activeSaleTime)) || dayjs().isSame(dayjs(activeSaleTime))) {
            setSaleAvailable(true);
            setCountdown({ days: 0, hours: '00', minutes: '00', seconds: '00' });
            return;
        }

        const targetTime = dayjs(activeSaleTime);
        let timerId;
        setSaleAvailable(false);

        const updateCountdown = () => {
            const now = dayjs();
            const diffMs = targetTime.diff(now);

            if (diffMs <= 0) {
                setCountdown({ days: 0, hours: '00', minutes: '00', seconds: '00' });
                setSaleAvailable(true);
                if (timerId) clearInterval(timerId);
                return;
            }

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
        };

        updateCountdown();
        timerId = setInterval(updateCountdown, 1000);

        return () => clearInterval(timerId);
    }, [event, selectedSessionId, activeStatus, activeSaleTime]);

    // 正式开售时，如果用户已配置当前 session 的预约，自动带入票档和数量。
    useEffect(() => {
        if (saleAvailable && reservedData && selectedSession?.tickets && !hasAutoFilled.current) {
            const matchedTicket = selectedSession.tickets.find(t => Number(t.id) === Number(reservedData.ticketId));
            if (matchedTicket) {
                setSelectedTicket(matchedTicket);
                setQuantity(reservedData.spectatorIds?.length || 1);
                hasAutoFilled.current = true;
            }
        }
    }, [saleAvailable, reservedData, selectedSessionId, event]);

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

    const handleSaveReservation = async () => {
        if (!selectedSession?.id) return message.warning('请选择演出时间场次');
        if (!tempTicketId) return message.warning('请选择要预约的票档');
        if (tempSpectatorIds.length === 0) return message.warning('请至少选择一位观演人');

        try {
            const res = await axios.post('/api/reservation/save', {
                eventId: Number(id),
                sessionId: selectedSession.id,
                ticketId: tempTicketId,
                spectatorIds: tempSpectatorIds
            });

            if (res.data.code === 200) {
                hasAutoFilled.current = false;
                setReservedData({
                    eventId: Number(id),
                    sessionId: selectedSession.id,
                    ticketId: tempTicketId,
                    spectatorIds: tempSpectatorIds
                });
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

    const handleBuy = async () => {
        if (isNoSession) {
            message.info('演出时间待定，暂未开放预约或购票');
            return;
        }

        if (isStatus3Future) {
            message.info('演出暂未开放购票，敬请期待');
            return;
        }

        if (isPresale) {
            const token = localStorage.getItem('token');
            if (!token) {
                message.info('请先登录再进行预约');
                return navigate('/auth');
            }
            if (!selectedSession?.id) {
                message.warning('请选择演出时间场次');
                return;
            }

            if (reservedData) {
                setTempTicketId(reservedData.ticketId);
                setTempSpectatorIds(reservedData.spectatorIds || []);
            } else {
                setTempTicketId(null);
                setTempSpectatorIds([]);
            }
            setReservationModalVisible(true);
            return;
        }

        if (!selectedSession?.id) return message.warning('请选择演出时间场次');
        if (!selectedTicket) return message.warning('请先选择票档');

        const currentTicketInfo = selectedSession.tickets?.find(t => Number(t.id) === Number(selectedTicket.id));
        if (currentTicketInfo && Number(currentTicketInfo.remainingStock ?? 0) <= 0) {
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
                eventId: event.id,
                sessionId: selectedSession.id
            });

            hideLoading();
            if (res.data.code === 200) {
                let finalPrefilledSpectators = [];
                if (reservedData) {
                    const isSameSession = String(selectedSession.id) === String(reservedData.sessionId);
                    const isSameTicket = Number(selectedTicket.id) === Number(reservedData.ticketId);
                    const isSameQuantity = quantity === (reservedData.spectatorIds?.length || 1);

                    if (isSameSession && isSameTicket && isSameQuantity) {
                        finalPrefilledSpectators = reservedData.spectatorIds;
                    }
                }

                navigate('/order/confirm', {
                    state: {
                        event,
                        selectedSession,
                        sessionId: selectedSession.id,
                        selectedTicket,
                        quantity,
                        submitToken: res.data.data,
                        prefilledSpectators: finalPrefilledSpectators
                    }
                });
            } else if (res.data.code === 2001) {
                message.warning(res.data.message || '您点击太快了，请稍后再试');
            } else {
                message.error(res.data.message || '预检失败');
            }
        } catch (error) {
            hideLoading();
            message.error('网络请求异常，请稍后再试');
        }
    };

    if (loading) {
        return (
            <div style={{ height: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                <Spin size="large" />
            </div>
        );
    }

    const actionButtonDisabled = isNoSession || activeStatus !== 1;

    const actionButtonText = isNoSession
        ? '敬请期待'
        : activeStatus === 1
            ? (!saleAvailable
                ? (reservedData ? '已预约' : '立即预约')
                : '立即购票')
            : isStatus3Future
                ? '敬请期待'
                : isStatus3Past
                    ? '已结束'
                    : '已停售';

    if (!event) return null;

    return (
        <div className="detail-page-container">
            <div className="hero-blurred-bg" style={{ backgroundImage: `url(${event.posterUrl})` }} />
            <PublicHeader />

            <div className="content-wrapper">
                <div style={{ display: 'flex' }}>
                    <Button
                        type="text"
                        icon={<ArrowLeftOutlined />}
                        onClick={() => navigate(-1)}
                        style={{ marginBottom: 20, color: '#fff', fontSize: 16 }}
                    >
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
                            <span>演出时间：{activeShowTime || '时间待定'}</span>
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

                        {collectionEvents.length > 1 && (
                            <div className="collection-switch-section">
                                <div className="collection-switch-title-row">选择巡演站点</div>

                                <div className="collection-segment-wrap">
                                    {collectionEvents.map(item => {
                                        const isCurrent = Number(item.id) === Number(event.id);

                                        return (
                                            <button
                                                type="button"
                                                key={item.id}
                                                className={`collection-segment-item ${isCurrent ? 'active' : ''}`}
                                                onClick={() => {
                                                    if (!isCurrent) navigate(`/event/${item.id}`);
                                                }}
                                                title={item.title}
                                            >
                                                <span className="collection-segment-name">
                                                    {getCollectionEventName(item)}
                                                </span>
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>
                        )}

                        {sessions.length > 1 && (
                            <div className="session-switch-section">
                                <div className="session-switch-title-row">选择时间</div>

                                <div className="session-segment-wrap">
                                    {sessions.map((session, index) => {
                                        const isCurrent = String(session.id) === String(selectedSessionId);

                                        return (
                                            <button
                                                type="button"
                                                key={session.id ?? index}
                                                className={`session-segment-item ${isCurrent ? 'active' : ''}`}
                                                onClick={() => {
                                                    if (!isCurrent) {
                                                        setSelectedSessionId(session.id ?? null);
                                                    }
                                                }}
                                                title={getSessionDisplayName(session, index)}
                                            >
                        <span className="session-segment-name">
                            {getSessionDisplayName(session, index)}
                        </span>
                                            </button>
                                        );
                                    })}
                                </div>
                            </div>
                        )}

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

                        {activeStatus === 1 && !saleAvailable && (
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
                                    {activeSaleTime ? dayjs(activeSaleTime).format('MM月DD日 HH:mm开抢') : '即将开抢'}
                                </div>
                            </div>
                        )}

                        {showPurchaseOptions && (
                            <div style={{ marginTop: '24px', textAlign: 'left' }}>
                                <div style={{ fontWeight: 'bold', color: '#333', marginBottom: 8 }}>选择票档</div>
                                <div className="tickets-container">
                                    {sortedTickets.map(ticket => {
                                        const isSoldOut = Number(ticket.remainingStock ?? 0) <= 0;
                                        const isActive = Number(selectedTicket?.id) === Number(ticket.id);
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
                                    <InputNumber
                                        min={1}
                                        max={6}
                                        value={quantity}
                                        onChange={(value) => setQuantity(value || 1)}
                                        size="large"
                                        disabled={!selectedTicket}
                                    />
                                    <span style={{ marginLeft: 12, color: '#999', fontSize: 13 }}>每笔订单限购 6 张</span>
                                </div>
                            </div>
                        )}

                        <div className="action-bar">
                            {!isNoSession && !hidePurchaseOptions && !isPresale && (
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
                                disabled={actionButtonDisabled}
                                style={{
                                    background: actionButtonDisabled ? '#ccc' : 'linear-gradient(135deg, #FF8899, #ff6b80)',
                                    boxShadow: actionButtonDisabled ? 'none' : '0 4px 12px rgba(255, 136, 153, 0.4)',
                                    border: 'none',
                                    marginLeft: hidePurchaseOptions || isPresale || isNoSession ? 'auto' : 0
                                }}
                            >
                                {actionButtonText}
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
                                        style={{ cursor: 'pointer', transition: 'all 0.3s' }}
                                        onClick={() => artist.id && navigate(`/artist/${artist.id}`)}
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
                    <EventComments eventId={id} event={event} />
                    <div className="section-header">图文详情</div>
                    {event.detailsUrl ? (
                        <img src={event.detailsUrl} alt="详情长图" className="details-long-img" />
                    ) : (
                        <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>主办方暂未提供详细图文介绍</div>
                    )}
                </div>
            </div>

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
                                    className={`ticket-pill reservation-ticket-pill ${Number(tempTicketId) === Number(ticket.id) ? 'active' : ''}`}
                                    onClick={() => setTempTicketId(ticket.id)}
                                >
                                    <span className="ticket-name">{ticket.name}</span>
                                    <span className="ticket-price">¥ {ticket.price}</span>
                                </div>
                            )) : (
                                <div className="reservation-empty-line">该场次暂未设置票档</div>
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
                        label="证件号码"
                        name="idCard"
                        dependencies={['idType']}
                        rules={[
                            { required: true, message: '请输入证件号码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value) return Promise.resolve();
                                    const type = getFieldValue('idType');
                                    if (type === 1 && !/^[1-9]\d{5}(18|19|20)\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\d{3}[0-9Xx]$/.test(value)) {
                                        return Promise.reject(new Error('身份证格式不正确'));
                                    }
                                    return Promise.resolve();
                                }
                            })
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
