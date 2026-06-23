import React, { useEffect, useMemo, useState } from 'react';
import {
    Button,
    Card,
    Descriptions,
    Empty,
    Form,
    Input,
    message,
    Modal,
    Select,
    Space,
    Steps,
    Table,
    Tag,
    Typography
} from 'antd';
import {
    CheckCircleOutlined,
    CloseCircleOutlined,
    EyeOutlined,
    ReloadOutlined,
    SearchOutlined
} from '@ant-design/icons';
import axios from '../../utils/request';
import './OrderManager.css';

const { TextArea } = Input;

const STATUS_OPTIONS = [
    { value: 0, label: '全部订单' },
    { value: 1, label: '未支付' },
    { value: 2, label: '已取消' },
    { value: 3, label: '已完成订单' },
    { value: 4, label: '申请退款中' },
    { value: 5, label: '异常订单' },
    { value: 6, label: '未检票' },
    { value: 7, label: '已退票' }
];

const SEARCH_OPTIONS = [
    { value: 'orderId', label: '订单ID' },
    { value: 'userId', label: '用户ID' },
    { value: 'eventName', label: '演出名称' },
    { value: 'eventId', label: '演出ID' }
];

const statusColorMap = {
    1: 'warning',
    2: 'default',
    3: 'success',
    4: 'red',
    5: 'error',
    6: 'blue',
    7: 'grey'
};

const statusTextMap = {
    1: '未支付',
    2: '已取消',
    3: '已完成订单',
    4: '申请退款中',
    5: '异常订单',
    6: '未检票',
    7: '已退票'
};

const getStatusText = (record) => record.statusText || statusTextMap[Number(record.status)] || '未知状态';
const getStatusColor = (status) => statusColorMap[Number(status)] || 'default';

const formatMoney = (value) => Number(value || 0).toFixed(2);

const maskPhone = (phone) => {
    if (!phone) return '-';
    const text = String(phone);
    if (text.length < 7) return text;
    return `${text.slice(0, 3)}****${text.slice(-4)}`;
};

const maskIdCard = (idCard) => {
    if (!idCard) return '-';
    const text = String(idCard);
    if (text.length <= 6) return text;
    return `${text.slice(0, 4)}************${text.slice(-2)}`;
};

const readRecord = (record, flatKey, nestedPath, fallback = '-') => {
    if (record?.[flatKey] !== undefined && record?.[flatKey] !== null && record?.[flatKey] !== '') {
        return record[flatKey];
    }

    if (!nestedPath) return fallback;
    const value = nestedPath.split('.').reduce((obj, key) => obj?.[key], record);
    return value !== undefined && value !== null && value !== '' ? value : fallback;
};

