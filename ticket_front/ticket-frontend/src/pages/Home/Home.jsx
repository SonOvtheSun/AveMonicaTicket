import React, { useState, useEffect } from 'react';
import { Layout, Carousel, Button, Spin, Empty, message } from 'antd';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import AiAssistantFloat from '../../components/AiAssistantFloat/AiAssistantFloat';
import dayjs from 'dayjs';
import axios from '../../utils/request';
import './Home.css';

const { Content } = Layout;

const Home = () => {
    const navigate = useNavigate();

    const [upcomingEvents, setUpcomingEvents] = useState([]);
    const [loadingEvents, setLoadingEvents] = useState(true);

    const [recommendEvents, setRecommendEvents] = useState([]);
    const [loadingRecommend, setLoadingRecommend] = useState(true);

    const [banners, setBanners] = useState([]);
    const [currentCity, setCurrentCity] = useState(localStorage.getItem('currentCity') || '全国');

    useEffect(() => {
        document.title = 'Ave Monica - 与同好们同聚';

        const handleCityChange = (e) => {
            setCurrentCity(e.detail || '全国');
        };

        // PublicHeader 切换地区后会广播 headerCityChange，监听它即可立即刷新首页数据
        window.addEventListener('headerCityChange', handleCityChange);

        // 兼容旧的广播名，防止其他组件仍然派发 cityChanged
        window.addEventListener('cityChanged', handleCityChange);

        return () => {
            window.removeEventListener('headerCityChange', handleCityChange);
            window.removeEventListener('cityChanged', handleCityChange);
        };
    }, []);

    useEffect(() => {
        const fetchEvents = async () => {
            setLoadingEvents(true);
            try {
                const res = await axios.get('/api/event/upcoming', {
                    params: { city: currentCity }
                });
                if (res.data.code === 200) {
                    setUpcomingEvents(res.data.data || []);
                } else {
                    message.error('获取演出数据失败');
                }
            } catch (error) {
                console.error('获取即将上演数据异常', error);
            } finally {
                setLoadingEvents(false);
            }
        };

        const fetchBanners = async () => {
            try {
                const res = await axios.get('/api/event/banner/active');
                if (res.data.code === 200) {
                    setBanners(res.data.data || []);
                }
            } catch (error) {
                console.error('拉取横幅失败', error);
            }
        };

        const fetchRecommendEvents = async () => {
            setLoadingRecommend(true);
            try {
                const res = await axios.get('/api/recommend/events', {
                    params: {
                        scene: 'home',
                        size: 10,
                        city: currentCity
                    }
                });

                if (res.data.code === 200) {
                    setRecommendEvents(res.data.data || []);
                } else {
                    setRecommendEvents([]);
                }
            } catch (error) {
                console.error('获取为您推荐数据异常', error);
                setRecommendEvents([]);
            } finally {
                setLoadingRecommend(false);
            }
        };

        fetchEvents();
        fetchBanners();
        fetchRecommendEvents();
    }, [currentCity]);

    const getEventTickets = (event) => {
        if (!event) return [];

        if (Array.isArray(event.tickets) && event.tickets.length > 0) {
            return event.tickets;
        }

        if (Array.isArray(event.sessions)) {
            return event.sessions.flatMap(session => Array.isArray(session.tickets) ? session.tickets : []);
        }

        return [];
    };

    const CustomPrevArrow = ({ className, style, onClick }) => (
        <div className={className} style={style} onClick={onClick}>
            <ChevronLeft size={24} />
        </div>
    );

    const CustomNextArrow = ({ className, style, onClick }) => (
        <div className={className} style={style} onClick={onClick}>
            <ChevronRight size={24} />
        </div>
    );


    const getMinPrice = (event) => {
        const tickets = getEventTickets(event);

        if (tickets.length === 0) {
            return '票档待定';
        }

        const prices = tickets
            .map(t => Number(t.price))
            .filter(price => Number.isFinite(price));

        if (prices.length === 0) {
            return '票档待定';
        }

        return `¥${Math.min(...prices)}起`;
    };

    // 首页卡片摘要时间：优先用 event.showTime，没有则从 sessions 中取最早演出时间
    const getDisplayShowTime = (event) => {
        if (event?.showTime && dayjs(event.showTime).isValid()) {
            return event.showTime;
        }

        const sessionTimes = Array.isArray(event?.sessions)
            ? event.sessions
                .map(session => session.showTime)
                .filter(time => time && dayjs(time).isValid())
                .sort((a, b) => dayjs(a).valueOf() - dayjs(b).valueOf())
            : [];

        return sessionTimes[0] || null;
    };

    // 首页预售判断：优先用 event.saleTime，没有则从 sessions 中取最早开票时间
    const getDisplaySaleTime = (event) => {
        if (event?.saleTime && dayjs(event.saleTime).isValid()) {
            return event.saleTime;
        }

        const saleTimes = Array.isArray(event?.sessions)
            ? event.sessions
                .map(session => session.saleTime)
                .filter(time => time && dayjs(time).isValid())
                .sort((a, b) => dayjs(a).valueOf() - dayjs(b).valueOf())
            : [];

        return saleTimes[0] || null;
    };

    const getPriceText = (event) => {
        if (Number(event.status) === 3) {
            const displayShowTime = getDisplayShowTime(event);
            const showTime = displayShowTime ? new Date(displayShowTime).getTime() : NaN;
            return Number.isFinite(showTime) && showTime > Date.now() ? '敬请期待' : '已结束';
        }

        return getMinPrice(event);
    };

    const getStyleTags = (styleText) => {
        if (!styleText) return [];
        return String(styleText)
            .split('/')
            .map(item => item.trim())
            .filter(Boolean)
            .slice(0, 2);
    };

    const renderEventCard = (event, index) => {
        const styleTags = getStyleTags(event.style);
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
                className="home-event-card"
                style={{ '--card-index': index }}
                onClick={() => navigate(`/event/${event.id}`)}
            >
                <div className="home-event-cover-wrapper">
                    <img
                        src={event.posterUrl || 'https://via.placeholder.com/300x424?text=No+Poster'}
                        alt={event.title}
                        className="home-event-cover"
                    />
                    <div className="home-event-image-mask" />

                    <div className="home-event-style-tags">
                        {styleTags.length > 0 ? styleTags.map(tag => (
                            <span key={tag} className="home-event-style-tag">{tag}</span>
                        )) : (
                            <span className="home-event-style-tag">现场</span>
                        )}
                    </div>

                    <div className={`home-event-price-on-cover ${isStatusText ? 'is-status' : ''} ${priceText === '敬请期待' ? 'is-coming-soon' : ''} ${priceText === '已结束' ? 'is-ended' : ''}`}>
                        {priceText}
                    </div>

                    {isPresale && (
                        <div className="home-event-presale-tag">
                            预售中
                        </div>
                    )}
                </div>

                <div className="home-event-info">
                    <h3 className="home-event-title" title={event.title}>{event.title}</h3>
                    <div className="home-event-meta">
                        {displayShowTime ? displayShowTime.substring(0, 16) : '时间待定'}
                    </div>
                    <div className="home-event-meta">
                        [{event.city || currentCity || '全国'}] {event.venue || '场馆待定'}
                    </div>
                </div>
            </div>
        );
    };

    return (
        <Layout className="home-layout">
            <PublicHeader />
            <Content className="home-content">
                <section className="home-banner-section">
                    <Carousel autoplay effect="fade" arrows prevArrow={<CustomPrevArrow />} nextArrow={<CustomNextArrow />}>
                        {Array.from({ length: Math.max(banners.length, 1) }).map((_, index) => {
                            const banner = banners[index];
                            return (
                                <div key={banner?.id || `placeholder-${index}`} className="home-banner-slide">
                                    <div
                                        className="home-banner-image-frame"
                                        style={{
                                            cursor: banner?.eventId ? 'pointer' : 'default'
                                        }}
                                        onClick={() => banner?.eventId && navigate(`/event/${banner.eventId}`)}
                                    >
                                        <img
                                            src={banner?.posterUrl || '/uploads/poster/defalut.png'}
                                            alt={banner?.title || '首页横幅'}
                                            className="home-banner-original-img"
                                            loading={index === 0 ? 'eager' : 'lazy'}
                                            decoding="async"
                                        />
                                    </div>
                                </div>
                            );
                        })}
                    </Carousel>
                </section>



                <section className="home-event-section home-recommend-section">
                    <div className="home-section-header">
                        <div>
                            <h2 className="home-section-title">
                                为您推荐
                            </h2>
                            <p className="home-section-subtitle">
                                {localStorage.getItem('token')
                                    ? '根据偏好生成'
                                    : `${currentCity === '全国' ? '全国' : currentCity} · 热门精选推荐`}
                            </p>
                        </div>
                        <Button className="home-section-more" onClick={() => navigate('/events')}>
                            发现更多
                        </Button>
                    </div>

                    {loadingRecommend ? (
                        <div className="home-loading-container">
                            <Spin size="large" tip="正在生成您的推荐..." />
                        </div>
                    ) : recommendEvents.length > 0 ? (
                        <div className="home-event-grid">
                            {recommendEvents.map((event, index) => renderEventCard(event, index))}
                        </div>
                    ) : (
                        <Empty description="暂无推荐内容，先浏览几场演出试试吧~" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                </section>

                <section className="home-event-section">
                    <div className="home-section-header">
                        <div>
                            <h2 className="home-section-title">即将上演</h2>
                            <p className="home-section-subtitle">
                                {currentCity === '全国' ? '全国' : currentCity} · 精选演出日程
                            </p>
                        </div>
                        <Button className="home-section-more" onClick={() => navigate('/events')}>
                            查看更多
                        </Button>
                    </div>

                    {loadingEvents ? (
                        <div className="home-loading-container">
                            <Spin size="large" tip="正在为您拉取最新演出..." />
                        </div>
                    ) : upcomingEvents.length > 0 ? (
                        <div className="home-event-grid">
                            {upcomingEvents.map((event, index) => renderEventCard(event, index))}
                        </div>
                    ) : (
                        <Empty description="最近暂无即将上演的演出哦~" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                </section>
            </Content>

            <AiAssistantFloat
                currentCity={currentCity}
                onOpenEvent={(eventId) => navigate(`/event/${eventId}`)}
            />
        </Layout>
    );
};

export default Home;
