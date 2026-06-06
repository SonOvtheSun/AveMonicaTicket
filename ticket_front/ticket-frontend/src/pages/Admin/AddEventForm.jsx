import React, { useState, useEffect, useRef } from 'react';
import { Form, Input, DatePicker, Button, Upload, Space, InputNumber, message, Divider, Select, Row, Col, Modal, Avatar, Spin, Cascader } from 'antd';
import { PlusOutlined, MinusCircleOutlined, UploadOutlined } from '@ant-design/icons';
import axios from 'axios';
import dayjs from 'dayjs'; // 🚨 引入 dayjs，用于解析后端传来的时间字符串以回显
import './AddEventForm.css';
import pcasData from '../../assets/pcas.json';
import ImgCrop from 'antd-img-crop';

// 🚨 2. 解析 JSON：只取前两级 (省 -> 市)，过滤掉区县，并处理直辖市
const cityOptions = Object.keys(pcasData).map(province => {
    const cityKeys = Object.keys(pcasData[province]);

    // 处理直辖市的情况：将"市辖区"或"县"替换为省/直辖市名称本身
    const validCities = cityKeys.map(cityKey => {
        if (cityKey === '市辖区' || cityKey === '县' || cityKey.includes('直辖')) {
            return province;
        }
        return cityKey;
    });

    // 去重，防止出现多个同名选项
    const uniqueCities = [...new Set(validCities)];

    return {
        value: province,
        label: province,
        children: uniqueCities.map(cityName => ({
            value: cityName,
            label: cityName
        }))
    };
});

