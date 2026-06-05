import React, { useState, useEffect } from 'react';
import { Layout, Carousel, Input, Dropdown, Avatar, Row, Col, Button, Spin, Empty, message } from 'antd';
import { Search, MapPin, ChevronDown, User, FileText, Heart, LogOut, ChevronLeft, ChevronRight, Settings } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import axios from 'axios';
import './Home.css';

const { Header, Content } = Layout;

const Home = () => {
    const navigate = useNavigate();

    // 演出数据状态
    const [upcomingEvents, setUpcomingEvents] = useState([]);
    const [loadingEvents, setLoadingEvents] = useState(true);

    // 🚨 1. 独立维护当前城市状态，默认从 localStorage 拿
    const [currentCity, setCurrentCity] = useState(localStorage.getItem('currentCity') || '全国');

    // 🚨 2. 全局监听顶部 Header 派发的城市变化事件
    useEffect(() => {
        document.title = "Ave Monica - 与同好们同聚";

        const handleCityChange = (e) => {
            setCurrentCity(e.detail);
        };

        window.addEventListener('cityChanged', handleCityChange);
        return () => window.removeEventListener('cityChanged', handleCityChange);
    }, []);

    // 初始化：获取用户信息 & 获取即将上演的演出
    useEffect(() => {
        // 2. 获取首页演出数据 (调用我们刚才写的公开接口)
        const fetchEvents = async () => {
            try {
                console.log("当前请求的城市参数:", currentCity);
                const res = await axios.get('/api/event/upcoming',{
                    params: {city:currentCity}
                });
                if (res.data.code === 200) {
                    setUpcomingEvents(res.data.data);
                } else {
                    message.error('获取演出数据失败');
                }
            } catch (error) {
                console.error('获取即将上演数据异常', error);
            } finally {
                setLoadingEvents(false);
            }
        };

        fetchEvents();
    }, [currentCity]);

    // 轮播图自定义箭头
    const CustomPrevArrow = (props) => {
        const { className, style, onClick } = props;
        return (
            <div className={className} style={style} onClick={onClick}>
                <ChevronLeft size={24} />
            </div>
        );
    };

    const CustomNextArrow = (props) => {
        const { className, style, onClick } = props;
        return (
            <div className={className} style={style} onClick={onClick}>
                <ChevronRight size={24} />
            </div>
        );
    };

    // 辅助函数：计算演出最低票价
    const getMinPrice = (tickets) => {
        if (!tickets || tickets.length === 0) return '--';
        const prices = tickets.map(t => t.price);
        return Math.min(...prices);
    };

    return (
        <Layout className="home-layout">
            <PublicHeader />
            <Content className="home-content">
                {/* --- 顶部 Banner --- */}
                <div className="banner-section">
                    <Carousel
                        autoplay
                        effect="fade"
                        arrows={true}
                        prevArrow={<CustomPrevArrow />}
                        nextArrow={<CustomNextArrow />}
                    >
                        <div className="banner-slide">
                            <div className="banner-img" style={{ backgroundImage: `url(https://picsum.photos/1200/200?random=10)` }} />
                        </div>
                        <div className="banner-slide">
                            <div className="banner-img" style={{ backgroundImage: `url(https://picsum.photos/1200/200?random=11)` }} />
                        </div>
                    </Carousel>
                </div>

                {/* --- 演出列表区域 --- */}
                <div className="event-section">
                    <h2 className="section-title">
                        <span className="title-icon">🤘</span> 即将上演
                    </h2>

                    {loadingEvents ? (
                        <div className="loading-container">
                            <Spin size="large" tip="正在为您拉取最新演出..." />
                        </div>
                    ) : upcomingEvents.length > 0 ? (
                        // 👇 核心替换：抛弃 Row 和 Col，使用原生 Grid
                        <div className="event-grid">
                            {upcomingEvents.map((event, index) => (
                                <div
                                    key={event.id}
                                    className="event-card animate-fade-in"
                                    style={{ animationDelay: `${index * 0.08}s` }}
                                    onClick={() => navigate(`/event/${event.id}`)}
                                >
                                    <div className="event-cover-wrapper">
                                        <img
                                            src={event.posterUrl || 'https://via.placeholder.com/300x400?text=No+Poster'}
                                            alt={event.title}
                                            className="event-cover"
                                        />
                                        <div className="event-price">
                                            <span className="price-symbol">¥</span>
                                            <span className="price-num">{getMinPrice(event.tickets)}</span>
                                            <span className="price-suffix">起</span>
                                        </div>
                                    </div>
                                    <div className="event-info">
                                        <h3 className="event-title">{event.title}</h3>

                                        {/* 👇 使用刚刚新定义的 class，让文字缩小一行显示 */}
                                        <div className="event-info-sub">
                                            {event.showTime ? event.showTime.substring(0, 10) : '时间待定'}
                                            &nbsp;|&nbsp;
                                            {event.venue || '场馆待定'}
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <Empty description="最近暂无即将上演的演出哦~" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    )}
                </div>
            </Content>
        </Layout>
    );
};

export default Home;