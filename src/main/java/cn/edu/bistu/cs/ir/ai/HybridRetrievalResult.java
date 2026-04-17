package cn.edu.bistu.cs.ir.ai;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class HybridRetrievalResult {

    private String question;

    private String mode;

    private int pageNo;

    private int pageSize;

    private int totalResults;

    private String degradedReason;

    private List<HybridChunkResult> results = new ArrayList<>();
}
