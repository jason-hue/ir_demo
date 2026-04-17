package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.ai.ChatAnswerResult;
import cn.edu.bistu.cs.ir.ai.ChatAnswerService;
import cn.edu.bistu.cs.ir.controller.dto.ChatAskRequest;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatAnswerService chatAnswerService;

    public ChatController(@Autowired ChatAnswerService chatAnswerService) {
        this.chatAnswerService = chatAnswerService;
    }

    @PostMapping(value = "/ask", produces = "application/json;charset=UTF-8", consumes = "application/json")
    public QueryResponse<ChatAnswerResult> ask(@RequestBody ChatAskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return QueryResponse.genErr("问题不可以为空");
        }
        try {
            ChatAnswerResult result = chatAnswerService.ask(request.getQuestion());
            if (result.isAnswerAvailable()) {
                return QueryResponse.genSucc("问答成功", result);
            }
            QueryResponse<ChatAnswerResult> response = new QueryResponse<>();
            response.setSuccess(false);
            response.setMsg("聊天模型或向量路径不可用，已返回可校验的降级结果");
            response.setData(result);
            return response;
        }
        catch (Exception e) {
            log.error("问答过程中发生异常:[{}]", e.getMessage(), e);
            return QueryResponse.genErr("问答过程中发生异常");
        }
    }
}