const OrderManager = () => {
    const [form] = Form.useForm();

    const [loading, setLoading] = useState(false);
    const [orders, setOrders] = useState([]);
    const [pagination, setPagination] = useState({
        current: 1,
        pageSize: 10,
        total: 0
    });

    const [detailVisible, setDetailVisible] = useState(false);
    const [detailLoading, setDetailLoading] = useState(false);
    const [currentOrder, setCurrentOrder] = useState(null);

    const [refundVisible, setRefundVisible] = useState(false);
    const [refundMode, setRefundMode] = useState('approve');
    const [refundOrder, setRefundOrder] = useState(null);
    const [refundReason, setRefundReason] = useState('');
    const [refundSubmitting, setRefundSubmitting] = useState(false);

    useEffect(() => {
        form.setFieldsValue({
            status: 0,
            searchType: 'orderId',
            keyword: ''
        });
        fetchOrders(1, 10);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const fetchOrders = async (current = pagination.current, pageSize = pagination.pageSize) => {
        setLoading(true);

        try {
            const values = form.getFieldsValue();
            const res = await axios.get('/api/admin/order/page', {
                params: {
                    current,
                    size: pageSize,
                    status: values.status || 0,
                    searchType: values.searchType || 'orderId',
                    keyword: values.keyword || ''
                }
            });

            if (res.data.code === 200) {
                const data = res.data.data || {};
                setOrders(data.records || []);
                setPagination({
                    current: Number(data.current || current),
                    pageSize: Number(data.size || pageSize),
                    total: Number(data.total || 0)
                });
            } else {
                message.error(res.data.message || '获取订单列表失败');
            }
        } catch (error) {
            message.error(error.response?.data?.message || '网络异常，获取订单列表失败');
        } finally {
            setLoading(false);
        }
    };

    const handleSearch = () => {
        const values = form.getFieldsValue();
        const keyword = String(values.keyword || '').trim();

        if (keyword && ['orderId', 'userId', 'eventId'].includes(values.searchType) && !/^\d+$/.test(keyword)) {
            message.warning('ID 搜索必须完整输入数字');
            return;
        }

        fetchOrders(1, pagination.pageSize);
    };

    const handleReset = () => {
        form.setFieldsValue({
            status: 0,
            searchType: 'orderId',
            keyword: ''
        });
        fetchOrders(1, pagination.pageSize);
    };

    const openDetail = async (record) => {
        setDetailVisible(true);
        setDetailLoading(true);
        setCurrentOrder(null);

        try {
            const res = await axios.get(`/api/admin/order/detail/${record.id}`);
            if (res.data.code === 200) {
                setCurrentOrder(res.data.data || record);
            } else {
                message.error(res.data.message || '获取订单详情失败');
            }
        } catch (error) {
            message.error(error.response?.data?.message || '网络异常，获取订单详情失败');
        } finally {
            setDetailLoading(false);
        }
    };

    const openRefund = async (record) => {
        setRefundVisible(true);
        setRefundOrder(null);
        setRefundReason('');
        setRefundMode('view');

        try {
            const res = await axios.get(`/api/admin/order/detail/${record.id}`);
            if (res.data.code === 200) {
                setRefundOrder(res.data.data || record);
            } else {
                message.error(res.data.message || '获取退款详情失败');
                setRefundOrder(record);
            }
        } catch (error) {
            message.error(error.response?.data?.message || '网络异常，获取退款详情失败');
            setRefundOrder(record);
        }
    };

    const getRefundStepCurrent = (order) => {
        const step = Number(order?.refundStep || 1);
        return Math.max(0, Math.min(3, step - 1));
    };

    const getRefundStepStatus = (order) => {
        if (Number(order?.refundStatus) === 5 || Number(order?.refundStatus) === 6) {
            return 'error';
        }
        if (Number(order?.refundStatus) === 4 || Number(order?.status) === 7) {
            return 'finish';
        }
        return 'process';
    };


    const getRefundFailText = (order) => {
        const refundStatus = Number(order?.refundStatus);

        // 只有审核拒绝或退款失败时，才显示红色错误原因
        if (refundStatus === 5 || refundStatus === 6) {
            return order?.refundFailReason || order?.refundRejectReason || '';
        }

        return '';
    };

    const canAuditRefund = (order) => {
        return Number(order?.status) === 4 && [1, 2].includes(Number(order?.refundStatus || 1));
    };

    const canForceRefund = (order) => {
        const status = Number(order?.status);

        // 1 未支付：不能退款，因为还没付款
        // 2 已取消：不能退款
        // 7 已退票：不能重复退款
        return order && ![1, 2, 7].includes(status);
    };

    const handleForceRefund = async () => {
        if (!currentOrder) return;

        Modal.confirm({
            title: '确认强制退款？',
            content: '强制退款会绕过用户申请流程，直接执行退款操作。请确认该订单确实需要退款。',
            okText: '确认退款',
            cancelText: '取消',
            okButtonProps: {
                danger: true
            },
            async onOk() {
                try {
                    const res = await axios.post('/api/admin/order/refund/force', {
                        orderId: currentOrder.id
                    });

                    if (res.data.code === 200) {
                        message.success('强制退款成功');

                        const detailRes = await axios.get(`/api/admin/order/detail/${currentOrder.id}`);
                        if (detailRes.data.code === 200) {
                            setCurrentOrder(detailRes.data.data);
                        }

                        fetchOrders(pagination.current, pagination.pageSize);
                    } else {
                        message.error(res.data.message || '强制退款失败');
                    }
                } catch (error) {
                    message.error(error.response?.data?.message || '网络异常，强制退款失败');
                }
            }
        });
    };

    const submitRefundAudit = async (mode) => {
        if (!refundOrder) return;

        if (mode === 'reject' && !refundReason.trim()) {
            message.warning('拒绝退票时必须填写原因');
            return;
        }

        setRefundSubmitting(true);

        try {
            const res = await axios.post('/api/admin/order/refund/audit', {
                orderId: refundOrder.id,
                approve: mode === 'approve',
                rejectReason: refundReason.trim()
            });

            if (res.data.code === 200) {
                message.success(mode === 'approve' ? '已同意退票' : '已拒绝退票');

                const detailRes = await axios.get(`/api/admin/order/detail/${refundOrder.id}`);
                if (detailRes.data.code === 200) {
                    setRefundOrder(detailRes.data.data);
                }

                fetchOrders(pagination.current, pagination.pageSize);
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (error) {
            message.error(error.response?.data?.message || '网络异常，操作失败');
        } finally {
            setRefundSubmitting(false);
        }
    };

    const columns = useMemo(() => [
        {
            title: '订单ID',
            dataIndex: 'id',
            width: 188,
            render: (value) => (
                <Typography.Text copyable={{ text: String(value) }} className="aom-order-id">
                    {value}
                </Typography.Text>
            )
        },
        {
            title: '状态',
            dataIndex: 'status',
            width: '9%',
            render: (status, record) => (
                <Tag color={getStatusColor(status)}>{getStatusText(record)}</Tag>
            )
        },
        {
            title: '用户',
            width: '12%',
            render: (_, record) => (
                <div className="aom-user-cell">
                    <div className="aom-main-text">{readRecord(record, 'username', 'user.username')}</div>
                    <div className="aom-sub-text">ID：{readRecord(record, 'userId', 'user.id')}</div>
                    <div className="aom-sub-text">{maskPhone(readRecord(record, 'phone', 'user.phone', ''))}</div>
                </div>
            )
        },
        {
            title: '演出',
            width: '19%',
            render: (_, record) => (
                <div className="aom-event-cell">
                    <div className="aom-main-text" title={readRecord(record, 'eventTitle', 'event.title')}>
                        {readRecord(record, 'eventTitle', 'event.title')}
                    </div>
                    <div className="aom-sub-text">演出ID：{readRecord(record, 'eventId', 'event.id')}</div>
                    <div className="aom-sub-text">
                        {readRecord(record, 'city', 'event.city')} | {readRecord(record, 'venue', 'event.venue')}
                    </div>
                </div>
            )
        },
        {
            title: '场次/票档',
            width: '16%',
            render: (_, record) => (
                <div>
                    <div className="aom-main-text">{readRecord(record, 'ticketCategoryName', 'ticketCategory.name')}</div>
                    <div className="aom-sub-text">{readRecord(record, 'sessionName', 'event.sessionName', '默认场次')}</div>
                    <div className="aom-sub-text">{readRecord(record, 'showTime', 'event.showTime')}</div>
                </div>
            )
        },
        {
            title: '金额',
            width: '8%',
            align: 'right',
            render: (_, record) => (
                <div>
                    <div className="aom-price">¥{formatMoney(record.totalAmount)}</div>
                </div>
            )
        },
        {
            title: '创建时间',
            dataIndex: 'createTime',
            width: '14%'
        },
        {
            title: '操作',
            width: 230,
            render: (_, record) => (
                <Space>
                    <Button size="small" icon={<EyeOutlined />} onClick={() => openDetail(record)}>
                        详情
                    </Button>

                    {(Number(record.status) === 4 || record.refundStatus) && (
                        <Button
                            size="small"
                            onClick={() => openRefund(record)}
                        >
                            退款详情
                        </Button>
                    )}
                </Space>
            )
        }
    ], []);

    const renderRefundModalContent = () => {
        if (!refundOrder) {
            return <div className="aom-detail-loading">加载中...</div>;
        }

        const failText = getRefundFailText(refundOrder);

        const stepItems = [
            {
                title: '申请退款',
                description: refundOrder.refundApplyTime || '已提交'
            },
            {
                title: '后台审核',
                description: refundOrder.refundAuditTime || '等待审核'
            },
            {
                title: '金额退还',
                description: refundOrder.refundReturnTime || '等待退款'
            },
            {
                title: '退款完成',
                description: refundOrder.refundFinishTime || '等待完成'
            }
        ];

        return (
            <div className="aom-refund-flow">
                <Descriptions title="退款订单信息" bordered size="small" column={2}>
                    <Descriptions.Item label="订单ID">{refundOrder.id}</Descriptions.Item>
                    <Descriptions.Item label="订单状态">
                        <Tag color={getStatusColor(refundOrder.status)}>
                            {getStatusText(refundOrder)}
                        </Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="用户ID">{readRecord(refundOrder, 'userId', 'user.id')}</Descriptions.Item>
                    <Descriptions.Item label="用户名">{readRecord(refundOrder, 'username', 'user.username')}</Descriptions.Item>
                    <Descriptions.Item label="演出">{readRecord(refundOrder, 'eventTitle', 'event.title')}</Descriptions.Item>
                    <Descriptions.Item label="实付金额">¥{formatMoney(refundOrder.totalAmount)}</Descriptions.Item>
                    <Descriptions.Item label="退款原因" span={2}>
                        {refundOrder.refundReason || '-'}
                    </Descriptions.Item>
                </Descriptions>

                <div className="aom-refund-steps">
                    <Steps
                        current={getRefundStepCurrent(refundOrder)}
                        status={getRefundStepStatus(refundOrder)}
                        items={stepItems}
                    />
                </div>

                {failText && (
                    <div className="aom-refund-error-box">
                        {failText}
                    </div>
                )}

                {canAuditRefund(refundOrder) && (
                    <div className="aom-refund-audit-panel">
                        <div className="aom-refund-audit-title">审核操作</div>

                        {refundMode === 'reject' && (
                            <>
                                <div style={{ marginBottom: 8 }}>拒绝退票原因：</div>
                                <TextArea
                                    rows={4}
                                    maxLength={500}
                                    showCount
                                    value={refundReason}
                                    onChange={(e) => setRefundReason(e.target.value)}
                                    placeholder="请输入拒绝退票原因"
                                />
                            </>
                        )}

                        <div className="aom-refund-audit-actions">
                            {refundMode === 'reject' ? (
                                <>
                                    <Button
                                        onClick={() => {
                                            setRefundMode('view');
                                            setRefundReason('');
                                        }}
                                        disabled={refundSubmitting}
                                    >
                                        返回
                                    </Button>

                                    <Button
                                        danger
                                        onClick={() => submitRefundAudit('reject')}
                                        loading={refundSubmitting}
                                    >
                                        确认拒绝
                                    </Button>
                                </>
                            ) : (
                                <>
                                    <Button
                                        danger
                                        onClick={() => {
                                            setRefundMode('reject');
                                            setRefundReason('');
                                        }}
                                        disabled={refundSubmitting}
                                    >
                                        拒绝退票
                                    </Button>

                                    <Button
                                        type="primary"
                                        onClick={() => submitRefundAudit('approve')}
                                        loading={refundSubmitting}
                                        style={{ backgroundColor: '#52c41a', border: 'none' }}
                                    >
                                        同意退票
                                    </Button>
                                </>
                            )}
                        </div>
                    </div>
                )}
            </div>
        );
    };

    const renderDetail = () => {
        if (!currentOrder) {
            return detailLoading ? <div className="aom-detail-loading">加载中...</div> : <Empty />;
        }

        const order = currentOrder;
        const spectators = order.spectators || [];
        const tickets = order.tickets || [];

        return (
            <div className="aom-detail">
                <Descriptions title="订单信息" bordered size="small" column={2}>
                    <Descriptions.Item label="订单ID">{order.id}</Descriptions.Item>
                    <Descriptions.Item label="订单状态">
                        <Tag color={getStatusColor(order.status)}>{getStatusText(order)}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="实付金额">¥{formatMoney(order.totalAmount)}</Descriptions.Item>
                    <Descriptions.Item label="数量">{order.quantity || 0}</Descriptions.Item>
                    <Descriptions.Item label="创建时间">{order.createTime || '-'}</Descriptions.Item>
                    <Descriptions.Item label="过期时间">{order.expireTime || '-'}</Descriptions.Item>
                </Descriptions>

                {canForceRefund(order) && (
                    <div className="aom-detail-force-refund">
                        <Button
                            danger
                            onClick={handleForceRefund}
                        >
                            强制退款
                        </Button>
                        <span className="aom-force-refund-tip">
            注意：请谨慎操作！
        </span>
                    </div>
                )}

                <Descriptions title="用户信息" bordered size="small" column={2} style={{ marginTop: 18 }}>
                    <Descriptions.Item label="用户ID">{readRecord(order, 'userId', 'user.id')}</Descriptions.Item>
                    <Descriptions.Item label="用户名">{readRecord(order, 'username', 'user.username')}</Descriptions.Item>
                    <Descriptions.Item label="手机号">{maskPhone(readRecord(order, 'phone', 'user.phone', ''))}</Descriptions.Item>
                    <Descriptions.Item label="实名">{readRecord(order, 'realName', 'user.realName')}</Descriptions.Item>
                </Descriptions>

                <Descriptions title="演出信息" bordered size="small" column={2} style={{ marginTop: 18 }}>
                    <Descriptions.Item label="演出ID">{readRecord(order, 'eventId', 'event.id')}</Descriptions.Item>
                    <Descriptions.Item label="演出标题">{readRecord(order, 'eventTitle', 'event.title')}</Descriptions.Item>
                    <Descriptions.Item label="城市/场馆">
                        {readRecord(order, 'city', 'event.city')} | {readRecord(order, 'venue', 'event.venue')}
                    </Descriptions.Item>
                    <Descriptions.Item label="允许退票">
                        {Number(readRecord(order, 'allowRefund', 'event.allowRefund', 0)) === 1 ? (
                            <Tag color="success">允许</Tag>
                        ) : (
                            <Tag>不允许</Tag>
                        )}
                    </Descriptions.Item>
                    <Descriptions.Item label="场次">
                        {readRecord(order, 'sessionName', 'event.sessionName', '默认场次')}
                    </Descriptions.Item>
                    <Descriptions.Item label="演出时间">
                        {readRecord(order, 'showTime', 'event.showTime')}
                    </Descriptions.Item>
                    <Descriptions.Item label="票档">
                        {readRecord(order, 'ticketCategoryName', 'ticketCategory.name')}
                    </Descriptions.Item>
                    <Descriptions.Item label="票价">
                        ¥{formatMoney(readRecord(order, 'ticketPrice', 'ticketCategory.price', 0))}
                    </Descriptions.Item>
                </Descriptions>


                <div className="aom-detail-section">
                    <div className="aom-detail-title">观演人</div>
                    <Table
                        size="small"
                        rowKey={(row) => row.id}
                        pagination={false}
                        dataSource={spectators}
                        columns={[
                            { title: '观演人ID', dataIndex: 'id' },
                            { title: '姓名', dataIndex: 'name' },
                            { title: '证件类型', dataIndex: 'idType' },
                            { title: '证件号', dataIndex: 'idCard', render: maskIdCard }
                        ]}
                    />
                </div>

                <div className="aom-detail-section">
                    <div className="aom-detail-title">电子票</div>
                    <Table
                        size="small"
                        rowKey={(row) => row.id}
                        pagination={false}
                        dataSource={tickets}
                        columns={[
                            { title: '票ID', dataIndex: 'id' },
                            { title: '票名', dataIndex: 'ticketName' },
                            { title: '观演人', dataIndex: 'spectatorName' },
                            {
                                title: '检票状态',
                                dataIndex: 'checkStatus',
                                render: (value) => {
                                    if (Number(value) === 1) return <Tag color="processing">未检票</Tag>;
                                    if (Number(value) === 2) return <Tag color="success">已检票</Tag>;
                                    if (Number(value) === 4) return <Tag>未出票</Tag>;
                                    return <Tag>未知</Tag>;
                                }
                            },
                            { title: '座位', dataIndex: 'seatInfo', render: (v) => v || '自由入座' }
                        ]}
                    />
                </div>
            </div>
        );
    };

    return (
        <Card
            title={<span style={{ fontSize: 16, fontWeight: 'bold', color: '#333' }}><div style={{ display: 'flex' }}>订单管理看板</div></span>}
            bordered={false}
            className="admin-order-manager-card"
            style={{ borderRadius: 12, boxShadow: '0 4px 12px rgba(0,0,0,0.03)' }}
        >
            <div className="aom-toolbar">
                <Form form={form} layout="inline" className="aom-filter-form">
                    <Form.Item label="订单状态" name="status">
                        <Select options={STATUS_OPTIONS} style={{ width: 160 }} />
                    </Form.Item>

                    <Form.Item label="搜索方式" name="searchType">
                        <Select options={SEARCH_OPTIONS} style={{ width: 140 }} />
                    </Form.Item>

                    <Form.Item name="keyword" className="aom-keyword-item">
                        <Input
                            allowClear
                            prefix={<SearchOutlined />}
                            placeholder="请输入搜索内容"
                            onPressEnter={handleSearch}
                        />
                    </Form.Item>

                    <Form.Item className="aom-action-item">
                        <Space>
                            <Button type="primary" onClick={handleSearch}>
                                搜索
                            </Button>
                            <Button onClick={handleReset}>重置</Button>
                        </Space>
                    </Form.Item>
                </Form>

            </div>

            <Table
                rowKey="id"
                loading={loading}
                columns={columns}
                dataSource={orders}
                tableLayout="fixed"
                className="aom-table"
                pagination={{
                    current: pagination.current,
                    pageSize: pagination.pageSize,
                    total: pagination.total,
                    showSizeChanger: true,
                    showTotal: (total) => `共 ${total} 条订单`,
                    onChange: (page, pageSize) => fetchOrders(page, pageSize)
                }}
            />

            <Modal
                title="订单详情"
                open={detailVisible}
                onCancel={() => setDetailVisible(false)}
                footer={null}
                width={920}
                className="admin-order-detail-modal"
            >
                {renderDetail()}
            </Modal>

            <Modal
                title="退款处理"
                open={refundVisible}
                onCancel={() => {
                    setRefundVisible(false);
                    setRefundOrder(null);
                    setRefundReason('');
                }}
                footer={null}
                width={860}
                className="admin-order-refund-modal"
            >
                {renderRefundModalContent()}
            </Modal>
        </Card>
    );
};

export default OrderManager;
