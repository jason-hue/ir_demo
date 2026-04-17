package cn.edu.bistu.cs.ir.ai;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ChatAnswerResult {

    private String question;

    private String retrievalMode;

    private boolean answerAvailable;

    private String answer;

    private String degradedReason;

    private List<HybridChunkResult> citations = new ArrayList<>();
}
