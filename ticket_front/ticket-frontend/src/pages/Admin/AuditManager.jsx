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
        try {
            // 💡 接口示例：/api/admin/event/audit/2?status=1 (1为通过，2为驳回)
            let endpoint = '';

            if (type === 'event') {
                endpoint = `/api/admin/event/audit/${id}?isPass=${isPass}`;
            } else if (type === 'artist') {
                endpoint = `/api/admin/artist/audit/${id}?isPass=${isPass}`;
            } else if (type === 'banner') {
                endpoint = `/api/admin/banner/audit/${id}?isPass=${isPass}`;
            }

            const res = await axios.put(endpoint);
            if (res.data.code === 200) {
                message.success(`已${isPass ? '通过' : '驳回'}该申请`);
                if (type === 'event') fetchPendingEvents(eventPagination.current, eventPagination.pageSize);
                else if (type === 'artist') fetchPendingArtists(artistPagination.current, artistPagination.pageSize);
                else fetchPendingBanners();
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
                    <Button type="link" icon={<FileSearch size={14} />} onClick={() => showDetails(record, 'banner')}>
                        查看详情
                    </Button>
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

            <Card title={<span style={{ fontWeight: 'bold' }}>待审核首页横幅</span>} bordered={false} style={{ borderRadius: 12 }}>
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
                {detailRecord && detailType === 'event' && (
                    <Descriptions column={2} bordered size="small">
                        <Descriptions.Item label="演出标题" span={2}>{detailRecord.title}</Descriptions.Item>

                        {/* 🚨 新增：演出音乐风格 */}
                        <Descriptions.Item label="演出风格" span={2}>
                            {detailRecord.style ? (
                                <Tag color="purple">{detailRecord.style}</Tag>
                            ) : (
                                <span style={{ color: '#999' }}>暂无风格</span>
                            )}
                        </Descriptions.Item>

                        {/* 🚨 新增：演出城市与场馆对齐显示 */}
                        <Descriptions.Item label="演出城市">
                            <Tag color="blue">{detailRecord.city || '未指定'}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="场馆">{detailRecord.venue}</Descriptions.Item>

                        {/* 🚨 新增：将演出时间和开票时间放在同一行对比显示 */}
                        <Descriptions.Item label="演出时间">
                            {detailRecord.showTime ? dayjs(detailRecord.showTime).format('YYYY-MM-DD HH:mm:ss') : '待定'}
                        </Descriptions.Item>
                        <Descriptions.Item label="预开票时间">
                            {detailRecord.saleTime ? (
                                <span style={{ color: '#e60026', fontWeight: 'bold' }}>
                                    {dayjs(detailRecord.saleTime).format('YYYY-MM-DD HH:mm:ss')}
                                </span>
                            ) : (
                                <span style={{ color: '#999' }}>待定</span>
                            )}
                        </Descriptions.Item>

                        <Descriptions.Item label="详细地址" span={2}>{detailRecord.address}</Descriptions.Item>

                        {/* 👇 保持不变的参演音乐人显示逻辑 */}
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

                        {/* 🚨 新增：票务档位策略查看 */}
                        <Descriptions.Item label="票务档位" span={2}>
                            {detailRecord.tickets && detailRecord.tickets.length > 0 ? (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                    {detailRecord.tickets.map((ticket, idx) => (
                                        <div key={idx} style={{ display: 'flex', alignItems: 'center', gap: '12px', background: '#fff0f3', padding: '6px 12px', borderRadius: '6px', border: '1px solid #ffe6e8' }}>
                                            <Tag color="#FF8899" style={{ margin: 0 }}>{ticket.name}</Tag>
                                            <span style={{ color: '#e60026', fontWeight: 'bold', width: '80px' }}>¥ {ticket.price}</span>
                                            <span style={{ color: '#666', fontSize: '13px' }}>
                                                初始库存: <b>{ticket.totalStock || ticket.stock || 0}</b> 张
                                            </span>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <span style={{ color: '#999', fontSize: 12 }}>暂未设置票档</span>
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