import React, { useState, useEffect } from 'react';
import { Card, Table, Tag, Button, Space, message, Modal, Descriptions, Image, Divider, Popconfirm } from 'antd';
import { CheckCircle, XCircle, FileSearch } from 'lucide-react';
import axios from '../../utils/request';
import dayjs from 'dayjs'; // 用于格式化时间，消除 'T'

const AuditManager = () => {
    // === 状态管理 ===
    // 演出审核状态
    const [events, setEvents] = useState([]);
    const [loadingEvents, setLoadingEvents] = useState(false);
    const [eventPagination, setEventPagination] = useState({ current: 1, pageSize: 5, total: 0 });

    // 艺人审核状态
    const [artists, setArtists] = useState([]);
    const [loadingArtists, setLoadingArtists] = useState(false);
    const [artistPagination, setArtistPagination] = useState({ current: 1, pageSize: 5, total: 0 });

    // 详情弹窗状态
    const [detailVisible, setDetailVisible] = useState(false);
    const [detailRecord, setDetailRecord] = useState(null);
    const [detailType, setDetailType] = useState('event'); // 'event' 或 'artist'

    const [diffVisible, setDiffVisible] = useState(false);
    const [diffRows, setDiffRows] = useState([]);
    const [diffTitle, setDiffTitle] = useState('');

    const [banners, setBanners] = useState([]);
    const [loadingBanners, setLoadingBanners] = useState(false);

    const fetchPendingBanners = async () => {
        setLoadingBanners(true);
        try {
            const res = await axios.get('/api/admin/banner/audit-list');
            if (res.data.code === 200) {
                setBanners(res.data.data || []);
            }
        } catch (error) {
            message.error('获取待审核横幅失败');
        } finally {
            setLoadingBanners(false);
        }
    };

    const safeParseJson = (jsonText) => {
        if (!jsonText) return null;
        try {
            return typeof jsonText === 'string' ? JSON.parse(jsonText) : jsonText;
        } catch (e) {
            message.error('修改内容解析失败');
            return null;
        }
    };

    const formatTickets = (tickets = []) => {
        if (!tickets || tickets.length === 0) return '暂未设置票档';

        return tickets.map(t => {
            const name = t.name || '未命名票档';
            const price = t.price ?? '未设置价格';
            const stock = t.stock ?? t.totalStock ?? '未设置库存';
            return `${name} / ¥${price} / 库存${stock}`;
        }).join('\n');
    };

    const formatSessionTime = (time) => {
        if (!time) return '时间待定';
        const value = dayjs(time);
        return value.isValid() ? value.format('YYYY-MM-DD HH:mm:ss') : String(time).replace('T', ' ');
    };

    const formatSessions = (sessions = []) => {
        if (!sessions || sessions.length === 0) return '暂未设置时间场次';

        return sessions.map((session, index) => {
            const sessionName = session.sessionName || `场次${index + 1}`;
            const showTime = formatSessionTime(session.showTime);
            const saleTime = session.saleTime ? formatSessionTime(session.saleTime) : '未设置开票时间';
            const ticketsText = formatTickets(session.tickets || []);

            return `${sessionName}
                演出时间：${showTime}
                开票时间：${saleTime}
                票档：
                ${ticketsText}`;
        }).join('\n\n');
    };

    const formatArtists = (artists = []) => {
        if (!artists || artists.length === 0) return '暂无艺人';

        return artists.map(a => {
            if (typeof a === 'object') {
                return a.name ? `${a.name}${a.id ? `（ID:${a.id}）` : ''}` : `艺人ID：${a.id}`;
            }
            return `艺人ID：${a}`;
        }).join(' / ');
    };

    const formatArtistIds = (artistIds = []) => {
        if (!artistIds || artistIds.length === 0) return '暂无艺人';
        return artistIds.map(id => `艺人ID：${id}`).join(' / ');
    };

    const buildEventExtraDiffRows = (record, pending) => {

        const rows = [];

        const oldSessions = formatSessions(record.sessions || []);
        const newSessions = formatSessions(pending.sessions || []);

        if (oldSessions !== newSessions) {
            rows.push({
                label: '时间场次与票档',
                oldText: oldSessions,
                newText: newSessions,
                changed: true
            });
        }

        const oldArtistList = record.artists || [];
        const newArtistList = record.pendingArtists && record.pendingArtists.length > 0
            ? record.pendingArtists
            : (pending.artistIds || []);

        const oldArtistIds = oldArtistList
            .map(a => Number(a.id))
            .filter(id => Number.isFinite(id))
            .sort((a, b) => a - b);

        const newArtistIds = newArtistList
            .map(a => Number(typeof a === 'object' ? a.id : a))
            .filter(id => Number.isFinite(id))
            .sort((a, b) => a - b);

        const isSameArtists =
            oldArtistIds.length === newArtistIds.length &&
            oldArtistIds.every((id, index) => id === newArtistIds[index]);

        if (!isSameArtists) {
            rows.push({
                label: '参演艺人',
                oldText: formatArtists(oldArtistList),
                newText: formatArtists(newArtistList),
                changed: true
            });
        }

        return rows;
    };

    const normalizeValue = (value) => {
        if (value === null || value === undefined || value === '') return '未设置';
        if (Array.isArray(value)) return value.join(' / ');
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value).replace('T', ' ');
    };

    const buildDiffRows = (record, pending, fieldMap) => {
        return fieldMap
            .map(item => {
                const oldValue = record?.[item.key];
                const newValue = pending?.[item.key];

                const oldText = normalizeValue(oldValue);
                const newText = normalizeValue(newValue);

                return {
                    label: item.label,
                    oldText,
                    newText,
                    changed: oldText !== newText
                };
            })
            .filter(item => item.changed);
    };

    const showModifyDiff = (record, type) => {
        const pending = safeParseJson(record.pendingPayload);

        if (!pending) {
            message.warning('暂无可查看的修改内容');
            return;
        }

        let fieldMap = [];
        let title = '';

        if (type === 'event') {
            title = '演出修改内容';
            fieldMap = [
                { key: 'title', label: '演出标题' },
                { key: 'city', label: '演出城市' },
                { key: 'venue', label: '演出场馆' },
                { key: 'address', label: '详细地址' },
                { key: 'showTime', label: '演出时间' },
                { key: 'saleTime', label: '开票时间' },
                { key: 'style', label: '演出风格' },
                { key: 'posterUrl', label: '主海报' },
                { key: 'detailsUrl', label: '详情长图' },
                { key: 'status', label: '演出状态' },
                { key: 'runningTime', label: '演出时长' }
            ];
        } else if (type === 'artist') {
            title = '艺人修改内容';
            fieldMap = [
                { key: 'name', label: '艺人名称' },
                { key: 'region', label: '国家/地区' },
                { key: 'style', label: '音乐风格' },
                { key: 'description', label: '艺人简介' },
                { key: 'avatarUrl', label: '头像' }
            ];
        } else if (type === 'banner') {
            title = 'Banner 修改内容';
            fieldMap = [
                { key: 'posterUrl', label: '横幅图片' },
                { key: 'eventId', label: '关联演出ID' },
                { key: 'startTime', label: '开始展示时间' },
                { key: 'endTime', label: '结束展示时间' }
            ];
        }

        let rows = buildDiffRows(record, pending, fieldMap);

        if (type === 'event') {
            rows = [
                ...rows,
                ...buildEventExtraDiffRows(record, pending)
            ];
        }

        setDiffTitle(title);
        setDiffRows(rows);
        setDiffVisible(true);
    };

    // === 数据获取 ===
    // 1. 获取待审核演出
    const fetchPendingEvents = async (page = 1, size = 5) => {
        setLoadingEvents(true);
        try {
            // 💡 请确保这里的接口路径与你的后端一致 (通常是获取 auditStatus = 0 的列表)
            const res = await axios.get('/api/admin/event/audit-list', {
                params: { current: page, size: size }
            });
            if (res.data.code === 200) {
                setEvents(res.data.data.records);
                setEventPagination({
                    current: res.data.data.current,
                    pageSize: res.data.data.size,
                    total: res.data.data.total
                });
            }
        } catch (error) {
            message.error('获取待审核演出失败');
        } finally {
            setLoadingEvents(false);
        }
    };

    // 2. 获取待审核艺人
    const fetchPendingArtists = async (page = 1, size = 5) => {
        setLoadingArtists(true);
        try {
            // 💡 请确保这里的接口路径与你的后端一致
            const res = await axios.get('/api/admin/artist/audit-list', {
                params: { current: page, size: size }
            });
            if (res.data.code === 200) {
                setArtists(res.data.data.records);
                setArtistPagination({
                    current: res.data.data.current,
                    pageSize: res.data.data.size,
                    total: res.data.data.total
                });
            }
        } catch (error) {
            message.error('获取待审核艺人失败');
        } finally {
            setLoadingArtists(false);
        }
    };

    useEffect(() => {
        fetchPendingEvents();
        fetchPendingArtists();
        fetchPendingBanners();
    }, []);

    // === 审核操作 ===
    const handleAudit = async (id, type, isPass) => {
        if (!id) {
            message.error('审核对象ID为空');
            return;
        }

        let endpoint = '';

        if (type === 'event') {
            endpoint = `/api/admin/event/audit/${id}?isPass=${isPass}`;
        } else if (type === 'artist') {
            endpoint = `/api/admin/artist/audit/${id}?isPass=${isPass}`;
        } else if (type === 'banner') {
            endpoint = `/api/admin/banner/audit/${id}?isPass=${isPass}`;
        } else {
            message.error('未知审核类型');
            return;
        }

        try {
            const res = await axios.put(endpoint);

            if (res.data.code === 200) {
                message.success(res.data.message || `已${isPass ? '通过' : '驳回'}该申请`);

                if (type === 'event') {
                    fetchPendingEvents(eventPagination.current, eventPagination.pageSize);
                } else if (type === 'artist') {
                    fetchPendingArtists(artistPagination.current, artistPagination.pageSize);
                } else if (type === 'banner') {
                    fetchPendingBanners();
                }

                setDetailVisible(false);
            } else {
                message.error(res.data.message || '审核失败');
            }
        } catch (error) {
            console.error('审核接口异常:', error);
            console.error('后端返回:', error.response?.data);

            message.error(
                error.response?.data?.message ||
                error.response?.data?.msg ||
                error.response?.data?.error ||
                '审核操作失败'
            );
        }
    };

    const getAuditDisplayRecord = (record, type) => {
        if (!record) return null;

        // 非修改审核，直接展示当前记录
        if (record.editAuditStatus !== 0 || !record.pendingPayload) {
            return record;
        }

        const pending = safeParseJson(record.pendingPayload);
        if (!pending) return record;

        if (type === 'event') {
            return {
                ...record,
                ...pending,

                // 多场次模型优先展示 pending.sessions
                sessions: pending.sessions || record.sessions || [],

                // 兼容旧字段
                tickets: pending.tickets || record.tickets || [],

                artists: record.pendingArtists && record.pendingArtists.length > 0
                    ? record.pendingArtists
                    : (
                        pending.artistIds
                            ? pending.artistIds.map(id => ({
                                id,
                                name: `艺人ID：${id}`,
                                auditStatus: 1
                            }))
                            : (record.artists || [])
                    ),

                __isPendingPreview: true
            };
        }

        if (type === 'artist') {
            return {
                ...record,
                ...pending,
                __isPendingPreview: true
            };
        }

        if (type === 'banner') {
            return {
                ...record,
                ...pending,
                __isPendingPreview: true
            };
        }

        return record;
    };

    // === 打开详情弹窗 ===
    const showDetails = (record, type) => {
        setDetailRecord(record);
        setDetailType(type);
        setDetailVisible(true);
    };

    // === 表格列配置 ===
    // 演出列表 Columns
    const eventColumns = [
        {
            title: '海报',
            dataIndex: 'posterUrl',
            key: 'posterUrl',
            render: (url) => <Image src={url} width={40} height={55} style={{ objectFit: 'cover', borderRadius: 4 }} />
        },
        { title: '演出标题', dataIndex: 'title', key: 'title' },
        {
            title: '申请时间',
            dataIndex: 'createTime', // 假设后端叫这个名字
            key: 'createTime',
            // 🚨 核心修复：消除 'T'，格式化为更易读的日期时间
            render: (text) => text ? dayjs(text).format('YYYY-MM-DD HH:mm:ss') : '未知'
        },
        {
            title: '状态',
            dataIndex: 'auditStatus',
            key: 'auditStatus',
            render: () => <Tag color="warning">待审核</Tag>
        },
        {
            title: '详情',
            key: 'details',
            render: (_, record) => (
                <Button type="link" size="small" icon={<FileSearch size={14} />} onClick={() => showDetails(record, 'event')}>
                    查看详情
                </Button>
            )
        },

        {
            title: '审核操作',
            key: 'action',
            render: (_, record) => (
                <Space>
                    {record.editAuditStatus === 0 && (
                        <Button
                            type="link"
                            size="small"
                            onClick={() => showModifyDiff(record, 'event')}
                        >
                            查看修改内容
                        </Button>
                    )}
                    <Popconfirm
                        title="确定通过审核？"
                        description="通过后，该演出将转为预售状态。"
                        onConfirm={() => handleAudit(record.id, 'event', true)}
                        okText="确定通过"
                        cancelText="取消"
                    >
                        <Button type="primary" size="small" style={{ backgroundColor: '#52c41a' }} icon={<CheckCircle size={14} />}>通过</Button>
                    </Popconfirm>

                    <Popconfirm
                        title="确定驳回该申请？"
                        description="驳回后，该演出将被打回。"
                        onConfirm={() => handleAudit(record.id, 'event', false)}
                        okText="确定驳回"
                        cancelText="再想想"
                        okButtonProps={{ danger: true }} // 驳回确认按钮标红，增强警示
                    >
                        <Button type="primary" danger size="small" icon={<XCircle size={14} />}>驳回</Button>
                    </Popconfirm>
                </Space>
            )
        }
    ];

    // 艺人列表 Columns
    const artistColumns = [
        {
            title: '头像',
            dataIndex: 'avatarUrl',
            key: 'avatarUrl',
            render: (url) => <Image src={url || 'https://via.placeholder.com/40'} width={40} height={40} style={{ borderRadius: '50%', objectFit: 'cover' }} />
        },
        { title: '艺人/乐队名', dataIndex: 'name', key: 'name' },
        {
            title: '申请时间',
            dataIndex: 'createTime',
            key: 'createTime',
            render: (text) => text ? dayjs(text).format('YYYY-MM-DD HH:mm:ss') : '未知'
        },
        {
            title: '状态',
            dataIndex: 'auditStatus',
            key: 'auditStatus',
            render: () => <Tag color="warning">待审核</Tag>
        },
        {
            title: '详情',
            key: 'details',
            render: (_, record) => (
                <Button type="link" size="small" icon={<FileSearch size={14} />} onClick={() => showDetails(record, 'artist')}>
                    查看详情
                </Button>
            )
        },
        {
            title: '审核操作',
            key: 'action',
            render: (_, record) => (
                <Space>
                    {record.editAuditStatus === 0 && (
                        <Button
                            type="link"
                            size="small"
                            onClick={() => showModifyDiff(record, 'artist')}
                        >
                            查看修改内容
                        </Button>
                    )}
                    <Button type="primary" size="small" style={{ backgroundColor: '#52c41a' }} icon={<CheckCircle size={14} />} onClick={() => handleAudit(record.id, 'artist', true)}>通过</Button>
                    <Button type="primary" danger size="small" icon={<XCircle size={14} />} onClick={() => handleAudit(record.id, 'artist', false)}>驳回</Button>
                </Space>
            )
        }
    ];

    const bannerColumns = [
        {
            title: '横幅',
            dataIndex: 'posterUrl',
            key: 'posterUrl',
            render: (url) => <Image src={url} width={120} style={{ borderRadius: 6 }} />
        },
        {
            title: '关联演出ID',
            dataIndex: 'eventId',
            key: 'eventId',
            render: id => id || <span style={{ color: '#999' }}>无跳转</span>
        },
        {
            title: '审核类型',
            key: 'auditType',
            render: (_, record) => record.editAuditStatus === 0
                ? <Tag color="processing">修改审核</Tag>
                : <Tag color="orange">新增审核</Tag>
        },
        {
            title: '展示时间',
            key: 'time',
            render: (_, record) => (
                <span>
                {dayjs(record.startTime).format('YYYY-MM-DD HH:mm')} ~ {dayjs(record.endTime).format('YYYY-MM-DD HH:mm')}
            </span>
            )
        },
        {
            title: '操作',
            key: 'action',
            render: (_, record) => (
                <Space>
                    {record.editAuditStatus === 0 && (
                        <Button
                            type="link"
                            size="small"
                            onClick={() => showModifyDiff(record, 'banner')}
                        >
                            查看修改内容
                        </Button>
                    )}
                    <Button type="primary" size="small" style={{ backgroundColor: '#52c41a' }} icon={<CheckCircle size={14} />} onClick={() => handleAudit(record.id, 'banner', true)}>
                        通过
                    </Button>
                    <Button type="primary" danger size="small" icon={<XCircle size={14} />} onClick={() => handleAudit(record.id, 'banner', false)}>
                        驳回
                    </Button>
                </Space>
            )
        }
    ];
    const displayRecord = getAuditDisplayRecord(detailRecord, detailType);

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            {/* 卡片 1：待审核演出 */}
            <Card title={
                <div style={{display: 'flex'}}>
                <span style={{ fontWeight: 'bold' }}>待审核演出项目
                </span>
                </div>
            } bordered={false} style={{ borderRadius: 12 }}>
                <Table
                    columns={eventColumns}
                    dataSource={events}
                    rowKey="id"
                    loading={loadingEvents}
                    pagination={eventPagination}
                    onChange={(newPagination) => fetchPendingEvents(newPagination.current, newPagination.pageSize)}
                />
            </Card>

            {/* 卡片 2：待审核艺人 */}
            <Card title={
                <div style = {{display:'flex'}}>
                <span style={{ fontWeight: 'bold' }}>待审核入驻艺人
                </span></div>
            } bordered={false} style={{ borderRadius: 12 }}>
                <Table
                    columns={artistColumns}
                    dataSource={artists}
                    rowKey="id"
                    loading={loadingArtists}
                    pagination={artistPagination}
                    onChange={(newPagination) => fetchPendingArtists(newPagination.current, newPagination.pageSize)}
                />
            </Card>

            <Card title={<div style = {{display:'flex'}}><span style={{ fontWeight: 'bold' }}>待审核首页横幅</span></div>} bordered={false} style={{ borderRadius: 12 }}>
                <Table
                    columns={bannerColumns}
                    dataSource={banners}
                    rowKey="id"
                    loading={loadingBanners}
                    pagination={false}
                />
            </Card>

            {/* 统一的详情查看弹窗 */}
            <Modal
                title={`审核详情 - ${detailType === 'event' ? '演出项目' : '艺人'}`}
                open={detailVisible}
                onCancel={() => setDetailVisible(false)}
                width={700}
                footer={[
                    <Button key="reject" danger onClick={() => handleAudit(detailRecord?.id, detailType, false)}>驳回申请</Button>,
                    <Button key="pass" type="primary" style={{ backgroundColor: '#52c41a' }} onClick={() => handleAudit(detailRecord?.id, detailType, true)}>通过审核</Button>
                ]}
            >
                {displayRecord && detailType === 'event' && (
                    <Descriptions column={2} bordered size="small">

                        <Descriptions.Item label="演出标题" span={2}>{displayRecord.title}</Descriptions.Item>

                        {/* 🚨 新增：演出音乐风格 */}
                        <Descriptions.Item label="演出风格" span={2}>
                            {displayRecord.style ? (
                                <Tag color="purple">{displayRecord.style}</Tag>
                            ) : (
                                <span style={{ color: '#999' }}>暂无风格</span>
                            )}
                        </Descriptions.Item>

                        {/* 🚨 新增：演出城市与场馆对齐显示 */}
                        <Descriptions.Item label="演出城市">
                            <Tag color="blue">{displayRecord.city || '未指定'}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="场馆">{displayRecord.venue}</Descriptions.Item>

                        {/* 🚨 新增：将演出时间和开票时间放在同一行对比显示 */}
                        <Descriptions.Item label="演出时间">
                            {displayRecord.showTime ? dayjs(displayRecord.showTime).format('YYYY-MM-DD HH:mm:ss') : '待定'}
                        </Descriptions.Item>
                        <Descriptions.Item label="预开票时间">
                            {displayRecord.saleTime ? (
                                <span style={{ color: '#e60026', fontWeight: 'bold' }}>
                                    {dayjs(displayRecord.saleTime).format('YYYY-MM-DD HH:mm:ss')}
                                </span>
                            ) : (
                                <span style={{ color: '#999' }}>待定</span>
                            )}
                        </Descriptions.Item>

                        <Descriptions.Item label="详细地址" span={2}>{displayRecord.address}</Descriptions.Item>

                        {/* 👇 保持不变的参演音乐人显示逻辑 */}
                        <Descriptions.Item label="参演音乐人" span={2}>
                            {displayRecord.artists && displayRecord.artists.length > 0 ? (
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                                    {displayRecord.artists.map((artist, idx) => {
                                        let tagColor = 'cyan';
                                        let displayText = artist.name;

                                        if (artist.notFound) {
                                            tagColor = 'red';
                                            displayText = `⚠️ ${artist.name}`;
                                        } else if (artist.auditStatus === 0) {
                                            tagColor = '#FF8899';
                                            displayText = `${artist.name} (待审)`;
                                        }

                                        return (
                                            <Tag key={idx} color={tagColor} style={{ margin: 0, borderRadius: 4 }}>
                                                {displayText}
                                            </Tag>
                                        );
                                    })}
                                </div>
                            ) : (
                                <span style={{ color: '#999', fontSize: 12 }}>暂无配置音乐人</span>
                            )}
                        </Descriptions.Item>

                        {/* 🚨 新增：票务档位策略查看 */}
                        <Descriptions.Item label="时间场次" span={2}>
                            {displayRecord.sessions && displayRecord.sessions.length > 0 ? (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                                    {displayRecord.sessions.map((session, idx) => (
                                        <div
                                            key={session.id || idx}
                                            style={{
                                                padding: '12px 14px',
                                                borderRadius: 10,
                                                background: '#f6fffe',
                                                border: '1px solid rgba(23, 185, 185, 0.18)'
                                            }}
                                        >
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                                                <Tag color="cyan" style={{ margin: 0 }}>
                                                    {session.sessionName || `场次${idx + 1}`}
                                                </Tag>
                                                <span style={{ color: '#333', fontWeight: 700 }}>
                            {session.showTime ? dayjs(session.showTime).format('YYYY-MM-DD HH:mm:ss') : '时间待定'}
                        </span>
                                            </div>

                                            <div style={{ color: '#999', fontSize: 12, marginBottom: 8 }}>
                                                开票时间：
                                                {session.saleTime ? dayjs(session.saleTime).format('YYYY-MM-DD HH:mm:ss') : '未设置'}
                                            </div>

                                            {session.tickets && session.tickets.length > 0 ? (
                                                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                                                    {session.tickets.map((ticket, ticketIdx) => (
                                                        <div
                                                            key={ticket.id || ticketIdx}
                                                            style={{
                                                                display: 'flex',
                                                                alignItems: 'center',
                                                                gap: 12,
                                                                padding: '6px 10px',
                                                                borderRadius: 6,
                                                                background: '#fff'
                                                            }}
                                                        >
                                                            <Tag color="#FF8899" style={{ margin: 0 }}>
                                                                {ticket.name || '未命名票档'}
                                                            </Tag>
                                                            <span style={{ color: '#e60026', fontWeight: 800 }}>
                                        ¥ {ticket.price ?? '未设置'}
                                    </span>
                                                            <span style={{ color: '#666', fontSize: 12 }}>
                                        库存：{ticket.stock ?? ticket.totalStock ?? 0}
                                    </span>
                                                        </div>
                                                    ))}
                                                </div>
                                            ) : (
                                                <span style={{ color: '#999', fontSize: 12 }}>该场次暂未设置票档</span>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <span style={{ color: '#999', fontSize: 12 }}>暂未设置时间场次</span>
                            )}
                        </Descriptions.Item>

                        <Descriptions.Item label="主海报" span={2}>
                            <Image src={displayRecord.posterUrl} width={100} style={{ borderRadius: 4 }} />
                        </Descriptions.Item>
                        <Descriptions.Item label="详情长图" span={2}>
                            {displayRecord.detailsUrl ? <Image src={displayRecord.detailsUrl} width={100} style={{ borderRadius: 4 }} /> : '无'}
                        </Descriptions.Item>
                    </Descriptions>
                )}

                {displayRecord && detailType === 'artist' && (
                    <Descriptions column={1} bordered size="small">
                        <Descriptions.Item label="艺人名称">{displayRecord.name}</Descriptions.Item>
                        <Descriptions.Item label="地区">{displayRecord.region || '该艺人暂无地区'}</Descriptions.Item>
                        <Descriptions.Item label="风格">{displayRecord.style || '该艺人暂无风格'}</Descriptions.Item>
                        <Descriptions.Item label="简介描述">{displayRecord.description || '该艺人暂无简介'}</Descriptions.Item>
                        <Descriptions.Item label="官方头像">
                            <Image src={displayRecord.avatarUrl || 'https://via.placeholder.com/100'} width={100} style={{ borderRadius: 8 }} />
                        </Descriptions.Item>
                    </Descriptions>
                )}
            </Modal>
            <Modal
                title={diffTitle}
                open={diffVisible}
                onCancel={() => setDiffVisible(false)}
                footer={[
                    <Button key="close" onClick={() => setDiffVisible(false)}>
                        关闭
                    </Button>
                ]}
                width={820}
            >
                {diffRows.length > 0 ? (
                    <Descriptions column={1} bordered size="small">
                        {diffRows.map(row => (
                            <Descriptions.Item key={row.label} label={row.label}>
                                <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                                    <div style={{ flex: 1 }}>
                                        <div style={{ color: '#999', fontSize: 12, marginBottom: 4 }}>修改前</div>
                                        <div style={{
                                            padding: '8px 10px',
                                            borderRadius: 6,
                                            background: '#fafafa',
                                            color: '#666',
                                            wordBreak: 'break-all',
                                            whiteSpace: 'pre-wrap'
                                        }}>
                                            {row.oldText}
                                        </div>
                                    </div>

                                    <div style={{ flex: 1 }}>
                                        <div style={{ color: '#FF8899', fontSize: 12, marginBottom: 4 }}>修改后</div>
                                        <div style={{
                                            padding: '8px 10px',
                                            borderRadius: 6,
                                            background: '#fff0f3',
                                            color: '#e60026',
                                            wordBreak: 'break-all',
                                            whiteSpace: 'pre-wrap',
                                            fontWeight: 600
                                        }}>
                                            {row.newText}
                                        </div>
                                    </div>
                                </div>
                            </Descriptions.Item>
                        ))}
                    </Descriptions>
                ) : (
                    <div style={{ color: '#999', textAlign: 'center', padding: '30px 0' }}>
                        未检测到字段变化
                    </div>
                )}
            </Modal>
        </div>
    );
};

export default AuditManager;