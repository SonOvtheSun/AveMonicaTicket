import React, { useState, useEffect } from 'react';
// 👈 重点：引入 Image (用于点击预览图片) 和 Popover (用于悬浮查看票档)
import {Table, Button, Space, Card, Tag, Modal, Drawer, message, Image, Popover, Input, Popconfirm} from 'antd';
import { Plus, Edit, Trash2, EyeOff, Eye, ShieldOff } from 'lucide-react';
import axios from 'axios';
import AddEventForm from './AddEventForm';
import './EventManager.css';
import { SearchOutlined } from '@ant-design/icons';

const EventManager = () => {
    const [searchText, setSearchText] = useState('');

    const [events, setEvents] = useState([]);
    const [loading, setLoading] = useState(false);
    const [drawerVisible, setDrawerVisible] = useState(false);

    const [previewVisible, setPreviewVisible] = useState(false);
    const [previewImageUrl, setPreviewImageUrl] = useState('');

    const [editingRecord, setEditingRecord] = useState(null);

    const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });

    const [userPerms, setUserPerms] = useState([]);
    const [userRole, setUserRole] = useState(6);

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
        fetchEvents(); }, []);

    const isSuperAdmin = userRole === 1;
    const hasPublishPerm = isSuperAdmin || userPerms.includes('event:publish');
    const hasAuditPerm = isSuperAdmin || userPerms.includes('audit:manage');

    const handleTableChange = (newPagination) => fetchEvents(newPagination.current, newPagination.pageSize);

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

    const handleRevokeAudit = async (id) => {
        try {
            const res = await axios.put(`/api/admin/event/revoke/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已撤销审核');
                fetchEvents(pagination.current, pagination.pageSize);
            } else {
                message.error(res.data.message || '撤销失败');
            }
        } catch (error) {
            message.error('撤销审核失败');
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
        setEditingRecord(record); // 将当前这一行的完整数据喂给状态
        setDrawerVisible(true);    // 唤起抽屉
    };

    const statusConfig = {
        1: { color: 'cyan', text: '预售中' },
        2: { color: 'green', text: '在售' },
        3: { color: 'red', text: '已停售' },
        4: { color: 'default', text: '已隐藏' }
    };

    // ==========================================
    // 💡 全新升级的 Table Columns 配置
    // ==========================================
    const columns = [
        {
            title: '海报',
            dataIndex: 'posterUrl',
            key: 'poster',
            width: 70,
            // 需求 3：使用 Antd 的 Image 组件，自带点击弹出大图预览功能！
            render: (url) => (
                <Image
                    src={url || 'https://via.placeholder.com/40x55?text=No+Pic'}
                    width={40}
                    height={55}
                    style={{ objectFit: 'cover', borderRadius: 4, border: '1px solid #f0f0f0' }}
                />
            )
        },
        {
            title: '演出标题及艺人',
            key: 'titleAndArtists',
            render: (_, record) => (
                <div style={{ wordBreak: 'break-all', whiteSpace: 'pre-wrap', minWidth: 200 }}>
                    {/* 1. 演出标题 (🚨 新增隐藏黄字后缀判断) */}
                    <div style={{ fontWeight: 600, color: '#333', fontSize: 14, display: 'flex', alignItems: 'center' }}>
                        <span>{record.title}</span>
                        {record.status === 4 && (
                            <span style={{ color: '#faad14', marginLeft: 8, fontSize: 13, fontWeight: 500 }}>
                                (已隐藏)
                            </span>
                        )}
                    </div>

                    {/* 2. 艺人阵容标签排布 (保持不变) */}
                    {record.artists && record.artists.length > 0 && (
                        <div style={{ marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                            {record.artists.map((artist, idx) => {
                                let tagColor = 'cyan';
                                let tagBorder = 'transparent';
                                let displayText = artist.name;

                                if (artist.notFound) {
                                    tagColor = 'red';
                                    displayText = `⚠️ ${artist.name}`;
                                } else if (artist.auditStatus === 0) {
                                    tagColor = '#FF8899';
                                    displayText = `${artist.name} (待审)`;
                                }

                                return (
                                    <Tag key={idx} color={tagColor} style={{ margin: 0, borderRadius: 4, border: tagBorder }}>
                                        {displayText}
                                    </Tag>
                                );
                            })}
                        </div>
                    )}
                </div>
            )
        },
        {
            title: '详图',
            dataIndex: 'detailsUrl',
            key: 'detailsUrl',
            width: 90, // 💡 稍微加宽一点点（改为90），确保“查看详图”四个字在一行内不换行
            render: (url) => (
                url ? (
                    // 💡 改为青色链接样式的按钮，点击时把图片 URL 喂给状态，并打开大图预览
                    <Button
                        type="link"
                        size="small"
                        style={{ padding: 0, color: '#17b9b9' }}
                        onClick={() => {
                            setPreviewImageUrl(url);
                            setPreviewVisible(true);
                        }}
                    >
                        查看详图
                    </Button>
                ) : <span style={{ color: '#999', fontSize: 12 }}>无</span>
            )
        },
        {
            title: '场馆与地址',
            key: 'location',
            // 需求 2 & 6：组合场馆与详细地址，竖向排布，不限高度
            render: (_, record) => (
                <div style={{ wordBreak: 'break-all', whiteSpace: 'pre-wrap', minWidth: 140 }}>
                    <div style={{ color: '#333' }}>{record.venue}</div>
                    <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>{record.address}</div>
                </div>
            )
        },
        {
            title: '演出时间',
            dataIndex: 'showTime',
            key: 'showTime',
            width: 140
        },
        {
            title: '票务策略',
            key: 'tickets',
            width: 90,
            // 需求 4：用优雅的悬浮气泡显示多行票档信息
            render: (_, record) => {
                const tickets = record.tickets || [];

                if (tickets.length === 0) {
                    return <span style={{ color: '#999', fontSize: 12 }}>暂未设置票档</span>;
                }

                const popoverContent = (
                    <div style={{ minWidth: 200, maxWidth: 300 }}>
                        {tickets.map((t, index) => (
                            <div key={index} style={{ marginBottom: 8, borderBottom: '1px solid #f0f0f0', paddingBottom: 6 }}>
                                <div><Tag color="#FF8899">{t.name}</Tag> <strong style={{color: '#ff4d4f'}}>¥{t.price}</strong></div>
                                <div style={{fontSize: 12, color: '#888', marginTop: 4}}>剩余库存：{t.remainingStock ?? t.stock ?? 0} 张</div>
                            </div>
                        ))}
                    </div>
                );

                return (
                    <Popover content={popoverContent} title="票档明细" trigger="hover" placement="left">
                        <Button type="link" size="small" style={{ color: '#17b9b9', padding: 0 }}>
                            查看票档
                        </Button>
                    </Popover>
                );
            }
        },
        {
            title: '当前状态',
            dataIndex: 'status',
            key: 'status',
            width: 90,
            render: (_, record) => {
                if (record.editAuditStatus === 0) return <Tag color="processing">修改待审核</Tag>;
                if (record.editAuditStatus === 2) return <Tag color="red">修改被驳回</Tag>;
                if (record.auditStatus === 0) return <Tag color="orange">新增待审核</Tag>;
                if (record.auditStatus === 2) return <Tag color="red">新增被驳回</Tag>;
                if (record.auditStatus === 3) return <Tag color="default">已撤销</Tag>;

                const config = statusConfig[record.status] || { color: 'default', text: '未知' };
                return <Tag color={config.color}>{config.text}</Tag>;
            }
        },
        {
            title: '管理操作',
            key: 'action',
            width: 160,
            render: (_, record) => {
                const isNewPending = record.auditStatus === 0;
                const isEditPending = record.editAuditStatus === 0;
                const hasPendingAudit = isNewPending || isEditPending;
                const canEdit = isSuperAdmin || !hasPendingAudit;

                return(
                <Space size="middle">
                    {/* 场景 A：拥有发布/编辑权限的人 (超管、演出管理方) */}
                    {hasPublishPerm && (
                        <>
                            <Button
                                type="text"
                                icon={<Edit size={14} />}
                                disabled={!canEdit}
                                style={{ color: canEdit ? '#1890ff' : '#999', padding: 0 }}
                                onClick={() => handleEditClick(record)}
                            >
                                编辑
                            </Button>
                            {!isSuperAdmin && hasPendingAudit && (
                                <Popconfirm
                                    title="确定撤销审核申请？"
                                    description="撤销后可重新编辑并提交审核。"
                                    onConfirm={() => handleRevokeAudit(record.id)}
                                    okText="确定撤销"
                                    cancelText="取消"
                                    okButtonProps={{ danger: true }}
                                >
                                    <Button type="text" danger style={{ padding: 0 }}>
                                        撤销审核
                                    </Button>
                                </Popconfirm>
                            )}
                            {record.status !== 4 ? (
                                <Button type="text" icon={<EyeOff size={14} />} onClick={() => handleQuickHide(record.id)} style={{ color: '#faad14', padding: 0 }}>隐藏</Button>
                            ) : (
                                <Button type="text" icon={<Eye size={14} />} onClick={() => handleQuickShow(record.id)} style={{ color: '#52c41a', padding: 0 }}>恢复</Button>
                            )}
                            <Button type="text" icon={<Trash2 size={14} />} onClick={() => handleDelete(record.id)} style={{ color: '#ff4d4f', padding: 0 }}>删除</Button>
                        </>
                    )}
                    {/* 场景 B：仅有审核权限的人 (审核员) */}
                    {(hasAuditPerm || isSuperAdmin) && (
                        <Button
                            type="text"
                            icon={<ShieldOff size={14} />}
                            onClick={() => handleTakeDown(record.id)}
                            style={{ color: '#ff4d4f', padding: 0 }}
                            disabled={record.auditStatus === 0} // 如果已经是未审核状态，则禁用按钮
                        >
                            {record.auditStatus === 0 ? '已下架' : '下架'}
                        </Button>)
                    }

                </Space>
            )}
        },
    ];

    return (
        <Card
            title={
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}>演出项目管理看板</span>
                </div>
            }
            bordered={false}
            style={{ borderRadius: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.03)' }}
        >
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', gap: 10, flex: 1, margin: '0 0px', maxWidth: 450 }}>
                    <Input
                        placeholder="搜索演出标题、场馆或艺人名称"
                        prefix={<SearchOutlined />}
                        value={searchText}
                        onChange={e => setSearchText(e.target.value)}
                        onPressEnter={() => fetchEvents(1, pagination.pageSize, searchText)}
                        allowClear
                    />
                    <Button type="primary" onClick={() => fetchEvents(1, pagination.pageSize, searchText)}>搜索</Button>
                </div>
                {/* 👇 新增的中心搜索区域 */}
                {/* 🚨 动态判断：只有拥有 event:publish 权限的人才能看到发布按钮 */}
                {hasPublishPerm && (
                    <Button
                        type="primary"
                        icon={<Plus size={16} />}
                        onClick={() => {
                            setEditingRecord(null);
                            setDrawerVisible(true);
                        }}
                        style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', borderRadius: 8 }}
                    >
                        发布新演出
                    </Button>
                )}
            </div>
            <Table
                columns={columns}
                dataSource={events}
                rowKey="id"
                loading={loading}
                pagination={pagination}
                onChange={handleTableChange}
            />

            <Drawer
                title={
                    <span style={{ color: '#FF8899', fontWeight: 'bold', fontSize: 16 }}>
                        {editingRecord ? '编辑演出信息' : '发布新演出'}
                    </span>
                } width={760}
                open={drawerVisible}
                onClose={() => {setDrawerVisible(false); setEditingRecord(null);}}
                destroyOnClose
                bodyStyle={{ paddingBottom: 80 }}
            >
                <AddEventForm
                    editingRecord={editingRecord}
                    onSuccess={() => {
                        setDrawerVisible(false);
                        setEditingRecord(null);
                        fetchEvents(pagination.current, pagination.pageSize); // 维持当前页刷新
                    }}
                />
            </Drawer>
            <div style={{ display: 'none' }}>
                <Image
                    preview={{
                        visible: previewVisible,
                        src: previewImageUrl,
                        onVisibleChange: (value) => setPreviewVisible(value),
                    }}
                />
            </div>
        </Card>
    );
};

export default EventManager;