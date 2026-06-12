import React, { useEffect, useState } from 'react';
import { ConfigProvider, Empty, Pagination, Spin, Tabs } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import axios from '../../utils/request';
import dayjs from 'dayjs';
import locale from 'antd/locale/zh_CN';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './SearchResultPage.css';

const getStyleTags = (styleText) => {
    if (!styleText) return ['流派未定'];
    return String(styleText)
        .split('/')
        .map(item => item.trim())
        .filter(Boolean)
        .slice(0, 2);
};

const SearchResultPage = () => {
    const navigate = useNavigate();
    const location = useLocation();

    const searchParams = new URLSearchParams(location.search);
    const keyword = searchParams.get('keyword')?.trim() || '';
    const queryType = searchParams.get('type');
    const initialTab = queryType === 'artist' || queryType === 'artists' ? 'artists' : 'events';

    const [activeTab, setActiveTab] = useState(initialTab);

    const [events, setEvents] = useState([]);
    const [artists, setArtists] = useState([]);

    const [loadingEvents, setLoadingEvents] = useState(false);
    const [loadingArtists, setLoadingArtists] = useState(false);

    const [currentCity, setCurrentCity] = useState(localStorage.getItem('currentCity') || '全国');

    const [eventPagination, setEventPagination] = useState({ current: 1, pageSize: 12, total: 0 });
    const [artistPagination, setArtistPagination] = useState({ current: 1, pageSize: 18, total: 0 });

    const getSafeTotal = (pageData, records) => {
        const backendTotal = Number(pageData?.total);
        return backendTotal > 0 ? backendTotal : records.length;
    };

    const fetchEventResults = async (page = 1) => {
        if (!keyword) {
            setEvents([]);
            setEventPagination(prev => ({ ...prev, current: 1, total: 0 }));
            return;
        }

        setLoadingEvents(true);
        try {
            const res = await axios.get('/api/event/page', {
                params: {
                    current: page,
                    size: eventPagination.pageSize,
                    keyword,
                    city: currentCity
                }
            });

            if (res.data.code === 200) {
                const pageData = res.data.data || {};
                const records = pageData.records || [];

                setEvents(records);
                setEventPagination(prev => ({
                    ...prev,
                    current: page,
                    total: getSafeTotal(pageData, records)
                }));
            }
        } catch (error) {
            console.error('搜索演出失败', error);
        } finally {
            setLoadingEvents(false);
        }
    };

    const fetchArtistResults = async (page = 1) => {
        if (!keyword) {
            setArtists([]);
            setArtistPagination(prev => ({ ...prev, current: 1, total: 0 }));
            return;
        }

        setLoadingArtists(true);
        try {
            const res = await axios.get('/api/artist/page', {
                params: {
                    current: page,
                    size: artistPagination.pageSize,
                    keyword
                }
            });

            if (res.data.code === 200) {
                const pageData = res.data.data || {};
                const records = pageData.records || [];

                setArtists(records);
                setArtistPagination(prev => ({
                    ...prev,
                    current: page,
                    total: getSafeTotal(pageData, records)
                }));
            }
        } catch (error) {
            console.error('搜索音乐人失败', error);
        } finally {
            setLoadingArtists(false);
        }
    };

    useEffect(() => {
        document.title = keyword ? `搜索 ${keyword} - Ave Monica` : '搜索 - Ave Monica';
    }, [keyword]);

    useEffect(() => {
        setActiveTab(initialTab);
    }, [location.search]);

    useEffect(() => {
        const handleCityChange = (e) => {
            setCurrentCity(e.detail || '全国');
        };

        window.addEventListener('headerCityChange', handleCityChange);
        window.addEventListener('cityChanged', handleCityChange);

        return () => {
            window.removeEventListener('headerCityChange', handleCityChange);
            window.removeEventListener('cityChanged', handleCityChange);
        };
    }, []);

    // 演出结果：受 keyword + 当前城市影响
    useEffect(() => {
        fetchEventResults(1);
    }, [keyword, currentCity]);

    // 音乐人结果：只受 keyword 影响
    useEffect(() => {
        fetchArtistResults(1);
    }, [keyword]);

    const handleTabChange = (key) => {
        setActiveTab(key);

        const nextParams = new URLSearchParams(location.search);
        nextParams.set('type', key === 'artists' ? 'artist' : 'event');

        navigate({
            pathname: location.pathname,
            search: nextParams.toString()
        }, { replace: true });
    };

    const getMinPrice = (tickets) => {
        if (!tickets || tickets.length === 0) return '票档待定';

        const validPrices = tickets
            .map(t => Number(t.price))
            .filter(price => Number.isFinite(price));

        if (validPrices.length === 0) return '票档待定';

        return `¥${Math.min(...validPrices)}起`;
    };

    const getPriceText = (event) => {
        if (Number(event.status) === 3) {
            return dayjs(event.showTime).isAfter(dayjs()) ? '敬请期待' : '已结束';
        }

        return getMinPrice(event.tickets);
    };

    const getStatusClassName = (event) => {
        if (Number(event.status) !== 3) return '';
        return dayjs(event.showTime).isAfter(dayjs()) ? 'is-coming-soon' : 'is-ended';
    };

    const getArtistNames = (artistList) => {
        if (!artistList || artistList.length === 0) return '未知艺人';
        return artistList.map(a => a.name).join(' / ');
    };

    const renderEventResults = () => (
        <Spin spinning={loadingEvents}>
            {events.length > 0 ? (
                <>
                    <div className="search-event-grid">
                        {events.map((event, index) => {
                            const styleTags = getStyleTags(event.style);
                            const priceText = getPriceText(event);
                            const statusClassName = getStatusClassName(event);
                            const isStatusText = priceText === '敬请期待' || priceText === '已结束' || priceText === '票档待定';

                            return (
                                <div
                                    key={event.id}
                                    className="search-event-card"
                                    style={{ '--card-index': index }}
                                    onClick={() => navigate(`/event/${event.id}`)}
                                >
                                    <div className="search-event-poster-wrapper">
                                        <img
                                            src={event.posterUrl || 'https://via.placeholder.com/600x848?text=Event'}
                                            alt={event.title}
                                            className="search-event-poster"
                                        />
                                        <div className="search-event-image-mask" />
                                        <div className="search-event-hover-action">查看详情</div>

                                        {styleTags.length > 0 && (
                                            <div className="search-event-style-tags">
                                                {styleTags.map(item => (
                                                    <span key={item} className="search-event-style-tag">{item}</span>
                                                ))}
                                            </div>
                                        )}

                                        <div className={`search-event-price-on-poster ${isStatusText ? 'is-status' : ''} ${statusClassName}`}>
                                            {priceText}
                                        </div>
                                    </div>

                                    <div className="search-event-info">
                                        <div className="search-event-title" title={event.title}>{event.title}</div>

                                        <div className="search-event-artist" title={getArtistNames(event.artists)}>
                                            {getArtistNames(event.artists)}
                                        </div>

                                        <div className="search-event-meta">
                                            {event.showTime ? dayjs(event.showTime).format('YYYY/MM/DD HH:mm') : '时间待定'}
                                        </div>

                                        <div className="search-event-meta" title={`${event.city || ''} ${event.venue || ''}`}>
                                            [{event.city || '城市待定'}] {event.venue || '场馆待定'}
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>

                    <div className="search-pagination-wrapper">
                        <Pagination
                            current={eventPagination.current}
                            pageSize={eventPagination.pageSize}
                            total={eventPagination.total}
                            onChange={(page) => fetchEventResults(page)}
                            showSizeChanger={false}
                        />
                    </div>
                </>
            ) : (
                <Empty
                    description={keyword ? `暂无“${keyword}”相关演出` : '请输入关键词后搜索'}
                    style={{ margin: '90px 0' }}
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                />
            )}
        </Spin>
    );

    const renderArtistResults = () => (
        <Spin spinning={loadingArtists}>
            {artists.length > 0 ? (
                <>
                    <div className="search-artist-grid">
                        {artists.map((artist, index) => {
                            const styleTags = getStyleTags(artist.style);

                            return (
                                <div
                                    key={artist.id}
                                    className="search-artist-card"
                                    style={{ '--card-index': index }}
                                    onClick={() => navigate(`/artist/${artist.id}`)}
                                >
                                    <div className="search-artist-avatar-wrapper">
                                        <img
                                            src={artist.avatarUrl || 'https://via.placeholder.com/300?text=Artist'}
                                            alt={artist.name}
                                        />
                                        <div className="search-artist-image-mask" />
                                        <div className="search-artist-card-glow" />
                                        <div className="search-artist-hover-action">查看主页</div>
                                    </div>

                                    <div className="search-artist-info">
                                        <div className="search-artist-name-row">
                                            <div className="search-artist-name" title={artist.name}>{artist.name}</div>
                                        </div>

                                        <div className="search-artist-style-tags">
                                            {styleTags.map(item => (
                                                <span key={item} className="search-artist-style-tag">{item}</span>
                                            ))}
                                        </div>

                                        <div className="search-artist-region-line">
                                            {artist.region || '地区未设置'}
                                        </div>

                                        <div className="search-artist-recent-shows">
                                            最近有 <span>{artist.recentEventCount ?? 0}</span> 场演出
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>

                    <div className="search-pagination-wrapper">
                        <Pagination
                            current={artistPagination.current}
                            pageSize={artistPagination.pageSize}
                            total={artistPagination.total}
                            onChange={(page) => fetchArtistResults(page)}
                            showSizeChanger={false}
                        />
                    </div>
                </>
            ) : (
                <Empty
                    description={keyword ? `暂无“${keyword}”相关音乐人` : '请输入关键词后搜索'}
                    style={{ margin: '90px 0' }}
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                />
            )}
        </Spin>
    );

    const activeTotal = activeTab === 'artists' ? artistPagination.total : eventPagination.total;
    const activeTypeText = activeTab === 'artists' ? '音乐人' : '演出';

    return (
        <ConfigProvider locale={locale}>
            <div className="search-page-bg">
                <PublicHeader />

                <div className="search-container">
                    <div className="search-page-header">
                        <div>
                            <span className="search-page-title">搜索结果</span>
                            <div className="search-page-subtitle">
                                {keyword
                                    ? `“${keyword}”相关${activeTypeText}${activeTab === 'events' ? ` · ${currentCity === '全国' ? '全国' : currentCity}` : ''}`
                                    : '请输入演出名称或艺人名称进行搜索'}
                            </div>
                        </div>

                        {keyword && (
                            <div className="search-total-pill">
                                共 <span>{activeTotal}</span> 个{activeTypeText}结果
                            </div>
                        )}
                    </div>

                    <Tabs
                        className="search-type-tabs"
                        activeKey={activeTab}
                        onChange={handleTabChange}
                        items={[
                            {
                                key: 'events',
                                label: `演出 ${eventPagination.total ? `(${eventPagination.total})` : ''}`,
                                children: renderEventResults()
                            },
                            {
                                key: 'artists',
                                label: `音乐人 ${artistPagination.total ? `(${artistPagination.total})` : ''}`,
                                children: renderArtistResults()
                            }
                        ]}
                    />
                </div>
            </div>
        </ConfigProvider>
    );
};

export default SearchResultPage;
