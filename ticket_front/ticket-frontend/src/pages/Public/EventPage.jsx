import React, { useState, useEffect, useMemo } from 'react';
import { DatePicker, Pagination, Spin, Empty, ConfigProvider } from 'antd';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from '../../utils/request';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import locale from 'antd/locale/zh_CN';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './EventPage.css';

const { RangePicker } = DatePicker;

// 预设筛选常量
const CITY_LIST = [
    '全部', '北京', '上海', '广州', '深圳', '武汉', '重庆', '成都', '长沙', '杭州', '南京',
    '澳门', '香港', '台北', '天津', '西安', '苏州', '郑州', '青岛', '合肥', '宁波', '东莞', '佛山', '沈阳',
    '济南', '大连', '厦门', '福州', '哈尔滨', '长春', '石家庄', '南宁', '太原', '贵阳',
    '南昌', '昆明', '无锡', '温州', '珠海', '中山', '海口', '兰州', '呼和浩特', '乌鲁木齐', // 原有城市
    '银川', '西宁', '拉萨', '泉州', '南通', '常州', '烟台', '徐州', '洛阳', '惠州'      // 新增的10个城市
];
const TIME_LIST = [
    { label: '全部', value: 0 },
    { label: '今天', value: 1 },
    { label: '最近一周内', value: 2 },
    { label: '下周内', value: 3 },
    { label: '最近一个月', value: 4 }
];
const STYLE_LIST = ['全部', '古典', '流行', '世界音乐', '独立', '摇滚', '爵士', 'HipHop', '轻音乐', '民谣', '动漫', '电子', '金属', '核', '雷鬼'];

const getStyleTags = (styleText) => {
    if (!styleText) return [];
    return String(styleText)
        .split('/')
        .map(item => item.trim())
        .filter(Boolean)
        .slice(0, 2);
};

