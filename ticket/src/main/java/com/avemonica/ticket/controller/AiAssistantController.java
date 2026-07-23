package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.AiAssistantChatRequest;
import com.avemonica.ticket.dto.AiAssistantChatResponse;
import com.avemonica.ticket.service.AiAssistantService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai-assistant")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    public AiAssistantController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @PostMapping("/chat")
    public Result<AiAssistantChatResponse> chat(@RequestBody AiAssistantChatRequest request) {
        if (request == null) {
            return Result.error("请求参数不能为空");
        }

        AiAssistantChatResponse response = aiAssistantService.chat(
                request.getQuestion(),
                request.getCity(),
                request.getSize()
        );

        return Result.success(response);
    }

    @PostMapping("/debug-visible")
    public Result<Map<String, Object>> debugVisible(@RequestBody AiAssistantChatRequest request) {
        if (request == null) {
            return Result.error("请求参数不能为空");
        }

        return Result.success(
                aiAssistantService.debugVisibleEvents(
                        request.getQuestion(),
                        request.getCity(),
                        request.getSize()
                )
        );
    }
}