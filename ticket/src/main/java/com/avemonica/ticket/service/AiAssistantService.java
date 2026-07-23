package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.AiAssistantChatResponse;

import java.util.Map;

public interface AiAssistantService {

    AiAssistantChatResponse chat(String question, String city, Integer size);

    Map<String, Object> debugVisibleEvents(String question, String city, Integer size);
}