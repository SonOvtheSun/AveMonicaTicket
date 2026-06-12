import React from 'react';
import {BrowserRouter, Routes, Route, Navigate} from 'react-router-dom';
import Auth from './pages/Auth/Auth';
import Home from './pages/Home/Home';
import EventManager from "./pages/Admin/EventManager.jsx";
import AdminLayout from './pages/Admin/AdminLayout';
import AuditManager from "./pages/Admin/AuditManager.jsx";
import axios from 'axios';
import UserManager from "./pages/Admin/UserManager.jsx";
import ArtistLibrary from "./pages/Admin/ArtistLibrary.jsx";
import EventDetail from "./pages/EventDetail/EventDetail.jsx";
import OrderConfirm from "./pages/Order/OrderConfirm.jsx";
import SimulatePay from "./pages/Order/SimulatePay.jsx";
import PaySuccess from './pages/Order/PaySuccess';
import UserProfile from "./pages/User/UserProfile.jsx";
import UserOrders from "./pages/User/UserOrders.jsx";
import BannerManager from "./pages/Admin/BannerManager.jsx";
import EventPage from "./pages/Public/EventPage.jsx";
import ArtistsPage from "./pages/Public/ArtistsPage.jsx";
import ArtistDetail from "./pages/ArtistDetail/ArtistDetail.jsx";
import SearchResultPage from "./pages/Search/SearchResultPage.jsx";
import Favorites from "./pages/User/Favorites.jsx";

axios.interceptors.request.use(
    (config) => {
        // 1. 从浏览器的本地存储中拿取 Token
        const token = localStorage.getItem('token');

        // 2. 如果 Token 存在，就把它塞进请求头里
        if (token) {
            // ⚠️ 注意：这里的写法取决于你后端的 JwtAuthenticationFilter 怎么写的
            // 情况 A：如果你后端要求带 "Bearer " 前缀（标准写法）
            // config.headers['Authorization'] = `Bearer ${token}`;

            // 情况 B：如果你后端直接读取纯 token 字符串（简单写法）
            config.headers.Authorization = token.startsWith('Bearer ')
                ? token
                : `Bearer ${token}`;
        }

        return config;
    },
    (error) => {
        return Promise.reject(error);
    }
);

function App() {
    return (
        <BrowserRouter>
            <Routes>
                {/* 默认访问首页，不再强制跳转 /auth */}
                <Route path="/" element={<Home />} />
                <Route path="/event/:id" element={<EventDetail />} />
                {/* 认证页面路径 */}
                <Route path="/auth" element={<Auth />} />
                <Route path={"/order/confirm"} element={<OrderConfirm />} />
                <Route path="/simulate-pay" element={<SimulatePay />} />
                <Route path="/pay/success" element={<PaySuccess />} />
                <Route path="/user/profile" element={<UserProfile />} />
                <Route path="/user/orders" element={<UserOrders />} />
                <Route path="/events" element={<EventPage />} />
                <Route path="/artists" element={<ArtistsPage />} />
                <Route path="/artist/:id" element={<ArtistDetail />} />
                <Route path="/search" element={<SearchResultPage />} />
                <Route path="/user/favorites" element={<Favorites />} />


                <Route path="/admin" element={<AdminLayout />}>
                    {/* 👈 加这一行：当访问 /admin 时，自动重定向到 /admin/events */}
                    <Route index element={<Navigate to="events" replace />} />

                    <Route path="dashboard" element={<div>数据大盘 (待开发)</div>} />
                    <Route path="events" element={<EventManager />} />
                    <Route path="audit" element={<AuditManager />} />
                    <Route path="users" element={<UserManager />} />
                    <Route path="artists" element={<ArtistLibrary />} />
                    <Route path="banners" element={<BannerManager />} />
                </Route>
            </Routes>
        </BrowserRouter>
    );
}

export default App;