const EventsPage = () => {
    const navigate = useNavigate();
    const location = useLocation();

    // 从导航栏搜索跳过来时，可能携带 keyword
    const searchParams = new URLSearchParams(location.search);
    const initialKeyword = searchParams.get('keyword') || '';

    // 筛选状态
    const [city, setCity] = useState('全部');
    const [timeType, setTimeType] = useState(0);
    const [dateRange, setDateRange] = useState([]);
    const [style, setStyle] = useState('全部');
    const [keyword, setKeyword] = useState(initialKeyword);

    // 数据状态
    const [events, setEvents] = useState([]);
    const [loading, setLoading] = useState(false);
    const [pagination, setPagination] = useState({ current: 1, pageSize: 12, total: 0 });

    const fetchEvents = async (page = 1) => {
        setLoading(true);
        try {
            const params = {
                current: page,
                size: pagination.pageSize,
                city,
                style,
                timeType,
                keyword
            };
            if (dateRange && dateRange.length === 2) {
                params.startDate = dateRange[0].format('YYYY-MM-DD');
                params.endDate = dateRange[1].format('YYYY-MM-DD');
            }

            const res = await axios.get('/api/event/page', { params });
            if (res.data.code === 200) {
                setEvents(res.data.data.records);
                setPagination(prev => ({ ...prev, current: page, total: res.data.data.total }));
            }
        } catch (error) {
            console.error('获取演出列表失败', error);
        } finally {
            setLoading(false);
        }
    };

    // 监听 Header 城市切换的广播
    useEffect(() => {
        const handleHeaderSync = (e) => {
            let newHeaderCity = e.detail;
            if (newHeaderCity === '全国' || !newHeaderCity) {
                setCity('全部');
            } else {
                newHeaderCity = newHeaderCity.replace('市', '');
                setCity(newHeaderCity);
            }
        };
        window.addEventListener('headerCityChange', handleHeaderSync);
        return () => window.removeEventListener('headerCityChange', handleHeaderSync);
    }, []);

    const dynamicCityList = useMemo(() => {
        if (city !== '全部' && !CITY_LIST.includes(city)) {
            return [...CITY_LIST, city];
        }
        return CITY_LIST;
    }, [city]);

    // 导航栏 keyword 改变时同步页面筛选条件
    useEffect(() => {
        const nextKeyword = new URLSearchParams(location.search).get('keyword') || '';
        setKeyword(nextKeyword);
    }, [location.search]);

    // 当任何筛选条件发生变化时，回到第一页重新拉取
    useEffect(() => {
        fetchEvents(1);
    }, [city, timeType, dateRange, style, keyword]);

    // 新数据结构：票档只存在于 sessions[*].tickets，不再读取 event.tickets
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

        const validPrices = tickets
            .map(t => Number(t.price))
            .filter(price => Number.isFinite(price));

        if (validPrices.length === 0) return '票档待定';
        return `¥${Math.min(...validPrices)}起`;
    };

    // 获取用于列表展示的演出时间。优先使用 event.showTime；没有时从 sessions 中取最早时间
    const getDisplayShowTime = (event) => {
        if (event?.showTime && dayjs(event.showTime).isValid()) {
            return event.showTime;
        }

        const validSessionTimes = Array.isArray(event?.sessions)
            ? event.sessions
                .map(session => session.showTime)
                .filter(time => time && dayjs(time).isValid())
                .sort((a, b) => dayjs(a).valueOf() - dayjs(b).valueOf())
            : [];

        return validSessionTimes[0] || null;
    };

    // 获取用于判断预售的开票时间。优先使用 event.saleTime；没有时从 sessions 中取最早开票时间
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

    // 根据演出状态展示价格或状态文案
    const getPriceText = (event) => {
        const showTime = getDisplayShowTime(event);

        // 没有拿到任何演出时间时，不能判定为已结束，只能显示敬请期待
        if (!showTime) return '敬请期待';

        if (Number(event.status) === 3) {
            return dayjs(showTime).isAfter(dayjs()) ? '敬请期待' : '已结束';
        }

        return getMinPrice(event);
    };

    // 提取艺人名字
    const getArtistNames = (artists) => {
        if (!artists || artists.length === 0) return '未知艺人';
        return artists.map(a => a.name).join(' / ');
    };

    const getStatusClassName = (event) => {
        const showTime = getDisplayShowTime(event);
        if (!showTime) return 'is-coming-soon';
        if (Number(event.status) !== 3) return '';
        return dayjs(showTime).isAfter(dayjs()) ? 'is-coming-soon' : 'is-ended';
    };

    return (
        <ConfigProvider locale={locale}>
            <div className="events-page-bg">
                <PublicHeader />

                <div className="events-container">
                    <div className="events-page-header">
                        <div>
                            <span className="page-title">演出</span>
                            <div className="page-subtitle">
                                {keyword ? `搜索“${keyword}”相关演出` : '发现近期值得去现场的演出'}
                            </div>
                        </div>
                    </div>

                    {/* 筛选面板 */}
                    <div className="event-filter-panel">
                        <div className="event-filter-row">
                            <span className="event-filter-label">演出城市</span>
                            <div className="event-filter-options">
                                {dynamicCityList.map(c => (
                                    <span
                                        key={c}
                                        className={`event-filter-item ${city === c ? 'active' : ''}`}
                                        onClick={() => setCity(c)}
                                    >
                                        {c}
                                    </span>
                                ))}
                            </div>
                        </div>

                        <div className="event-filter-row">
                            <span className="event-filter-label">演出时间</span>
                            <div className="event-filter-options event-filter-options-with-picker">
                                {TIME_LIST.map(t => (
                                    <span
                                        key={t.value}
                                        className={`event-filter-item ${timeType === t.value && dateRange.length === 0 ? 'active' : ''}`}
                                        onClick={() => { setTimeType(t.value); setDateRange([]); }}
                                    >
                                        {t.label}
                                    </span>
                                ))}
                                <div className="event-filter-datepicker">
                                    <RangePicker
                                        value={dateRange}
                                        onChange={(dates) => { setDateRange(dates || []); setTimeType(0); }}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="event-filter-row no-border">
                            <span className="event-filter-label">演出风格</span>
                            <div className="event-filter-options">
                                {STYLE_LIST.map(s => (
                                    <span
                                        key={s}
                                        className={`event-filter-item ${style === s ? 'active' : ''}`}
                                        onClick={() => setStyle(s)}
                                    >
                                        {s}
                                    </span>
                                ))}
                            </div>
                        </div>
                    </div>

                    {/* 演出卡片网格 */}
                    <Spin spinning={loading}>
                        {events.length > 0 ? (
                            <>
                                <div className="event-list-grid">
                                    {events.map((event, index) => {
                                        const styleTags = getStyleTags(event.style);
                                        const statusClassName = getStatusClassName(event);
                                        const displayShowTime = getDisplayShowTime(event);
                                        const displaySaleTime = getDisplaySaleTime(event);
                                        const isPresale = Number(event.status) === 1 && displaySaleTime && dayjs().isBefore(dayjs(displaySaleTime));
                                        return (
                                            <div
                                                key={event.id}
                                                className="event-list-card"
                                                style={{ '--card-index': index }}
                                                onClick={() => navigate(`/event/${event.id}`)}
                                            >
                                                <div className="event-list-poster-wrapper">
                                                    <img
                                                        src={event.posterUrl || 'https://via.placeholder.com/600x800?text=Event'}
                                                        alt={event.title}
                                                        className="event-list-poster"
                                                    />
                                                    <div className="event-list-image-mask" />
                                                    <div className="event-list-card-glow" />
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
                                                    {styleTags.length > 0 && (
                                                        <div className="event-list-style-tags-on-image">
                                                            {styleTags.map(item => (
                                                                <span key={item} className="event-list-style-tag-on-image">{item}</span>
                                                            ))}
                                                        </div>
                                                    )}

                                                    <div className={`event-list-price-on-poster ${Number(event.status) === 3 ? 'event-list-price-status' : ''} ${statusClassName}`}>
                                                        {getPriceText(event)}
                                                    </div>
                                                </div>

                                                <div className="event-list-info">
                                                    <div className="event-list-title" title={event.title}>{event.title}</div>

                                                    <div className="event-list-artist" title={getArtistNames(event.artists)}>
                                                        {getArtistNames(event.artists)}
                                                    </div>

                                                    <div className="event-list-meta-line">
                                                        {displayShowTime ? dayjs(displayShowTime).format('YYYY/MM/DD HH:mm') : '时间待定'}
                                                    </div>

                                                    <div className="event-list-meta-line" title={`${event.city || ''} ${event.venue || ''}`}>
                                                        [{event.city || '城市待定'}] {event.venue || '场馆待定'}
                                                    </div>

                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>

                                <div className="pagination-wrapper">
                                    <Pagination
                                        current={pagination.current}
                                        pageSize={pagination.pageSize}
                                        total={pagination.total}
                                        onChange={(page) => fetchEvents(page)}
                                        showSizeChanger={false}
                                    />
                                </div>
                            </>
                        ) : (
                            <Empty description="暂无符合条件的演出" style={{ margin: '80px 0' }} />
                        )}
                    </Spin>
                </div>
            </div>
        </ConfigProvider>
    );
};

export default EventsPage;
