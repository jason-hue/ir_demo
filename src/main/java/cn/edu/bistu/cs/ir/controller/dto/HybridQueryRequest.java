package cn.edu.bistu.cs.ir.controller.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HybridQueryRequest {

    private String question;

    private Integer pageNo = 1;

    private Integer pageSize = 10;

    public int normalizedPageNo() {
        return pageNo == null ? 1 : Math.max(pageNo, 1);
    }

    public int normalizedPageSize() {
        return pageSize == null ? 10 : Math.max(pageSize, 1);
    }
}
