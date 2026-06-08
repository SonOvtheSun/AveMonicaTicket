import React, { useState, useEffect } from 'react';
import { DatePicker, Pagination, Spin, Empty, ConfigProvider } from 'antd';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from 'axios';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import locale from 'antd/locale/zh_CN';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './EventPage.css';

const { RangePicker } = DatePicker;

// 预设筛选常量
const CITY_LIST = ['全部', '北京', '上海', '广州', '深圳', '武汉', '重庆', '成都', '长沙', '杭州', '南京'];
const TIME_LIST = [{label: '全部', value: 0}, {label: '今天', value: 1}, {label: '最近一周内', value: 2}, {label: '下周内', value: 3}, {label: '最近一个月', value: 4}];
const STYLE_LIST = ['全部', '古典', '流行', '世界音乐', '独立', '摇滚', '爵士', 'HipHop', '轻音乐', '民谣', '动漫', '电子', '金属', '二次元'];

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

    // 当任何筛选条件发生变化时，回到第一页重新拉取
    useEffect(() => {
        fetchEvents(1);
    }, [city, timeType, dateRange, style, keyword]);

    // 计算最低票价
    const getMinPrice = (tickets) => {
        if (!tickets || tickets.length === 0) return '暂未设置票档';
        const min = Math.min(...tickets.map(t => t.price));
        return `¥${min}起`;
    };

    // 根据演出状态展示价格或状态文案
    const getPriceText = (event) => {
        // status = 3 不展示价格，改成展示状态
        if (Number(event.status) === 3) {
            return dayjs(event.showTime).isAfter(dayjs()) ? '敬请期待' : '已结束';
        }
        return getMinPrice(event.tickets);
    };

    // 提取艺人名字
    const getArtistNames = (artists) => {
        if (!artists || artists.length === 0) return '未知艺人';
        return artists.map(a => a.name).join(' / ');
    };

    return (
        <ConfigProvider locale={locale}>
            <div className="events-page-bg">
                <PublicHeader />

                <div className="events-container">
                    {/* 1. 秀动风格的筛选面板 */}
                    <div className="filter-panel">
                        <div className="filter-row">
                            <span className="filter-label">演出城市</span>
                            <div className="filter-options">
                                {CITY_LIST.map(c => (
                                    <span key={c} className={`filter-item ${city === c ? 'active' : ''}`} onClick={() => setCity(c)}>
                                        {c}
                                    </span>
                                ))}
                            </div>
                        </div>

                        <div className="filter-row">
                            <span className="filter-label">演出时间</span>
                            <div className="filter-options">
                                {TIME_LIST.map(t => (
                                    <span key={t.value} className={`filter-item ${timeType === t.value && dateRange.length === 0 ? 'active' : ''}`}
                                          onClick={() => { setTimeType(t.value); setDateRange([]); }}>
                                        {t.label}
                                    </span>
                                ))}
                                <div className="filter-datepicker">
                                    <RangePicker
                                        value={dateRange}
                                        onChange={(dates) => { setDateRange(dates || []); setTimeType(0); }}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="filter-row no-border">
                            <span className="filter-label">演出风格</span>
                            <div className="filter-options">
                                {STYLE_LIST.map(s => (
                                    <span key={s} className={`filter-item ${style === s ? 'active' : ''}`} onClick={() => setStyle(s)}>
                                        {s}
                                    </span>
                                ))}
                            </div>
                        </div>
                    </div>

                    {/* 2. 演出卡片网格 */}
                    <Spin spinning={loading}>
                        {events.length > 0 ? (
                            <>
                                <div className="event-grid">
                                    {events.map(event => (
                                        <div key={event.id} className="event-card" onClick={() => navigate(`/event/${event.id}`)}>
                                            <div className="card-poster-wrapper">
                                                <img src={event.posterUrl} alt={event.title} className="card-poster" />
                                                {event.style && <div className="card-style-tag">{event.style}</div>}
                                            </div>
                                            <div className="card-info">
                                                <div className="card-title" title={event.title}>{event.title}</div>
                                                <div className="card-artist">艺人: {getArtistNames(event.artists)}</div>
                                                <div className={`card-price ${Number(event.status) === 3 ? 'card-price-status' : ''}`}>{getPriceText(event)}</div>
                                                <div className="card-time">{dayjs(event.showTime).format('YYYY/MM/DD HH:mm')}</div>
                                                <div className="card-venue">
                                                    <i className="lucide-map-pin" style={{fontSize: 12, marginRight: 4}}></i>
                                                    [{event.city}] {event.venue}
                                                </div>
                                            </div>
                                        </div>
                                    ))}
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