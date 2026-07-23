package com.avemonica.ticket.dto;

import com.avemonica.ticket.vo.AiAssistantEventVO;
import lombok.Data;

import java.util.List;

@Data
public class AiAssistantChatResponse {

    private String answer;

    private List<AiAssistantEventVO> events;
}