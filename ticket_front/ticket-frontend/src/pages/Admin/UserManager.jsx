import React, { useState, useEffect } from 'react';
import { Table, Card, Tag, Select, message, Avatar, Space } from 'antd';
import { User, ShieldAlert } from 'lucide-react'; // 修复了之前的图标报错
import axios from 'axios';

const UserManager = () => {
    const [users, setUsers] = useState([]);
    const [loading, setLoading] = useState(false);
    const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });

    const fetchUsers = async (page = 1, size = 10) => {
        setLoading(true);
        try {
            const res = await axios.get('/api/admin/user/list', {
                params: { current: page, size: size }
            });
            if (res.data.code === 200) {
                setUsers(res.data.data.records);
                setPagination({
                    current: res.data.data.current,
                    pageSize: res.data.data.size,
                    total: res.data.data.total
                });
            } else {
                message.error(res.data.message || '获取用户列表失败');
            }
        } catch (error) {
            message.error('请求异常，请检查是否拥有超管权限');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { fetchUsers(); }, []);

    const handleTableChange = (newPagination) => fetchUsers(newPagination.current, newPagination.pageSize);

    // 核心：处理角色变更 (此时 newRole 是一个 1-6 的数字)
    const handleRoleChange = async (userId, newRole) => {
        try {
            const res = await axios.put(`/api/admin/user/role/${userId}?role=${newRole}`);
            if (res.data.code === 200) {
                message.success('权限更新成功，用户重新登录后生效');
                fetchUsers(pagination.current, pagination.pageSize);
            } else {
                message.error(res.data.message);
            }
        } catch (error) {
            message.error('更新失败');
        }
    };

    // 🚨 角色字典配置 (严格按照架构图分配 TINYINT 对应关系)
    const roleOptions = [
        { value: 2, label: '1. 演出审核员', color: 'volcano' },
        { value: 3, label: '2. 评论审核员', color: 'purple' },
        { value: 4, label: '3. 订单管理员', color: 'cyan' },
        { value: 5, label: '4. 演出管理方', color: 'blue' },
        { value: 6, label: '5. 普通游客', color: 'default' },
    ];

    const roleOptionsR = [
        { value: 1, label: '0. 超级管理员', color: 'volcano' },
        { value: 2, label: '1. 演出审核员', color: 'volcano' },
        { value: 3, label: '2. 评论审核员', color: 'purple' },
        { value: 4, label: '3. 订单管理员', color: 'cyan' },
        { value: 5, label: '4. 演出管理方', color: 'blue' },
        { value: 6, label: '5. 普通游客', color: 'default' },
    ];

    const columns = [
        {
            title: '用户信息',
            key: 'userInfo',
            render: (_, record) => (
                <Space>
                    <Avatar src={record.avatar} icon={<User />} />
                    <div style={{ display: 'flex', flexDirection: 'column' }}>
                        <span style={{ fontWeight: 500, color: '#333' }}>{record.username}</span>
                        <span style={{ fontSize: 12, color: '#888' }}>ID: {record.id}</span>
                    </div>
                </Space>
            )
        },
        {
            title: '绑定手机号',
            dataIndex: 'phone',
            key: 'phone',
            render: (text) => <span style={{ fontFamily: 'monospace' }}>{text || '未绑定'}</span>
        },
        {
            title: '当前角色状态',
            key: 'currentRole',
            render: (_, record) => {
                // 如果数据库没设默认值或者为空，默认显示为 6(普通游客)
                const currentRoleConfig = roleOptionsR.find(r => r.value === (record.role || 6)) || roleOptions[4];
                return <Tag color={currentRoleConfig.color}>{currentRoleConfig.label}</Tag>;
            }
        },
        {
            title: '权限分配操作',
            key: 'action',
            render: (_, record) => (
                record.id === 1 ? (
                    <span style={{ color: '#ff4d4f', fontSize: 12, display: 'flex', alignItems: 'center', gap: 4 }}>
                        <ShieldAlert size={14}/> 系统内置超管不可修改
                    </span>
                ) : (
                    <Select
                        value={record.role || 6}
                        style={{ width: 160 }}
                        onChange={(val) => handleRoleChange(record.id, val)}
                        options={roleOptions}
                        size="small"
                    />
                )
            )
        }
    ];

    return (
        <Card
            title={<div style={{ textAlign: 'left', width: '100%' }}>
      <span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}>
        用户角色与系统权限分配
      </span>
            </div>}
            bordered={false}
            style={{ borderRadius: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.03)' }}
        >
            <Table
                columns={columns}
                dataSource={users}
                rowKey="id"
                loading={loading}
                pagination={pagination}
                onChange={handleTableChange}
            />
        </Card>
    );
};

export default UserManager;