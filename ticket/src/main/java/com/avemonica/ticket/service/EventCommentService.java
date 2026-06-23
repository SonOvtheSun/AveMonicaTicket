package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.EventCommentAddDTO;
import com.avemonica.ticket.dto.EventCommentVoteDTO;
import com.avemonica.ticket.entity.EventComment;
import com.avemonica.ticket.vo.EventCommentVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

public interface EventCommentService extends IService<EventComment> {

    Page<EventCommentVO> pageComments(Long eventId,
                                      Long currentUserId,
                                      Integer current,
                                      Integer size,
                                      String sort);

    void addComment(EventCommentAddDTO dto, Long userId);

    void vote(EventCommentVoteDTO dto, Long userId);
}