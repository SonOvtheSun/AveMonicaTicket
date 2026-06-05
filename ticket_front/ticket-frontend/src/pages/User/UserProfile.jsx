import React, { useState, useEffect, useMemo } from 'react';
import {
    Tabs,
    Form,
    Input,
    Button,
    Radio,
    DatePicker,
    Upload,
    Avatar,
    List,
    Tag,
    Modal,
    message,
    Row,
    Col,
    Popconfirm,
    Select,
    Cascader
} from 'antd';
import { UserOutlined, SafetyCertificateOutlined, SettingOutlined, TeamOutlined, PlusOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import axios from 'axios';
import PublicHeader from '../../components/PublicHeader/PublicHeader';
import './UserProfile.css';
import pcasData from '../../assets/pcas.json';

const { TextArea } = Input;
const { confirm } = Modal;

const UserProfile = () => {
    // ==========================================
    // 状态管理
    // ==========================================
    const [userInfo, setUserInfo] = useState({
        avatar: '',
        username: 'AveMonica_User', // 🚨 已修改为 username
        gender: '1',
        birthday: '2000-01-01',
        bio: '热爱现场音乐！',
        realName: '未认证',
        idCard: '未认证',
        isRealNameAuth: false,
        phone: '138****5678',
        email: 'user@example.com'
    });

    const idTypeMap = {
        1: '身份证',
        2: '护照',
        3: '港澳台居住证'
    };

    const formattedPcasData = useMemo(() => {
        if (!pcasData) return [];
        return Object.entries(pcasData).map(([province, cities]) => ({
            name: province,
            children: Object.entries(cities).map(([city, districts]) => ({
                name: city,
                children: Object.entries(districts).map(district => ({
                    name: district
                }))
            }))
        }));
    }, []);

    const [spectators, setSpectators] = useState([]);
    const [addresses, setAddresses] = useState([]);

    // 🚨 表单实例控制（补充了 basicForm）
    const [basicForm] = Form.useForm();

    const [authModalVisible, setAuthModalVisible] = useState(false);
    const [authForm] = Form.useForm();

    const [spectatorModalVisible, setSpectatorModalVisible] = useState(false);
    const [spectatorForm] = Form.useForm();

    const [addressModalVisible, setAddressModalVisible] = useState(false);
    const [addressForm] = Form.useForm();

    // 🚨 新增：记录当前正在编辑的项 ID，如果为 null 说明是新增模式
    const [editingSpectatorId, setEditingSpectatorId] = useState(null);
    const [editingAddressId, setEditingAddressId] = useState(null);

    const [uploading, setUploading] = useState(false);

    const [pwdModalVisible, setPwdModalVisible] = useState(false);
    const [pwdForm] = Form.useForm();

    const [emailModalVisible, setEmailModalVisible] = useState(false);
    const [emailForm] = Form.useForm();

    const [phoneModalVisible, setPhoneModalVisible] = useState(false);
    const [phoneForm] = Form.useForm()

    // ==========================================
    // 生命周期：初始化加载数据
    // ==========================================
    useEffect(() => {
        fetchData();
    }, []);

    const fetchData = async () => {
        const token = localStorage.getItem('token');
        if (!token) return;

        const headers = { Authorization: `Bearer ${token}` };
        try {
            // 🚨 将用户基本信息接口一并加入请求
            const [specRes, addrRes, infoRes] = await Promise.all([
                axios.get('/api/user/spectator/list', { headers }),
                axios.get('/api/user/address/list', { headers }),
                axios.get('/api/user/profile/info', { headers })
            ]);

            if (specRes.data.code === 200) setSpectators(specRes.data.data);
            if (addrRes.data.code === 200) setAddresses(addrRes.data.data);
            if (infoRes.data.code === 200) {
                const userData = infoRes.data.data;

                setUserInfo({
                    ...userInfo,
                    avatar: userData.avatar,
                    realName: userData.realName || '未认证',
                    idCard: userData.idCard || '未认证',
                    isRealNameAuth: userData.realName != null,
                    phone: userData.phone,
                    email: userData.email
                });

                // 🚨 动态回填表单数据 (包含 username)
                basicForm.setFieldsValue({
                    username: userData.username,
                    gender: userData.gender != null ? userData.gender.toString() : '3',
                    birthday: userData.birthday ? dayjs(userData.birthday) : null,
                    bio: userData.bio
                });
            }
        } catch (error) {
            message.error('数据加载失败，请检查网络或重新登录');
        }
    };

    // ==========================================
    // 业务逻辑：基本信息与实名认证
    // ==========================================

    // 🚨 替换为真实 axios 保存逻辑
    const handleSaveBasicInfo = async (values) => {
        try {
            const token = localStorage.getItem('token');
            const payload = {
                username: values.username, // 🚨 使用 username
                gender: parseInt(values.gender),
                avatar: userInfo.avatar,
                birthday: values.birthday ? values.birthday.format('YYYY-MM-DD') : null,
                bio: values.bio
            };

            const res = await axios.post('/api/user/profile/update', payload, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                message.success('基本信息保存成功');
                fetchData();
            } else {
                message.error(res.data.message || '保存失败');
            }
        } catch (error) {
            message.error('保存出现异常');
        }
    };

    // 🚨 替换为真实 axios 实名认证逻辑
    const handleRealNameSubmit = async () => {
        try {
            const values = await authForm.validateFields();
            const token = localStorage.getItem('token');

            const res = await axios.post('/api/user/profile/real-name-auth', {
                realName: values.realName,
                idCard: values.idCard,
                idType: 1
            }, {
                headers: { Authorization: `Bearer ${token}` }
            });

            if (res.data.code === 200) {
                message.success('实名认证成功！身份信息已永久锁定');
                setAuthModalVisible(false);
                fetchData();
            } else if (res.data.code === 409) {
                setAuthModalVisible(false);
                confirm({
                    title: '身份信息冲突',
                    content: res.data.message + '。是否解绑已绑定账户的实名信息并绑定至当前新账户？',
                    okText: '确认解绑并绑定',
                    okType: 'danger',
                    cancelText: '取消',
                    onOk() {
                        message.success('后续对接解绑接口...');
                    }
                });
            } else {
                message.error(res.data.message || '认证失败');
            }
        } catch (error) {
            console.log('表单校验失败', error);
        }
    };

    // ==========================================
    // 业务逻辑：购票人 增、删
    // ==========================================
    const handleAddSpectator = async () => {
        try {
            const values = await spectatorForm.validateFields();
            if (!editingSpectatorId && spectators.length >= 50) return message.warning('最多只能保存 50 个常用购票人');

            const url = editingSpectatorId ? '/api/user/spectator/update' : '/api/user/spectator/add';
            const payload = { ...values, id: editingSpectatorId };

            const res = await axios.post(url, payload, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });

            if (res.data.code === 200) {
                message.success(editingSpectatorId ? '修改成功！' : '添加成功！');
                setSpectatorModalVisible(false);
                spectatorForm.resetFields();
                setEditingSpectatorId(null); // 清空编辑状态
                fetchData();
            } else {
                message.error(res.data.message);
            }
        } catch (error) { console.log(error); }
    };

    // 🚨 触发编辑购票人
    const handleEditSpectator = (sp) => {
        setEditingSpectatorId(sp.id); // 记录正在编辑的ID
        spectatorForm.setFieldsValue({
            name: sp.name,
            idType: sp.idType,
            idCard: sp.idCard
        });
        setSpectatorModalVisible(true);
    };

    // 🚨 触发编辑地址
    const handleEditAddress = (addr) => {
        setEditingAddressId(addr.id);
        addressForm.setFieldsValue({
            receiverName: addr.receiverName,
            phone: addr.phone,
            region: [addr.province, addr.city, addr.district], // 将分散的省市区组装成 Cascader 需要的数组
            detailAddress: addr.detailAddress
        });
        setAddressModalVisible(true);
    };

    const handleDeleteSpectator = async (id) => {
        try {
            const res = await axios.post(`/api/user/spectator/delete/${id}`, {}, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });
            if (res.data.code === 200) {
                message.success('删除成功');
                fetchData();
            } else {
                message.error(res.data.message);
            }
        } catch (error) { message.error('删除失败'); }
    };

    // ==========================================
    // 业务逻辑：收货地址 增、删
    // ==========================================
    const handleAddAddress = async () => {
        try {
            const values = await addressForm.validateFields();
            if (!editingAddressId && addresses.length >= 20) return message.warning('最多只能保存 20 个收货地址');

            const [province, city, district] = values.region || [];
            const payload = {
                id: editingAddressId, // 🚨 传入ID，如果是新增则为 null
                receiverName: values.receiverName,
                phone: values.phone,
                province: province,
                city: city,
                district: district,
                detailAddress: values.detailAddress
            };

            const url = editingAddressId ? '/api/user/address/update' : '/api/user/address/add';

            const res = await axios.post(url, payload, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });

            if (res.data.code === 200) {
                message.success(editingAddressId ? '修改成功！' : '添加成功！');
                setAddressModalVisible(false);
                addressForm.resetFields();
                setEditingAddressId(null);
                fetchData();
            } else {
                message.error(res.data.message);
            }
        } catch (error) { console.log(error); }
    };

    const handleDeleteAddress = async (id) => {
        try {
            const res = await axios.post(`/api/user/address/delete/${id}`, {}, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });
            if (res.data.code === 200) {
                message.success('删除成功');
                fetchData();
            } else {
                message.error(res.data.message);
            }
        } catch (error) { message.error('删除失败'); }
    };

    // 🚨 校验上传文件（限制只能传 JPG/PNG，且小于 2MB）
    const beforeUpload = (file) => {
        const isJpgOrPng = file.type === 'image/jpeg' || file.type === 'image/png';
        if (!isJpgOrPng) message.error('只能上传 JPG/PNG 格式的图片！');
        const isLt2M = file.size / 1024 / 1024 < 2;
        if (!isLt2M) message.error('图片大小必须小于 2MB！');
        return isJpgOrPng && isLt2M;
    };

    // 🚨 处理上传进度与结果
    const handleAvatarChange = (info) => {
        if (info.file.status === 'uploading') {
            setUploading(true);
            return;
        }
        if (info.file.status === 'done') {
            // 假设后端的上传接口返回的结构是 { code: 200, data: "图片URL" }
            if (info.file.response && info.file.response.code === 200) {
                const imageUrl = info.file.response.data;
                // 实时更新本地 state 预览新头像
                setUserInfo({ ...userInfo, avatar: imageUrl });
                message.success('头像上传成功！记得点击底部保存生效哦');
            } else {
                message.error(info.file.response?.message || '上传失败');
            }
            setUploading(false);
        } else if (info.file.status === 'error') {
            message.error('网络异常，上传失败');
            setUploading(false);
        }
    };

    // 🚨 新增：修改密码提交
    const handleUpdatePassword = async () => {
        try {
            const values = await pwdForm.validateFields();
            if (values.newPassword !== values.confirmPassword) {
                return message.error('两次输入的新密码不一致');
            }
            const res = await axios.post('/api/user/profile/update-password', values, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });
            if (res.data.code === 200) {
                message.success('密码修改成功！');
                setPwdModalVisible(false);
                pwdForm.resetFields();
            } else {
                message.error(res.data.message);
            }
        } catch (error) { console.log(error); }
    };

    // 🚨 新增：修改邮箱提交
    const handleUpdateEmail = async () => {
        try {
            const values = await emailForm.validateFields();
            const res = await axios.post('/api/user/profile/update-email', values, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });
            if (res.data.code === 200) {
                message.success('邮箱绑定成功！');
                setEmailModalVisible(false);
                emailForm.resetFields();
                fetchData(); // 刷新数据以显示新邮箱
            } else {
                message.error(res.data.message);
            }
        } catch (error) { console.log(error); }
    };

    // 🚨 新增：修改手机号提交
    const handleUpdatePhone = async () => {
        try {
            const values = await phoneForm.validateFields();
            const res = await axios.post('/api/user/profile/update-phone', values, {
                headers: { Authorization: `Bearer ${localStorage.getItem('token')}` }
            });
            if (res.data.code === 200) {
                message.success('手机号更换成功！');
                setPhoneModalVisible(false);
                phoneForm.resetFields();
                fetchData(); // 刷新数据
            } else {
                message.error(res.data.message);
            }
        } catch (error) { console.log(error); }
    };

    // ==========================================
    // 渲染：板块 1 - 基本信息
    // ==========================================
    const renderBasicInfo = () => (
        <div className="tab-pane-content" style={{ textAlign: 'left' }}>
            <div className="section-header">基本信息</div>
            {/* 🚨 绑定了 basicForm 并移除了 initialValues */}
            <Form form={basicForm} layout="vertical" onFinish={handleSaveBasicInfo} style={{ maxWidth: 600 }}>
                <Form.Item label="头像">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
                        <Avatar size={80} icon={<UserOutlined />} src={userInfo.avatar} />
                        <Upload
                            name="file" // 与后端的 @RequestParam("file") 对应
                            // 🚨 1. 修改为真实的上传接口路径
                            action="/api/common/upload"
                            // 🚨 2. 新增：附带额外的表单参数，告诉后端这是头像(avatar)
                            data={{ type: 'avatar' }}
                            headers={{ Authorization: `Bearer ${localStorage.getItem('token')}` }}
                            showUploadList={false}
                            beforeUpload={beforeUpload}
                            onChange={handleAvatarChange}
                        >
                            <Button loading={uploading}>更换头像</Button>
                        </Upload>
                    </div>
                </Form.Item>
                <Row gutter={16}>
                    <Col span={12}>
                        <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
                            {/* 🚨 新增：通过 disabled 属性动态锁定 admin 账号 */}
                            <Input
                                placeholder="请输入用户名"
                                disabled={userInfo.username === 'admin'}
                            />
                        </Form.Item>
                    </Col>
                    <Col span={12}>
                        <Form.Item label="性别" name="gender">
                            <Radio.Group>
                                <Radio value="1">男</Radio>
                                <Radio value="2">女</Radio>
                                <Radio value="3">保密</Radio>
                            </Radio.Group>
                        </Form.Item>
                    </Col>
                </Row>
                <Form.Item label="生日" name="birthday">
                    <DatePicker style={{ width: '100%' }} />
                </Form.Item>
                <Form.Item label="个人简介" name="bio">
                    <TextArea rows={4} placeholder="介绍一下你自己吧..." maxLength={200} showCount />
                </Form.Item>

                <div className="section-header" style={{ marginTop: 40, fontSize: 16 }}>实名信息
                    {userInfo.isRealNameAuth ? <Tag color="success" style={{ marginLeft: 10 }}>已认证</Tag> : <Tag color="default" style={{ marginLeft: 10 }}>未认证</Tag>}
                </div>
                <div style={{ color: '#999', marginBottom: 20, fontSize: 12 }}>注：账户的真实姓名和身份证号认证后才能购票，且填写过一次之后不能修改。</div>

                <Row gutter={16}>
                    <Col span={12}>
                        <Form.Item label="真实姓名">
                            <Input value={userInfo.realName} disabled placeholder="未认证" />
                        </Form.Item>
                    </Col>
                    <Col span={12}>
                        <Form.Item label="身份证号">
                            <Input value={userInfo.idCard} disabled placeholder="未认证" />
                        </Form.Item>
                    </Col>
                </Row>

                <Form.Item style={{ marginTop: 30 }}>
                    <Button type="primary" htmlType="submit" style={{ backgroundColor: '#FF8899', border: 'none', width: 120 }}>
                        保存修改
                    </Button>
                </Form.Item>
            </Form>
        </div>
    );

    // ==========================================
    // 渲染：板块 2 - 账号设置
    // ==========================================
    const renderAccountSettings = () => (
        <div className="tab-pane-content" style={{ textAlign: 'left' }}>
            <div className="section-header">账号设置</div>
            <List
                itemLayout="horizontal"
                dataSource={[
                    { key: 'pwd', title: '登录密码', desc: '定期修改密码有助于保护账号安全', action: '修改', status: '已设置' },
                    { key: 'phone', title: '手机验证', desc: `已绑定手机号：${userInfo.phone}`, action: '更换', status: '已验证' },
                    { key: 'email', title: '邮箱验证', desc: userInfo.email ? `已绑定邮箱：${userInfo.email}` : '未绑定邮箱，用于接收票务通知', action: userInfo.email ? '更换' : '去绑定', status: userInfo.email ? '已验证' : '未验证' },
                    { key: 'auth', title: '实名认证', desc: userInfo.isRealNameAuth ? `已认证：${userInfo.realName} (${userInfo.idCard})` : '完成实名认证后方可进行购票', action: userInfo.isRealNameAuth ? '查看' : '去认证', status: userInfo.isRealNameAuth ? '已认证' : '未认证' },
                ]}
                renderItem={item => (
                    <List.Item
                        actions={[
                            <Button
                                type="link"
                                onClick={() => {
                                    if (item.key === 'auth' && !userInfo.isRealNameAuth) setAuthModalVisible(true);
                                    if (item.key === 'pwd') setPwdModalVisible(true);
                                    if (item.key === 'email') setEmailModalVisible(true);
                                    if (item.key === 'phone') setPhoneModalVisible(true);
                                }}
                            >
                                {item.action}
                            </Button>
                        ]}
                    >
                        <List.Item.Meta
                            style={{ textAlign: 'left' }}
                            title={<span style={{ fontWeight: 'bold' }}>{item.title} {item.status === '已认证' || item.status === '已验证' || item.status === '已设置' ? <SafetyCertificateOutlined style={{ color: '#52c41a', marginLeft: 8 }} /> : null}</span>}
                            description={item.desc}
                        />
                    </List.Item>
                )}
            />

            {/* 1. 原有的实名认证弹窗 */}
            <Modal title="实名认证" open={authModalVisible} onOk={handleRealNameSubmit} onCancel={() => setAuthModalVisible(false)} okText="确认提交" cancelText="取消" okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}>
                <div style={{ color: '#ff4d4f', marginBottom: 20 }}>⚠️ 认证成功后身份信息将被锁定，不可修改。同一身份信息只能绑定一个账户。</div>
                <Form form={authForm} layout="vertical">
                    <Form.Item label="真实姓名" name="realName" rules={[{ required: true, message: '请输入真实姓名' }]}>
                        <Input placeholder="请输入证件上的真实姓名" />
                    </Form.Item>
                    <Form.Item label="身份证号" name="idCard" rules={[{ required: true, message: '请输入18位身份证号' }, { len: 18, message: '身份证号必须为18位' }]}>
                        <Input placeholder="请输入18位身份证号" maxLength={18} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 2. 修改密码弹窗 */}
            <Modal title="修改登录密码" open={pwdModalVisible} onOk={handleUpdatePassword} onCancel={() => {setPwdModalVisible(false); pwdForm.resetFields();}} okText="确认修改" cancelText="取消" okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}>
                <Form form={pwdForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="原密码" name="oldPassword" rules={[{ required: true, message: '请输入原密码' }]}>
                        <Input.Password placeholder="请输入当前登录密码" />
                    </Form.Item>
                    <Form.Item label="新密码" name="newPassword" rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '密码长度至少为 6 位' }]}>
                        <Input.Password placeholder="请输入新密码（至少 6 位）" />
                    </Form.Item>
                    <Form.Item label="确认新密码" name="confirmPassword" rules={[{ required: true, message: '请再次输入新密码' }]}>
                        <Input.Password placeholder="请再次输入新密码" />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 3. 绑定/更换邮箱弹窗 */}
            <Modal title="绑定邮箱" open={emailModalVisible} onOk={handleUpdateEmail} onCancel={() => {setEmailModalVisible(false); emailForm.resetFields();}} okText="保存" cancelText="取消" okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}>
                <Form form={emailForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="邮箱地址" name="email" rules={[{ required: true, message: '请输入邮箱' }, { type: 'email', message: '邮箱格式不正确' }]}>
                        <Input placeholder="请输入新的邮箱地址" />
                    </Form.Item>
                    <Form.Item label="验证码" name="code" rules={[{ required: true, message: '请输入验证码' }]}>
                        <div style={{ display: 'flex', gap: 10 }}>
                            <Input placeholder="请输入 6 位验证码" />
                            <Button>获取验证码</Button>
                        </div>
                    </Form.Item>
                </Form>
            </Modal>

            {/* 4. 更换手机号弹窗 */}
            <Modal title="换绑手机号" open={phoneModalVisible} onOk={handleUpdatePhone} onCancel={() => {setPhoneModalVisible(false); phoneForm.resetFields();}} okText="保存" cancelText="取消" okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}>
                <Form form={phoneForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="新手机号" name="phone" rules={[{ required: true, message: '请输入新手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]}>
                        <Input placeholder="请输入新的 11 位手机号" maxLength={11} />
                    </Form.Item>
                    <Form.Item label="验证码" name="code" rules={[{ required: true, message: '请输入验证码' }]}>
                        <div style={{ display: 'flex', gap: 10 }}>
                            <Input placeholder="请输入验证码" />
                            <Button>获取验证码</Button>
                        </div>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );

    // ==========================================
    // 渲染：板块 3 - 常用购票人和收货地址
    // ==========================================
    const renderSpectatorsAndAddress = () => (
        <div className="tab-pane-content" style={{ textAlign: 'left' }}>

            {/* 购票人区块 */}
            <div className="section-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>常用购票人 <span style={{ fontSize: 14, color: '#999', fontWeight: 'normal' }}>(已保存 {spectators.length}/50 个)</span></span>
                <Button type="primary" icon={<PlusOutlined />} style={{ backgroundColor: '#FF8899', border: 'none' }} onClick={() => setSpectatorModalVisible(true)}>
                    新建购票人
                </Button>
            </div>
            <Row gutter={16} style={{ marginBottom: 40 }}>
                {spectators.map(sp => (
                    <Col span={12} key={sp.id}>
                        <div className="info-card">
                            <div style={{ fontWeight: 'bold', fontSize: 16, marginBottom: 8 }}>{sp.name}</div>
                            <div style={{ color: '#666' }}>{idTypeMap[sp.idType] || '未知证件'}：{sp.idCard}</div>
                            <div className="info-card-actions">
                                {/* 🚨 新增编辑按钮 */}
                                <Button type="link" onClick={() => handleEditSpectator(sp)}>编辑</Button>
                                <Popconfirm title="确定要删除该购票人吗？" onConfirm={() => handleDeleteSpectator(sp.id)} okText="确定" cancelText="取消">
                                    <Button type="link" danger>删除</Button>
                                </Popconfirm>
                            </div>
                        </div>
                    </Col>
                ))}
            </Row>

            {/* 地址区块 */}
            <div className="section-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span>收货地址 <span style={{ fontSize: 14, color: '#999', fontWeight: 'normal' }}>(已保存 {addresses.length}/20 个)</span></span>
                <Button type="primary" icon={<PlusOutlined />} style={{ backgroundColor: '#FF8899', border: 'none' }} onClick={() => setAddressModalVisible(true)}>
                    创建新地址
                </Button>
            </div>
            <Row gutter={16}>
                {addresses.map(addr => (
                    <Col span={12} key={addr.id}>
                        <div className="info-card">
                            <div style={{ fontWeight: 'bold', fontSize: 16, marginBottom: 8 }}>{addr.receiverName} <span style={{ fontSize: 14, color: '#666', fontWeight: 'normal', marginLeft: 10 }}>{addr.phone}</span></div>
                            <div style={{ color: '#666' }}>{addr.province}{addr.city}{addr.district}{addr.detailAddress}</div>
                            <div className="info-card-actions">
                                {/* 🚨 新增编辑按钮 */}
                                <Button type="link" onClick={() => handleEditAddress(addr)}>编辑</Button>
                                <Popconfirm title="确定要删除该地址吗？" onConfirm={() => handleDeleteAddress(addr.id)} okText="确定" cancelText="取消">
                                    <Button type="link" danger>删除</Button>
                                </Popconfirm>
                            </div>
                        </div>
                    </Col>
                ))}
            </Row>

            {/* 新增购票人弹窗 */}
            <Modal
                title={editingSpectatorId ? "编辑常用购票人" : "新增常用购票人"}
                open={spectatorModalVisible}
                onOk={handleAddSpectator}
                onCancel={() => {
                    setSpectatorModalVisible(false);
                    spectatorForm.resetFields();
                    setEditingSpectatorId(null); // 🚨 关闭时重置状态
                }}
                okText="保存"
                cancelText="取消"
                okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}
            >
                <Form form={spectatorForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="真实姓名" name="name" rules={[{ required: true, message: '请输入观演人真实姓名' }]}>
                        <Input placeholder="请输入证件上的真实姓名" />
                    </Form.Item>

                    <Form.Item label="证件类型" name="idType" initialValue={1}>
                        <Select placeholder="请选择证件类型">
                            <Select.Option value={1}>身份证</Select.Option>
                            <Select.Option value={2}>护照</Select.Option>
                            <Select.Option value={3}>港澳台居民居住证</Select.Option>
                        </Select>
                    </Form.Item>

                    <Form.Item
                        label="证件号码"
                        name="idCard"
                        dependencies={['idType']}
                        rules={[
                            { required: true, message: '请输入证件号码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value) return Promise.resolve();
                                    const type = getFieldValue('idType');
                                    if (type === 1) {
                                        const reg = /^[1-9]\d{5}(18|19|20)\d{2}((0[1-9])|(1[0-2]))(([0-2][1-9])|10|20|30|31)\d{3}[0-9Xx]$/;
                                        if (!reg.test(value)) return Promise.reject(new Error('身份证格式不正确'));
                                    } else if (type === 2) {
                                        if (!/^[a-zA-Z0-9]{5,17}$/.test(value)) return Promise.reject(new Error('护照格式不正确'));
                                    } else if (type === 3) {
                                        if (value.length < 8) return Promise.reject(new Error('证件号码长度不正确'));
                                    }
                                    return Promise.resolve();
                                },
                            }),
                        ]}
                    >
                        <Input placeholder="请输入证件号码" maxLength={18} />
                    </Form.Item>
                </Form>
            </Modal>

            {/* 新增收货地址弹窗 */}
            <Modal
                title={editingAddressId ? "编辑收货地址" : "新增收货地址"}
                open={addressModalVisible}
                onOk={handleAddAddress}
                onCancel={() => {
                    setAddressModalVisible(false);
                    addressForm.resetFields();
                    setEditingAddressId(null); // 🚨 关闭时重置状态
                }}
                okText="保存"
                cancelText="取消"
                okButtonProps={{ style: { backgroundColor: '#FF8899', border: 'none' } }}
            >
                <Form form={addressForm} layout="vertical" style={{ marginTop: 20 }}>
                    <Form.Item label="收件人姓名" name="receiverName" rules={[{ required: true, message: '请输入收件人姓名' }]}>
                        <Input placeholder="请输入真实姓名" />
                    </Form.Item>

                    <Form.Item label="联系电话" name="phone" rules={[{ required: true, message: '请输入手机号' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }]}>
                        <Input placeholder="请输入11位手机号" maxLength={11} />
                    </Form.Item>

                    <Form.Item label="所在地区" name="region" rules={[{ required: true, message: '请选择所在地区' }]}>
                        <Cascader
                            options={formattedPcasData}
                            placeholder="请选择所在地区"
                            fieldNames={{ label: 'name', value: 'name', children: 'children' }}
                        />
                    </Form.Item>

                    <Form.Item label="详细地址" name="detailAddress" rules={[{ required: true, message: '请输入详细地址' }]}>
                        <TextArea rows={3} placeholder="请输入小区、楼栋号、单元室等详细信息" />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );

    // ==========================================
    // 侧边栏 Tabs 导航配置
    // ==========================================
    const tabItems = [
        { key: '1', label: <span><UserOutlined /> 基本信息</span>, children: renderBasicInfo() },
        { key: '2', label: <span><SettingOutlined /> 账号设置</span>, children: renderAccountSettings() },
        { key: '3', label: <span><TeamOutlined /> 购票人/地址</span>, children: renderSpectatorsAndAddress() },
    ];

    return (
        <div className="user-profile-container">
            <PublicHeader />
            <div className="profile-content">
                <Tabs tabPosition="left" items={tabItems} defaultActiveKey="1" style={{ width: '100%' }} />
            </div>
        </div>
    );
};

export default UserProfile;