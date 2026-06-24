import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Tabs, Spin, Empty, message, ConfigProvider, Button } from 'antd';
import axios from '../../utils/request';
import dayjs from 'dayjs';
import locale from 'antd/locale/zh_CN';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './ArtistDetail.css';
import {CheckCircleOutlined, HeartOutlined, FireFilled} from "@ant-design/icons";

const EVENT_PAGE_SIZE = 10;

const ArtistDetail = () => {
    const { id } = useParams();
    const navigate = useNavigate();

    const [artist, setArtist] = useState(null);
    const [events, setEvents] = useState([]);
    const [loading, setLoading] = useState(true);
    const [eventsLoadingMore, setEventsLoadingMore] = useState(false);
    const [eventPage, setEventPage] = useState(1);
    const [hasMoreEvents, setHasMoreEvents] = useState(false);

    // 在状态定义区加入
    const [isFavorited, setIsFavorited] = useState(false);

    // 🚨 关注艺人交互逻辑
    const handleToggleFavorite = async () => {
        const token = localStorage.getItem('token');
        if (!token) {
            message.info('请先登录后再操作');
            return navigate('/auth');
        }
        try {
            // 假设后端接口为 /api/favorite/toggle，type=2 代表艺人
            const res = await axios.post('/api/favorite/toggle', { targetId: id, type: 2 });
            if (res.data.code === 200) {
                const nextFavorited = !isFavorited;
                setIsFavorited(nextFavorited);

                setArtist(prev => {
                    if (!prev) return prev;

                    const oldCount = Number(prev.likeCount || 0);
                    return {
                        ...prev,
                        likeCount: nextFavorited ? oldCount + 1 : Math.max(0, oldCount - 1)
                    };
                });

                message.success(nextFavorited ? '已关注该音乐人' : '已取消关注');
            }
        } catch (err) {
            message.error('操作失败');
        }
    };

    const fetchArtistEvents = async (page = 1, append = false) => {
        if (append) {
            setEventsLoadingMore(true);
        }

        try {
            const eventRes = await axios.get('/api/event/page', {
                params: {
                    artistId: id,
                    current: page,
                    size: EVENT_PAGE_SIZE
                }
            });

            if (eventRes.data.code === 200) {
                const pageData = eventRes.data.data || {};
                const records = pageData.records || [];
                const current = Number(pageData.current || page);
                const size = Number(pageData.size || EVENT_PAGE_SIZE);
                const total = Number(pageData.total || 0);

                setEvents(prev => append ? [...prev, ...records] : records);
                setEventPage(current);
                setHasMoreEvents(current * size < total);
            } else {
                message.error(eventRes.data.message || '获取音乐人演出失败');
            }
        } catch (error) {
            console.error('加载音乐人演出失败', error);
            message.error('加载音乐人演出失败');
        } finally {
            if (append) {
                setEventsLoadingMore(false);
            }
        }
    };

    const handleLoadMoreEvents = () => {
        if (eventsLoadingMore || !hasMoreEvents) return;
        fetchArtistEvents(eventPage + 1, true);
    };

    useEffect(() => {
        const fetchDetailAndEvents = async () => {
            setLoading(true);
            try {
                // 1. 获取音乐人详情
                const artistRes = await axios.get(`/api/artist/${id}`);
                if (artistRes.data.code === 200) {
                    setArtist(artistRes.data.data);
                    setIsFavorited(artistRes.data.data.isFavorited || false); // 🚨 初始化关注状态
                    document.title = `${artistRes.data.data.name} - Ave Monica`;
                } else {
                    message.error(artistRes.data.message);
                    navigate('/artists');
                    return;
                }

                // 2. 默认只拉取前 10 场，后续通过“加载更多”分页追加
                await fetchArtistEvents(1, false);
            } catch (error) {
                console.error("加载音乐人主页失败", error);
            } finally {
                setLoading(false);
            }
        };

        fetchDetailAndEvents();
        window.scrollTo(0, 0);
    }, [id, navigate]);

    // 新 session 结构：票档在 event.sessions[*].tickets
    const getAllSessionTickets = (event) => {
        if (!event || !Array.isArray(event.sessions)) return [];

        return event.sessions.flatMap(session =>
            Array.isArray(session.tickets) ? session.tickets : []
        );
    };

// 计算全部场次中的最低票价
    const getMinPrice = (event) => {
        const tickets = getAllSessionTickets(event);
        if (tickets.length === 0) return '票档待定';

        const prices = tickets
            .map(t => Number(t.price))
            .filter(price => Number.isFinite(price));

        return prices.length === 0 ? '票档待定' : `¥${Math.min(...prices)}起`;
    };

// 获取展示用演出时间：优先 event.showTime，没有就从 sessions 取最早 showTime
    const getDisplayShowTime = (event) => {
        if (event?.showTime && dayjs(event.showTime).isValid()) {
            return event.showTime;
        }

        const validTimes = Array.isArray(event?.sessions)
            ? event.sessions
                .map(session => session.showTime)
                .filter(time => time && dayjs(time).isValid())
                .sort((a, b) => dayjs(a).valueOf() - dayjs(b).valueOf())
            : [];

        return validTimes[0] || null;
    };

// 获取展示用开票时间：优先 event.saleTime，没有就从 sessions 取最早 saleTime
    const getDisplaySaleTime = (event) => {
        if (event?.saleTime && dayjs(event.saleTime).isValid()) {
            return event.saleTime;
        }

        const validSaleTimes = Array.isArray(event?.sessions)
            ? event.sessions
                .map(session => session.saleTime)
                .filter(time => time && dayjs(time).isValid())
                .sort((a, b) => dayjs(a).valueOf() - dayjs(b).valueOf())
            : [];

        return validSaleTimes[0] || null;
    };

// 状态/价格显示
    const getPriceText = (event) => {
        const showTime = getDisplayShowTime(event);

        // 没有任何场次时间时，不能判定为已结束
        if (!showTime) return '敬请期待';

        if (Number(event.status) === 3) {
            return dayjs(showTime).isAfter(dayjs()) ? '敬请期待' : '已结束';
        }

        return getMinPrice(event);
    };

    if (loading) {
        return <div className="ad-loading"><Spin size="large" tip="加载音乐人档案中..." /></div>;
    }

    if (!artist) return null;

    // Tabs 配置
    const tabItems = [
        {
            key: 'events',
            label: '全部演出',
            children: (
                <div className="ad-events-section">
                    {events.length > 0 ? (
                        <>
                            <div className="ad-event-grid">
                                {events.map((event, index) => {
                                    const priceText = getPriceText(event);
                                    const isStatusText = priceText === '敬请期待' || priceText === '已结束' || priceText === '票档待定';
                                    const displayShowTime = getDisplayShowTime(event);
                                    const displaySaleTime = getDisplaySaleTime(event);
                                    const isPresale = Number(event.status) === 1
                                        && displaySaleTime
                                        && dayjs().isBefore(dayjs(displaySaleTime));
                                    return (
                                        <div
                                            key={event.id}
                                            className="ad-event-card"
                                            style={{ '--card-index': index }}
                                            onClick={() => navigate(`/event/${event.id}`)}
                                        >
                                            <div className="ad-event-cover-wrapper">
                                                <img src={event.posterUrl} alt={event.title} className="ad-event-cover" />
                                                <div className="ad-event-image-mask" />
                                                {event.style && (
                                                    <div className="ad-event-style-tags">
                                                        <span className="ad-event-style-tag">{event.style.split('/')[0]}</span>
                                                    </div>
                                                )}
                                                {/* 🚨 新增：与演出大厅完全一致的右下角“预售中”标签 */}
                                                {isPresale && (
                                                    <div style={{
                                                        position: 'absolute',
                                                        bottom: 14,
                                                        right: 14,
                                                        padding: '2px 8px',
                                                        color: '#FF8899',
                                                        fontSize: '12px',
                                                        fontWeight: 'bold',
                                                        backgroundColor: '#fff0f3',
                                                        zIndex: 3,
                                                        border: '1px solid rgba(255, 136, 153, 0.3)',
                                                        borderRadius: '4px',
                                                        lineHeight: '1.2'
                                                    }}>
                                                        预售中
                                                    </div>
                                                )}
                                            </div>

                                            <div className="ad-event-info">
                                                <h3 className="ad-event-title" title={event.title}>{event.title}</h3>
                                                <div className="ad-event-artist">艺人：{artist.name}</div>
                                                <div className={`ad-event-price ${isStatusText ? 'is-status' : ''} ${priceText === '敬请期待' ? 'is-coming-soon' : ''}`}>
                                                    {priceText}
                                                </div>
                                                <div className="ad-event-meta">
                                                    {displayShowTime && dayjs(displayShowTime).isValid()
                                                        ? dayjs(displayShowTime).format('YYYY/MM/DD HH:mm')
                                                        : '时间待定'}
                                                </div>
                                                <div className="ad-event-meta">
                                                    <i className="lucide-map-pin" style={{fontSize: 12, marginRight: 4}}></i>
                                                    [{event.city}] {event.venue}
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>

                            {hasMoreEvents && (
                                <div className="ad-load-more-wrapper" style={{ textAlign: 'center', marginTop: 32 }}>
                                    <Button
                                        className="ad-load-more-btn"
                                        loading={eventsLoadingMore}
                                        onClick={handleLoadMoreEvents}
                                        style={{
                                            minWidth: 160,
                                            height: 40,
                                            borderRadius: 999,
                                            color: '#FF6B80',
                                            borderColor: '#FFB3C0',
                                            fontWeight: 700,
                                            background: '#fff6f8'
                                        }}
                                    >
                                        加载更多
                                    </Button>
                                </div>
                            )}
                        </>
                    ) : (
                        <Empty description={`${artist.name} 最近暂无演出排期哦~`} image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                </div>
            )
        },
        {
            key: 'intro',
            label: '简介',
            children: (
                <div className="ad-intro-section">
                    {artist.description ? (
                        <p className="ad-intro-text">{artist.description}</p>
                    ) : (
                        <Empty description="该音乐人很神秘，暂时没有留下简介~" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                </div>
            )
        }
    ];

    return (
        <ConfigProvider locale={locale}>
            <div className="ad-page-bg">
                <PublicHeader />

                <div className="ad-container">
                    {/* 1. 音乐人头图：现代活力风格 */}
                    <div className="ad-hero-card">
                        <div className="ad-hero-bg-avatar" style={{ backgroundImage: `url(${artist.avatarUrl || 'https://via.placeholder.com/600'})` }} />
                        <div className="ad-hero-orb ad-hero-orb-one" />
                        <div className="ad-hero-orb ad-hero-orb-two" />

                        <div className="ad-hero-avatar-wrap">
                            <div className="ad-hero-avatar-ring">
                                <img
                                    src={artist.avatarUrl || 'https://via.placeholder.com/200'}
                                    alt={artist.name}
                                    className="ad-avatar"
                                />
                            </div>
                        </div>

                        <div className="ad-hero-info">
                            <div className="ad-hero-heat-badge">
                                <FireFilled className="ad-hero-heat-icon" />
                                <span className="ad-hero-heat-label">热度</span>
                                <span className="ad-hero-heat-value">
                                {Number(artist.heatValue || 0).toLocaleString()}
                                </span>
                            </div>

                            <div className="ad-hero-title-row">
                                <h1 className="ad-artist-name">{artist.name}</h1>
                                <Button
                                    className={`ad-follow-btn ${isFavorited ? 'followed' : ''}`}
                                    shape="round"
                                    icon={isFavorited ? <CheckCircleOutlined /> : <HeartOutlined />}
                                    onClick={handleToggleFavorite}
                                >
                                    {isFavorited ? '已关注' : '关注'}
                                </Button>
                            </div>

                            <div className="ad-hero-meta">
        <span className="ad-hero-chip">
            <span className="ad-hero-chip-label">地区</span>
            {artist.region || '未知'}
        </span>

                                <span className="ad-hero-chip">
            <span className="ad-hero-chip-label">风格</span>
                                    {artist.style || '未定'}
        </span>

                                <span className="ad-hero-chip">
            <span className="ad-hero-chip-label">关注</span>
                                    {Number(artist.likeCount || 0).toLocaleString()} 人
        </span>

                            </div>
                        </div>
                    </div>

                    {/* 2. 详情内容区 (包含演出列表) */}
                    <div className="ad-content">
                        <Tabs
                            defaultActiveKey="events"
                            items={tabItems}
                            className="ad-custom-tabs"
                            size="large"
                            tabBarGutter={40}
                        />
                    </div>
                </div>
            </div>
        </ConfigProvider>
    );
};

export default ArtistDetail;