import React, { useState, useEffect } from 'react';
import { Table, Button, Space, Card, Tag, Modal, Drawer, message, Image, Popover, Input, Tabs, Popconfirm, Form, Select, Descriptions, Tooltip, Dropdown } from 'antd';
import { Plus, Edit, Trash2, EyeOff, Eye, ShieldOff } from 'lucide-react';
import axios from '../../utils/request';
import AddEventForm from './AddEventForm';
import './EventManager.css';
import { SearchOutlined, MinusCircleOutlined, PlusOutlined, RobotOutlined, SyncOutlined, MoreOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';

const EventManager = () => {
    // === 演出相关状态 ===
    const [searchText, setSearchText] = useState('');
    const [events, setEvents] = useState([]);
    const [loading, setLoading] = useState(false);
    const [drawerVisible, setDrawerVisible] = useState(false);
    const [aiRefreshingIds, setAiRefreshingIds] = useState(new Set());
    const [editingRecord, setEditingRecord] = useState(null);
    const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });

    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewImageUrl, setPreviewImageUrl] = useState('');

    // === 权限相关状态 ===
    const [userPerms, setUserPerms] = useState([]);
    const [userRole, setUserRole] = useState(6);

    // === 合集相关状态 ===
    const [activeTab, setActiveTab] = useState('events');
    const [collections, setCollections] = useState([]);
    const [collectionLoading, setCollectionLoading] = useState(false);
    const [collectionSearchText, setCollectionSearchText] = useState('');

    // 🚨 合集弹窗与异步选票池状态
    const [collectionModalVisible, setCollectionModalVisible] = useState(false);
    const [editingCollection, setEditingCollection] = useState(null);
    const [availableEvents, setAvailableEvents] = useState([]); // 弹窗可选演出列表
    const [collectionForm] = Form.useForm();

    // 合集内演出详情弹窗，复用审核详情的展示风格
    const [collectionEventDetailVisible, setCollectionEventDetailVisible] = useState(false);
    const [collectionEventDetailLoading, setCollectionEventDetailLoading] = useState(false);
    const [collectionEventDetail, setCollectionEventDetail] = useState(null);

    const fetchEvents = async (page = 1, size = 10, keyword = searchText) => {
        setLoading(true);
        try {
            const res = await axios.get('/api/admin/event/list', {
                params: { current: page, size: size, keyword: keyword }
            });
            if (res.data.code === 200) {
                setEvents(res.data.data.records);
                setPagination({
                    current: res.data.data.current,
                    pageSize: res.data.data.size,
                    total: res.data.data.total
                });
            } else {
                message.error(res.data.message || '获取列表失败');
            }
        } catch (err) {
            message.error('网络请求异常');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        axios.get('/api/user/info').then(res => {
            if (res.data.code === 200) {
                setUserPerms(res.data.data.permissions || []);
                setUserRole(res.data.data.role || 6);
            }
        });
        fetchEvents();
    }, []);

    const isSuperAdmin = userRole === 1;
    const hasPublishPerm = isSuperAdmin || userPerms.includes('event:publish') || userPerms.includes("event:edit");
    const hasAuditPerm = isSuperAdmin || userPerms.includes('audit:manage');
    const hasEditPerm = isSuperAdmin || userPerms.includes('event:edit');

    const handleTableChange = (newPagination) => fetchEvents(newPagination.current, newPagination.pageSize);

    // ==========================================
    // 🚨 合集管理核心闭环控制
    // ==========================================
    const fetchCollections = async (keyword = collectionSearchText) => {
        setCollectionLoading(true);
        try {
            const res = await axios.get('/api/admin/collection/list', { params: { keyword } });
            if (res.data.code === 200) setCollections(res.data.data);
        } catch (e) { } finally {
            setCollectionLoading(false);
        }
    };

    useEffect(() => {
        if (activeTab === 'collections') fetchCollections();
    }, [activeTab]);

    const handleCollectionDelete = async (id) => {
        try {
            const res = await axios.delete(`/api/admin/collection/${id}`);
            if(res.data.code === 200) {
                message.success('删除成功');
                fetchCollections();
            }
        } catch(e) {}
    };

    // 🚨 打开合集弹窗并异步加载可选演出
    const openCollectionModal = async (record = null) => {
        setEditingCollection(record);
        setCollectionModalVisible(true);

        try {
            // 获取当前弹窗环境可以绑定的演出列表
            const res = await axios.get('/api/admin/collection/available-events', {
                params: record ? { collectionId: record.id } : {}
            });
            if (res.data.code === 200) {
                setAvailableEvents(res.data.data || []);
            }

            if (record) {
                // 回显合集名称，以及每个演出自己的别名
                const currentEvents = record.events
                    ? record.events.map(e => ({
                        eventId: e.id,
                        collectionAlias: e.collectionAlias || ''
                    }))
                    : [];

                collectionForm.setFieldsValue({
                    name: record.name,
                    collectionEvents: currentEvents
                });
            } else {
                collectionForm.resetFields();
                collectionForm.setFieldsValue({
                    collectionEvents: []
                });
            }
        } catch (e) {
            message.error('加载关联候选演出数据失败');
        }
    };

    const truncateText = (text, maxLength = 28) => {
        const value = String(text || '');
        return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value;
    };

    const getAvailableEventFullLabel = (eventId) => {
        const event = availableEvents.find(e => Number(e.id) === Number(eventId));
        if (!event) return `演出ID：${eventId}`;
        return `[ID: ${event.id}] ${event.title}（${event.city || '城市待定'}）`;
    };

    const getAvailableEventLabel = (eventId) => {
        return truncateText(getAvailableEventFullLabel(eventId), 32);
    };

    const getAvailableEventOptionFullLabel = (event) => {
        return `[ID: ${event.id}] ${event.title}（${event.city || '城市待定'}）`;
    };

    const getAvailableEventOptionShortLabel = (event) => {
        return truncateText(getAvailableEventOptionFullLabel(event), 32);
    };

    const handleShowEventDetailRecord = (record) => {
        if (!record) return;
        setCollectionEventDetail(record);
        setCollectionEventDetailLoading(false);
        setCollectionEventDetailVisible(true);
    };

    const handleShowCollectionEventDetail = async (eventId) => {
        if (!eventId) return;
        setCollectionEventDetailVisible(true);
        setCollectionEventDetailLoading(true);
        setCollectionEventDetail(null);

        try {
            const res = await axios.get(`/api/admin/collection/event-detail/${eventId}`);
            if (res.data.code === 200) {
                setCollectionEventDetail(res.data.data);
            } else {
                message.error(res.data.message || '获取演出详情失败');
            }
        } catch (e) {
            message.error('获取演出详情失败');
        } finally {
            setCollectionEventDetailLoading(false);
        }
    };

    const formatDateTime = (time) => {
        if (!time) return '待定';
        const parsed = dayjs(time);
        return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : String(time).replace('T', ' ');
    };

    const renderTicketList = (tickets = []) => {
        if (!tickets || tickets.length === 0) {
            return <span style={{ color: '#999', fontSize: 12 }}>暂未设置票档</span>;
        }

        return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {tickets.map((ticket, idx) => (
                    <div
                        key={ticket.id || idx}
                        style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 12,
                            background: '#fff0f3',
                            padding: '6px 12px',
                            borderRadius: 6,
                            border: '1px solid #ffe6e8'
                        }}
                    >
                        <Tag color="#FF8899" style={{ margin: 0 }}>
                            {ticket.name || '未命名票档'}
                        </Tag>
                        <span style={{ color: '#e60026', fontWeight: 'bold', width: 90 }}>
                            ¥ {ticket.price ?? '未设置'}
                        </span>
                        <span style={{ color: '#666', fontSize: 13 }}>
                            库存：<b>{ticket.totalStock ?? ticket.stock ?? ticket.remainingStock ?? 0}</b> 张
                        </span>
                    </div>
                ))}
            </div>
        );
    };

    const renderSessionList = (detail) => {
        const sessions = detail?.sessions || [];
        if (!sessions || sessions.length === 0) {
            return <span style={{ color: '#999', fontSize: 12 }}>暂未配置时间场次</span>;
        }

        return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {sessions.map((session, idx) => (
                    <div
                        key={session.id || idx}
                        style={{
                            padding: '10px 12px',
                            borderRadius: 10,
                            background: '#f6fffe',
                            border: '1px solid rgba(23, 185, 185, 0.18)'
                        }}
                    >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flexWrap: 'wrap' }}>
                            <Tag color="cyan" style={{ margin: 0 }}>
                                {session.sessionName || `场次${idx + 1}`}
                            </Tag>
                            <span style={{ color: '#333', fontWeight: 700 }}>
                                {formatDateTime(session.showTime)}
                            </span>
                            <span style={{ color: '#999', fontSize: 12 }}>
                                开票：{formatDateTime(session.saleTime)}
                            </span>
                        </div>
                        {renderTicketList(session.tickets || [])}
                    </div>
                ))}
            </div>
        );
    };

    const renderEventDetailDescriptions = (detail) => {
        if (!detail) return null;

        const statusText = statusConfig[detail.status]?.text || '未知';
        const statusColor = statusConfig[detail.status]?.color || 'default';

        return (
            <Descriptions column={2} bordered size="small">
                <Descriptions.Item label="演出标题" span={2}>{detail.title || '未设置'}</Descriptions.Item>

                <Descriptions.Item label="当前状态">
                    <Tag color={statusColor}>{statusText}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="演出风格">
                    {detail.style ? <Tag color="purple">{detail.style}</Tag> : <span style={{ color: '#999' }}>暂无风格</span>}
                </Descriptions.Item>

                <Descriptions.Item label="演出城市">
                    <Tag color="blue">{detail.city || '未指定'}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="场馆">{detail.venue || '未设置'}</Descriptions.Item>

                <Descriptions.Item label="演出时间">{formatDateTime(detail.showTime)}</Descriptions.Item>
                <Descriptions.Item label="预开票时间">
                    {detail.saleTime ? (
                        <span style={{ color: '#e60026', fontWeight: 'bold' }}>{formatDateTime(detail.saleTime)}</span>
                    ) : (
                        <span style={{ color: '#999' }}>待定</span>
                    )}
                </Descriptions.Item>

                <Descriptions.Item label="演出时长">
                    {detail.runningTime ? `约 ${detail.runningTime} 分钟` : '未设置'}
                </Descriptions.Item>
                <Descriptions.Item label="合集别名">
                    {detail.collectionAlias ? <Tag color="#FF8899">{detail.collectionAlias}</Tag> : <span style={{ color: '#999' }}>未设置</span>}
                </Descriptions.Item>

                <Descriptions.Item label="详细地址" span={2}>{detail.address || '未设置'}</Descriptions.Item>

                <Descriptions.Item label="参演音乐人" span={2}>
                    {detail.artists && detail.artists.length > 0 ? (
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                            {detail.artists.map((artist, idx) => (
                                <Tag key={idx} color={artist.auditStatus === 0 ? '#FF8899' : 'cyan'} style={{ margin: 0, borderRadius: 4 }}>
                                    {artist.name || `艺人ID：${artist.id}`}(ID：{artist.id})
                                </Tag>
                            ))}
                        </div>
                    ) : (
                        <span style={{ color: '#999', fontSize: 12 }}>暂无配置音乐人</span>
                    )}
                </Descriptions.Item>

                <Descriptions.Item label="时间场次" span={2}>
                    {renderSessionList(detail)}
                </Descriptions.Item>

                {(!detail.sessions || detail.sessions.length === 0) && (
                    <Descriptions.Item label="票务档位" span={2}>
                        {renderTicketList(detail.tickets || [])}
                    </Descriptions.Item>
                )}

                <Descriptions.Item label="主海报" span={2}>
                    {detail.posterUrl ? <Image src={detail.posterUrl} width={100} style={{ borderRadius: 4 }} /> : '无'}
                </Descriptions.Item>
                <Descriptions.Item label="详情长图" span={2}>
                    {detail.detailsUrl ? <Image src={detail.detailsUrl} width={100} style={{ borderRadius: 4 }} /> : '无'}
                </Descriptions.Item>
            </Descriptions>
        );
    };

    // 🚨 提交合集数据：支持动态添加/删除演出，同时支持为每个演出设置合集别名
    const handleCollectionSubmit = async () => {
        try {
            const values = await collectionForm.validateFields();

            const collectionEvents = (values.collectionEvents || [])
                .filter(item => item && item.eventId)
                .map(item => ({
                    eventId: item.eventId,
                    collectionAlias: item.collectionAlias || ''
                }));

            const duplicateIds = collectionEvents
                .map(item => Number(item.eventId))
                .filter((eventId, index, arr) => arr.indexOf(eventId) !== index);

            if (duplicateIds.length > 0) {
                message.warning('同一个演出不能在一个合集中重复添加');
                return;
            }

            const payload = {
                name: values.name,
                events: collectionEvents,
                // 兼容旧后端字段；新后端会优先读取 events
                eventIds: collectionEvents.map(item => item.eventId)
            };

            let res;
            if (editingCollection) {
                payload.id = editingCollection.id;
                res = await axios.put('/api/admin/collection/update', payload);
            } else {
                res = await axios.post('/api/admin/collection/add', payload);
            }

            if (res.data.code === 200) {
                message.success(editingCollection ? '合集修改成功' : '合集创建成功');
                setCollectionModalVisible(false);
                fetchCollections();
            } else {
                message.error(res.data.message);
            }
        } catch (e) {
            console.error("验证失败", e);
        }
    };

    const collectionColumns = [
        { title: '合集ID', dataIndex: 'id', width: 80 },
        { title: '合集/巡演名称', dataIndex: 'name', fontWeight: 'bold' },
        {
            title: '包含演出场次',
            key: 'events',
            width: 130,
            render: (_, record) => {
                const subEvents = record.events || [];

                if (subEvents.length === 0) {
                    return <span style={{ color: '#999', fontSize: 13 }}>暂无包含场次</span>;
                }

                const popContent = (
                    <div style={{ maxHeight: 380, overflowY: 'auto', padding: 4, minWidth: 420 }}>
                        {subEvents.map((e, i) => (
                            <div
                                key={e.id}
                                style={{
                                    marginBottom: i === subEvents.length - 1 ? 0 : 8,
                                    borderBottom: i === subEvents.length - 1 ? 'none' : '1px dashed #f0f0f0',
                                    padding: '8px 6px',
                                    borderRadius: 8
                                }}
                            >
                                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4, flexWrap: 'wrap' }}>
                                    <Tag color="blue" style={{ margin: 0 }}>ID:{e.id}</Tag>
                                    <Tag color="#FF8899" style={{ margin: 0 }}>
                                        {e.collectionAlias || '未设别名'}
                                    </Tag>
                                    <Button
                                        type="link"
                                        size="small"
                                        style={{ padding: 0, marginLeft: 'auto' }}
                                        onClick={() => handleShowCollectionEventDetail(e.id)}
                                    >
                                        查看
                                    </Button>
                                </div>
                                <div style={{ color: '#333', fontWeight: 600, lineHeight: 1.4 }}>
                                    {e.title}
                                </div>
                                <div style={{ color: '#888', fontSize: 12, marginTop: 3 }}>
                                    {e.city || '城市待定'}
                                </div>
                            </div>
                        ))}
                    </div>
                );

                return (
                    <Popover
                        content={popContent}
                        title={`合集包含演出明细（共 ${subEvents.length} 场）`}
                        trigger="click"
                        placement="bottomLeft"
                    >
                        <Button type="link" size="small" style={{ padding: 0, color: '#17b9b9' }}>
                            查看详情
                        </Button>
                    </Popover>
                );
            }
        },
        { title: '创建时间', dataIndex: 'createTime', render: (t) => t ? String(t).replace('T', ' ') : '-' },
        {
            title: '操作',
            key: 'action',
            width: 150,
            render: (_, record) => (
                <Space>
                    <Button type="link" onClick={() => openCollectionModal(record)}>查看与编辑</Button>
                    <Popconfirm title="确定删除该合集？(仅删除合集分类，下属演出不会被删除)" onConfirm={() => handleCollectionDelete(record.id)} okButtonProps={{ danger: true }}>
                        <Button type="link" danger>删除</Button>
                    </Popconfirm>
                </Space>
            )
        }
    ];

    // ==========================================
    // 演出管理核心业务逻辑 (保持不变)
    // ==========================================
    const handleQuickHide = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/status/${id}?status=4`);
            if (res.data.code === 200) {
                message.success('已将该演出设为隐藏状态');
                fetchEvents(pagination.current, pagination.pageSize);
            }
        } catch (error) {
            message.error('快捷隐藏请求失败');
        }
    };

    const handleQuickShow = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/status/${id}?status=3`);
            if (res.data.code === 200) {
                message.success('已取消隐藏，恢复显示状态');
                fetchEvents(pagination.current, pagination.pageSize);
            } else{
                message.warning(res.data.message);
            }
        } catch (error) {
            message.error('恢复请求失败');
        }
    };

    const handleDelete = (id) => {
        Modal.confirm({
            title: '危险操作确认',
            content: '确定要下架并删除该演出及其所有的票档配置吗？此操作不可逆！',
            okText: '确认删除',
            okType: 'danger',
            onOk: async () => {
                try {
                    const res = await axios.delete(`/api/admin/event/${id}`);
                    if (res.data.code === 200) {
                        message.success('演出删除成功');
                        fetchEvents(pagination.current, pagination.pageSize);
                    }
                } catch (err) {
                    message.error('删除接口请求异常');
                }
            }
        });
    };

    const handleConfirmEditReject = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/confirm-edit-reject/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已确认修改审核未通过');
                fetchEvents(pagination.current, pagination.pageSize);
            } else {
                message.error(res.data.message || '确认修改审核未通过失败');
            }
        } catch (error) {
            message.error(
                error.response?.data?.message ||
                error.response?.data?.msg ||
                '确认修改审核未通过失败'
            );
        }
    };

    const handleConfirmNewReject = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/confirm-new-reject/${id}`);

            if (res.data.code === 200) {
                message.success(res.data.message || '已确认审核未通过');
                fetchEvents(pagination.current, pagination.pageSize);
            } else {
                message.error(res.data.message || '确认失败');
            }
        } catch (error) {
            message.error(
                error.response?.data?.message ||
                error.response?.data?.msg ||
                '确认审核未通过失败'
            );
        }
    };

    const handleTakeDown = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/takedown/${id}`);
            if (res.data.code === 200) {
                message.success('已成功下架该演出');
                fetchEvents(pagination.current, pagination.pageSize);
            }
        } catch (error) {
            message.error('下架请求失败');
        }
    };

    const handleEditClick = (record) => {
        setEditingRecord(record);
        setDrawerVisible(true);
    };

    const statusConfig = {
        1: { color: 'cyan', text: '上架中' },
        2: { color: 'green', text: '在售' },
        3: { color: 'red', text: '已停售' },
        4: { color: 'default', text: '已隐藏' }
    };

    const resolveAiIndexStatus = (record) => {
        const rawStatus =
            record?.aiIndexStatus ??
            record?.aiProfile?.indexStatus ??
            null;

        if (rawStatus !== null && rawStatus !== undefined) {
            return Number(rawStatus);
        }

        // 兼容后端暂时只返回摘要 / 类型但没返回 indexStatus 的情况
        if (record?.aiSummary || record?.aiEventType || record?.aiStyleTags) {
            return 1;
        }

        return null;
    };

    const renderAiAnnotationTag = (record) => {
        const status = resolveAiIndexStatus(record);

        if (status === 1) {
            const content = (
                <div style={{ maxWidth: 360 }}>
                    <Descriptions column={1} size="small" bordered>
                        <Descriptions.Item label="演出类型">
                            {record.aiEventType || '未识别'}
                        </Descriptions.Item>
                        <Descriptions.Item label="AI风格">
                            {record.aiStyleTags || '未识别'}
                        </Descriptions.Item>
                        <Descriptions.Item label="AI摘要">
                            {record.aiSummary || '暂无摘要'}
                        </Descriptions.Item>
                        <Descriptions.Item label="更新时间">
                            {record.aiUpdateTime ? formatDateTime(record.aiUpdateTime) : '未知'}
                        </Descriptions.Item>
                    </Descriptions>
                </div>
            );

            return (
                <Popover content={content} title="AI标注详情" trigger="click">
                    <Tag color="green" icon={<RobotOutlined />} style={{ cursor: 'pointer' }}>
                        已标注
                    </Tag>
                </Popover>
            );
        }

        if (status === 0) {
            return (
                <Tag color="processing" icon={<RobotOutlined />}>
                    待处理
                </Tag>
            );
        }

        if (status === 2) {
            const content = (
                <div style={{ maxWidth: 320, color: '#ff4d4f', whiteSpace: 'pre-wrap' }}>
                    {record.aiErrorMsg || 'AI标注失败，暂无错误详情'}
                </div>
            );

            return (
                <Popover content={content} title="失败原因" trigger="click">
                    <Tag color="red" icon={<RobotOutlined />} style={{ cursor: 'pointer' }}>
                        标注失败
                    </Tag>
                </Popover>
            );
        }

        return (
            <Tag color="default" icon={<RobotOutlined />}>
                未标注
            </Tag>
        );
    };

    const isStatusValue = (value, target) => {
        return value !== null
            && value !== undefined
            && Number(value) === target;
    };

    const canRefreshAiIndex = (record) => {
        if (!record?.id) return false;

        // 新增待审核
        if (isStatusValue(record.auditStatus, 0)) return false;

        // 修改待审核。注意：editAuditStatus = null 是正常状态，不能 Number(null)
        if (isStatusValue(record.editAuditStatus, 0)) return false;

        // 只允许新增审核已通过的演出刷新 AI 标注
        return isStatusValue(record.auditStatus, 1);
    };

    const handleRefreshAiIndex = async (record) => {
        if (!record?.id) return;

        if (!canRefreshAiIndex(record)) {
            message.warning('该演出尚未正式审核通过，暂不能刷新AI标注');
            return;
        }

        const eventId = record.id;

        setAiRefreshingIds(prev => {
            const next = new Set(prev);
            next.add(eventId);
            return next;
        });

        try {
            const res = await axios.post(`/api/admin/event/${eventId}/rebuild-ai-index`);

            if (res.data.code === 200) {
                message.success(res.data.message || 'AI标注刷新任务已提交');

                // 后端是异步任务，稍后刷新列表拿最新状态。
                setTimeout(() => {
                    fetchEvents(pagination.current, pagination.pageSize, searchText);
                }, 1500);
            } else {
                message.error(res.data.message || 'AI标注刷新失败');
            }
        } catch (error) {
            message.error(
                error.response?.data?.message ||
                error.response?.data?.msg ||
                'AI标注刷新请求失败'
            );
        } finally {
            setAiRefreshingIds(prev => {
                const next = new Set(prev);
                next.delete(eventId);
                return next;
            });
        }
    };

    const columns = [
        { title: '演出ID', dataIndex: 'id', key: 'id', width: 90 },
        {
            title: '海报',
            dataIndex: 'posterUrl',
            key: 'poster',
            width: 70,
            render: (url) => (
                <Image src={url || 'https://via.placeholder.com/40x55?text=No+Pic'} width={40} height={55} style={{ objectFit: 'cover', borderRadius: 4, border: '1px solid #f0f0f0' }} />
            )
        },
        {
            title: '演出标题及艺人',
            key: 'titleAndArtists',
            render: (_, record) => (
                <div style={{ wordBreak: 'break-all', whiteSpace: 'pre-wrap', minWidth: 240 }}>
                    <div style={{ fontWeight: 600, color: '#333', fontSize: 14, display: 'flex', alignItems: 'center' }}>
                        <span>{record.title}</span>
                        {record.status === 4 && <span style={{ color: '#faad14', marginLeft: 8, fontSize: 13, fontWeight: 500 }}>(已隐藏)</span>}
                    </div>
                    {record.artists && record.artists.length > 0 && (
                        <div style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                            {record.artists.map((artist, idx) => (
                                <Tag key={idx} color={artist.auditStatus === 0 ? '#FF8899' : 'cyan'} style={{ margin: 0, borderRadius: 4 }}>
                                    {artist.name}{artist.auditStatus === 0 ? ' (待审)' : ''}（ID:{artist.id}）
                                </Tag>
                            ))}
                        </div>
                    )}
                </div>
            )
        },
        {
            title: '场馆与地址',
            key: 'location',
            render: (_, record) => (
                <div style={{ wordBreak: 'break-all', whiteSpace: 'pre-wrap', minWidth: 160 }}>
                    <div style={{ color: '#333' }}>{record.venue || '未设置'}</div>
                    <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>{record.address || '未设置'}</div>
                </div>
            )
        },
        {
            title: '演出时间',
            dataIndex: 'showTime',
            key: 'showTime',
            width: 150,
            render: (showTime) => showTime ? (
                <span style={{ color: '#666', fontSize: 13 }}>{formatDateTime(showTime)}</span>
            ) : (
                <span style={{ color: '#999', fontSize: 12 }}>时间待定</span>
            )
        },
        {
            title: '详图',
            dataIndex: 'detailsUrl',
            key: 'detailsUrl',
            width: 90,
            render: (url) => url ? (
                <Button type="link" size="small" style={{ padding: 0, color: '#17b9b9' }} onClick={() => { setPreviewImageUrl(url); setPreviewVisible(true); }}>查看详图</Button>
            ) : <span style={{ color: '#999', fontSize: 12 }}>无</span>
        },
        {
            title: '查看详情',
            key: 'detail',
            width: 90,
            render: (_, record) => (
                <Button
                    type="link"
                    size="small"
                    style={{ padding: 0, color: '#17b9b9' }}
                    onClick={() => handleShowEventDetailRecord(record)}
                >
                    查看详情
                </Button>
            )
        },
        {
            title: '当前状态',
            dataIndex: 'status',
            key: 'status',
            width: 90,
            render: (_, record) => {
                if (record.editAuditStatus === 0) {
                    return <Tag color="processing">修改待审核</Tag>;
                }

                if (record.editAuditStatus === 2) {
                    return <Tag color="red">修改审核未通过</Tag>;
                }

                if (record.auditStatus === 0) {
                    return <Tag color="orange">新增待审核</Tag>;
                }

                if (record.auditStatus === 2) {
                    return <Tag color="red">审核未通过</Tag>;
                }

                if (record.auditStatus === 3) {
                    return <Tag color="default">未审核</Tag>;
                }

                const config = statusConfig[record.status] || { color: 'default', text: '未知' };
                return <Tag color={config.color}>{config.text}</Tag>;
            }
        },
        {
            title: 'AI标注',
            key: 'aiAnnotation',
            width: 110,
            render: (_, record) => renderAiAnnotationTag(record)
        },
        {
            title: '管理操作',
            key: 'action',
            width: 190,
            render: (_, record) => {
                // 🚨 补充丢失的权限计算变量
                const isNewPending = record.auditStatus === 0;
                const isEditPending = record.editAuditStatus === 0;
                const hasPendingAudit = isNewPending || isEditPending;
                // 判断当前行是否允许被编辑：超管无视一切，普通人遇到待审核则禁用
                const canEdit = isSuperAdmin || !hasPendingAudit;

                return (
                    <Space size="middle">
                        {hasPublishPerm && (
                            <>
                                {record.auditStatus === 2 && (
                                    <Popconfirm
                                        title="确认审核未通过？"
                                        description="确认后，该演出将回到未审核状态，可重新编辑后再次提交审核。"
                                        onConfirm={() => handleConfirmNewReject(record.id)}
                                        okText="确定"
                                        cancelText="取消"
                                    >
                                        <Button type="text" style={{ color: '#52c41a', padding: 0 }}>
                                            确定
                                        </Button>
                                    </Popconfirm>
                                )}

                                {record.editAuditStatus === 2 && (
                                    <Popconfirm
                                        title="确认修改审核未通过？"
                                        description="确认后，将清除本次修改审核结果，演出回到原已生效状态。"
                                        onConfirm={() => handleConfirmEditReject(record.id)}
                                        okText="确定"
                                        cancelText="取消"
                                    >
                                        <Button type="text" style={{ color: '#52c41a', padding: 0 }}>
                                            确定
                                        </Button>
                                    </Popconfirm>
                                )}
                                <Button type="text" icon={<Edit size={14} />} disabled={!canEdit} style={{ color: canEdit ? '#1890ff' : '#999', padding: 0 }} onClick={() => handleEditClick(record)}>编辑</Button>
                                {(hasEditPerm || isSuperAdmin) && (
                                    <Tooltip
                                        title={
                                            canRefreshAiIndex(record)
                                                ? '重新生成AI标签、中文摘要和向量索引'
                                                : '演出尚未审核通过或存在待审核修改，暂不能刷新AI标注'
                                        }
                                    >
                                        <Button
                                            type="text"
                                            icon={<SyncOutlined spin={aiRefreshingIds.has(record.id)} />}
                                            loading={aiRefreshingIds.has(record.id)}
                                            disabled={!canRefreshAiIndex(record)}
                                            onClick={() => handleRefreshAiIndex(record)}
                                            style={{
                                                color: canRefreshAiIndex(record) ? '#722ed1' : '#999',
                                                padding: 0
                                            }}
                                        >
                                            AI标注
                                        </Button>
                                    </Tooltip>
                                )}
                                {record.status !== 4 ? (
                                    <Button type="text" icon={<EyeOff size={14} />} onClick={() => handleQuickHide(record.id)} style={{ color: '#faad14', padding: 0 }}>隐藏</Button>
                                ) : (
                                    <Button type="text" icon={<Eye size={14} />} onClick={() => handleQuickShow(record.id)} style={{ color: '#52c41a', padding: 0 }}>恢复</Button>
                                )}
                                <Button type="text" icon={<Trash2 size={14} />} onClick={() => handleDelete(record.id)} style={{ color: '#ff4d4f', padding: 0 }}>删除</Button>
                            </>
                        )}
                        {(hasAuditPerm || hasEditPerm || isSuperAdmin) && (
                            <Button type="text" icon={<ShieldOff size={14} />} onClick={() => handleTakeDown(record.id)} style={{ color: '#ff4d4f', padding: 0 }} disabled={record.auditStatus === 0}>下架</Button>
                        )}
                    </Space>
                );
            }
        },
    ];

    return (
        <Card title={<span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}><div style={{display:'flex'}}>项目管理看板</div></span>} bordered={false} style={{ borderRadius: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.03)' }}>
            <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
                {
                    key: 'events',
                    label: '单场演出管理',
                    children: (
                        <>
                            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                                <div style={{ display: 'flex', gap: 10, flex: 1, maxWidth: 450 }}>
                                    <Input placeholder="搜索演出标题、场馆或艺人名称" prefix={<SearchOutlined />} value={searchText} onChange={e => setSearchText(e.target.value)} onPressEnter={() => fetchEvents(1, pagination.pageSize, searchText)} allowClear />
                                    <Button type="primary" onClick={() => fetchEvents(1, pagination.pageSize, searchText)}>搜索</Button>
                                </div>
                                {hasPublishPerm && (
                                    <Button type="primary" icon={<Plus size={16} />} onClick={() => { setEditingRecord(null); setDrawerVisible(true); }} style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', borderRadius: 8 }}>发布新演出</Button>
                                )}
                            </div>
                            <Table
                                className="event-manager-table"
                                columns={columns}
                                dataSource={events}
                                rowKey="id"
                                loading={loading}
                                pagination={pagination}
                                onChange={handleTableChange}
                                scroll={{ x: 1700 }}
                            />
                        </>
                    )
                },
                {
                    key: 'collections',
                    label: '巡演/合集管理',
                    children: (
                        <>
                            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                                <div style={{ display: 'flex', gap: 10, flex: 1, maxWidth: 450 }}>
                                    <Input placeholder="搜索合集/巡演名称" prefix={<SearchOutlined />} value={collectionSearchText} onChange={e => setCollectionSearchText(e.target.value)} onPressEnter={() => fetchCollections(collectionSearchText)} allowClear />
                                    <Button type="primary" onClick={() => fetchCollections(collectionSearchText)}>搜索</Button>
                                </div>
                                {hasPublishPerm && (
                                    <Button type="primary" icon={<Plus size={16} />} onClick={() => openCollectionModal()} style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', borderRadius: 8 }}>新建合集</Button>
                                )}
                            </div>
                            <Table columns={collectionColumns} dataSource={collections} rowKey="id" loading={collectionLoading} pagination={{ pageSize: 15 }} />
                        </>
                    )
                }
            ]} />

            {/* 演出抽屉表单 */}
            <Drawer title={<span style={{ color: '#FF8899', fontWeight: 'bold', fontSize: 16 }}>{editingRecord ? '编辑演出信息' : '发布新演出'}</span>} width={760} open={drawerVisible} onClose={() => { setDrawerVisible(false); setEditingRecord(null); }} destroyOnClose bodyStyle={{ paddingBottom: 80 }}>
                <AddEventForm editingRecord={editingRecord} onSuccess={() => { setDrawerVisible(false); setEditingRecord(null); fetchEvents(pagination.current, pagination.pageSize); }} />
            </Drawer>

            {/* 🚨 升级版：合集查看与双向绑定编辑弹窗 */}
            <Modal
                title={<span style={{ fontWeight: 'bold' }}>{editingCollection ? `查看与编辑合集: ${editingCollection.name}` : '新建合集/巡演'}</span>}
                open={collectionModalVisible}
                onOk={handleCollectionSubmit}
                onCancel={() => setCollectionModalVisible(false)}
                okText="保存配置"
                cancelText="关闭"
                width={820}
                destroyOnClose
            >
                <Form form={collectionForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item name="name" label="合集名称" rules={[{ required: true, message: '请输入合集名称' }]}>
                        <Input placeholder="例如：Ave Mujica 1st 巡演" size="large" />
                    </Form.Item>

                    <Form.List name="collectionEvents">
                        {(fields, { add, remove }) => (
                            <>
                                <div style={{ marginBottom: 10, fontWeight: 600, color: '#333' }}>
                                    包含演出场次
                                    <span style={{ marginLeft: 8, color: '#999', fontSize: 12, fontWeight: 400 }}>
                                        选择演出后，可为该演出单独设置合集别名，例如：北京场 / Day 1 / 首发站
                                    </span>
                                </div>

                                {fields.length === 0 && (
                                    <div style={{
                                        marginBottom: 12,
                                        padding: '10px 12px',
                                        borderRadius: 8,
                                        background: '#fff7e6',
                                        border: '1px solid #ffe7ba',
                                        color: '#8c6d1f',
                                        fontSize: 13
                                    }}>
                                        当前合集还没有绑定演出，请点击下方“添加合集演出”
                                    </div>
                                )}

                                {fields.map(({ key, name, ...restField }) => {
                                    const rows = collectionForm.getFieldValue('collectionEvents') || [];
                                    const currentEventId = rows?.[name]?.eventId;
                                    const selectedIds = rows
                                        .map((item, idx) => idx === name ? null : item?.eventId)
                                        .filter(Boolean)
                                        .map(Number);

                                    return (
                                        <div
                                            key={key}
                                            style={{
                                                marginBottom: 12,
                                                padding: 12,
                                                borderRadius: 12,
                                                background: '#fff8fa',
                                                border: '1px solid rgba(255, 136, 153, 0.18)'
                                            }}
                                        >
                                            <Space align="baseline" style={{ width: '100%' }}>
                                                <Form.Item
                                                    {...restField}
                                                    name={[name, 'eventId']}
                                                    rules={[{ required: true, message: '请选择演出' }]}
                                                    style={{ flex: 1, minWidth: 390, marginBottom: 0 }}
                                                >
                                                    <Select
                                                        showSearch
                                                        placeholder={availableEvents.length === 0 ? '暂无可关联的空闲演出' : '请选择要加入合集的演出'}
                                                        size="large"
                                                        optionLabelProp="label"
                                                        filterOption={(input, option) =>
                                                            String(option?.searchText || '')
                                                                .toLowerCase()
                                                                .includes(input.toLowerCase())
                                                        }
                                                        onChange={(eventId) => {
                                                            const event = availableEvents.find(e => Number(e.id) === Number(eventId));
                                                            const nextRows = collectionForm.getFieldValue('collectionEvents') || [];
                                                            const oldAlias = nextRows?.[name]?.collectionAlias;
                                                            nextRows[name] = {
                                                                ...nextRows[name],
                                                                eventId,
                                                                collectionAlias: oldAlias || event?.collectionAlias || ''
                                                            };
                                                            collectionForm.setFieldsValue({ collectionEvents: nextRows });
                                                        }}
                                                    >
                                                        {availableEvents.map(e => {
                                                            const fullLabel = getAvailableEventOptionFullLabel(e);
                                                            const shortLabel = getAvailableEventOptionShortLabel(e);

                                                            return (
                                                                <Select.Option
                                                                    key={e.id}
                                                                    value={e.id}
                                                                    disabled={selectedIds.includes(Number(e.id))}
                                                                    label={
                                                                        <span className="collection-event-select-label" title={fullLabel}>
                        {shortLabel}
                    </span>
                                                                    }
                                                                    searchText={fullLabel}
                                                                >
                                                                    <div className="collection-event-option" title={fullLabel}>
                                                                        {shortLabel}
                                                                    </div>
                                                                </Select.Option>
                                                            );
                                                        })}
                                                    </Select>
                                                </Form.Item>

                                                <Form.Item
                                                    {...restField}
                                                    name={[name, 'collectionAlias']}
                                                    style={{ width: 190, marginBottom: 0 }}
                                                >
                                                    <Input size="large" placeholder="别名，如：北京场" maxLength={30} />
                                                </Form.Item>

                                                <Button
                                                    type="link"
                                                    size="small"
                                                    disabled={!currentEventId}
                                                    onClick={() => handleShowCollectionEventDetail(currentEventId)}
                                                >
                                                    查看详情
                                                </Button>

                                                <MinusCircleOutlined
                                                    onClick={() => remove(name)}
                                                    style={{ color: '#ff4d4f', fontSize: 18, cursor: 'pointer' }}
                                                />
                                            </Space>

                                            {currentEventId && (
                                                <div
                                                    className="collection-current-event-line"
                                                    title={getAvailableEventFullLabel(currentEventId)}
                                                >
                                                    当前选择：{getAvailableEventLabel(currentEventId)}
                                                </div>
                                            )}
                                        </div>
                                    );
                                })}

                                <Button
                                    type="dashed"
                                    block
                                    icon={<PlusOutlined />}
                                    size="large"
                                    onClick={() => add({ eventId: null, collectionAlias: '' })}
                                    style={{ borderColor: '#FF8899', color: '#FF8899', borderRadius: 8 }}
                                >
                                    添加合集演出
                                </Button>
                            </>
                        )}
                    </Form.List>
                </Form>
            </Modal>

            <Modal
                title="演出详情"
                open={collectionEventDetailVisible}
                onCancel={() => setCollectionEventDetailVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setCollectionEventDetailVisible(false)}>
                        关闭
                    </Button>
                ]}
                width={760}
                destroyOnClose
            >
                {collectionEventDetailLoading ? (
                    <div style={{ padding: '36px 0', textAlign: 'center', color: '#999' }}>加载中...</div>
                ) : collectionEventDetail ? (
                    renderEventDetailDescriptions(collectionEventDetail)
                ) : (
                    <div style={{ padding: '36px 0', textAlign: 'center', color: '#999' }}>暂无详情</div>
                )}
            </Modal>

            <div style={{ display: 'none' }}>
                <Image preview={{ visible: previewVisible, src: previewImageUrl, onVisibleChange: (value) => setPreviewVisible(value) }} />
            </div>
        </Card>
    );
};

export default EventManager;