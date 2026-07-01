import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Avatar,
    Button,
    Empty,
    Image,
    Input,
    message,
    Modal,
    Select,
    Spin,
    Tag,
    Upload
} from 'antd';
import {
    DislikeOutlined,
    LikeOutlined,
    PictureOutlined,
    UploadOutlined,
    UserOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import axios from '../../utils/request';
import './EventComments.css';

const { TextArea } = Input;

const SORT_OPTIONS = [
    { value: 'time_desc', label: '时间倒序' },
    { value: 'time_asc', label: '时间正序' },
    { value: 'hot_desc', label: '热度倒序' },
    { value: 'hot_asc', label: '热度正序' }
];


const normalizeComment = (item) => ({
    id: String(item.id),
    eventId: String(item.eventId),
    userId: String(item.userId),
    username: item.username || '匿名用户',
    avatar: item.avatar || '',
    content: item.content || '',
    images: item.imageUrls || item.images || [],
    likeCount: Number(item.likeCount || 0),
    dislikeCount: Number(item.dislikeCount || 0),
    createdAt: item.createTime || item.createdAt || '',
    myVote: Number(item.myVote) === 1 ? 'like' : Number(item.myVote) === -1 ? 'dislike' : null
});

const CommentItem = ({ comment, onVote }) => (
    <div className="event-comment-item">
        <Avatar size={40} src={comment.avatar || undefined} icon={<UserOutlined />} />

        <div className="event-comment-main">
            <div className="event-comment-topline">
                <div className="event-comment-user">{comment.username || '匿名用户'}</div>
                <div className="event-comment-time">{comment.createdAt || '-'}</div>
            </div>

            <div className="event-comment-content">{comment.content}</div>

            {comment.images && comment.images.length > 0 && (
                <Image.PreviewGroup>
                    <div className="event-comment-images">
                        {comment.images.map((url, idx) => (
                            <Image
                                key={`${comment.id}-${idx}`}
                                src={url}
                                width={82}
                                height={82}
                                className="event-comment-img"
                                fallback=""
                            />
                        ))}
                    </div>
                </Image.PreviewGroup>
            )}

            <div className="event-comment-actions">
                <Button
                    size="small"
                    type={comment.myVote === 'like' ? 'primary' : 'default'}
                    icon={<LikeOutlined />}
                    onClick={() => onVote(comment.id, 1)}
                >
                    {comment.likeCount || 0}
                </Button>

                <Button
                    size="small"
                    danger={comment.myVote === 'dislike'}
                    icon={<DislikeOutlined />}
                    onClick={() => onVote(comment.id, -1)}
                >
                    {comment.dislikeCount || 0}
                </Button>

            </div>
        </div>
    </div>
);

const EventComments = ({ eventId, event }) => {
    const navigate = useNavigate();

    const [previewComments, setPreviewComments] = useState([]);
    const [comments, setComments] = useState([]);

    const [modalVisible, setModalVisible] = useState(false);
    const [sortType, setSortType] = useState('time_desc');

    const [previewLoading, setPreviewLoading] = useState(false);
    const [commentLoading, setCommentLoading] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    const [commentText, setCommentText] = useState('');
    const [pendingImages, setPendingImages] = useState([]);

    const [pageInfo, setPageInfo] = useState({
        current: 1,
        size: 20,
        total: 0
    });

    const totalLikes = useMemo(
        () => previewComments.reduce((sum, item) => sum + Number(item.likeCount || 0), 0),
        [previewComments]
    );

    const requireLogin = (tip = '请先登录后操作') => {
        const token = localStorage.getItem('token');

        if (!token) {
            message.info(tip);
            navigate('/auth');
            return false;
        }

        return true;
    };

    const fetchPreviewComments = async () => {
        if (!eventId) return;

        setPreviewLoading(true);
        try {
            const res = await axios.get('/api/event/comment/page', {
                params: {
                    eventId,
                    current: 1,
                    size: 3,
                    sort: 'hot_desc'
                }
            });

            if (res.data.code === 200) {
                const records = res.data.data?.records || [];
                setPreviewComments(records.map(normalizeComment));
            } else {
                message.error(res.data.message || '获取评论失败');
            }
        } catch (error) {
            message.error('网络异常，获取评论失败');
        } finally {
            setPreviewLoading(false);
        }
    };

    const fetchComments = async (current = 1, size = pageInfo.size, sort = sortType) => {
        if (!eventId) return;

        setCommentLoading(true);
        try {
            const res = await axios.get('/api/event/comment/page', {
                params: {
                    eventId,
                    current,
                    size,
                    sort
                }
            });

            if (res.data.code === 200) {
                const data = res.data.data || {};
                setComments((data.records || []).map(normalizeComment));
                setPageInfo({
                    current: Number(data.current || current),
                    size: Number(data.size || size),
                    total: Number(data.total || 0)
                });
            } else {
                message.error(res.data.message || '获取评论失败');
            }
        } catch (error) {
            message.error('网络异常，获取评论失败');
        } finally {
            setCommentLoading(false);
        }
    };

    useEffect(() => {
        fetchPreviewComments();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [eventId]);

    useEffect(() => {
        if (modalVisible) {
            fetchComments(1, pageInfo.size, sortType);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sortType]);

    const handleOpenAllComments = () => {
        if (!requireLogin('请先登录后查看全部评论')) return;

        setModalVisible(true);
        fetchComments(1, pageInfo.size, sortType);
    };

    const handleVote = async (commentId, voteType) => {
        if (!requireLogin('请先登录后进行点赞或拉踩')) return;

        try {
            const res = await axios.post('/api/event/comment/vote', {
                commentId: String(commentId),
                voteType
            });

            if (res.data.code === 200) {
                await fetchPreviewComments();

                if (modalVisible) {
                    await fetchComments(pageInfo.current, pageInfo.size, sortType);
                }
            } else {
                message.error(res.data.message || '操作失败');
            }
        } catch (error) {
            message.error('网络异常，操作失败');
        }
    };

    const customUploadImage = async (options) => {
        const { file, onSuccess, onError } = options;

        const isImage = file.type?.startsWith('image/');
        if (!isImage) {
            message.error('只能上传图片');
            onError?.(new Error('只能上传图片'));
            return;
        }

        if (pendingImages.length >= 6) {
            message.warning('最多添加 6 张图片');
            onError?.(new Error('最多添加 6 张图片'));
            return;
        }

        try {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('type', 'poster');

            const res = await axios.post('/api/common/upload', formData, {
                headers: {
                    'Content-Type': 'multipart/form-data',
                    Authorization: `Bearer ${localStorage.getItem('token') || ''}`
                }
            });

            if (res.data.code === 200) {
                const url = res.data.data;

                setPendingImages(prev => [
                    ...prev,
                    {
                        uid: file.uid,
                        name: file.name,
                        status: 'done',
                        url
                    }
                ]);

                onSuccess?.(res.data);
            } else {
                message.error(res.data.message || '图片上传失败');
                onError?.(new Error(res.data.message || '图片上传失败'));
            }
        } catch (error) {
            message.error('网络异常，图片上传失败');
            onError?.(error);
        }
    };

    const handleRemovePendingImage = async (file) => {
        setPendingImages(prev => prev.filter(item => item.uid !== file.uid));

        // 可选：删除未提交图片，避免上传后又移除造成孤儿文件
        if (file.url) {
            try {
                await axios.post('/api/common/delete-upload', {
                    url: file.url
                });
            } catch (error) {
                // 删除失败不阻断前端操作
                console.warn('删除未使用评论图片失败', error);
            }
        }
    };

    const handleSubmitComment = async () => {
        if (!requireLogin('请先登录后发布评论')) return;

        const content = commentText.trim();

        if (!content) {
            message.warning('请输入评论内容');
            return;
        }

        setSubmitting(true);

        try {
            const res = await axios.post('/api/event/comment/add', {
                eventId,
                content,
                imageUrls: pendingImages.map(item => item.url)
            });

            if (res.data.code === 200) {
                message.success('评论已发布');

                setCommentText('');
                setPendingImages([]);

                await fetchPreviewComments();
                await fetchComments(1, pageInfo.size, sortType);
            } else {
                message.error(res.data.message || '评论发布失败');
            }
        } catch (error) {
            message.error('网络异常，评论发布失败');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="section-block event-comments-section">
            <div className="section-header">讨论区</div>

            <div className="event-comment-summary-box">
                <div className="event-comment-summary-head">
                    <div>
                        <div className="event-comment-summary-sub">
                            共 {pageInfo.total || previewComments.length} 条评论 · {totalLikes} 次点赞
                        </div>
                    </div>
                </div>

                <Spin spinning={previewLoading}>
                    {previewComments.length === 0 ? (
                        <Empty description="暂无讨论" />
                    ) : (
                        <div className="event-comment-preview-list">
                            {previewComments.map(comment => (
                                <CommentItem
                                    key={comment.id}
                                    comment={comment}
                                    onVote={handleVote}
                                />
                            ))}
                        </div>
                    )}
                </Spin>

                <Button
                    type="link"
                    className="event-comment-more-link"
                    onClick={handleOpenAllComments}
                >
                    查看全部讨论
                </Button>
            </div>

            <Modal
                title="全部讨论"
                open={modalVisible}
                onCancel={() => setModalVisible(false)}
                footer={null}
                width={860}
                className="event-comment-modal"
            >
                <div className="event-comment-compose">
                    <TextArea
                        rows={4}
                        maxLength={500}
                        showCount
                        value={commentText}
                        onChange={(e) => setCommentText(e.target.value)}
                        placeholder="说说你对这场演出的期待或观演体验..."
                    />

                    <div className="event-comment-compose-footer">
                        <Upload
                            fileList={pendingImages}
                            showUploadList={true}
                            accept="image/*"
                            customRequest={customUploadImage}
                            onRemove={handleRemovePendingImage}
                            className="event-comment-image-upload"
                        >
                            {pendingImages.length >= 6 ? null : (
                                <Button
                                    type="text"
                                    icon={<UploadOutlined />}
                                    className="event-comment-upload-icon-btn"
                                />
                            )}
                        </Upload>

                        <Button
                            type="primary"
                            className="event-comment-submit-btn"
                            onClick={handleSubmitComment}
                            loading={submitting}
                        >
                            发布讨论
                        </Button>
                    </div>
                </div>

                <div className="event-comment-toolbar">
                    <div className="event-comment-count">
                        <PictureOutlined />
                        <span>{pageInfo.total || comments.length} 条讨论</span>
                    </div>

                    <Select
                        value={sortType}
                        options={SORT_OPTIONS}
                        onChange={setSortType}
                        style={{ width: 132 }}
                    />
                </div>

                <Spin spinning={commentLoading}>
                    {comments.length === 0 ? (
                        <Empty description="暂无讨论" />
                    ) : (
                        <div className="event-comment-full-list">
                            {comments.map(comment => (
                                <CommentItem
                                    key={comment.id}
                                    comment={comment}
                                    onVote={handleVote}
                                />
                            ))}
                        </div>
                    )}

                    {pageInfo.total > comments.length && (
                        <div style={{ textAlign: 'center', marginTop: 18 }}>
                            <Button
                                onClick={() => fetchComments(pageInfo.current + 1, pageInfo.size, sortType)}
                                loading={commentLoading}
                            >
                                加载更多
                            </Button>
                        </div>
                    )}
                </Spin>
            </Modal>
        </div>
    );
};

export default EventComments;