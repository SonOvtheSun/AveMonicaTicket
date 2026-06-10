import React, { useState, useEffect } from 'react';
import { Card, Table, Button, Space, Input, Tag, Popconfirm, message, Modal, Form, Select, Upload, Image } from 'antd';
// 👇 新增了 DeleteOutlined 和 PlusOutlined
import { SearchOutlined, EditOutlined, StopOutlined, CheckCircleOutlined, UploadOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import axios from 'axios';
import ImgCrop from "antd-img-crop";

const MUSIC_STYLE_OPTIONS = [
    '古典',
    '流行',
    '世界音乐',
    '独立',
    '摇滚',
    '爵士',
    'HipHop',
    '轻音乐',
    '民谣',
    '动漫',
    '朋克',
    '电子',
    '金属',
    '雷鬼',
    '核'
].map(style => ({ value: style, label: style }));

const splitStyleText = (style) => {
    if (!style) return [];
    return String(style).split('/').map(item => item.trim()).filter(Boolean);
};

const ArtistLibrary = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(false);
    const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
    const [searchText, setSearchText] = useState('');

    // 弹窗状态管理 (共用一个弹窗)
    const [modalVisible, setModalVisible] = useState(false);
    const [modalType, setModalType] = useState('add'); // 'add' 或 'edit'
    const [editingArtist, setEditingArtist] = useState(null);
    const [form] = Form.useForm();
    const [submitting, setSubmitting] = useState(false);
    const [userRole, setUserRole] = useState(6);

    const [avatarFileList, setAvatarFileList] = useState([]);

    const fetchData = async (page = pagination.current, keyword = searchText) => {
        setLoading(true);
        try {
            const res = await axios.get('/api/admin/artist/page', {
                params: { current: page, size: pagination.pageSize, keyword: keyword }
            });
            if (res.data.code === 200) {
                setData(res.data.data.records);
                setPagination(prev => ({ ...prev, current: page, total: res.data.data.total }));
            }
        } catch (error) {
            message.error('获取艺人库失败');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchData();
        axios.get('/api/user/info').then(res => {
            if (res.data.code === 200) {
                setUserRole(res.data.data.role || 6);
            }
        });
    }, []);

    const isSuperAdmin = userRole === 1;

    const handleRevokeAudit = async (id) => {
        try {
            const res = await axios.put(`/api/admin/artist/revoke/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已撤销审核');
                fetchData();
            } else {
                message.error(res.data.message || '撤销失败');
            }
        } catch (error) {
            message.error('撤销审核失败');
        }
    };

    // 状态切换 (下架/恢复)
    const handleStatusChange = async (id, newStatus) => {
        try {
            const res = await axios.put(`/api/admin/artist/${id}/status/${newStatus}`);
            if (res.data.code === 200) {
                message.success('状态更新成功');
                fetchData();
            } else {
                message.error(res.data.message);
            }
        } catch (error) {
            message.error('操作失败');
        }
    };

    // 🚨 新增：删除艺人
    const handleDelete = async (id) => {
        try {
            const res = await axios.delete(`/api/admin/artist/delete/${id}`);
            if (res.data.code === 200) {
                message.success('删除成功');
                // 如果当前页只有一条数据被删了，跳回上一页
                if (data.length === 1 && pagination.current > 1) {
                    fetchData(pagination.current - 1);
                } else {
                    fetchData();
                }
            } else {
                message.error(res.data.message || '删除失败');
            }
        } catch (error) {
            message.error('系统异常，删除失败');
        }
    };

    // 提取上传的 URL
    // 提取上传的 URL (终极兼容版)
    const parseUploadedUrl = (fileList) => {
        if (!fileList || fileList.length === 0) {
            throw new Error('请先上传头像');
        }

        const file = fileList[0];

        console.log('头像文件对象:', file);
        console.log('上传响应:', file.response);

        if (file.status === 'uploading') {
            throw new Error('头像还在上传中，请稍后再提交');
        }

        if (file.status === 'error') {
            throw new Error('头像上传失败，请重新上传');
        }

        // 编辑回显时已有 url
        if (file.url) {
            return file.url;
        }

        // 正常上传成功，后端返回 Result.success("/uploads/avatar/xxx.jpg")
        if (file.response && file.response.code == 200 && file.response.data) {
            return file.response.data;
        }

        // 兼容后端直接返回字符串
        if (typeof file.response === 'string') {
            return file.response;
        }

        // 兼容 xhr 原始响应
        if (file.xhr?.responseText) {
            try {
                const resObj = JSON.parse(file.xhr.responseText);
                if (resObj.code == 200 && resObj.data) {
                    return resObj.data;
                }
            } catch (e) {
                console.error('解析上传响应失败:', e);
            }
        }

        throw new Error('没有获取到头像地址，请重新上传');
    };



    // 打开新增弹窗
    const openAddModal = () => {
        setModalType('add');
        setEditingArtist(null);
        form.resetFields(); // 清空表单
        setAvatarFileList([]);
        setModalVisible(true);
    };

    // 打开编辑弹窗
    const openEditModal = (record) => {
        setModalType('edit');
        setEditingArtist(record);
        const fileList = record.avatarUrl ? [{ uid: '-1', name: 'avatar.png', status: 'done', url: record.avatarUrl }] : [];
        setAvatarFileList(fileList);

        form.setFieldsValue({
            ...record,
            // 逆向转换：字符串转为数组回显
            region: record.region ? [record.region] : [],
            style: splitStyleText(record.style),
            // avatar: avatarFileList
        });
        setModalVisible(true);
    };

    // 🚨 核心修改：合并新增和编辑的提交逻辑
    const handleModalSubmit = async (values) => {
        setSubmitting(true);

        try {
            const finalAvatarUrl = parseUploadedUrl(avatarFileList);

            const regionStr = values.region && values.region.length > 0
                ? values.region[0]
                : '';

            const styleStr = Array.isArray(values.style)
                ? values.style.join('/')
                : (values.style || '');

            const payload = {
                name: values.name,
                region: regionStr,
                style: styleStr,
                description: values.description,
                avatarUrl: finalAvatarUrl
            };

            console.log('最终提交 payload:', payload);

            let res;
            if (modalType === 'edit') {
                payload.id = editingArtist.id;
                res = await axios.put('/api/admin/artist/update', payload);
            } else {
                res = await axios.post('/api/admin/artist/add', payload);
            }

            if (res.data.code === 200) {
                message.success(modalType === 'edit' ? '修改成功' : '新增成功');
                setModalVisible(false);
                fetchData(modalType === 'add' ? 1 : pagination.current);
            } else {
                message.error(res.data.message);
            }
        } catch (error) {
            message.error(error.message || '提交失败');
        } finally {
            setSubmitting(false);
        }
    };

    const columns = [
        {
            title: '头像',
            dataIndex: 'avatarUrl',
            key: 'avatarUrl',
            render: (url) => <Image src={url || 'https://via.placeholder.com/50'} width={50} height={50} style={{ borderRadius: '50%', objectFit: 'cover' }} />
        },
        { title: '名称', dataIndex: 'name', key: 'name', fontWeight: 'bold' },
        { title: '国家/地区', dataIndex: 'region', key: 'region', render: text => text || '-' },
        {
            title: '风格',
            dataIndex: 'style',
            key: 'style',
            render: text => {
                const styles = splitStyleText(text);
                return styles.length > 0
                    ? styles.map(style => <Tag color="blue" key={style}>{style}</Tag>)
                    : '-';
            }
        },
        {
            title: '状态',
            dataIndex: 'auditStatus',
            key: 'auditStatus',
            render: (_, record) => {
                if (record.editAuditStatus === 0) return <Tag color="processing">修改待审核</Tag>;
                if (record.editAuditStatus === 2) return <Tag color="red">修改被驳回</Tag>;
                if (record.auditStatus === 0) return <Tag color="orange">新增待审核</Tag>;
                if (record.auditStatus === 1) return <Tag color="green">正常在库</Tag>;
                if (record.auditStatus === 2) return <Tag color="red">新增被驳回</Tag>;
                if (record.auditStatus === 3) return <Tag color="default">已撤销</Tag>;
                return <Tag>{record.auditStatus}</Tag>;
            }
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
                    <Button
                        type="primary"
                        size="small"
                        icon={<EditOutlined />}
                        disabled={!canEdit}
                        onClick={() => openEditModal(record)}
                    >
                        编辑
                    </Button>
                    {!isSuperAdmin && hasPendingAudit && (
                        <Popconfirm
                            title="确定撤销审核申请？"
                            description="撤销后可重新编辑并提交审核。"
                            onConfirm={() => handleRevokeAudit(record.id)}
                            okButtonProps={{ danger: true }}
                        >
                            <Button danger size="small">撤销审核</Button>
                        </Popconfirm>
                    )}

                    {record.auditStatus === 1 ? (
                        <Popconfirm title="确定要下架该艺人吗？" onConfirm={() => handleStatusChange(record.id, 2)} okButtonProps={{ danger: true }}>
                            <Button danger size="small" icon={<StopOutlined />}>下架</Button>
                        </Popconfirm>
                    ) : record.auditStatus === 2 ? (
                        <Popconfirm title="确定恢复该艺人？" onConfirm={() => handleStatusChange(record.id, 1)}>
                            <Button size="small" style={{ color: '#52c41a', borderColor: '#52c41a' }} icon={<CheckCircleOutlined />}>恢复</Button>
                        </Popconfirm>
                    ) : null}

                    {/* 🚨 新增的删除按钮 */}
                    <Popconfirm
                        title="确定要彻底删除吗？"
                        description="警告：删除后数据不可恢复！如果该艺人已被演出绑定，建议使用“下架”功能。"
                        onConfirm={() => handleDelete(record.id)}
                        okButtonProps={{ danger: true }}
                    >
                        <Button type="primary" danger size="small" icon={<DeleteOutlined />}>删除</Button>
                    </Popconfirm>
                </Space>
            )}
        }
    ];

    const normFile = (e) => {
        if (Array.isArray(e)) return e;
        return e?.fileList;
    };

    return (
        <Card title={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}>演出项目管理看板</span>
            </div>
        }
        >
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', gap: 10 }}>
                    <Input
                        placeholder="输入姓名、地区或风格进行搜索"
                        prefix={<SearchOutlined />}
                        value={searchText}
                        onChange={e => setSearchText(e.target.value)}
                        onPressEnter={() => fetchData(1)}
                        style={{ width: 300 }}
                        allowClear // 加个一键清空按钮体验更好
                    />
                    <Button type="primary" onClick={() => fetchData(1)}>搜索</Button>
                </div>

                {/* 🚨 顶部新增按钮 */}
                <Button type="primary" icon={<PlusOutlined />} style={{ backgroundColor: '#FF8899', borderColor: '#FF8899' }} onClick={openAddModal}>
                    新增音乐人
                </Button>
            </div>

            <Table
                columns={columns}
                dataSource={data}
                rowKey="id"
                loading={loading}
                pagination={{
                    ...pagination,
                    onChange: (page) => fetchData(page)
                }}
            />

            {/* 动态切换标题的新增/编辑共用弹窗 */}
            <Modal
                title={modalType === 'add' ? '新增音乐人入驻' : '编辑艺人资料'}
                open={modalVisible}
                onCancel={() => setModalVisible(false)}
                footer={null}
                destroyOnClose
            >
                <Form form={form} layout="vertical" onFinish={handleModalSubmit} style={{ marginTop: 20 }}>
                    <Form.Item name="name" label="艺人名称" rules={[{ required: true, message: '请输入名称' }]}>
                        <Input />
                    </Form.Item>

                    <Form.Item name="region" label="国家或地区" rules={[{ required: true }]}>
                        <Select mode="tags" maxCount={1} placeholder="请选择或输入" options={[
                            { value: '中国大陆', label: '中国大陆' },
                            { value: '中国港澳台', label: '中国港澳台' },
                            { value: '日本', label: '日本' },
                            { value: '韩国', label: '韩国' },
                            { value: '欧美', label: '欧美' },
                            { value: '其他海外地区', label: '其他海外地区' }
                        ]} />
                    </Form.Item>

                    <Form.Item
                        name="style"
                        label="音乐风格"
                        rules={[
                            {
                                validator: (_, value) => {
                                    if (!value || value.length <= 2) {
                                        return Promise.resolve();
                                    }
                                    return Promise.reject(new Error('音乐风格最多选择两项'));
                                }
                            }
                        ]}
                    >
                        <Select
                            mode="multiple"
                            maxCount={2}
                            placeholder="请选择音乐风格，最多两项"
                            options={MUSIC_STYLE_OPTIONS}
                            optionFilterProp="label"
                            allowClear
                        />
                    </Form.Item>

                    <Form.Item label="官方头像" required>
                        <ImgCrop
                            rotationSlider
                            aspect={1}
                            modalTitle="裁剪头像"
                            modalOk="确认裁剪"
                            modalCancel="取消"
                        >
                            <Upload
                                name="file"
                                action="/api/common/upload"
                                data={{ type: 'avatar' }}
                                listType="picture"
                                maxCount={1}
                                headers={{ Authorization: `Bearer ${localStorage.getItem('token')}` }}
                                // 👇 🚨 核心绑定：让 Upload 的生老病死直接和本地状态挂钩
                                fileList={avatarFileList}
                                onChange={({ fileList }) => setAvatarFileList(fileList)}
                            >
                                <Button icon={<UploadOutlined />}>上传头像</Button>
                            </Upload>
                        </ImgCrop>
                    </Form.Item>

                    <Form.Item name="description" label="艺人简介">
                        <Input.TextArea rows={4} />
                    </Form.Item>

                    <div style={{ textAlign: 'right', marginTop: 30 }}>
                        <Button onClick={() => setModalVisible(false)} style={{ marginRight: 10 }}>取消</Button>
                        <Button type="primary" htmlType="submit" loading={submitting} style={modalType === 'add' ? {backgroundColor: '#FF8899', borderColor: '#FF8899'} : {}}>
                            {modalType === 'add' ? '确认新增' : '保存修改'}
                        </Button>
                    </div>
                </Form>
            </Modal>
        </Card>
    );
};

export default ArtistLibrary;