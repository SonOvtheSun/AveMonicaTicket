import React, { useEffect, useRef, useState } from 'react';
import { Button, Input, Avatar, Spin, Tag, Empty, message } from 'antd';
import {
    RobotOutlined,
    MessageOutlined,
    SendOutlined,
    CloseOutlined
} from '@ant-design/icons';
import axios from '../../utils/request';
import './AiAssistantFloat.css';

const { TextArea } = Input;
const AI_CHAT_TIMEOUT = 30000; // 30秒

const AiAssistantFloat = ({ currentCity = '全国', onOpenEvent }) => {
    const [open, setOpen] = useState(false);
    const [inputValue, setInputValue] = useState('');
    const [loading, setLoading] = useState(false);

    const [messages, setMessages] = useState([
        {
            role: 'assistant',
            content: '你好，我是 Ave AI。你可以告诉我想看什么类型的演出、城市、预算、时间，我会帮你推荐合适的演出。',
            events: []
        }
    ]);

    const bodyRef = useRef(null);

    useEffect(() => {
        if (bodyRef.current) {
            bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
        }
    }, [messages, loading, open]);

    const handleSend = async () => {
        const question = inputValue.trim();

        if (!question) {
            message.warning('请输入你的喜好或问题');
            return;
        }

        if (loading) {
            return;
        }

        const userMessage = {
            role: 'user',
            content: question,
            events: []
        };

        setMessages(prev => [...prev, userMessage]);
        setInputValue('');
        setLoading(true);

        try {
            const res = await axios.post('/api/ai-assistant/chat', {
                question,
                city: currentCity,
                size: 5
            }, {
                timeout: AI_CHAT_TIMEOUT
            });

            if (res.data.code === 200) {
                const data = res.data.data || {};

                setMessages(prev => [
                    ...prev,
                    {
                        role: 'assistant',
                        content: data.answer || '我找到了一些可能适合你的演出。',
                        events: data.events || []
                    }
                ]);
            } else {
                setMessages(prev => [
                    ...prev,
                    {
                        role: 'assistant',
                        content: res.data.message || '抱歉，暂时没有找到合适的演出。',
                        events: []
                    }
                ]);
            }
        } catch (error) {
            console.error('AI助手请求失败：', error);
            setMessages(prev => [
                ...prev,
                {
                    role: 'assistant',
                    content: 'AI 助手暂时不可用，请稍后再试。',
                    events: []
                }
            ]);
        } finally {
            setLoading(false);
        }
    };

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    };

    const handleClear = () => {
        setMessages([
            {
                role: 'assistant',
                content: '对话已清空。你可以重新告诉我想看什么类型的演出。',
                events: []
            }
        ]);
    };

    const renderEventCard = (event) => {
        const showTime = event.showTime || event.firstShowTime || '';
        const minPrice = event.minPrice || event.priceText || '';

        return (
            <div
                key={event.id}
                className="ai-assistant-event-card"
                onClick={() => onOpenEvent && onOpenEvent(event.id)}
            >
                <img
                    src={event.posterUrl || 'https://via.placeholder.com/80x110?text=No+Poster'}
                    alt={event.title}
                    className="ai-assistant-event-poster"
                />

                <div className="ai-assistant-event-info">
                    <div className="ai-assistant-event-title" title={event.title}>
                        {event.title || '未命名演出'}
                    </div>

                    <div className="ai-assistant-event-meta">
                        {event.city || '城市待定'} · {event.venue || '场馆待定'}
                    </div>

                    <div className="ai-assistant-event-meta">
                        {showTime ? String(showTime).replace('T', ' ').slice(0, 16) : '时间待定'}
                    </div>

                    <div className="ai-assistant-event-bottom">
                        {event.eventType && <Tag color="purple">{event.eventType}</Tag>}
                        {minPrice && <span className="ai-assistant-event-price">¥{minPrice}起</span>}
                    </div>

                    {event.reason && (
                        <div className="ai-assistant-event-reason">
                            {event.reason}
                        </div>
                    )}
                </div>
            </div>
        );
    };

    if (!open) {
        return (
            <button
                type="button"
                className="ai-assistant-float-btn"
                onClick={() => setOpen(true)}
            >
                <RobotOutlined className="ai-assistant-float-icon" />
                <span>AI 找演出</span>
            </button>
        );
    }

    return (
        <div className="ai-assistant-window">
            <div className="ai-assistant-header">
                <div className="ai-assistant-header-left">
                    <Avatar
                        size={34}
                        icon={<RobotOutlined />}
                        className="ai-assistant-avatar"
                    />
                    <div>
                        <div className="ai-assistant-title">Ave AI 找演出</div>
                        <div className="ai-assistant-subtitle">
                            当前城市：{currentCity || '全国'}
                        </div>
                    </div>
                </div>

                <div className="ai-assistant-header-actions">
                    <Button type="text" size="small" onClick={handleClear}>
                        清空
                    </Button>
                    <Button
                        type="text"
                        size="small"
                        icon={<CloseOutlined />}
                        onClick={() => setOpen(false)}
                    />
                </div>
            </div>

            <div className="ai-assistant-body" ref={bodyRef}>
                {messages.length === 0 ? (
                    <Empty description="开始和 Ave AI 对话吧" />
                ) : (
                    messages.map((msg, index) => (
                        <div
                            key={index}
                            className={`ai-assistant-message-row ${msg.role === 'user' ? 'user' : 'assistant'}`}
                        >
                            {msg.role === 'assistant' && (
                                <Avatar
                                    size={28}
                                    icon={<RobotOutlined />}
                                    className="ai-assistant-message-avatar"
                                />
                            )}

                            <div className="ai-assistant-message-block">
                                <div className="ai-assistant-message-content">
                                    {msg.content}
                                </div>

                                {msg.events && msg.events.length > 0 && (
                                    <div className="ai-assistant-event-list">
                                        {msg.events.map(renderEventCard)}
                                    </div>
                                )}
                            </div>
                        </div>
                    ))
                )}

                {loading && (
                    <div className="ai-assistant-message-row assistant">
                        <Avatar
                            size={28}
                            icon={<RobotOutlined />}
                            className="ai-assistant-message-avatar"
                        />
                        <div className="ai-assistant-message-block">
                            <div className="ai-assistant-message-content loading">
                                <Spin size="small" />
                                <span>正在检索演出并生成推荐...</span>
                            </div>
                        </div>
                    </div>
                )}
            </div>

            <div className="ai-assistant-suggestions">
                {['上海周末二次元演唱会', '预算500以内的Livehouse', '适合情侣看的演出'].map(item => (
                    <button
                        type="button"
                        key={item}
                        onClick={() => setInputValue(item)}
                    >
                        {item}
                    </button>
                ))}
            </div>

            <div className="ai-assistant-input-area">
                <TextArea
                    value={inputValue}
                    onChange={(e) => setInputValue(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="告诉我你想看什么，例如：想看上海周末的二次元演唱会，预算500以内"
                    autoSize={{ minRows: 1, maxRows: 3 }}
                    disabled={loading}
                />

                <Button
                    type="primary"
                    icon={<SendOutlined />}
                    onClick={handleSend}
                    loading={loading}
                    className="ai-assistant-send-btn"
                />
            </div>
        </div>
    );
};

export default AiAssistantFloat;