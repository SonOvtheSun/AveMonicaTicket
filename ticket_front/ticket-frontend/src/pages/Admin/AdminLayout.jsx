import React, { useState, useEffect } from 'react';
import { Layout, Menu, Dropdown, Avatar, message } from 'antd';
import {
    LayoutDashboard,
    Ticket,
    ShoppingCart,
    Users,
    ChevronDown,
    LogOut,
    User as UserIcon,
    Home,
    CheckSquare,
    MessageSquare, Mic2,
    Projector// 新增：用于审核菜单的图标
} from 'lucide-react';
import { useNavigate, Outlet, useLocation } from 'react-router-dom';
import axios from 'axios';
import './AdminLayout.css';


const { Header, Sider, Content } = Layout;

const AdminLayout = () => {
    const navigate = useNavigate();
    const location = useLocation();


    // 状态：存储当前登录用户信息
    const [currentUser, setCurrentUser] = useState(null);

    // 2. 挂载时获取真实账号信息
    useEffect(() => {
        document.title = "Ave Monica管理系统";
        const fetchUserInfo = async () => {
            try {
                // 假设你的 token 存在 localStorage 中，并且 axios 拦截器已配置自动携带 token
                const res = await axios.get('/api/user/info');
                if (res.data.code === 200) {
                    setCurrentUser(res.data.data);
                } else {
                    message.error('登录状态已失效，请重新登录');
                    navigate('/auth');
                }
            } catch (error) {
                message.error('网络异常，无法获取用户信息');
                navigate('/auth');
            }
        };
        fetchUserInfo();
    }, [navigate]);

    // 防止数据未加载完就渲染页面导致报错
    if (!currentUser) return null;

    // 核心改造：使用权限标识符进行鉴定
    const role = currentUser.role || 6;
    const isSuperAdmin = role === 1 || currentUser.id === 1;
    const perms = currentUser.permissions || [];

    // 如果是超管直接放行，否则检查是否包含对应的 permission_code
    const hasAccess = (permCode) => isSuperAdmin || perms.includes(permCode);

    // 动态配置左侧菜单项 (完全基于 sys_permission 表配置)
    const menuItems = [
        hasAccess('dashboard:view') && {
            key: '/admin/dashboard',
            icon: <LayoutDashboard size={18} />,
            label: '工作台大盘'
        },
        (hasAccess('event:view')|| hasAccess('event:publish') || hasAccess('audit:manage')) && {
            key: '/admin/events',
            icon: <Ticket size={18} />,
            label: '演出项目管理'
        },
        (hasAccess('artist:view') || hasAccess('artist:manage') || hasAccess('audit:manage')) && {
            key: '/admin/artists',
            icon: <Mic2 size={18} />,
            label: '音乐人库管理'
        },
        (hasAccess('banner:manage') || hasAccess('banner:view') || isSuperAdmin) && {
            key: '/admin/banners',
            icon: <Projector size={18} />,
            label: '首页横幅管理'
        },
        hasAccess('audit:manage') && {
            key: '/admin/audit',
            icon: <CheckSquare size={18} />,
            label: '演出与艺人审核'
        },

        hasAccess('comment:manage') && {
            key: '/admin/comments',
            icon: <MessageSquare size={18} />,
            label: '评论管理'
        },
        hasAccess('order:refund') && {
            key: '/admin/orders',
            icon: <ShoppingCart size={18} />,
            label: '订单退票处理'
        },
        // 用户权限管理属于最高敏感权限，强制要求只有 Super Admin 可见
        isSuperAdmin && {
            key: '/admin/users',
            icon: <Users size={18} />,
            label: '用户权限管理'
        },
    ].filter(Boolean); // 过滤掉无权限返回的 false 项目

    // 顶部右侧管理员下拉菜单配置
    const adminMenuProps = {
        items: [
            {
                key: 'back-home',
                icon: <Home size={14} />,
                label: '返回前台首页',
                onClick: () => navigate('/')
            },
            { type: 'divider' },
            {
                key: 'logout',
                icon: <LogOut size={14} />,
                label: '退出管理后台',
                danger: true,
                onClick: () => {
                    localStorage.removeItem('token');
                    navigate('/auth');
                }
            }
        ]
    };

    return (
        <Layout className="admin-layout-container">
            <Sider theme="light" width={240} className="admin-sider">
                {/* 1. 替换为包含 Logo 的视觉区域 */}
                <div className="admin-logo-zone" onClick={() => navigate('/')}>
                    <img src="/uploads/scrollbar/logo.png" alt="Ave Monica Logo" style={{ height: 50, marginRight: 10 }} />
                </div>

                <Menu
                    mode="inline"
                    selectedKeys={[location.pathname]}
                    items={menuItems}
                    onClick={({ key }) => navigate(key)}
                    className="admin-menu"
                />
            </Sider>

            <Layout>
                <Header className="admin-header">
                    <div className="header-left">
                        <span className="current-module-title">管理控制台</span>
                    </div>

                    <div className="header-right">
                        <Dropdown menu={adminMenuProps} placement="bottomRight" trigger={['click', 'hover']}>
                            <div className="admin-profile-trigger">
                                {/* 2. 渲染真实头像（结合兜底逻辑）和用户名 */}
                                <Avatar
                                    src={currentUser.avatar}
                                    size={40}
                                    icon={<UserIcon size={16} />}
                                    className="admin-avatar"
                                />
                                <span className="admin-name">{currentUser.username}</span>
                                <ChevronDown size={14} color="#999" />
                            </div>
                        </Dropdown>
                    </div>
                </Header>

                <Content className="admin-content-wrapper">
                    <Outlet />
                </Content>
            </Layout>
        </Layout>
    );
};

export default AdminLayout;