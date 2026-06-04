import React, { useState, useEffect } from 'react';
import { Input, Dropdown, Avatar, Button, message } from 'antd';
import { Search, MapPin, ChevronDown, User, FileText, Heart, LogOut, Settings } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import './PublicHeader.css'; // 我们下一步会把相关 CSS 移到这里

const PublicHeader = () => {
    const navigate = useNavigate();
    const [isLoggedIn, setIsLoggedIn] = useState(false);
    const [currentUser, setCurrentUser] = useState(null);

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
            { key: 'orders', icon: <FileText size={16} />, label: '订单' },
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
                    <div className="location-selector">
                        <MapPin size={16} /> 北京 <ChevronDown size={14} />
                    </div>
                    <nav className="main-nav">
                        <span className="active" onClick={() => navigate('/')}>首页</span>
                        <span>演出</span>
                        <span>音乐人</span>
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