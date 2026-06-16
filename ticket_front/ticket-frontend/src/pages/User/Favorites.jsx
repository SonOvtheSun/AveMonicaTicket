import React, { useState, useEffect } from 'react';
import { Tabs, Spin, Empty, ConfigProvider, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import axios from '../../utils/request';
import locale from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import PublicHeader from '../../components/PublicHeader/PublicHeader';

const Favorites = () => {
    const navigate = useNavigate();
    const [activeTab, setActiveTab] = useState('events');
    const [events, setEvents] = useState([]);
    const [artists, setArtists] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        document.title = '我的收藏 - Ave Monica';
        fetchData(activeTab);
    }, [activeTab]);

    const fetchData = async (tab) => {
        setLoading(true);
        try {
            if (tab === 'events') {
                const res = await axios.get('/api/favorite/events');
                if (res.data.code === 200) {
                    setEvents(res.data.data || []);
                }
            } else {
                const res = await axios.get('/api/favorite/artists');
                if (res.data.code === 200) {
                    setArtists(res.data.data || []);
                }
            }
        } catch (error) {
            message.error('获取收藏列表失败');
        } finally {
            setLoading(false);
        }
    };

    const formatShowTime = (value) => {
        if (!value) {
            return '时间待定';
        }

        const date = dayjs(value);
        if (!date.isValid()) {
            return value;
        }

        return date.format('YYYY.MM.DD HH:mm');
    };

    const renderEventCards = () => (
        <Spin spinning={loading}>
            {events.length > 0 ? (
                <div className="favorites-grid favorites-event-grid">
                    {events.map((event) => (
                        <div
                            key={event.id}
                            className="favorite-card"
                            onClick={() => navigate(`/event/${event.id}`)}
                        >
                            <div className="favorite-poster-wrap">
                                <img
                                    src={event.posterUrl}
                                    alt={event.title}
                                    className="favorite-poster"
                                    loading="lazy"
                                />
                            </div>
                            <div className="favorite-card-body">
                                <div className="favorite-title" title={event.title}>
                                    {event.title}
                                </div>
                                <div className="favorite-time">
                                    {formatShowTime(event.showTime)}
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            ) : (
                <Empty description="暂无想看的演出" className="favorites-empty" />
            )}
        </Spin>
    );

    const renderArtistCards = () => (
        <Spin spinning={loading}>
            {artists.length > 0 ? (
                <div className="favorites-grid favorites-artist-grid">
                    {artists.map((artist) => (
                        <div
                            key={artist.id}
                            className="favorite-card favorite-artist-card"
                            onClick={() => navigate(`/artist/${artist.id}`)}
                        >
                            <div className="favorite-poster-wrap favorite-artist-avatar-wrap">
                                <img
                                    src={artist.avatarUrl}
                                    alt={artist.name}
                                    className="favorite-poster"
                                    loading="lazy"
                                />
                            </div>
                            <div className="favorite-card-body">
                                <div className="favorite-title favorite-artist-name" title={artist.name}>
                                    {artist.name}
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            ) : (
                <Empty description="暂无关注的音乐人" className="favorites-empty" />
            )}
        </Spin>
    );

    const tabItems = [
        {
            key: 'events',
            label: '想看的演出',
            children: renderEventCards()
        },
        {
            key: 'artists',
            label: '关注的音乐人',
            children: renderArtistCards()
        }
    ];

    return (
        <ConfigProvider locale={locale}>
            <div className="favorites-page">
                <PublicHeader />

                <main className="favorites-shell">
                    <div className="favorites-header">
                        <div>
                            <h1>我的收藏</h1>
                        </div>
                    </div>

                    <Tabs
                        activeKey={activeTab}
                        onChange={setActiveTab}
                        items={tabItems}
                        size="large"
                        tabBarGutter={36}
                        className="favorites-tabs"
                    />
                </main>

                <style>{`
                    .favorites-page {
                        min-height: 100vh;
                        padding-bottom: 72px;
                        background:
                            radial-gradient(circle at 8% 12%, rgba(255, 136, 153, 0.16), transparent 30%),
                            radial-gradient(circle at 88% 18%, rgba(23, 185, 185, 0.10), transparent 28%),
                            linear-gradient(180deg, #fff7fa 0%, #f7f8fb 42%, #f5f7fa 100%);
                    }

                    .favorites-shell {
                        width: min(1200px, calc(100% - 48px));
                        margin: 36px auto 0;
                        padding: 34px 38px 42px;
                        border: 1px solid rgba(255, 136, 153, 0.14);
                        border-radius: 24px;
                        background: rgba(255, 255, 255, 0.92);
                        box-shadow: 0 22px 55px rgba(31, 35, 52, 0.08);
                        backdrop-filter: blur(14px);
                    }

                    .favorites-header {
                        display: flex;
                        align-items: flex-end;
                        justify-content: space-between;
                        margin-bottom: 20px;
                    }

                    .favorites-eyebrow {
                        margin-bottom: 6px;
                        color: #ff7f92;
                        font-size: 12px;
                        font-weight: 800;
                        letter-spacing: 0.16em;
                    }

                    .favorites-header h1 {
                        margin: 0;
                        color: #2f2f3a;
                        font-size: 28px;
                        font-weight: 900;
                        letter-spacing: -0.02em;
                    }

                    .favorites-tabs .ant-tabs-nav {
                        margin-bottom: 28px;
                    }

                    .favorites-tabs .ant-tabs-tab {
                        padding: 10px 0;
                        color: #777;
                        font-size: 16px;
                    }

                    .favorites-tabs .ant-tabs-tab-btn {
                        font-weight: 700;
                    }

                    .favorites-tabs .ant-tabs-tab:hover {
                        color: #ff6f84;
                    }

                    .favorites-tabs .ant-tabs-tab.ant-tabs-tab-active .ant-tabs-tab-btn {
                        color: #ff6f84;
                    }

                    .favorites-tabs .ant-tabs-ink-bar {
                        height: 3px;
                        border-radius: 999px;
                        background: linear-gradient(90deg, #FF8899, #ffb4c0);
                    }

                    .favorites-grid {
                        display: grid;
                        gap: 24px;
                    }

                    .favorites-event-grid {
                        grid-template-columns: repeat(4, minmax(0, 1fr));
                    }

                    .favorites-artist-grid {
                        grid-template-columns: repeat(5, minmax(0, 1fr));
                    }

                    .favorite-card {
                        overflow: hidden;
                        border: 1px solid rgba(31, 35, 52, 0.08);
                        border-radius: 18px;
                        background: #fff;
                        cursor: pointer;
                        box-shadow: 0 10px 26px rgba(31, 35, 52, 0.055);
                        transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.2s ease;
                    }

                    .favorite-card:hover {
                        border-color: rgba(255, 136, 153, 0.42);
                        box-shadow: 0 18px 38px rgba(31, 35, 52, 0.10);
                        transform: translateY(-3px);
                    }

                    .favorite-poster-wrap {
                        position: relative;
                        width: 100%;
                        aspect-ratio: 1 / 1.414;
                        overflow: hidden;
                        background: #f3f4f6;
                    }

                    .favorite-artist-avatar-wrap {
                        aspect-ratio: 1 / 1;
                    }

                    .favorite-poster {
                        width: 100%;
                        height: 100%;
                        display: block;
                        object-fit: cover;
                        transition: transform 0.28s ease;
                    }

                    .favorite-card:hover .favorite-poster {
                        transform: scale(1.035);
                    }

                    .favorite-card-body {
                        padding: 14px 14px 16px;
                    }

                    .favorite-title {
                        color: #2f2f3a;
                        font-size: 15px;
                        font-weight: 800;
                        line-height: 1.45;
                        overflow: hidden;
                        display: -webkit-box;
                        -webkit-line-clamp: 2;
                        -webkit-box-orient: vertical;
                        min-height: 42px;
                    }

                    .favorite-time {
                        margin-top: 8px;
                        color: #8b8b96;
                        font-size: 13px;
                        font-weight: 600;
                        line-height: 1.4;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }

                    .favorite-artist-card .favorite-card-body {
                        padding: 14px 12px 16px;
                        text-align: center;
                    }

                    .favorite-artist-name {
                        min-height: auto;
                        -webkit-line-clamp: 1;
                    }

                    .favorites-empty {
                        margin: 80px 0 60px;
                    }

                    @media (max-width: 1100px) {
                        .favorites-event-grid {
                            grid-template-columns: repeat(3, minmax(0, 1fr));
                        }

                        .favorites-artist-grid {
                            grid-template-columns: repeat(4, minmax(0, 1fr));
                        }
                    }

                    @media (max-width: 820px) {
                        .favorites-shell {
                            width: calc(100% - 28px);
                            margin-top: 20px;
                            padding: 24px 18px 32px;
                            border-radius: 18px;
                        }

                        .favorites-event-grid,
                        .favorites-artist-grid {
                            grid-template-columns: repeat(2, minmax(0, 1fr));
                            gap: 16px;
                        }

                        .favorites-header h1 {
                            font-size: 24px;
                        }
                    }

                    @media (max-width: 480px) {
                        .favorites-event-grid,
                        .favorites-artist-grid {
                            grid-template-columns: 1fr;
                        }
                    }
                `}</style>
            </div>
        </ConfigProvider>
    );
};

export default Favorites;
