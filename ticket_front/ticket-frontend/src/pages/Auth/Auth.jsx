import React, { useState, useEffect, useRef } from 'react';
import { Form, Input, Button, message, Modal } from 'antd';
import { motion, AnimatePresence } from 'framer-motion';
import { User, Lock, ArrowRight, Smartphone, ShieldCheck, Smile, ArrowLeft } from 'lucide-react';
import { Turnstile } from '@marsidev/react-turnstile'; // 引入验证组件
import './Auth.css';
import axios from '../../utils/request';
import { useNavigate } from 'react-router-dom';
import { debounce } from 'lodash';

const Auth = () => {
    const [form] = Form.useForm();
    const [autoRegForm] = Form.useForm();
    const navigate = useNavigate();

    const [isLogin, setIsLogin] = useState(true);
    const [loginType, setLoginType] = useState('password');
    const [loading, setLoading] = useState(false);
    const [countdown, setCountdown] = useState(0);

    // Turnstile 状态
    const [turnstileToken, setTurnstileToken] = useState(null);
    const turnstileRef = useRef();

    const [showAutoRegModal, setShowAutoRegModal] = useState(false);
    const [tempAuthData, setTempAuthData] = useState({});


// 1. 独立管理网页标题
    useEffect(() => {
        document.title = isLogin ? '登录 - Ave Monica Ticket' : '注册 - Ave Monica Ticket';
    }, [isLogin]);

    // 2. 独立管理随机昵称的获取
    useEffect(() => {
        // 只有当切换到注册页面时，才去获取一次
        if (!isLogin) {
            // 优化：如果用户已经自己输入了昵称，切换回来时不要覆盖掉
            const currentUsername = form.getFieldValue('username');
            if (!currentUsername) {
                axios.get('/api/user/random-username').then(res => {
                    if (res.data.code === 200) {
                        form.setFieldsValue({ username: res.data.data });
                    }
                });
            }
        }
    }, [isLogin, form]); // 只监听 isLogin 的变化

    // 3. 独立管理倒计时定时器
    useEffect(() => {
        let timer;
        if (countdown > 0) {
            timer = setInterval(() => setCountdown(c => c - 1), 1000);
        }
        return () => clearInterval(timer);
    }, [countdown]); // 只监听倒计时的变化

    // 获取验证码
    const handleGetCode = async () => {
        try {
            const values = await form.validateFields(['phone']);
            // 发送验证码前最好也校验 Turnstile，防止短信轰炸
            if (!turnstileToken) {
                message.warning('请先完成人机安全验证');
                return;
            }

            // 调用后端发送验证码接口
            const res = await axios.post('/api/user/send-code', { phone: values.phone });

            if (res.data.code === 200) {
                message.success('验证码已发送');
                setCountdown(60); // 开启 60 秒倒计时
            } else {
                console.error(err);
            }
        } catch (err) {}
    };

    // 主表单提交 (登录 / 注册)
    const onFinish = async (values) => {
        if (!turnstileToken) {
            message.error('请完成安全验证');
            return;
        }

        setLoading(true);
        try {
            if (isLogin) {
                // ================= 登录分支 =================
                if (loginType === 'password') {
                    // 1. 密码登录
                    const res = await axios.post('/api/user/login', {
                        account: values.username, // 你的表单 name 是 username，后端需要 phone
                        password: values.password
                    });

                    if (res.data.code === 200) {
                        localStorage.setItem('token', res.data.data);
                        message.success('登录成功');
                        navigate('/'); // 跳转回主页
                    } else {
                        message.error(res.data.message);
                        turnstileRef.current?.reset(); // 失败后必须重置验证码，Turnstile 是一次性的
                    }
                } else if (loginType === 'sms') {
                    // 2. 免密登录
                    const res = await axios.post(`/api/user/login-sms?phone=${values.phone}&code=${values.code}`);

                    if (res.data.code === 200) {
                        // 老用户免密登录成功
                        localStorage.setItem('token', res.data.data);
                        message.success('登录成功');
                        navigate('/');
                    } else if (res.data.code === 201) {
                        // 新用户，验证码正确，弹出完善信息框
                        setTempAuthData({
                            phone: values.phone,
                            registerTicket: res.data.data
                        });
                        setShowAutoRegModal(true);
                        setTurnstileToken(null);
                        turnstileRef.current?.reset(); // 重置供弹窗里使用
                    } else {
                        message.error(res.data.message);
                        turnstileRef.current?.reset();
                    }
                }
            } else {
                // ================= 注册分支 =================
                // (注意：严谨起见，注册时后端也应该校验验证码，这里前端按照你后端的 UserRegisterDTO 传参)
                const res = await axios.post('/api/user/register', {
                    phone: values.phone,
                    password: values.password,
                    username: values.username,
                    code: values.code
                });

                if (res.data.code === 200) {
                    message.success('注册成功，请登录');
                    setIsLogin(true); // 切换回登录界面
                    setLoginType('password');
                    form.resetFields();
                } else {
                    message.error(res.data.message);
                    turnstileRef.current?.reset();
                }
            }
        } catch (err) {
            // 捕获网络错误或后端的全局异常 (例如 400 Bad Request)
            message.error(err.response?.data?.message || '系统繁忙，请稍后再试');
            turnstileRef.current?.reset();
        } finally {
            setLoading(false);
        }
    };

// 弹窗提交 (免密登录时发现是新用户)
    const onAutoRegisterSubmit = async (values) => {
        // 🚨 移除了这里的 Turnstile 校验，因为免密阶段已经验证过手机了
        try {
            const res = await axios.post('/api/user/register', {
                phone: tempAuthData.phone,
                password: values.password,
                registerTicket: tempAuthData.registerTicket,
                username: values.username
            });

            if (res.data.code === 200) {
                message.success('注册成功，快去登录吧！');
                setShowAutoRegModal(false);
                setIsLogin(true);
                setLoginType('password');
                form.resetFields();
            } else {
                message.error(res.data.message);
            }
        } catch (err) {
            message.error(err.response?.data?.message || '注册失败');
        }
    };

    // 2. 异步校验函数 (带防抖，防止输入每个字母都发请求)
    const checkUsernameUnique = async (rule, value) => {
        if (!value || value.length < 3) return Promise.resolve();

        // 调用后端接口
        const res = await axios.get(`/api/user/check-username?username=${value}`);
        if (res.data.data === true) {
            return Promise.resolve();
        } else {
            return Promise.reject(new Error('该昵称已被占用'));
        }
    };

    return (
        <div className="auth-container">

            {/* 返回键 */}
            <div className="auth-back-btn" onClick={() => navigate(-1)}>
                <ArrowLeft size={18} />
                <span>返回</span>
            </div>

            <div className="auth-bg-decoration" />

            <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="auth-card">
                <div className="auth-header">
                    <motion.div
                        className="auth-logo-wrapper"
                        initial={{ y: -8 }}
                        animate={{ y: 0 }}
                        transition={{
                            type: "spring",
                            stiffness: 100,
                            repeat: Infinity,
                            repeatType: "mirror",
                            duration: 2
                        }}
                        whileHover={{ scale: 1.05, rotate: 5 }} // 悬停时轻微放大并倾斜，更有活力
                    >
                        <img
                            src="/uploads/scrollbar/logo_login.png"
                            alt="Ave Monica Logo"
                            className="auth-logo-img"
                        />
                    </motion.div>
                    <p>{isLogin ? '与同好们相聚' : '开启你的二次元生活'}</p>
                </div>

                {isLogin && (
                    <div className="login-type-switch">
                        <div className={`login-type-btn ${loginType === 'password' ? 'active' : ''}`} onClick={() => setLoginType('password')}>密码登录</div>
                        <div className={`login-type-btn ${loginType === 'sms' ? 'active' : ''}`} onClick={() => setLoginType('sms')}>免密 / 注册</div>
                    </div>
                )}

                <Form form={form} layout="vertical" onFinish={onFinish} requiredMark={false}>
                    <AnimatePresence mode="wait">
                        <motion.div key={isLogin ? loginType : 'register'} initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>

                            {/* 字段渲染逻辑 */}
                            {(loginType === 'sms' || !isLogin) && (
                                <Form.Item name="phone" rules={[{ required: true, message: '请输入手机号' }, { pattern: /^1\d{10}$/, message: '格式错误' }]}>
                                    <Input prefix={<Smartphone size={16} color="#17b9b9" />} placeholder="手机号" allowClear maxLength={11} />
                                </Form.Item>
                            )}

                            {isLogin && loginType === 'password' && (
                                <Form.Item name="username" rules={[{ required: true, message: '请输入账号' }]}>
                                    <Input prefix={<User size={16} color="#17b9b9" />} placeholder="账号 / 手机号" allowClear />
                                </Form.Item>
                            )}

                            {(!isLogin || (isLogin && loginType === 'sms')) && (
                                <div className="code-input-group">
                                    <Form.Item name="code" rules={[{ required: true, message: '请输入验证码' }]}>
                                        <Input prefix={<ShieldCheck size={16} color="#17b9b9" />} placeholder="验证码" allowClear maxLength={6} />
                                    </Form.Item>
                                    <Button className="get-code-btn" onClick={handleGetCode} disabled={countdown > 0}>
                                        {countdown > 0 ? `${countdown}s` : '获取验证码'}
                                    </Button>
                                </div>
                            )}

                            {!isLogin && (
                                <>
                                    <Form.Item name="username"
                                               validateTrigger="onBlur"
                                               rules={[
                                        { required: true, message: '请输入昵称' },
                                        { validator: checkUsernameUnique }
                                    ]}>
                                        <Input prefix={<Smile size={16} color="#17b9b9" />} placeholder="昵称" allowClear/>
                                    </Form.Item>
                                </>
                            )}

                            {(loginType === 'password' || !isLogin) && (
                                <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                                    <Input.Password prefix={<Lock size={16} color="#17b9b9" />} placeholder="密码" allowClear/>
                                </Form.Item>
                            )}

                            {/* Cloudflare Turnstile 验证组件 */}
                            <div className="turnstile-wrapper">
                                <Turnstile
                                    ref={turnstileRef}
                                    siteKey="1x00000000000000000000AA" // 替换为你的真实 Site Key
                                    options={{ theme: 'light' }}
                                    onSuccess={(token) => setTurnstileToken(token)}
                                />
                            </div>

                            <Button type="primary" htmlType="submit" className="auth-submit-btn" block loading={loading}>
                                {isLogin ? '登 录' : '立 即 注 册'} <ArrowRight size={18} style={{marginLeft: 8}} />
                            </Button>
                        </motion.div>
                    </AnimatePresence>
                </Form>

                <div className="auth-switch-text">
                    {isLogin ? "还没有账号?" : "已经有账号了?"}
                    <span className="auth-switch-link" onClick={() => { setIsLogin(!isLogin); form.resetFields(); }}>
            {isLogin ? '去注册' : '返回登录'}
          </span>
                </div>
            </motion.div>

            {/* 自动注册弹窗：设置密码 + 设置昵称 + 重新验证 */}
            <Modal
                title="欢迎加入 Ave Monica Ticket"
                open={showAutoRegModal}
                footer={null}
                className="glass-modal" // 🚨 类名改为了 glass-modal，稍后配 CSS
                centered
                maskClosable={false}
                onCancel={() => {setShowAutoRegModal(false);
                    autoRegForm.resetFields();}}
                destroyOnClose
            >
                <p style={{color: '#999', marginBottom: 24, textAlign: 'center', fontSize: '13px'}}>
                    检测到您是新用户，请完善资料完成注册
                </p>
                <Form form={autoRegForm} layout="vertical" onFinish={onAutoRegisterSubmit}>

                    <Form.Item
                        name="username"
                        validateTrigger="onBlur"
                        rules={[
                            { required: true, message: '请输入用户名' },
                            { validator: checkUsernameUnique } // 复用主界面的查重逻辑
                        ]}
                    >
                        <Input prefix={<Smile size={16} color="#FF8899" />} placeholder="设定一个专属用户名" />
                    </Form.Item>

                    <Form.Item
                        name="password"
                        rules={[
                            { required: true, message: '请输入密码' },
                            { min: 6, message: '密码至少 6 位' }
                        ]}
                    >
                        <Input.Password prefix={<Lock size={16} color="#FF8899" />} placeholder="设置登录密码" />
                    </Form.Item>

                    {/* 👇 新增：确认密码 */}
                    <Form.Item
                        name="confirmPassword"
                        dependencies={['password']} // 依赖上面的 password 字段
                        rules={[
                            { required: true, message: '请确认登录密码' },
                            ({ getFieldValue }) => ({
                                validator(_, value) {
                                    if (!value || getFieldValue('password') === value) {
                                        return Promise.resolve();
                                    }
                                    return Promise.reject(new Error('两次输入的密码不一致！'));
                                },
                            }),
                        ]}
                    >
                        <Input.Password prefix={<Lock size={16} color="#FF8899" />} placeholder="再次输入确认密码" />
                    </Form.Item>

                    <Button type="primary" htmlType="submit" className="modal-submit-btn" block>
                        开启乐迷之旅
                    </Button>
                </Form>
            </Modal>
        </div>
    );
};

export default Auth;