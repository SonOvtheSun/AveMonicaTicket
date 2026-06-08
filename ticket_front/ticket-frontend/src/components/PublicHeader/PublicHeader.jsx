import React, { useState, useEffect } from 'react';
import { Input, Dropdown, Avatar, Button, message, Cascader } from 'antd';
import { Search, MapPin, ChevronDown, User, FileText, Heart, LogOut, Settings } from 'lucide-react';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from 'axios';
import './PublicHeader.css'; // 我们下一步会把相关 CSS 移到这里
import pcasData from '../../assets/pcas.json';

const PublicHeader = () => {
    const navigate = useNavigate();
    const location = useLocation();
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [currentUser, setCurrentUser] = useState(null);

    const [selectedCity, setSelectedCity] = useState(localStorage.getItem('currentCity') || '全国');

    // 根据当前路由动态控制头部导航高亮
    const navItems = [
        { label: '首页', path: '/', match: (pathname) => pathname === '/' },
        { label: '演出', path: '/events', match: (pathname) => pathname === '/events' || pathname.startsWith('/event/') },
        { label: '音乐人', path: '/artists', match: (pathname) => pathname === '/artists' || pathname.startsWith('/artist/') },
    ];

    const isNavActive = (item) => item.match(location.pathname);

    // 🚨 4. 解析 pcas.json，提取“省-市”两级（与发布演出的逻辑一致）
    const cityOptions = React.useMemo(() => {
        if (!pcasData) return [];

        const options = Object.keys(pcasData).map(province => {
            const cityKeys = Object.keys(pcasData[province]);
            // 处理直辖市
            const validCities = cityKeys.map(cityKey => {
                if (cityKey === '市辖区' || cityKey === '县' || cityKey.includes('直辖')) {
                    return province;
                }
                return cityKey;
            });
            // 去重
            const uniqueCities = [...new Set(validCities)];
            return {
                value: province,
                label: province,
                children: uniqueCities.map(cityName => ({
                    value: cityName,
                    label: cityName
                }))
            };
        });

        // 在最前面插入“全国”选项
        return [
            { value: '全国', label: '全国' },
            ...options
        ];
    }, []);

    // 🚨 5. 城市切换处理函数
    const handleCityChange = (value) => {
        if (value && value.length > 0) {
            // 如果选的是省市层级，取最后一级（市）；如果选的是全国，就是 '全国'
            const city = value[value.length - 1];
            setSelectedCity(city);

            // 可选：将选择的城市存入 localStorage，方便其他页面读取
            localStorage.setItem('currentCity', city);

            // 可选：如果你想在切换城市后刷新首页，可以抛出一个自定义事件
            window.location.reload();
        }
    };

    // 退出登录
    const handleLogout = () => {
        localStorage.removeItem('token');
        setIsLoggedIn(false);
        setCurrentUser(null);
        message.success('已安全退出登录');
        navigate('/'); // 退出后强制回到首页
    };



    // 用户下拉菜单
    const userMenuProps = {
        items: [
            {
                key: 'account',
                icon: <User size={16} />,
                label: '我的账户',
                onClick: () => navigate('/user/profile')
            },
            {
                key: 'orders',
                icon: <FileText size={16} />,
                label: '订单' ,
                onClick: () => navigate('/user/orders')
            },
            { key: 'favorites', icon: <Heart size={16} />, label: '收藏' },
            { type: 'divider' },
            {
                key: 'logout',
                icon: <LogOut size={16} color="#ff4d4f" />,
                label: <span style={{ color: '#ff4d4f' }}>退出登录</span>,
                onClick: handleLogout
            },
        ]
    };

    // 鉴权逻辑：每次组件挂载时校验 Token
    useEffect(() => {
        const token = localStorage.getItem('token');
        if (token) {
            setIsLoggedIn(true);
            axios.get('/api/user/info', {
                headers: { Authorization: `Bearer ${token}` }
            }).then(res => {
                if (res.data.code === 200) {
                    setCurrentUser(res.data.data);
                } else {
                    handleLogout();
                }
            }).catch(() => {
                handleLogout();
            });
        }
    }, []);

    return (
        <header className="public-header">
            <div className="header-inner">
                {/* 1. Logo 与 导航 */}
                <div className="header-left">
                    <img
                        src="/uploads/scrollbar/logo.png"
                        alt="Ave Monica Logo"
                        className="home-logo-img"
                        onClick={() => navigate('/')}
                    />
                    <Cascader
                        options={cityOptions}
                        onChange={handleCityChange}
                        expandTrigger="hover"
                        placement="bottomLeft"
                    >
                        <div className="location-selector" style={{ cursor: 'pointer' }}>
                            <MapPin size={16} /> {selectedCity} <ChevronDown size={14} />
                        </div>
                    </Cascader>
                    <nav className="main-nav">
                        {navItems.map(item => (
                            <span
                                key={item.path}
                                className={isNavActive(item) ? 'active' : ''}
                                onClick={() => navigate(item.path)}
                            >
                                {item.label}
                            </span>
                        ))}
                    </nav>
                </div>

                {/* 2. 搜索框 */}
                <div className="header-center">
                    <Input
                        className="search-input"
                        prefix={<Search size={16} color="#999" />}
                        placeholder="搜索演出、艺人、场馆"
                    />
                </div>

                {/* 3. 账户操作区 */}
                <div className="header-right">
                    {currentUser?.role && currentUser.role < 6 && (
                        <Button className="admin-dashboard-btn" icon={<Settings size={16} />} onClick={() => navigate('/admin')}>
                            票务管理中心
                        </Button>
                    )}

                    {isLoggedIn ? (
                        currentUser ? (
                            <Dropdown menu={userMenuProps} placement="bottomRight" trigger={['hover', 'click']}>
                                <div className="user-profile-trigger">
                                    <Avatar
                                        src={currentUser.avatar || 'https://via.placeholder.com/40'}
                                        size={40}
                                        style={{ border: '2px solid #FF8899' }}
                                    />
                                    <span className="user-nickname">{currentUser.username}</span>
                                    <ChevronDown size={14} color="#999" style={{ marginLeft: 4 }} />
                                </div>
                            </Dropdown>
                        ) : (
                            <div className="user-profile-trigger">
                                <span className="user-nickname" style={{ color: '#999' }}>加载中...</span>
                            </div>
                        )
                    ) : (
                        <Button type="primary" className="login-btn" onClick={() => navigate('/auth')}>
                            登录 / 注册
                        </Button>
                    )}
                </div>
            </div>
        </header>
    );
};

export default PublicHeader;