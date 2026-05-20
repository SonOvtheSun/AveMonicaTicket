import React, { useState, useEffect } from 'react';
import { Card, Table, Tag, Button, Space, message, Modal, Descriptions, Image, Divider, Popconfirm } from 'antd';
import { CheckCircle, XCircle, FileSearch } from 'lucide-react';
import axios from 'axios';
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
    }, []);

    // === 审核操作 ===
    const handleAudit = async (id, type, isPass) => {
        try {
            // 💡 接口示例：/api/admin/event/audit/2?status=1 (1为通过，2为驳回)
            const endpoint = type === 'event'
                ? `/api/admin/event/audit/${id}?isPass=${isPass}`
                : `/api/admin/artist/audit/${id}?isPass=${isPass}`;

            const res = await axios.put(endpoint);
            if (res.data.code === 200) {
                message.success(`已${isPass ? '通过' : '驳回'}该申请`);
                if (type === 'event') fetchPendingEvents(eventPagination.current, eventPagination.pageSize);
                else fetchPendingArtists(artistPagination.current, artistPagination.pageSize);
                setDetailVisible(false); // 如果是在弹窗里点的，顺便关掉弹窗
            } else {
                message.error(res.data.message);
            }
        } catch (error) {
            message.error('审核操作失败');
        }
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
                    <Button type="primary" size="small" style={{ backgroundColor: '#52c41a' }} icon={<CheckCircle size={14} />} onClick={() => handleAudit(record.id, 'artist', true)}>通过</Button>
                    <Button type="primary" danger size="small" icon={<XCircle size={14} />} onClick={() => handleAudit(record.id, 'artist', false)}>驳回</Button>
                </Space>
            )
        }
    ];

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
                {detailRecord && detailType === 'event' && (
                    <Descriptions column={2} bordered size="small">
                        <Descriptions.Item label="演出标题" span={2}>{detailRecord.title}</Descriptions.Item>
                        <Descriptions.Item label="场馆">{detailRecord.venue}</Descriptions.Item>
                        <Descriptions.Item label="时间">{detailRecord.showTime ? dayjs(detailRecord.showTime).format('YYYY-MM-DD HH:mm') : ''}</Descriptions.Item>
                        <Descriptions.Item label="详细地址" span={2}>{detailRecord.address}</Descriptions.Item>

                        {/* 👇 新增：完美对齐看板样式的参演音乐人标签显示 */}
                        <Descriptions.Item label="参演音乐人" span={2}>
                            {detailRecord.artists && detailRecord.artists.length > 0 ? (
                                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                                    {detailRecord.artists.map((artist, idx) => {
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

                        <Descriptions.Item label="主海报" span={2}>
                            <Image src={detailRecord.posterUrl} width={100} style={{ borderRadius: 4 }} />
                        </Descriptions.Item>
                        <Descriptions.Item label="详情长图" span={2}>
                            {detailRecord.detailsUrl ? <Image src={detailRecord.detailsUrl} width={100} style={{ borderRadius: 4 }} /> : '无'}
                        </Descriptions.Item>
                    </Descriptions>
                )}

                {detailRecord && detailType === 'artist' && (
                    <Descriptions column={1} bordered size="small">
                        <Descriptions.Item label="艺人名称">{detailRecord.name}</Descriptions.Item>
                        <Descriptions.Item label="地区">{detailRecord.region || '该艺人暂无地区'}</Descriptions.Item>
                        <Descriptions.Item label="风格">{detailRecord.style || '该艺人暂无风格'}</Descriptions.Item>
                        <Descriptions.Item label="简介描述">{detailRecord.description || '该艺人暂无简介'}</Descriptions.Item>
                        <Descriptions.Item label="官方头像">
                            <Image src={detailRecord.avatarUrl || 'https://via.placeholder.com/100'} width={100} style={{ borderRadius: 8 }} />
                        </Descriptions.Item>
                    </Descriptions>
                )}
            </Modal>
        </div>
    );
};

export default AuditManager;