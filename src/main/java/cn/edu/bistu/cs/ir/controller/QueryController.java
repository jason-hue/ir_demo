package cn.edu.bistu.cs.ir.controller;

import cn.edu.bistu.cs.ir.ai.HybridRetrievalResult;
import cn.edu.bistu.cs.ir.ai.HybridRetrievalService;
import cn.edu.bistu.cs.ir.controller.dto.HybridQueryRequest;
import cn.edu.bistu.cs.ir.index.IdxService;
import cn.edu.bistu.cs.ir.index.ArticleIdxFields;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 面向检索服务接口的控制器类
 * Restful Web Services/Rest风格的Web服务
 * @author chenruoyu
 */
@RestController
@RequestMapping("/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);


    private final IdxService idxService;

    private final HybridRetrievalService hybridRetrievalService;

    public QueryController(@Autowired IdxService idxService,
                           @Autowired HybridRetrievalService hybridRetrievalService){
        this.idxService = idxService;
        this.hybridRetrievalService = hybridRetrievalService;
    }


    /**
     * 根据关键词对索引进行分页检索，
     * 根据页号和页面大小，
     * 返回指定页的数据记录
     * @param kw 待检索的关键词
     * @param pageNo 页号，默认为1
     * @param pageSize 页的大小，默认为10
     * @return 检索得到的结果记录，以<页面ID, 页面标题>二元组的形式返回
     */
    @GetMapping(value = "/kw", produces = "application/json;charset=UTF-8")
    public QueryResponse<List<Map<String, String>>> queryByKw(@RequestParam(name = "kw") String kw,
                                                                      @RequestParam(name = "pageNo", defaultValue = "1") int pageNo,
                                                                      @RequestParam(name = "pageSize", defaultValue = "10") int pageSize){
        try {
            List<Document> docs = idxService.queryByKw(kw, pageNo, pageSize);
            List<Map<String, String>> results = new ArrayList<>();
            for(Document doc : docs){
                Map<String, String> record = new LinkedHashMap<>(7);
                record.put(ArticleIdxFields.ID, doc.get(ArticleIdxFields.ID));
                record.put(ArticleIdxFields.TITLE, doc.get(ArticleIdxFields.TITLE));
                record.put(ArticleIdxFields.TIME, doc.get(ArticleIdxFields.TIME));
                record.put(ArticleIdxFields.SOURCE, doc.get(ArticleIdxFields.SOURCE));
                record.put(ArticleIdxFields.SOURCE_URL, doc.get(ArticleIdxFields.SOURCE_URL));
                record.put(ArticleIdxFields.AUTHOR, firstNonBlank(doc.get(ArticleIdxFields.AUTHOR), doc.get(ArticleIdxFields.BYLINE)));
                record.put(ArticleIdxFields.BYLINE, firstNonBlank(doc.get(ArticleIdxFields.BYLINE), doc.get(ArticleIdxFields.AUTHOR)));
                results.add(record);
            }
            return QueryResponse.genSucc("检索成功", results);
        } catch (Exception e) {
            log.error("检索过程中发生异常:[{}]", e.getMessage());
            return QueryResponse.genErr("检索过程中发生异常");
        }
    }

    @PostMapping(value = "/hybrid", produces = "application/json;charset=UTF-8", consumes = "application/json")
    public QueryResponse<HybridRetrievalResult> queryHybrid(@RequestBody HybridQueryRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            return QueryResponse.genErr("问题不可以为空");
        }
        try {
            HybridRetrievalResult result = hybridRetrievalService.retrieve(
                    request.getQuestion(),
                    request.normalizedPageNo(),
                    request.normalizedPageSize());
            String msg = "hybrid".equals(result.getMode()) ? "混合检索成功" : "向量路径不可用，已退化为词法检索";
            return QueryResponse.genSucc(msg, result);
        }
        catch (Exception e) {
            log.error("混合检索过程中发生异常:[{}]", e.getMessage(), e);
            return QueryResponse.genErr("混合检索过程中发生异常");
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
