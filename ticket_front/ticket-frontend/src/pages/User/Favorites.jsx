import React, { useState, useEffect } from 'react';
import { Tabs, Spin, Empty, ConfigProvider, message, Button } from 'antd';
import { HeartFilled, CheckCircleOutlined } from '@ant-design/icons';
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
                // 假设这是后端返回我收藏的演出的接口
                const res = await axios.get('/api/favorite/events');
                if (res.data.code === 200) setEvents(res.data.data || []);
            } else {
                // 假设这是后端返回我关注的艺人的接口
                const res = await axios.get('/api/favorite/artists');
                if (res.data.code === 200) setArtists(res.data.data || []);
            }
        } catch (error) {
            message.error('获取收藏列表失败');
        } finally {
            setLoading(false);
        }
    };

    const cancelFavorite = async (e, id, type) => {
        e.stopPropagation(); // 阻止卡片点击跳转
        try {
            const res = await axios.post('/api/favorite/toggle', { targetId: id, type });
            if (res.data.code === 200) {
                message.success('已取消');
                // 乐观更新，直接从当前列表中剔除
                if (type === 1) setEvents(events.filter(item => item.id !== id));
                else setArtists(artists.filter(item => item.id !== id));
            }
        } catch (error) {
            message.error('取消失败');
        }
    };

    // Tabs 内容项
    const tabItems = [
        {
            key: 'events',
            label: '想看的演出',
            children: (
                <Spin spinning={loading}>
                    {events.length > 0 ? (
                        <div className="home-event-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: '20px' }}>
                            {events.map((event) => (
                                <div key={event.id} className="home-event-card" onClick={() => navigate(`/event/${event.id}`)}>
                                    <div className="home-event-cover-wrapper">
                                        <img src={event.posterUrl} alt={event.title} className="home-event-cover" />
                                        <div className="home-event-price-on-cover">
                                            {event.showTime && dayjs(event.showTime).isBefore(dayjs()) ? '已结束' : '售票中'}
                                        </div>
                                    </div>
                                    <div className="home-event-info" style={{ position: 'relative' }}>
                                        <h3 className="home-event-title">{event.title}</h3>
                                        <div className="home-event-meta">{event.showTime || '时间待定'}</div>
                                        <div className="home-event-meta">{event.city} {event.venue}</div>

                                        {/* 取消收藏小红心 */}
                                        <HeartFilled
                                            style={{ position: 'absolute', right: 0, bottom: 5, color: '#FF8899', fontSize: 18, cursor: 'pointer' }}
                                            onClick={(e) => cancelFavorite(e, event.id, 1)}
                                            title="取消想看"
                                        />
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <Empty description="暂无想看的演出" style={{ marginTop: 60 }} />
                    )}
                </Spin>
            )
        },
        {
            key: 'artists',
            label: '关注的音乐人',
            children: (
                <Spin spinning={loading}>
                    {artists.length > 0 ? (
                        <div className="artist-list-grid" style={{ gridTemplateColumns: 'repeat(5, 1fr)', gap: '20px' }}>
                            {artists.map((artist) => (
                                <div key={artist.id} className="artist-list-card" onClick={() => navigate(`/artist/${artist.id}`)}>
                                    <div className="artist-list-avatar-wrapper" style={{ height: 'auto', aspectRatio: '1/1' }}>
                                        <img src={artist.avatarUrl} alt={artist.name} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                    </div>
                                    <div className="artist-list-info" style={{ textAlign: 'center', position: 'relative' }}>
                                        <div className="artist-list-name" style={{ fontSize: 16 }}>{artist.name}</div>
                                        <Button
                                            size="small"
                                            shape="round"
                                            icon={<CheckCircleOutlined />}
                                            style={{ marginTop: 10, color: '#999' }}
                                            onClick={(e) => cancelFavorite(e, artist.id, 2)}
                                        >
                                            已关注
                                        </Button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <Empty description="暂无关注的音乐人" style={{ marginTop: 60 }} />
                    )}
                </Spin>
            )
        }
    ];

    return (
        <ConfigProvider locale={locale}>
            <div style={{ backgroundColor: '#f5f7fa', minHeight: '100vh', paddingBottom: 60 }}>
                <PublicHeader />
                <div style={{ maxWidth: 1200, margin: '40px auto 0', backgroundColor: '#fff', padding: '30px 40px', borderRadius: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.05)' }}>
                    <h2 style={{ fontSize: 24, fontWeight: 'bold', marginBottom: 20 }}>我的收藏</h2>
                    <Tabs defaultActiveKey="events" items={tabItems} size="large" tabBarGutter={40} />
                </div>
            </div>
        </ConfigProvider>
    );
};

export default Favorites;