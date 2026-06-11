import React, { useState, useEffect } from 'react';
import { Pagination, Spin, Empty, Input, ConfigProvider } from 'antd';
import { useNavigate } from 'react-router-dom';
import axios from '../../utils/request';
import locale from 'antd/locale/zh_CN';
import PublicHeader from '../../components/PublicHeader/PublicHeader'; // 替换为你的路径
import './ArtistsPage.css';
import dayjs from "dayjs";

const { Search } = Input;

// 根据截图提取的丰富的音乐流派
const STYLE_LIST = [
    '全部', '流行', '世界音乐', '独立', '摇滚', '爵士', 'HipHop', '轻音乐', '民谣',
    '动漫', '朋克', '电子', '金属', '核', '雷鬼'
];

const getStyleTags = (styleText) => {
    if (!styleText) return ['流派未定'];
    return String(styleText)
        .split('/')
        .map(item => item.trim())
        .filter(Boolean)
        .slice(0, 2);
};

const ArtistsPage = () => {
    const navigate = useNavigate();

    // 状态管理
    const [style, setStyle] = useState('全部');
    const [keyword, setKeyword] = useState('');

    const [artists, setArtists] = useState([]);
    const [loading, setLoading] = useState(false);
    const [pagination, setPagination] = useState({ current: 1, pageSize: 18, total: 0 }); // 每页18个，正好排满3行

    const fetchArtists = async (page = 1, currentKeyword = keyword) => {
        setLoading(true);
        try {
            const res = await axios.get('/api/artist/page', {
                params: {
                    current: page,
                    size: pagination.pageSize,
                    style: style,
                    keyword: currentKeyword
                }
            });
            if (res.data.code === 200) {
                setArtists(res.data.data.records);
                setPagination(prev => ({ ...prev, current: page, total: res.data.data.total }));
            }
        } catch (error) {
            console.error('获取音乐人列表失败', error);
        } finally {
            setLoading(false);
        }
    };

    // 当流派筛选发生变化时，回到第一页重新获取
    useEffect(() => {
        fetchArtists(1, keyword);
    }, [style]);

    // 搜索框触发
    const handleSearch = (value) => {
        setKeyword(value);
        fetchArtists(1, value);
    };


    return (
        <ConfigProvider locale={locale}>
            <div className="artists-page-bg">
                <PublicHeader />

                <div className="artists-container">
                    {/* 1. 顶部搜索栏与标题 */}
                    <div className="artists-page-header">
                        <div>
                            <span className="page-title">音乐人</span>
                            <div className="page-subtitle">发现正在发生的现场与声音</div>
                        </div>
                        {/*<Search*/}
                        {/*    placeholder="搜索艺人 / 乐队 / 地区"*/}
                        {/*    allowClear*/}
                        {/*    onSearch={handleSearch}*/}
                        {/*    className="artist-search-bar"*/}
                        {/*/>*/}
                    </div>

                    {/* 2. 流派标签平铺筛选区 */}
                    <div className="artist-filter-panel">
                        <div className="filter-options">
                            {STYLE_LIST.map(s => (
                                <span
                                    key={s}
                                    className={`filter-item ${style === s ? 'active' : ''}`}
                                    onClick={() => setStyle(s)}
                                >
                                    {s}
                                </span>
                            ))}
                        </div>
                    </div>

                    {/* 3. 音乐人网格展示区 (1排6个) */}
                    <Spin spinning={loading}>
                        {artists.length > 0 ? (
                            <>
                                <div className="artist-list-grid">
                                    {artists.map((artist, index) => {
                                        const styleTags = getStyleTags(artist.style);
                                        return (
                                            <div
                                                key={artist.id}
                                                className="artist-list-card"
                                                style={{ '--card-index': index }}
                                                onClick={() => navigate(`/artist/${artist.id}`)}
                                            >
                                                <div className="artist-list-avatar-wrapper">
                                                    <img
                                                        src={artist.avatarUrl || 'https://via.placeholder.com/300'}
                                                        alt={artist.name}
                                                    />
                                                    <div className="artist-list-image-mask" />
                                                    <div className="artist-list-card-glow" />
                                                    <div className="artist-list-hover-action">查看主页</div>
                                                </div>

                                                <div className="artist-list-info">
                                                    <div className="artist-list-name-row">
                                                        <div className="artist-list-name" title={artist.name}>{artist.name}</div>
                                                    </div>

                                                    <div className="artist-list-style-tags">
                                                        {styleTags.map(item => (
                                                            <span key={item} className="artist-list-style-tag">{item}</span>
                                                        ))}
                                                    </div>

                                                    <div className="artist-list-region-line">
                                                        {artist.region || '地区未设置'}
                                                    </div>

                                                    {/* 🚨 新增：像素级同步秀动“最近有X场演出”展示 */}
                                                    <div className="artist-list-recent-shows">
                                                        最近有 <span>{artist.recentEventCount ?? 0}</span> 场演出
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
                                        onChange={(page) => fetchArtists(page)}
                                        showSizeChanger={false}
                                    />
                                </div>
                            </>
                        ) : (
                            <Empty description="暂无符合条件的音乐人" style={{ margin: '80px 0' }} />
                        )}
                    </Spin>
                </div>
            </div>
        </ConfigProvider>
    );
};

export default ArtistsPage;