// 🚨 接收父组件传来的 editingRecord
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

    // 用于防抖的定时器
    const searchTimeoutRef = useRef(null);

    // 🚨 1. 补全所有的本地状态池，彻底接管图片数据
    const [posterFileList, setPosterFileList] = useState([]);
    const [detailsFileList, setDetailsFileList] = useState([]);
    const [avatarFileList, setAvatarFileList] = useState([]);

    // 💡 核心魔法：远程分页搜索艺人
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
                    // 🚨 防丢失机制：保留表单中已经被选中的艺人，防止搜索其他词时导致已选标签变成纯 ID
                    const selectedIds = form.getFieldValue('artistIds') || [];
                    const preservedOptions = prev.filter(p => selectedIds.includes(p.value));

                    // 合并新老数据去重
                    const merged = append ? [...prev] : [...preservedOptions];
                    newOptions.forEach(opt => {
                        if (!merged.find(m => m.value === opt.value)) {
                            merged.push(opt);
                        }
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
        }, 500); // 停顿 0.5 秒后发请求
    };

    // 💡 点击加载更多
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



    // ==========================================
    // 💡 核心魔法：智能回显与重置
    // ==========================================
    useEffect(() => {
        if (editingRecord) {
            // 1. 处理时间回显 (将后端的字符串 'YYYY-MM-DD HH:mm:ss' 转为 Day.js 对象)
            const parsedTime = editingRecord.showTime ? dayjs(editingRecord.showTime) : null;

            const initPoster = editingRecord.posterUrl ? [{ uid: '-1', name: 'poster.png', status: 'done', url: editingRecord.posterUrl }] : [];
            const initDetails = editingRecord.detailsUrl ? [{ uid: '-2', name: 'details.png', status: 'done', url: editingRecord.detailsUrl }] : [];

            setPosterFileList(initPoster);
            setDetailsFileList(initDetails);

            let initialCityCascade = [];

            // 🚨 5. 逆向推导：根据城市名找到它所属的省份，用于前端回显
            if (editingRecord.city) {
                const targetCity = editingRecord.city;
                for (const province in pcasData) {
                    const cities = Object.keys(pcasData[province]);
                    // 如果普通城市在列表里，或者该城市是直辖市
                    if (cities.includes(targetCity) || (targetCity === province && (cities.includes('市辖区') || cities.includes('县')))) {
                        initialCityCascade = [province, targetCity];
                        break;
                    }
                }
            }

            form.setFieldsValue({
                ...editingRecord,
                cityCascade: initialCityCascade,

                // 🚨 新增：演出时间回显转换（你原本应该已经写了 showTime 的转换）
                showTime: editingRecord.showTime ? dayjs(editingRecord.showTime) : null,

                // 🚨 新增：开票时间回显转换
                saleTime: editingRecord.saleTime ? dayjs(editingRecord.saleTime) : null,
            });

            // 3. 处理票档回显 (后端 totalStock 映射回表单的 stock)
            const mappedTickets = editingRecord.tickets && editingRecord.tickets.length > 0
                ? editingRecord.tickets.map(t => ({
                    name: t.name,
                    price: t.price,
                    stock: t.totalStock // 编辑时，运营人员修改的是总库存量
                }))
                : [{ name: '', price: null, stock: null }];

            // 4. 处理艺人多选回显 (提取已绑定艺人的 ID 数组)
            const artistIds = editingRecord.artists ? editingRecord.artists.map(a => a.id).filter(id => id != null) : [];
            if (editingRecord.artists) {
                const initArtistOpts = editingRecord.artists.map(a => ({
                    label: a.auditStatus === 0 ? `${a.name} (待审核)` : a.name,
                    value: a.id,
                    avatarUrl: a.avatarUrl
                }));
                setArtistOptions(initArtistOpts);
            }

            // 5. 瞬间将所有处理好的数据注入表单
            form.setFieldsValue({
                ...editingRecord,
                cityCascade: initialCityCascade,
                showTime: parsedTime,
                tickets: mappedTickets,
                artistIds: artistIds
            });


        } else {
            // 如果没有 editingRecord，说明是【新建模式】，清空表单并填入默认值
            form.resetFields();
            form.setFieldsValue({
                status: 1,
                tickets: [{ name: '', price: null, stock: null }]
            });
            setPosterFileList([]);
            setDetailsFileList([]);
        }


    }, [editingRecord, form]); // 监听 editingRecord 的变化


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

        console.log("【解析器截获的真实状态】:", file.status, " 响应内容:", file.response);

        // 1. 如果是新上传成功的图片，且后端 Result 带有 code 结构
        if (file.response && file.response.code === 200) {
            return file.response.data;
        }

        // 2. 兼容部分特殊情况：后端直接返回了路径字符串本身
        if (file.response && typeof file.response === 'string') {
            return file.response;
        }

        // 3. 如果是编辑回显、或者组件状态残留时的 url
        if (file.url) {
            return file.url;
        }

        // 4. 保底金牌：如果状态已经是 done 但上面没截取到，尝试从 xhr 响应文本里硬解析
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
            const res = await axios.post('/api/admin/artist/add', {
                name: values.name,
                description: values.description,
                avatarUrl: avatarUrl,
                region: regionStr,
                style: values.style
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

    // ==========================================
    // 💡 智能分流提交逻辑 (POST vs PUT)
    // ==========================================
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
            // 取数组的最后一项（确保拿到的是市）
            finalCity = values.cityCascade[values.cityCascade.length - 1];
        }

        setLoading(true);
        try {
            const payload = {
                title: values.title,
                // 演出时间格式化
                showTime: values.showTime && values.showTime.format ? values.showTime.format('YYYY-MM-DD HH:mm:ss') : values.showTime,

                // 🚨 新增：开票时间格式化（带判空保护）
                saleTime: values.saleTime && values.saleTime.format ? values.saleTime.format('YYYY-MM-DD HH:mm:ss') : values.saleTime,

                city: finalCity,
                venue: values.venue,
                address: values.address,
                status: values.status,
                artistIds: values.artistIds,
                tickets: values.tickets,
                posterUrl: finalPosterUrl,
                detailsUrl: finalDetailsUrl || 'https://via.placeholder.com/800x1200?text=Default+Details'
            };

            let res;
            if (editingRecord) {
                // 编辑模式：调用刚才后端的 PUT 接口
                res = await axios.put(`/api/admin/event/${editingRecord.id}`, payload);
            } else {
                // 新增模式：调用 POST 接口
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
        <Form
            form={form}
            layout="vertical"
            onFinish={onFinish}
            // initialValues 被移除了，因为我们全权交给了 useEffect 里的 setFieldsValue 动态控制
        >
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
                // 🚨 移除之前强制必填的 validator 逻辑，现在完全变成可选填
            >
                <DatePicker
                    showTime
                    format="YYYY-MM-DD HH:mm:ss"
                    style={{ width: '100%' }}
                    size="large"
                    placeholder="选填：若填写则发布后进入预售倒计时；若留空则立即开售"
                />
            </Form.Item>

            {/* 🚨 3. 新增城市选择器，限制只能选到市级 */}
            <Form.Item
                name="cityCascade"
                label="所在城市"
                rules={[{ required: true, message: '请选择演出所在城市' }]}
            >
                <Cascader
                    options={cityOptions}
                    placeholder="请选择省份与城市"
                    size="large"
                    expandTrigger="hover"
                />
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
                        showSearch // 🚨 开启搜索框
                        filterOption={false} // 🚨 关闭前端本地过滤，全权交给后端查
                        onSearch={handleSearchArtist} // 绑定防抖事件
                        placeholder="输入音乐人姓名"
                        size="large"
                        options={artistOptions}
                        notFoundContent={fetchingArtists ? <Spin size="small" /> : "请在框内打字以搜索艺人库..."}

                        // 👇 需求一：底部插入“显示更多”按钮
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

                        // 👇 需求二：左侧渲染艺人专属头像
                        optionRender={(option) => (
                            <Space align="center">
                                <Avatar
                                    src={option.data.avatarUrl || 'https://via.placeholder.com/24'}
                                    size="small"
                                    style={{ border: '1px solid #eee' }}
                                />
                                <span>{option.data.label}</span>
                            </Space>
                        )}
                    />
                </Form.Item>

                <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    <span style={{ fontSize: '12px', color: '#888' }}>如未找到艺人，请先新增并提交审核</span>
                    <Button type="link" size="small" onClick={() => setArtistModalVisible(true)} style={{ padding: 0, color: '#FF8899', fontWeight: 500 }}>
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

                                {fields.length > 1 && (
                                    <MinusCircleOutlined onClick={() => remove(name)} style={{ color: '#ff4d4f', fontSize: '18px', marginLeft: '12px', cursor: 'pointer' }} />
                                )}
                            </Space>
                        ))}
                        <Form.Item style={{ marginTop: 8 }}>
                            <Button type="dashed" onClick={() => add()} block icon={<PlusOutlined />} size="large" style={{ borderColor: '#FF8899', color: '#FF8899', borderRadius: 8 }}>
                                追加一档票价
                            </Button>
                        </Form.Item>
                    </>
                )}
            </Form.List>

            <div style={{ marginTop: 40, textAlign: 'right' }}>
                <Button size="large" onClick={() => form.resetFields()} style={{ marginRight: 16, borderRadius: 8 }}>重置清空</Button>

                {/* 🚨 核心 UI 切换：动态按钮文案 */}
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
                        {/* 👇 核心升级：用 ImgCrop 包裹，并设置汉化和 1:1 比例 */}
                        {/*<ImgCrop*/}
                        {/*    rotationSlider*/}
                        {/*    aspect={1} // 1:1 的正方形遮罩*/}
                        {/*    modalTitle="裁剪头像"*/}
                        {/*    modalOk="确认裁剪"*/}
                        {/*    modalCancel="取消"*/}
                        {/*>*/}
                            <Upload
                                name="file"
                                action="/api/common/upload"
                                data={{ type: 'avatar' }}
                                listType="picture"
                                maxCount={1}
                                headers={{ Authorization: `Bearer ${localStorage.getItem('token')}` }}
                                onChange={(info) => handleUploadChange(info, '艺人头像')}
                            >
                                <Button icon={<UploadOutlined />}>上传专属头像</Button>
                            </Upload>
                        {/*</ImgCrop>*/}
                    </Form.Item>

                    <Form.Item name="region" label="国家或地区" rules={[{ required: true, message: '请选择或输入国家/地区' }]}>
                        <Select
                            mode="tags" // tags 模式允许用户在下拉框没有预设值时，自己自由打字回车输入
                            maxCount={1} // 限制只能选/填一个
                            placeholder="请选择或输入所属国家/地区"
                            size="large"
                            options={[
                                { value: '中国大陆', label: '中国大陆' },
                                { value: '中国港澳台', label: '中国港澳台' },
                                { value: '日本', label: '日本' },
                                { value: '韩国', label: '韩国' },
                                { value: '欧美', label: '欧美' },
                                { value: '其他海外地区', label: '其他海外地区' },
                            ]}
                        />
                    </Form.Item>

                    <Form.Item name="style" label="音乐风格">
                        <Input placeholder="如：流行、摇滚、ACG、J-Pop、重金属 等" size="large" />
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