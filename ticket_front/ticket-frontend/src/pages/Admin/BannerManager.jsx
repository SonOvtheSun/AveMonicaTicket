import React, { useState, useEffect, useRef } from 'react';
import { Card, Table, Tabs, Button, Modal, Form, Input, DatePicker, Upload, message, Tag, Image, Space, Popconfirm, Select, Spin } from 'antd';
import { PlusOutlined, UploadOutlined, EditOutlined, DeleteOutlined, SearchOutlined } from '@ant-design/icons';
import axios from 'axios';
import dayjs from 'dayjs';

const BannerManager = () => {
    const [eventOptions, setEventOptions] = useState([]);
    const [fetchingEvents, setFetchingEvents] = useState(false);
    const searchTimeoutRef = useRef(null);

    const [activeTab, setActiveTab] = useState('2');
    const [banners, setBanners] = useState([]);
    const [loading, setLoading] = useState(false);

    const [searchText, setSearchText] = useState('');

    const [modalVisible, setModalVisible] = useState(false);
    const [editingId, setEditingId] = useState(null);
    const [form] = Form.useForm();
    const [fileList, setFileList] = useState([]);

    const [userRole, setUserRole] = useState(6);
    const isSuperAdmin = userRole === 1;

    useEffect(() => {
        axios.get('/api/user/info').then(res => {
            if (res.data.code === 200) {
                setUserRole(res.data.data.role || 6);
            }
        });
    }, []);

    const fetchRemoteEvents = async (keyword) => {
        if (!keyword) {
            setEventOptions([]);
            return;
        }
        setFetchingEvents(true);
        try {
            const res = await axios.get('/api/admin/event/list', {
                params: { current: 1, size: 20, keyword }
            });
            if (res.data.code === 200) {
                const records = res.data.data.records || [];
                const options = records.map(event => ({
                    label: `[ID: ${event.id}] ${event.title}`,
                    value: event.id
                }));
                setEventOptions(options);
            }
        } catch (error) {
            message.error('搜索演出失败');
        } finally {
            setFetchingEvents(false);
        }
    };

    const handleSearchEvent = (value) => {
        if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
        searchTimeoutRef.current = setTimeout(() => {
            fetchRemoteEvents(value);
        }, 500);
    };

    const fetchBanners = async (type = activeTab, keyword = searchText) => {
        setLoading(true);
        try {
            const res = await axios.get('/api/admin/banner/list', {
                params: { type, keyword }
            });
            if (res.data.code === 200) {
                setBanners(res.data.data || []);
            }
        } catch (error) {
            message.error('获取横幅列表失败');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchBanners(activeTab, searchText);
    }, [activeTab]);

    const handleRevokeAudit = async (id) => {
        try {
            const res = await axios.put(`/api/admin/banner/revoke/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已撤销审核');
                fetchBanners();
            } else {
                message.error(res.data.message || '撤销失败');
            }
        } catch (error) {
            message.error('撤销审核失败');
        }
    };

    const getBannerStatusTag = (record) => {
        if (record?.editAuditStatus === 0) return <Tag color="processing">修改待审核</Tag>;
        if (record?.editAuditStatus === 2) return <Tag color="red">修改被驳回</Tag>;
        if (record?.auditStatus === 0) return <Tag color="orange">新增待审核</Tag>;
        if (record?.auditStatus === 2) return <Tag color="red">新增被驳回</Tag>;
        if (record?.auditStatus === 3) return <Tag color="default">已撤销</Tag>;

        if (activeTab === '1') return <Tag color="blue">即将展示</Tag>;
        if (activeTab === '2') return <Tag color="green">展示中</Tag>;
        return <Tag color="default">已过期</Tag>;
    };

    const columns = [
        {
            title: '横幅海报',
            dataIndex: 'posterUrl',
            render: (url) => <Image src={url} width={120} style={{ borderRadius: 8 }} />
        },
        {
            title: '关联演出ID',
            dataIndex: 'eventId',
            render: (id) => id || <span style={{ color: '#999' }}>纯展示(无跳转)</span>
        },
        {
            title: '开始展示时间',
            dataIndex: 'startTime',
            render: (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
        },
        {
            title: '过期下架时间',
            dataIndex: 'endTime',
            render: (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
        },
        {
            title: '当前状态',
            key: 'status',
            render: (_, record) => getBannerStatusTag(record)
        },
        {
            title: '操作',
            key: 'action',
            render: (_, record) => {
                const isNewPending = record.auditStatus === 0;
                const isEditPending = record.editAuditStatus === 0;
                const hasPendingAudit = isNewPending || isEditPending;
                const canEdit = isSuperAdmin || !hasPendingAudit;

                return (
                    <Space>
                        {record.editAuditStatus === 2 && (
                            <Popconfirm
                                title="确认修改审核被驳回？"
                                description="确认后将清除修改驳回状态，页面恢复为当前已生效横幅。"
                                onConfirm={() => handleConfirmEditReject(record.id)}
                                okText="确认"
                                cancelText="取消"
                            >
                                <Button type="link" style={{ color: '#52c41a' }}>
                                    确认
                                </Button>
                            </Popconfirm>
                        )}
                        <Button
                            type="link"
                            icon={<EditOutlined />}
                            disabled={!canEdit}
                            onClick={() => openModal(record)}
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
                                <Button type="link" danger>
                                    撤销审核
                                </Button>
                            </Popconfirm>
                        )}

                        <Popconfirm title="确定删除吗？" onConfirm={() => handleDelete(record.id)}>
                            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                    </Space>
                );
            }
        }
    ];

    const openModal = (record = null) => {
        setEditingId(record ? record.id : null);
        if (record) {
            form.setFieldsValue({
                eventId: record.eventId,
                timeRange: record.startTime && record.endTime ? [dayjs(record.startTime), dayjs(record.endTime)] : []
            });
            setFileList(record.posterUrl ? [{ uid: '-1', name: 'banner.jpg', status: 'done', url: record.posterUrl }] : []);
            if (record.eventId) {
                setEventOptions([{ label: `[ID: ${record.eventId}] 已关联演出`, value: record.eventId }]);
            } else {
                setEventOptions([]);
            }
        } else {
            form.resetFields();
            setFileList([]);
            setEventOptions([]);
        }
        setModalVisible(true);
    };

    const handleOk = async () => {
        try {
            const values = await form.validateFields();
            if (fileList.length === 0 || (!fileList[0].url && !fileList[0].response)) {
                return message.warning('请上传横幅图片');
            }

            const posterUrl = fileList[0].url || fileList[0].response.data;
            const payload = {
                id: editingId,
                posterUrl,
                eventId: values.eventId,
                startTime: values.timeRange[0].format('YYYY-MM-DD HH:mm:ss'),
                endTime: values.timeRange[1].format('YYYY-MM-DD HH:mm:ss'),
                isExpiredEdit: activeTab === '3'
            };

            const res = await axios.post('/api/admin/banner/save', payload);
            if (res.data.code === 200) {
                message.success(res.data.message || '操作成功');
                setModalVisible(false);
                fetchBanners();
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (error) {
            if (error?.message) {
                message.error(error.message);
            }
        }
    };

    const handleDelete = async (id) => {
        try {
            const res = await axios.delete(`/api/admin/banner/${id}?isExpired=${activeTab === '3'}`);
            if (res.data.code === 200) {
                message.success('删除成功');
                fetchBanners();
            } else {
                message.error(res.data.message || '删除失败');
            }
        } catch (error) {
            message.error('删除失败');
        }
    };

    const handleConfirmEditReject = async (id) => {
        try {
            const res = await axios.put(`/api/admin/banner/confirm-edit-reject/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已确认修改驳回结果');
                fetchBanners();
            } else {
                message.error(res.data.message || '确认失败');
            }
        } catch (error) {
            message.error(error.response?.data?.message || '确认修改驳回失败');
        }
    };

    return (
        <Card
            title={
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}>首页滚动横幅管理</span>
                </div>
            }
            bordered={false}
            style={{ borderRadius: 12 }}
        >
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', gap: 10 }}>
                    <Input
                        placeholder="输入关联演出标题或ID进行搜索"
                        prefix={<SearchOutlined />}
                        value={searchText}
                        onChange={e => setSearchText(e.target.value)}
                        onPressEnter={() => fetchBanners(activeTab, searchText)}
                        style={{ width: 300 }}
                        allowClear
                    />
                    <Button type="primary" onClick={() => fetchBanners(activeTab, searchText)}>搜索</Button>
                </div>

                <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => openModal()}
                    style={{
                        backgroundColor: '#FF8899',
                        borderColor: '#FF8899',
                        borderRadius: '6px',
                        boxShadow: '0 4px 12px rgba(255, 136, 153, 0.4)'
                    }}
                >
                    新增横幅
                </Button>
            </div>

            <Tabs activeKey={activeTab} onChange={(key) => setActiveTab(key)} items={[
                { key: '2', label: '展示中' },
                { key: '1', label: '即将展示' },
                { key: '3', label: '展示过期 (已归档)' }
            ]} />

            <Table columns={columns} dataSource={banners} rowKey="id" loading={loading} />

            <Modal
                title={editingId ? '编辑横幅' : '新增横幅'}
                open={modalVisible}
                onOk={handleOk}
                onCancel={() => setModalVisible(false)}
                width={600}
                okText={editingId ? '提交修改' : '提交新增'}
                cancelText="取消"
            >
                <Form form={form} layout="vertical">
                    <Form.Item label="横幅图片 (推荐比例 4:1)" required>
                        <Upload
                            action="/api/common/upload"
                            listType="picture-card"
                            maxCount={1}
                            headers={{ Authorization: `Bearer ${localStorage.getItem('token')}` }}
                            fileList={fileList}
                            onChange={({ fileList }) => setFileList(fileList)}
                        >
                            {fileList.length === 0 && <div><UploadOutlined /><div style={{ marginTop: 8 }}>点击上传</div></div>}
                        </Upload>
                    </Form.Item>

                    <Form.Item
                        name="eventId"
                        label="关联演出"
                        rules={[{ required: true, message: '请搜索并选择关联的演出' }]}
                    >
                        <Select
                            showSearch
                            allowClear
                            filterOption={false}
                            onSearch={handleSearchEvent}
                            placeholder="输入演出名称或 ID 进行搜索"
                            notFoundContent={fetchingEvents ? <Spin size="small" /> : '请输入关键词搜索...'}
                            options={eventOptions}
                        />
                    </Form.Item>

                    <Form.Item name="timeRange" label="横幅展示期限" rules={[{ required: true, message: '请选择展示时间' }]}>
                        <DatePicker.RangePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} />
                    </Form.Item>
                </Form>
            </Modal>
        </Card>
    );
};

export default BannerManager;
