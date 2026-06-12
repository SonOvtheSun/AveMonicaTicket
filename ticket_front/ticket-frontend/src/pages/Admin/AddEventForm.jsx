import React, { useState, useEffect, useRef } from 'react';
import { Form, Input, DatePicker, Button, Upload, Space, InputNumber, message, Divider, Select, Row, Col, Modal, Avatar, Spin, Cascader, Tag } from 'antd';
import { PlusOutlined, MinusCircleOutlined, UploadOutlined } from '@ant-design/icons';
import axios from 'axios';
import dayjs from 'dayjs';
import './AddEventForm.css';
import pcasData from '../../assets/pcas.json';
import ImgCrop from 'antd-img-crop';

const cityOptions = Object.keys(pcasData).map(province => {
    const cityKeys = Object.keys(pcasData[province]);
    const validCities = cityKeys.map(cityKey => {
        if (cityKey === '市辖区' || cityKey === '县' || cityKey.includes('直辖')) return province;
        return cityKey;
    });
    const uniqueCities = [...new Set(validCities)];
    return {
        value: province,
        label: province,
        children: uniqueCities.map(cityName => ({ value: cityName, label: cityName }))
    };
});

const AddEventForm = ({ onSuccess, editingRecord }) => {
    const [form] = Form.useForm();
    const [loading, setLoading] = useState(false);

    const [artistOptions, setArtistOptions] = useState([]);
    const [artistModalVisible, setArtistModalVisible] = useState(false);
    const [artistForm] = Form.useForm();
    const [submittingArtist, setSubmittingArtist] = useState(false);
    const [fetchingArtists, setFetchingArtists] = useState(false);
    const [artistPage, setArtistPage] = useState(1);
    const [artistKeyword, setArtistKeyword] = useState('');
    const [hasMoreArtists, setHasMoreArtists] = useState(false);

    // 风格预设选项
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
        '核',
        '其他'
    ].map(style => ({ value: style, label: style }));

    const styleOptions = MUSIC_STYLE_OPTIONS;
    const eventStyleOptions = MUSIC_STYLE_OPTIONS;

    const searchTimeoutRef = useRef(null);

    const [posterFileList, setPosterFileList] = useState([]);
    const [detailsFileList, setDetailsFileList] = useState([]);
    const [avatarFileList, setAvatarFileList] = useState([]);

    const fetchRemoteArtists = async (keyword, page = 1, append = false) => {
        if (!keyword) {
            if (!append) {
                setArtistOptions([]);
                setHasMoreArtists(false);
            }
            return;
        }

        setFetchingArtists(true);
        try {
            const res = await axios.get('/api/admin/artist/listAll', {
                params: { current: page, size: 10, keyword }
            });

            if (res.data.code === 200) {
                const { records, total } = res.data.data;
                const newOptions = records.map(artist => ({
                    label: artist.auditStatus === 0 ? `${artist.name} (待审核)` : artist.name,
                    value: artist.id,
                    avatarUrl: artist.avatarUrl
                }));

                setArtistOptions(prev => {
                    const selectedIds = form.getFieldValue('artistIds') || [];
                    const preservedOptions = prev.filter(p => selectedIds.includes(p.value));
                    const merged = append ? [...prev] : [...preservedOptions];
                    newOptions.forEach(opt => {
                        if (!merged.find(m => m.value === opt.value)) merged.push(opt);
                    });
                    return merged;
                });

                setHasMoreArtists(page * 10 < total);
                setArtistPage(page);
            }
        } catch (err) {
            message.error('搜索艺人失败');
        } finally {
            setFetchingArtists(false);
        }
    };

    const handleSearchArtist = (value) => {
        setArtistKeyword(value);
        if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
        searchTimeoutRef.current = setTimeout(() => {
            fetchRemoteArtists(value, 1, false);
        }, 500);
    };

    const loadMoreArtists = (e) => {
        e.preventDefault();
        fetchRemoteArtists(artistKeyword, artistPage + 1, true);
    };

    const customUpload = async (options, uploadType) => {
        const { file, onSuccess, onError, onProgress } = options;
        const formData = new FormData();
        formData.append('file', file, file.name || `cropped-${uploadType}.jpg`);
        formData.append('type', uploadType);

        try {
            const res = await axios.post('/api/common/upload', formData, {
                headers: {
                    'Content-Type': 'multipart/form-data',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                onUploadProgress: (progressEvent) => {
                    const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
                    onProgress({ percent });
                }
            });

            if (res.data.code === 200) {
                onSuccess(res.data);
            } else {
                onError(new Error(res.data.message));
            }
        } catch (err) {
            onError(err);
        }
    };

    const fetchArtists = async () => {
        try {
            const res = await axios.get('/api/admin/artist/listAll');
            if (res.data.code === 200 && res.data.data == null) {
                const options = res.data.data.map(artist => {
                    const labelName = artist.auditStatus === 0 ? `${artist.name} (待审核)` : artist.name;
                    return { label: labelName, value: artist.id };
                });
                setArtistOptions(options);
            }
        } catch (err) {
            message.error('获取艺人库失败，请检查网络');
        }
    };

    useEffect(() => {
        fetchArtists();
    }, []);

    useEffect(() => {
        if (editingRecord) {
            const parsedShowTime = editingRecord.showTime ? dayjs(editingRecord.showTime) : null;
            const parsedSaleTime = editingRecord.saleTime ? dayjs(editingRecord.saleTime) : null;

            const initPoster = editingRecord.posterUrl ? [{ uid: '-1', name: 'poster.png', status: 'done', url: editingRecord.posterUrl }] : [];
            const initDetails = editingRecord.detailsUrl ? [{ uid: '-2', name: 'details.png', status: 'done', url: editingRecord.detailsUrl }] : [];

            setPosterFileList(initPoster);
            setDetailsFileList(initDetails);

            let initialCityCascade = [];
            if (editingRecord.city) {
                const targetCity = editingRecord.city;
                for (const province in pcasData) {
                    const cities = Object.keys(pcasData[province]);
                    if (cities.includes(targetCity) || (targetCity === province && (cities.includes('市辖区') || cities.includes('县')))) {
                        initialCityCascade = [province, targetCity];
                        break;
                    }
                }
            }

            const mappedTickets = editingRecord.tickets && editingRecord.tickets.length > 0
                ? editingRecord.tickets.map(t => ({
                    name: t.name,
                    price: t.price,
                    stock: t.totalStock ?? t.stock ?? t.remainingStock
                }))
                : [];

            const artistIds = editingRecord.artists ? editingRecord.artists.map(a => a.id).filter(id => id != null) : [];
            if (editingRecord.artists) {
                const initArtistOpts = editingRecord.artists.map(a => ({
                    label: a.auditStatus === 0 ? `${a.name} (待审核)` : a.name,
                    value: a.id,
                    avatarUrl: a.avatarUrl
                }));
                setArtistOptions(initArtistOpts);
            }

            form.setFieldsValue({
                ...editingRecord,
                cityCascade: initialCityCascade,
                showTime: parsedShowTime,
                saleTime: parsedSaleTime,
                tickets: mappedTickets,
                artistIds: artistIds,
                style: editingRecord.style ? [editingRecord.style] : [],
            });

        } else {
            form.resetFields();
            form.setFieldsValue({
                status: 1,
                style: [],
                tickets: []
            });
            setPosterFileList([]);
            setDetailsFileList([]);
        }
    }, [editingRecord, form]);

    const normFile = (e) => {
        if (Array.isArray(e)) return e;
        return e?.fileList;
    };

    const handleUploadChange = (info, label) => {
        if (info.file.status === 'done') {
            if (info.file.response && info.file.response.code == 200) {
                message.success(`${label} 上传成功！`);
            } else {
                message.error(`${label} 上传失败: ${info.file.response?.message || '未知错误'}`);
            }
        } else if (info.file.status === 'error') {
            message.error(`${label} 上传失败，请检查网络！`);
        }
    };

    const parseUploadedUrl = (fileList) => {
        if (!fileList || fileList.length === 0) return '';
        const file = fileList[0];
        if (file.response && file.response.code === 200) return file.response.data;
        if (file.response && typeof file.response === 'string') return file.response;
        if (file.url) return file.url;
        if (file.status === 'done' && file.xhr?.responseText) {
            try {
                const resObj = JSON.parse(file.xhr.responseText);
                return resObj.data || resObj;
            } catch (e) {
                return '';
            }
        }
        return '';
    };

    const handleAddArtist = async (values) => {
        setSubmittingArtist(true);
        try {
            const avatarUrl = parseUploadedUrl(avatarFileList);
            const regionStr = (values.region && values.region.length > 0) ? values.region[0] : '';
            const styleStr = Array.isArray(values.style)
                ? values.style.join('/')
                : (values.style || '');
            const res = await axios.post('/api/admin/artist/add', {
                name: values.name,
                description: values.description,
                avatarUrl: avatarUrl,
                region: regionStr,
                style: styleStr
            });
            if (res.data.code === 200) {
                message.success('艺人提交成功！');
                setArtistModalVisible(false);
                artistForm.resetFields();
                fetchArtists();
            } else {
                message.error(res.data.message || '艺人提交失败');
            }
        } catch (error) {
            message.error('提交异常');
        } finally {
            setSubmittingArtist(false);
        }
    };

    const handleArtistStyleTagClick = (style) => {
        const currentValue = artistForm.getFieldValue('style');
        const currentStyles = Array.isArray(currentValue)
            ? currentValue
            : currentValue
                ? [currentValue]
                : [];

        if (currentStyles.includes(style)) {
            artistForm.setFieldsValue({
                style: currentStyles.filter(item => item !== style)
            });
            return;
        }

        if (currentStyles.length >= 2) {
            message.warning('音乐风格最多选择两项');
            return;
        }

        artistForm.setFieldsValue({
            style: [...currentStyles, style]
        });
    };


    const onFinish = async (values) => {
        if (posterFileList.length > 0 && posterFileList[0].status === 'uploading') {
            return message.warning('主海报正在后台传输，请等待提示“上传成功”！');
        }
        if (detailsFileList.length > 0 && detailsFileList[0].status === 'uploading') {
            return message.warning('详情图正在后台传输，请稍等片刻！');
        }

        const finalPosterUrl = parseUploadedUrl(posterFileList);
        const finalDetailsUrl = parseUploadedUrl(detailsFileList);

        if (!finalPosterUrl) {
            message.warning('请确保有演出主海报物料');
            return;
        }

        let finalCity = '';
        if (values.cityCascade && values.cityCascade.length > 0) {
            finalCity = values.cityCascade[values.cityCascade.length - 1];
        }
        const styleStr = Array.isArray(values.style) && values.style.length > 0 ? values.style[0] : '';

        const finalTickets = (values.tickets || [])
            .filter(t => t && (t.name || t.price != null || t.stock != null))
            .map(t => ({
                name: t.name,
                price: t.price,
                stock: t.stock
            }));

        setLoading(true);
        try {
            const payload = {
                title: values.title,
                showTime: values.showTime && values.showTime.format ? values.showTime.format('YYYY-MM-DD HH:mm:ss') : values.showTime,
                saleTime: values.saleTime && values.saleTime.format ? values.saleTime.format('YYYY-MM-DD HH:mm:ss') : values.saleTime,
                city: finalCity,
                venue: values.venue,
                address: values.address,
                status: values.status,
                artistIds: values.artistIds,
                tickets: finalTickets,
                posterUrl: finalPosterUrl,
                style: styleStr,
                runningTime: values.runningTime,
                detailsUrl: finalDetailsUrl || 'https://via.placeholder.com/800x1200?text=Default+Details'
            };

            let res;
            if (editingRecord) {
                res = await axios.put(`/api/admin/event/${editingRecord.id}`, payload);
            } else {
                res = await axios.post('/api/admin/event/add', payload);
            }

            if (res.data.code === 200) {
                message.success(editingRecord ? '演出信息修改成功！' : '全新演出项目发布成功！');
                form.resetFields();
                onSuccess();
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (error) {
            message.error('网络传输或服务器异常');
        } finally {
            setLoading(false);
        }
    };

    return (
        <Form form={form} layout="vertical" onFinish={onFinish}>
            <Divider orientation="left" style={{ borderColor: '#FF8899', color: '#FF8899', marginTop: 0 }}>核心基础数据</Divider>

            <Form.Item name="title" label="演出标题" rules={[{ required: true, message: '请输入演出精炼标题' }]}>
                <Input placeholder="输入品牌专场或音乐节标题" size="large" />
            </Form.Item>

            <Row gutter={16}>
                <Col span={12}>
                    <Form.Item name="showTime" label="定档演出时间" rules={[{ required: true, message: '请选择确切的演出执行时间' }]}>
                        <DatePicker placeholder="选择时间" showTime format="YYYY-MM-DD HH:mm" size="large" style={{ width: '100%' }} />
                    </Form.Item>
                </Col>
                <Col span={8}>
                    {/* 🚨 新增：演出时长输入框 */}
                    <Form.Item name="runningTime" label="演出时长 (分钟)" rules={[{ required: true, message: '请输入演出时长' }]}>
                        <InputNumber min={1} placeholder="如：120" size="large" style={{ width: '100%' }} />
                    </Form.Item>
                </Col>
                <Col span={12}>
                    <Form.Item name="status" label="项目状态 (将在通过审核后生效)" rules={[{ required: true }]}>
                        <Select size="large" options={[
                            { label: '上架（含预售/在售）', value: 1 },
                            { label: '已停售', value: 3 },
                            { label: '隐藏', value: 4 },
                        ]} />
                    </Form.Item>
                </Col>
            </Row>

            <Form.Item
                name="saleTime"
                label="预开票时间"
                dependencies={['status', 'showTime']}
                rules={[
                    ({ getFieldValue }) => ({
                        validator(_, value) {
                            const currentStatus = Number(getFieldValue('status'));
                            const showTimeValue = getFieldValue('showTime');

                            if (currentStatus === 1 && !value) {
                                return Promise.reject(new Error('演出设置为“上架”状态时，必须设定开票时间！'));
                            }
                            if (value && showTimeValue) {
                                const saleDate = dayjs(value);
                                const showDate = dayjs(showTimeValue);
                                const limitTime = showDate.subtract(24, 'hour');
                                if (saleDate.isAfter(limitTime)) {
                                    return Promise.reject(new Error('开票时间必须早于演出时间至少 24 小时！'));
                                }
                            }
                            return Promise.resolve();
                        },
                    }),
                ]}
            >
                <DatePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} size="large" placeholder="上架状态必填，且早于演出前24小时" />
            </Form.Item>

            {/* 🚨 演出风格平铺标签区 */}
            <Form.Item label="演出风格" tooltip="点击下方快捷标签一键填入，或在框内手动输入后敲回车添加">
                <Form.Item name="style" noStyle>
                    <Select mode="tags" maxCount={1} placeholder="请选择或输入演出风格" size="large" allowClear />
                </Form.Item>
                <div style={{ marginTop: 12, display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                    {eventStyleOptions.map(opt => (
                        <Tag
                            key={opt.value}
                            color="#fff0f3"
                            style={{ color: '#FF8899', border: '1px solid #FF8899', cursor: 'pointer', padding: '4px 14px', fontSize: 13, margin: 0, borderRadius: 16 }}
                            onClick={() => form.setFieldsValue({ style: [opt.value] })}
                        >
                            {opt.label}
                        </Tag>
                    ))}
                </div>
            </Form.Item>

            <Form.Item name="cityCascade" label="所在城市" rules={[{ required: true, message: '请选择演出所在城市' }]}>
                <Cascader options={cityOptions} placeholder="请选择省份与城市" size="large" expandTrigger="hover" />
            </Form.Item>

            <Row gutter={16}>
                <Col span={10}>
                    <Form.Item name="venue" label="演艺场馆" rules={[{ required: true, message: '请输入场馆名称' }]}>
                        <Input placeholder="输入演出场馆" size="large" />
                    </Form.Item>
                </Col>
                <Col span={14}>
                    <Form.Item name="address" label="场馆详细地址" rules={[{ required: true, message: '请输入详细的街道地址' }]}>
                        <Input placeholder="请输入详细地址" size="large" />
                    </Form.Item>
                </Col>
            </Row>

            <Form.Item label="参演音乐人 / 乐队（多选）" required>
                <Form.Item name="artistIds" noStyle rules={[{ required: true, message: '请选择至少一组参演艺人' }]}>
                    <Select
                        mode="multiple"
                        allowClear
                        showSearch
                        filterOption={false}
                        onSearch={handleSearchArtist}
                        placeholder="输入音乐人姓名"
                        size="large"
                        options={artistOptions}
                        notFoundContent={fetchingArtists ? <Spin size="small" /> : "请在框内打字以搜索艺人库..."}
                        dropdownRender={(menu) => (
                            <>
                                {menu}
                                {hasMoreArtists && (
                                    <div style={{ textAlign: 'center', padding: '10px 0', borderTop: '1px solid #f0f0f0' }}>
                                        <Button type="link" size="small" onClick={loadMoreArtists} loading={fetchingArtists}>
                                            向下加载更多
                                        </Button>
                                    </div>
                                )}
                            </>
                        )}
                        optionRender={(option) => (
                            <Space align="center">
                                <Avatar src={option.data.avatarUrl || 'https://via.placeholder.com/24'} size="small" style={{ border: '1px solid #eee' }} />
                                <span>{option.data.label}</span>
                            </Space>
                        )}
                    />
                </Form.Item>

                <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '12px', color: '#888' }}>如未找到艺人，请先新增并提交审核</span>
                    <Button
                        type="link"
                        size="small"
                        onClick={() => {
                            artistForm.resetFields();
                            setAvatarFileList([]);
                            setArtistModalVisible(true);
                        }}
                        style={{ padding: 0, color: '#FF8899', fontWeight: 500 }}
                    >
                        + 新增音乐人
                    </Button>
                </div>
            </Form.Item>

            <Divider orientation="left" style={{ borderColor: '#FF8899', color: '#FF8899' }}>媒体视觉物料</Divider>

            <Row gutter={16}>
                <Col span={12}>
                    <Form.Item label="演出主海报" required>
                        <ImgCrop rotationSlider aspect={1 / 1.414} modalTitle="裁剪演出海报" modalOk="确认裁剪" modalCancel="取消">
                            <Upload
                                customRequest={(options) => customUpload(options, 'poster')}
                                listType="picture"
                                maxCount={1}
                                fileList={posterFileList}
                                onChange={({ fileList }) => {
                                    setPosterFileList(fileList);
                                    if(fileList.length > 0 && fileList[0].status === 'done') message.success('主海报上传成功');
                                }}
                            >
                                <Button icon={<UploadOutlined />} size="large" style={{ width: '100%', borderRadius: 8 }}>上传高清主海报</Button>
                            </Upload>
                        </ImgCrop>
                    </Form.Item>
                </Col>
                <Col span={12}>
                    <Form.Item label="详情长图">
                        <Upload
                            customRequest={(options) => customUpload(options, 'poster')}
                            listType="picture"
                            maxCount={1}
                            fileList={detailsFileList}
                            onChange={({ fileList }) => setDetailsFileList(fileList)}
                        >
                            <Button icon={<UploadOutlined />} size="large" style={{ width: '100%', borderRadius: 8 }}>上传详情长图</Button>
                        </Upload>
                    </Form.Item>
                </Col>
            </Row>

            <Divider orientation="left" style={{ borderColor: '#FF8899', color: '#FF8899' }}>票务档位策略配置</Divider>

            <Form.List name="tickets">
                {(fields, { add, remove }) => (
                    <>
                        {fields.length === 0 && (
                            <div style={{
                                marginBottom: 12,
                                padding: '10px 12px',
                                borderRadius: 8,
                                background: '#fff7e6',
                                border: '1px solid #ffe7ba',
                                color: '#8c6d1f',
                                fontSize: 13
                            }}>
                                当前未设置票档
                            </div>
                        )}

                        {fields.map(({ key, name, ...restField }) => (
                            <Space key={key} style={{ display: 'flex', marginBottom: 12 }} align="baseline">
                                <Form.Item {...restField} name={[name, 'name']} rules={[{ required: true, message: '请规范输入票档标识' }]}>
                                    <Input placeholder="档位名称（如：VIP票）" size="large" style={{ width: '220px' }} />
                                </Form.Item>

                                <Form.Item {...restField} name={[name, 'price']} rules={[{ required: true, message: '请输入定价' }]}>
                                    <InputNumber placeholder="票价 (¥)" min={0} precision={2} size="large" style={{ width: '130px' }} />
                                </Form.Item>

                                <Form.Item {...restField} name={[name, 'stock']} rules={[{ required: true, message: '请输入初始库存' }]}>
                                    <InputNumber placeholder="总发行库存 (张)" min={1} precision={0} size="large" style={{ width: '140px' }} />
                                </Form.Item>

                                <MinusCircleOutlined
                                    onClick={() => remove(name)}
                                    title="删除该票档"
                                    style={{ color: '#ff4d4f', fontSize: '18px', marginLeft: '12px', cursor: 'pointer' }}
                                />
                            </Space>
                        ))}
                        <Form.Item style={{ marginTop: 8 }}>
                            <Button type="dashed" onClick={() => add({ name: '', price: null, stock: null })} block icon={<PlusOutlined />} size="large" style={{ borderColor: '#FF8899', color: '#FF8899', borderRadius: 8 }}>
                                {fields.length === 0 ? '新增票档' : '追加一档票价'}
                            </Button>
                        </Form.Item>
                    </>
                )}
            </Form.List>

            <div style={{ marginTop: 40, textAlign: 'right' }}>
                <Button
                    size="large"
                    onClick={() => {
                        form.resetFields();
                        form.setFieldsValue({ status: 1, style: [], tickets: [] });
                        setPosterFileList([]);
                        setDetailsFileList([]);
                    }}
                    style={{ marginRight: 16, borderRadius: 8 }}
                >重置清空</Button>
                <Button type="primary" htmlType="submit" size="large" loading={loading} style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', width: 160, borderRadius: 8, fontWeight: 'bold' }}>
                    {editingRecord ? '确 认 修 改' : '确 认 发 布'}
                </Button>
            </div>

            {/* 新增音乐人的弹窗 */}
            <Modal
                title={<span style={{ color: '#FF8899' }}>提交音乐人入驻审核</span>}
                open={artistModalVisible}
                onCancel={() => setArtistModalVisible(false)}
                footer={null}
                destroyOnClose
            >
                <Form form={artistForm} layout="vertical" onFinish={handleAddArtist} style={{ marginTop: 20 }}>
                    <Form.Item name="name" label="艺人/乐队名称" rules={[{ required: true, message: '名字不能为空' }]}>
                        <Input placeholder="输入官方常用名称" size="large" />
                    </Form.Item>

                    <Form.Item name="avatar" label="官方头像" valuePropName="fileList" getValueFromEvent={normFile}>
                        <Upload
                            name="file"
                            action="/api/common/upload"
                            data={{ type: 'avatar' }}
                            listType="picture"
                            maxCount={1}
                            headers={{ Authorization: `Bearer ${localStorage.getItem('token')}` }}
                            fileList={avatarFileList}
                            onChange={(info) => {
                                setAvatarFileList(info.fileList);
                                handleUploadChange(info, '艺人头像');
                            }}
                        >
                            <Button icon={<UploadOutlined />}>上传专属头像</Button>
                        </Upload>
                    </Form.Item>

                    <Form.Item name="region" label="国家或地区" rules={[{ required: true, message: '请选择或输入国家/地区' }]}>
                        <Select
                            mode="tags"
                            maxCount={1}
                            placeholder="请选择或输入所属国家/地区"
                            size="large"
                            options={[
                                { value: '中国大陆', label: '中国大陆' }, { value: '中国港澳台', label: '中国港澳台' },
                                { value: '日本', label: '日本' }, { value: '韩国', label: '韩国' },
                                { value: '欧美', label: '欧美' }, { value: '其他海外地区', label: '其他海外地区' },
                            ]}
                        />
                    </Form.Item>

                    {/* 🚨 音乐人风格平铺标签区：固定选项，多选，最多两项 */}
                    <Form.Item label="音乐风格" tooltip="从固定风格中选择，最多选择两项；提交后会用“/”合并">
                        <Form.Item name="style" noStyle>
                            <Select
                                mode="multiple"
                                style={{ width: '100%' }}
                                placeholder="请选择音乐风格，最多两项"
                                size="large"
                                maxCount={2}
                                allowClear
                                options={styleOptions}
                            />
                        </Form.Item>
                        <div style={{ marginTop: 12, display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                            {styleOptions.map(opt => (
                                <Tag
                                    key={opt.value}
                                    color="#fff0f3"
                                    style={{ color: '#FF8899', border: '1px solid #FF8899', cursor: 'pointer', padding: '4px 14px', fontSize: 13, margin: 0, borderRadius: 16 }}
                                    onClick={() => handleArtistStyleTagClick(opt.value)}
                                >
                                    {opt.label}
                                </Tag>
                            ))}
                        </div>
                    </Form.Item>

                    <Form.Item name="description" label="乐队/艺人简介">
                        <Input.TextArea placeholder="一句话介绍一下这组音乐人吧..." rows={4} />
                    </Form.Item>

                    <div style={{ textAlign: 'right', marginTop: 30 }}>
                        <Button type="primary" htmlType="submit" loading={submittingArtist} style={{ backgroundColor: '#FF8899', borderColor: '#FF8899', borderRadius: 6, fontWeight: 500 }}>
                            提交审核
                        </Button>
                    </div>
                </Form>
            </Modal>
        </Form>
    );
};

export default AddEventForm;