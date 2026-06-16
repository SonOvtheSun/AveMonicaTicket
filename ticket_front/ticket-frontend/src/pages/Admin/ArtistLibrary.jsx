import React, { useState, useEffect, useRef } from 'react';
import { Card, Table, Button, Space, Input, Tag, Popconfirm, message, Modal, Form, Select, Upload, Image, Empty } from 'antd';
import { SearchOutlined, EditOutlined, StopOutlined, CheckCircleOutlined, UploadOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import axios from '../../utils/request';
import ImgCrop from "antd-img-crop";
import { compressImageByShotEasy } from '../../shot-easy/compressImage.jsx';

const MUSIC_STYLE_OPTIONS = [
    '古典', '流行', '世界音乐', '独立', '摇滚', '爵士', 'HipHop', '轻音乐', '民谣', '动漫', '朋克', '电子', '金属', '雷鬼', '核'
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

    // 弹窗状态管理
    const [modalVisible, setModalVisible] = useState(false);
    const [modalType, setModalType] = useState('add');
    const [editingArtist, setEditingArtist] = useState(null);
    const [form] = Form.useForm();
    const [submitting, setSubmitting] = useState(false);
    const [avatarFileList, setAvatarFileList] = useState([]);

    // 记录本页面中“已上传成功但尚未确认保存”的头像 URL。
    // 编辑旧艺人时原本已有的 avatarUrl 不会进入这个集合，因此取消编辑不会误删旧头像。
    const uncommittedUploadUrlsRef = useRef(new Set());

    const getAvatarCompressOption = () => ({
        preview: {
            maxSize: 256,
        },
        resize: {
            method: 'fitWidth',
            width: 800,
            height: undefined,
        },
        format: {
            target: 'webp',
            transparentFill: '#FFFFFF',
        },
        jpeg: {
            quality: 0.90,
        },
        png: {
            colors: 64,
            dithering: 0,
            quality: 0.90
        },
        gif: {
            colors: 128,
            dithering: false,
        },
        avif: {
            quality: 50,
            speed: 8,
        },
    });

    const parseUploadedUrlFromFile = (file) => {
        if (!file) return '';

        if (file.response && file.response.code === 200 && file.response.data) {
            return file.response.data;
        }

        if (typeof file.response === 'string') {
            return file.response;
        }

        if (file.url) {
            return file.url;
        }

        if (file.status === 'done' && file.xhr?.responseText) {
            try {
                const resObj = JSON.parse(file.xhr.responseText);
                if (resObj.code === 200 && resObj.data) {
                    return resObj.data;
                }
                return resObj.data || resObj;
            } catch (e) {
                return '';
            }
        }

        return '';
    };

    const addUncommittedUploadUrl = (url) => {
        if (url && url.startsWith('/uploads/')) {
            uncommittedUploadUrlsRef.current.add(url);
        }
    };

    const markUploadCommitted = (url) => {
        if (url) {
            uncommittedUploadUrlsRef.current.delete(url);
        }
    };

    const deleteUploadedUrl = async (url) => {
        if (!url || !url.startsWith('/uploads/')) return;

        try {
            await axios.post('/api/common/delete-upload', { url });
        } catch (error) {
            console.warn('删除未确认上传头像失败：', url, error);
        }
    };

    const cleanupUncommittedUploads = async () => {
        const urls = Array.from(uncommittedUploadUrlsRef.current);

        if (urls.length === 0) {
            return;
        }

        uncommittedUploadUrlsRef.current.clear();

        await Promise.allSettled(
            urls.map(url => deleteUploadedUrl(url))
        );
    };

    const handleRemoveUploadedFile = async (file) => {
        const url = parseUploadedUrlFromFile(file);

        if (url && uncommittedUploadUrlsRef.current.has(url)) {
            uncommittedUploadUrlsRef.current.delete(url);
            await deleteUploadedUrl(url);
        }

        return true;
    };

    const customAvatarUpload = async (options) => {
        const { file, onSuccess, onError, onProgress } = options;
        const rawSize = file?.size || 0;
        const compressMessageKey = `artist-avatar-compress-${Date.now()}`;

        let uploadFile = file;

        try {
            onProgress?.({ percent: 1 });

            if (file && file.type && file.type.startsWith('image/')) {
                message.open({
                    key: compressMessageKey,
                    type: 'loading',
                    content: '正在压缩头像...',
                    duration: 0,
                });

                try {
                    uploadFile = await compressImageByShotEasy(file, getAvatarCompressOption());

                    const compressedSize = uploadFile?.size || 0;

                    if (rawSize > 0 && compressedSize > 0 && compressedSize < rawSize) {
                        const savedPercent = (((rawSize - compressedSize) / rawSize) * 100).toFixed(1);
                        message.open({
                            key: compressMessageKey,
                            type: 'success',
                            content: `头像压缩完成，体积减少 ${savedPercent}%`,
                            duration: 2,
                        });
                    } else {
                        message.open({
                            key: compressMessageKey,
                            type: 'info',
                            content: '头像已处理，继续上传',
                            duration: 2,
                        });
                    }
                } catch (compressError) {
                    console.error('头像压缩失败，已使用原图上传：', compressError);
                    message.open({
                        key: compressMessageKey,
                        type: 'warning',
                        content: '头像压缩失败，已使用原图上传',
                        duration: 2,
                    });
                    uploadFile = file;
                }
            }

            onProgress?.({ percent: 8 });

            const formData = new FormData();
            formData.append('file', uploadFile, uploadFile.name || 'artist-avatar.webp');
            formData.append('type', 'avatar');

            const res = await axios.post('/api/common/upload', formData, {
                headers: {
                    'Content-Type': 'multipart/form-data',
                    Authorization: `Bearer ${localStorage.getItem('token')}`,
                },
                onUploadProgress: (progressEvent) => {
                    if (!progressEvent.total) return;

                    const uploadPercent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                    const percent = Math.min(100, 8 + Math.round(uploadPercent * 0.92));

                    onProgress?.({ percent });
                },
            });

            if (res.data.code === 200) {
                addUncommittedUploadUrl(res.data.data);
                onSuccess(res.data);
            } else {
                onError(new Error(res.data.message || '上传失败'));
            }
        } catch (error) {
            message.destroy(compressMessageKey);
            onError(error);
        }
    };

    const handleModalCancel = async () => {
        await cleanupUncommittedUploads();
        setAvatarFileList([]);
        form.resetFields();
        setModalVisible(false);
    };

    // 🚨 权限状态管理
    const [userRole, setUserRole] = useState(6);
    const [userId, setUserId] = useState(null);
    const [permissions, setPermissions] = useState([]);

    // ==========================================
    // 🚨 核心权限计算逻辑
    // ==========================================
    const isSuperAdmin = userRole === 1;

    const hasPerm = (perm) => permissions.includes(perm);

// 艺人管理员：可以管理所有艺人
    const canManageArtist = isSuperAdmin || hasPerm('artist:manage');

// 艺人查看：artist:manage 包含 artist:view 的业务含义
    const canViewArtist = canManageArtist || hasPerm('artist:view') || hasPerm('event:publish');

// 新增艺人：艺人管理员、演出管理方、超管都可以
    const canAddArtist = canManageArtist || hasPerm('event:publish');

// 演出管理方：只能操作自己提交的艺人
    const canSubmitArtist = hasPerm('event:publish');

    const isOwnArtist = (record) => {
        if (!record || !userId) return false;
        return Number(record.createBy) === Number(userId);
    };

    const canOperateOwnArtist = (record) => {
        return canManageArtist || (canSubmitArtist && isOwnArtist(record));
    };

    const fetchData = async (page = pagination.current, keyword = searchText) => {
        // 如果没有浏览权限，直接不发请求
        if (!canViewArtist) return;

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
        axios.get('/api/user/info').then(res => {
            if (res.data.code === 200) {
                const user = res.data.data || {};
                setUserRole(user.role || 6);
                setUserId(user.id || user.userId || null);
                setPermissions(user.permissions || []);
            }
        });
    }, []);

    // 权限获取到之后再拉取数据
    useEffect(() => {
        if (canViewArtist) {
            fetchData();
        }
    }, [canViewArtist]);

    // 组件卸载时兜底清理本页面上传但未提交成功的头像。
    useEffect(() => {
        return () => {
            cleanupUncommittedUploads();
        };
    }, []);

    const handleConfirmEditReject = async (id) => {
        try {
            const res = await axios.put(`/api/admin/artist/confirm-edit-reject/${id}`);
            if (res.data.code === 200) {
                message.success(res.data.message || '已确认修改驳回结果');
                fetchData();
            } else {
                message.error(res.data.message || '确认失败');
            }
        } catch (error) {
            message.error(error.response?.data?.message || '确认修改驳回失败');
        }
    };

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

    const handleDelete = async (id) => {
        try {
            const res = await axios.delete(`/api/admin/artist/delete/${id}`);
            if (res.data.code === 200) {
                message.success('删除成功');
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

    const parseUploadedUrl = (fileList) => {
        if (!fileList || fileList.length === 0) throw new Error('请先上传头像');
        const file = fileList[0];
        if (file.status === 'uploading') throw new Error('头像还在上传中，请稍后再提交');
        if (file.status === 'error') throw new Error('头像上传失败，请重新上传');
        if (file.url) return file.url;
        if (file.response && file.response.code == 200 && file.response.data) return file.response.data;
        if (typeof file.response === 'string') return file.response;
        if (file.xhr?.responseText) {
            try {
                const resObj = JSON.parse(file.xhr.responseText);
                if (resObj.code == 200 && resObj.data) return resObj.data;
            } catch (e) { console.error('解析响应失败:', e); }
        }
        throw new Error('没有获取到头像地址，请重新上传');
    };

    const openAddModal = () => {
        setModalType('add');
        setEditingArtist(null);
        form.resetFields();
        setAvatarFileList([]);
        setModalVisible(true);
    };

    const openEditModal = (record) => {
        setModalType('edit');
        setEditingArtist(record);
        const fileList = record.avatarUrl ? [{ uid: '-1', name: 'avatar.png', status: 'done', url: record.avatarUrl }] : [];
        setAvatarFileList(fileList);
        form.setFieldsValue({
            ...record,
            region: record.region ? [record.region] : [],
            style: splitStyleText(record.style),
        });
        setModalVisible(true);
    };

    const handleModalSubmit = async (values) => {
        setSubmitting(true);
        try {
            const finalAvatarUrl = parseUploadedUrl(avatarFileList);
            const regionStr = values.region && values.region.length > 0 ? values.region[0] : '';
            const styleStr = Array.isArray(values.style) ? values.style.join('/') : (values.style || '');

            const payload = {
                name: values.name,
                region: regionStr,
                style: styleStr,
                description: values.description,
                avatarUrl: finalAvatarUrl
            };

            let res;
            if (modalType === 'edit') {
                payload.id = editingArtist.id;
                res = await axios.put('/api/admin/artist/update', payload);
            } else {
                res = await axios.post('/api/admin/artist/add', payload);
            }

            if (res.data.code === 200) {
                // 头像已被新增/修改业务正式使用，前端不再删除；
                // 后端 Artist 新增/修改成功后也需要调用 uploadTempFileService.markUsed(finalAvatarUrl)，避免凌晨定时任务误删。
                markUploadCommitted(finalAvatarUrl);
                await cleanupUncommittedUploads();

                message.success(modalType === 'edit' ? '修改成功' : '新增成功');
                setModalVisible(false);
                setAvatarFileList([]);
                form.resetFields();
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
                return styles.length > 0 ? styles.map(style => <Tag color="blue" key={style}>{style}</Tag>) : '-';
            }
        },
        {
            title: '状态',
            dataIndex: 'auditStatus',
            key: 'auditStatus',
            render: (_, record) => {
                if (record.editAuditStatus === 0) return <Tag color="processing">修改待审核</Tag>;
                if (record.editAuditStatus === 2) return <Tag color="red">修改被驳回</Tag>;
                if (record.auditStatus === 0) return <Tag color="orange">未审核</Tag>;
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

                const canOperateThis = canOperateOwnArtist(record);
                const canClickEdit = canManageArtist || (canOperateThis && !hasPendingAudit);

                return (
                    <Space>
                        {/* 修改被驳回后的确认：管理员可确认所有；提交人只能确认自己的 */}
                        {canOperateThis && record.editAuditStatus === 2 && (
                            <Popconfirm
                                title="确认修改审核被驳回？"
                                onConfirm={() => handleConfirmEditReject(record.id)}
                                okText="确认"
                                cancelText="取消"
                            >
                                <Button size="small" style={{ color: '#52c41a', borderColor: '#52c41a' }}>
                                    确认
                                </Button>
                            </Popconfirm>
                        )}

                        {/* 编辑：管理员可编辑所有；演出管理方只能编辑自己提交且非待审核中的 */}
                        {canOperateThis && (
                            <Button
                                type="primary"
                                size="small"
                                icon={<EditOutlined />}
                                disabled={!canClickEdit}
                                onClick={() => openEditModal(record)}
                            >
                                编辑
                            </Button>
                        )}

                        {/* 撤销审核：管理员和提交人都能撤销；event:publish 只能撤销自己的，后端也会二次校验 */}
                        {canOperateThis && hasPendingAudit && (
                            <Popconfirm
                                title="确定撤销审核申请？"
                                description="撤销后可重新编辑并提交审核。"
                                onConfirm={() => handleRevokeAudit(record.id)}
                                okText="确定撤销"
                                cancelText="取消"
                                okButtonProps={{ danger: true }}
                            >
                                <Button danger size="small">
                                    撤销审核
                                </Button>
                            </Popconfirm>
                        )}

                        {/* 下架 / 恢复 / 删除：只给 artist:manage 或超管 */}
                        {(canManageArtist) && record.auditStatus === 1 && (
                            <Popconfirm
                                title="确定要下架该艺人吗？"
                                onConfirm={() => handleStatusChange(record.id, 0)}
                                okButtonProps={{ danger: true }}
                            >
                                <Button danger size="small" icon={<StopOutlined />}>
                                    下架
                                </Button>
                            </Popconfirm>
                        )}

                        {canManageArtist && record.auditStatus === 2 && (
                            <Popconfirm
                                title="确定恢复该艺人？"
                                onConfirm={() => handleStatusChange(record.id, 1)}
                            >
                                <Button
                                    size="small"
                                    style={{ color: '#52c41a', borderColor: '#52c41a' }}
                                    icon={<CheckCircleOutlined />}
                                >
                                    恢复
                                </Button>
                            </Popconfirm>
                        )}

                        {canManageArtist && (
                            <Popconfirm
                                title="确定要彻底删除吗？"
                                description="警告：删除后数据不可恢复！"
                                onConfirm={() => handleDelete(record.id)}
                                okButtonProps={{ danger: true }}
                            >
                                <Button type="primary" danger size="small" icon={<DeleteOutlined />}>
                                    删除
                                </Button>
                            </Popconfirm>
                        )}
                    </Space>
                );
            }
        }
    ];

    return (
        <Card title={
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}>音乐人项目管理看板</span>
            </div>
        }>
            <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', gap: 10 }}>
                    {/* 🚨 如果没有 view 权限，连搜索框都不需要看了 */}
                    {canViewArtist && (
                        <>
                            <Input
                                placeholder="输入姓名、地区或风格进行搜索"
                                prefix={<SearchOutlined />}
                                value={searchText}
                                onChange={e => setSearchText(e.target.value)}
                                onPressEnter={() => fetchData(1)}
                                style={{ width: 300 }}
                                allowClear
                            />
                            <Button type="primary" onClick={() => fetchData(1)}>搜索</Button>
                        </>
                    )}
                </div>

                {/* 🚨 仅拥有 add 或 manage 权限的人可见新增按钮 */}
                {canAddArtist && (
                    <Button
                        type="primary"
                        icon={<PlusOutlined />}
                        style={{ backgroundColor: '#FF8899', borderColor: '#FF8899' }}
                        onClick={openAddModal}
                    >
                        新增音乐人
                    </Button>
                )}
            </div>

            {/* 🚨 仅拥有 view 权限的人可见列表 */}
            {canViewArtist ? (
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
            ) : (
                <Empty
                    description="您没有权限查看音乐人库，请联系系统管理员分配 artist:view 权限"
                    style={{ margin: '80px 0' }}
                />
            )}

            <Modal
                title={modalType === 'add' ? '新增音乐人入驻' : '编辑艺人资料'}
                open={modalVisible}
                onCancel={handleModalCancel}
                footer={null}
                destroyOnClose
            >
                <Form form={form} layout="vertical" onFinish={handleModalSubmit} style={{ marginTop: 20 }}>
                    {/* 表单项保持不变... */}
                    <Form.Item name="name" label="艺人名称" rules={[{ required: true, message: '请输入名称' }]}>
                        <Input />
                    </Form.Item>
                    <Form.Item name="region" label="国家或地区" rules={[{ required: true }]}>
                        <Select mode="tags" maxCount={1} placeholder="请选择或输入" options={[
                            { value: '中国大陆', label: '中国大陆' }, { value: '中国港澳台', label: '中国港澳台' },
                            { value: '日本', label: '日本' }, { value: '韩国', label: '韩国' },
                            { value: '欧美', label: '欧美' }, { value: '其他海外地区', label: '其他海外地区' }
                        ]} />
                    </Form.Item>
                    <Form.Item name="style" label="音乐风格" rules={[{ validator: (_, value) => (!value || value.length <= 2) ? Promise.resolve() : Promise.reject(new Error('音乐风格最多选择两项')) }]}>
                        <Select mode="multiple" maxCount={2} placeholder="请选择音乐风格，最多两项" options={MUSIC_STYLE_OPTIONS} optionFilterProp="label" allowClear />
                    </Form.Item>
                    <Form.Item label="官方头像" required>
                        <ImgCrop rotationSlider aspect={1} modalTitle="裁剪头像" modalOk="确认裁剪" modalCancel="取消">
                            <Upload
                                customRequest={customAvatarUpload}
                                listType="picture"
                                maxCount={1}
                                accept="image/*"
                                fileList={avatarFileList}
                                onRemove={handleRemoveUploadedFile}
                                onChange={({ file, fileList }) => {
                                    setAvatarFileList(fileList);

                                    if (file.status === 'done' && file.response?.code === 200) {
                                        message.success('头像上传成功');
                                    } else if (file.status === 'error') {
                                        message.error(file.response?.message || '头像上传失败');
                                    }
                                }}
                            >
                                <Button icon={<UploadOutlined />}>上传头像</Button>
                            </Upload>
                        </ImgCrop>
                    </Form.Item>
                    <Form.Item name="description" label="艺人简介">
                        <Input.TextArea rows={4} />
                    </Form.Item>
                    <div style={{ textAlign: 'right', marginTop: 30 }}>
                        <Button onClick={handleModalCancel} style={{ marginRight: 10 }}>取消</Button>
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