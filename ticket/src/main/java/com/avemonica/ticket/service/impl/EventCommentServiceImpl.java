package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventCommentAddDTO;
import com.avemonica.ticket.dto.EventCommentVoteDTO;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventComment;
import com.avemonica.ticket.entity.EventCommentVote;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.EventCommentMapper;
import com.avemonica.ticket.mapper.EventCommentVoteMapper;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.service.EventCommentService;
import com.avemonica.ticket.service.UserService;
import com.avemonica.ticket.vo.EventCommentVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class EventCommentServiceImpl extends ServiceImpl<EventCommentMapper, EventComment> implements EventCommentService {

    private static final int COMMENT_STATUS_NORMAL = 1;
    private static final int VOTE_LIKE = 1;
    private static final int VOTE_DISLIKE = -1;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private EventCommentVoteMapper voteMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Page<EventCommentVO> pageComments(Long eventId,
                                             Long currentUserId,
                                             Integer current,
                                             Integer size,
                                             String sort) {
        if (eventId == null) {
            throw new BusinessException("演出ID不能为空");
        }

        int safeCurrent = current == null || current < 1 ? 1 : current;
        int safeSize = size == null || size < 1 ? 10 : size;

        LambdaQueryWrapper<EventComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventComment::getEventId, eventId)
                .eq(EventComment::getStatus, COMMENT_STATUS_NORMAL);

        if ("time_asc".equals(sort)) {
            wrapper.orderByAsc(EventComment::getCreateTime);
        } else if ("hot_desc".equals(sort)) {
            wrapper.last("ORDER BY (like_count - dislike_count) DESC, create_time DESC");
        } else if ("hot_asc".equals(sort)) {
            wrapper.last("ORDER BY (like_count - dislike_count) ASC, create_time DESC");
        } else {
            wrapper.orderByDesc(EventComment::getCreateTime);
        }

        Page<EventComment> page = this.page(new Page<>(safeCurrent, safeSize), wrapper);

        List<EventCommentVO> voList = page.getRecords()
                .stream()
                .map(comment -> buildCommentVO(comment, currentUserId))
                .collect(Collectors.toList());

        Page<EventCommentVO> voPage = new Page<>();
        voPage.setCurrent(page.getCurrent());
        voPage.setSize(page.getSize());
        voPage.setTotal(page.getTotal());
        voPage.setPages(page.getPages());
        voPage.setRecords(voList);

        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addComment(EventCommentAddDTO dto, Long userId) {
        if (userId == null) {
            throw new BusinessException("请先登录后再评论");
        }

        if (dto == null || dto.getEventId() == null) {
            throw new BusinessException("演出ID不能为空");
        }

        if (!StringUtils.hasText(dto.getContent())) {
            throw new BusinessException("评论内容不能为空");
        }

        Event event = eventMapper.selectById(dto.getEventId());
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        EventComment comment = new EventComment();
        comment.setEventId(dto.getEventId());
        comment.setUserId(userId);
        comment.setContent(dto.getContent().trim());
        comment.setImageUrls(toImageJson(dto.getImageUrls()));
        comment.setLikeCount(0);
        comment.setDislikeCount(0);
        comment.setStatus(COMMENT_STATUS_NORMAL);

        this.save(comment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void vote(EventCommentVoteDTO dto, Long userId) {
        Long commentId;
        try {
            commentId = Long.valueOf(dto.getCommentId());
        } catch (Exception e) {
            throw new BusinessException("评论ID格式不正确");
        }


        if (userId == null) {
            throw new BusinessException("请先登录后再操作");
        }

        if (dto == null || commentId == null) {
            throw new BusinessException("评论ID不能为空");
        }

        if (!Objects.equals(dto.getVoteType(), VOTE_LIKE)
                && !Objects.equals(dto.getVoteType(), VOTE_DISLIKE)) {
            throw new BusinessException("投票类型不正确");
        }

        EventComment comment = this.getById(commentId);

        if (comment == null || !Objects.equals(comment.getStatus(), COMMENT_STATUS_NORMAL)) {
            throw new BusinessException("评论不存在或已被隐藏");
        }

        EventCommentVote oldVote = voteMapper.selectOne(
                new LambdaQueryWrapper<EventCommentVote>()
                        .eq(EventCommentVote::getCommentId, commentId)
                        .eq(EventCommentVote::getUserId, userId)
        );

        int likeDelta = 0;
        int dislikeDelta = 0;

        if (oldVote == null) {
            EventCommentVote vote = new EventCommentVote();
            vote.setCommentId(commentId);
            vote.setUserId(userId);
            vote.setVoteType(dto.getVoteType());
            voteMapper.insert(vote);

            if (Objects.equals(dto.getVoteType(), VOTE_LIKE)) {
                likeDelta = 1;
            } else {
                dislikeDelta = 1;
            }
        } else if (Objects.equals(oldVote.getVoteType(), dto.getVoteType())) {
            voteMapper.deleteById(oldVote.getId());

            if (Objects.equals(dto.getVoteType(), VOTE_LIKE)) {
                likeDelta = -1;
            } else {
                dislikeDelta = -1;
            }
        } else {
            Integer oldType = oldVote.getVoteType();
            oldVote.setVoteType(dto.getVoteType());
            voteMapper.updateById(oldVote);

            if (Objects.equals(oldType, VOTE_LIKE)) {
                likeDelta = -1;
                dislikeDelta = 1;
            } else {
                dislikeDelta = -1;
                likeDelta = 1;
            }
        }

        EventComment update = new EventComment();
        update.setId(comment.getId());
        update.setLikeCount(Math.max(0, comment.getLikeCount() + likeDelta));
        update.setDislikeCount(Math.max(0, comment.getDislikeCount() + dislikeDelta));

        this.updateById(update);
    }

    private EventCommentVO buildCommentVO(EventComment comment, Long currentUserId) {
        EventCommentVO vo = new EventCommentVO();

        vo.setId(String.valueOf(comment.getId()));
        vo.setEventId(String.valueOf(comment.getEventId()));
        vo.setUserId(String.valueOf(comment.getUserId()));
        vo.setContent(comment.getContent());
        vo.setImageUrls(parseImageUrls(comment.getImageUrls()));
        vo.setLikeCount(comment.getLikeCount());
        vo.setDislikeCount(comment.getDislikeCount());
        vo.setCreateTime(comment.getCreateTime() == null ? null : comment.getCreateTime().format(DATE_TIME_FORMATTER));

        User user = userService.getById(comment.getUserId());
        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setAvatar(user.getAvatar());
        }

        if (currentUserId != null) {
            EventCommentVote vote = voteMapper.selectOne(
                    new LambdaQueryWrapper<EventCommentVote>()
                            .eq(EventCommentVote::getCommentId, comment.getId())
                            .eq(EventCommentVote::getUserId, currentUserId)
            );

            if (vote != null) {
                vo.setMyVote(vote.getVoteType());
            }
        }

        return vo;
    }

    private String toImageJson(List<String> imageUrls) {
        try {
            List<String> urls = imageUrls == null ? new ArrayList<>() : imageUrls;
            return objectMapper.writeValueAsString(urls);
        } catch (Exception e) {
            throw new BusinessException("评论图片格式错误");
        }
    }

    private List<String> parseImageUrls(String imageUrls) {
        if (!StringUtils.hasText(imageUrls)) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(imageUrls, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}