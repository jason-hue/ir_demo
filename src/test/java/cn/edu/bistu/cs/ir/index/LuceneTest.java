package cn.edu.bistu.cs.ir.index;

import cn.edu.bistu.cs.ir.controller.QueryController;
import cn.edu.bistu.cs.ir.model.Blog;
import cn.edu.bistu.cs.ir.model.School;
import cn.edu.bistu.cs.ir.utils.QueryResponse;
import cn.edu.bistu.cs.ir.utils.FileUtils;
import cn.edu.bistu.cs.ir.utils.StringUtil;
import com.alibaba.fastjson.JSONObject;
import com.hankcs.lucene.HanLPAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.SloppyMath;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.edu.bistu.cs.ir.model.IdxFields.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(SpringExtension.class)
@Slf4j
public class LuceneTest{

    private static final String ARTICLE_TEST_HOME = "workspace/test-lucene-articles-" + System.nanoTime();

    private static final String ARTICLE_FIXTURE = "fixtures/tencent/news/article-page.html";

    /**
     * 分词器
     */
    private static final Class<? extends Analyzer> ANALYZER_CLS = HanLPAnalyzer.class;

    /**
     * 测试的工作目录
     */
    private static final String TEST_HOME = "workspace/test/";

    /**
     * 测试使用的资源文件
     */
    private static final String TEST_FILE = "school.json";

    @Autowired
    private IdxService idxService;

    @Autowired
    private QueryController queryController;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.enabled", () -> false);
        registry.add("app.vector.enabled", () -> false);
        registry.add("app.crawler.tencent.enabled", () -> false);
        registry.add("irdemo.dir.home", () -> ARTICLE_TEST_HOME);
        registry.add("irdemo.dir.idx", () -> ARTICLE_TEST_HOME + "/idx");
        registry.add("irdemo.dir.crawler", () -> ARTICLE_TEST_HOME + "/crawler");
    }

    /**
     * 索引的Writer
     */
    private IndexWriter iWriter;

    @BeforeEach
    public void initWriter() throws Exception {
        log.info("初始化Writer");
        Analyzer analyzer = ANALYZER_CLS.getConstructor().newInstance();
        Directory directory = new MMapDirectory(Paths.get(TEST_HOME));
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        this.iWriter = new IndexWriter(directory, config);
    }

    @AfterEach
    public void destroyWriter() throws Exception {
        if(this.iWriter!=null){
            this.iWriter.close();
        }
        log.info("关闭Writer");
    }

    @BeforeAll
    public static void init() throws Exception {
        IndexWriter iwriter = null;
        try{
            log.info("初始化索引内容");
            //初始化IndexWriter
            Analyzer analyzer = ANALYZER_CLS.getConstructor().newInstance();
            Directory directory = new MMapDirectory(Paths.get(TEST_HOME));
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            iwriter = new IndexWriter(directory, config);
            //读取资源文件中的信息
            String json = readClasspathResource(TEST_FILE);
            List<School> schoolList = JSONObject.parseArray(json, School.class);
            //为学校信息构建索引
            for(School school: schoolList){
                Document doc = toDoc(school);
                iwriter.updateDocument(new Term(ID.name(), String.valueOf(school.getId())), doc);
            }
        } catch (IOException e) {
            log.error("初始化索引失败[{}]", e.getMessage());
            throw new RuntimeException(e);
        }finally {
            if(iwriter!=null){
                iwriter.commit();
                iwriter.close();
                log.info("成功完成索引构建");
            }
        }
    }

    private static String readClasspathResource(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void docCountTest() throws IOException {
        //获取索引中的文档数信息
        try(IndexReader reader = DirectoryReader.open(iWriter)){
            Assertions.assertNotEquals(0, reader.getDocCount(ID.name()));
            Assertions.assertEquals(reader.getDocCount(NAME.name()), reader.getDocCount(ID.name()));
            Assertions.assertEquals(0, reader.getDocCount(EMAIL.name()));
            log.info("共有索引文档[{}]个", reader.getDocCount(ID.name()));
        }
    }

    @Test
    public void complexQueryTest() throws Exception {
        //根据关键词、学校人数、学校的经纬度进行检索并按照距离排序
        String keyword = "grammar";//检索关键词
        double lat = -36.800616;//纬度坐标
        double lon = 174.788477;//经度坐标
        int distance = 50;//到上述坐标的距离范围，以千米为单位
        int roll_upr = 2000;//学生人数上限
        int roll_lwr = 1000;//学生人数下限
        List<SortField> sorts = new ArrayList<>();
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        //添加距离检索，并添加排序条件
        Query qc = LatLonPoint.newDistanceQuery(LOCATION.name(), lat, lon, distance*1000);
        builder.add(qc, BooleanClause.Occur.MUST);
        sorts.add(LatLonDocValuesField.newDistanceSort(LOCATION.name(), lat, lon));

        //添加学生人数检索，并添加排序条件
        Query qr = IntPoint.newRangeQuery(ROLL.name(), roll_lwr, roll_upr);
        builder.add(qr, BooleanClause.Occur.MUST);
        sorts.add(new SortField(ROLL.name(), SortField.Type.INT, true));

        Analyzer analyzer = ANALYZER_CLS.getConstructor().newInstance();
        QueryParser queryParser = new QueryParser(NAME.name(), analyzer);
        String q = String.format("%s:%s", NAME.name(), QueryParser.escape(keyword));
        Query qk = queryParser.parse(q);
        builder.add(qk, BooleanClause.Occur.MUST);

        int page = 1;
        List<Map.Entry<School, Float>> result = pagedSearch(builder.build(), sorts, page, 10);
        while(!result.isEmpty()){
            log.info("检索结果第[{}]页，共[{}]条:", page, result.size());
            for(Map.Entry<School, Float> entry: result){
                School school = entry.getKey();
                //计算检索结果中的学校到坐标点的距离，单位为米
                double d = SloppyMath.haversinMeters(lat, lon,
                        Double.parseDouble(school.getLatitude()),
                        Double.parseDouble(school.getLongitude()));

                log.info("学校名称[{}], 与指定坐标点的距离[{}千米], 学生人数[{}]",
                        school.getName(), d/1000, school.getTotal_school_roll());
            }
            page++;
            result = pagedSearch(builder.build(), sorts, page, 10);
        }
    }

    @Test
    public void queryByNameTest() throws Exception{
        //根据学校名称字段进行关键词检索
        String keywords = "Albert";
        Analyzer analyzer = ANALYZER_CLS.getConstructor().newInstance();
        QueryParser queryParser = new QueryParser(NAME.name(), analyzer);
        String q = String.format("%s:%s", NAME.name(), QueryParser.escape(keywords));
        Query query = queryParser.parse(q);
        List<Map.Entry<School, Float>> results = search(query, null, 5);
        Assertions.assertFalse(results.isEmpty());
        log.info("检索结果共[{}]条:", results.size());
        for(Map.Entry<School, Float> entry: results){
            log.info("学校名称[{}], 检索评分[{}]", entry.getKey().getName(), entry.getValue());
        }
    }

    @Test
    public void queryByRollTest() throws Exception{
        //根据学生人数进行数字范围检索，并且按照学生人数从大到小排列
        int upperBound = 500;
        int lowerBound = 300;
        Query query =  IntPoint.newRangeQuery(ROLL.name(), lowerBound, upperBound);
        //按照学生人数，从大到小排序
        SortField rollSort = new SortField(ROLL.name(), SortField.Type.INT, true);
        List<Map.Entry<School, Float>> results = search(query, List.of(rollSort), 10);
        Assertions.assertFalse(results.isEmpty());
        log.info("检索结果共[{}]条:", results.size());
        for(Map.Entry<School, Float> entry: results){
            log.info("学校名称[{}], 学生人数[{}]", entry.getKey().getName(), entry.getKey().getTotal_school_roll());
        }
    }


    @Test
    public void queryByTypeTest() throws Exception{
        //根据学校类型进行枚举式的检索
        String type = "Composite";
        Query query =  new TermQuery(new Term(TYPE.name(), type));
        List<Map.Entry<School, Float>> results = search(query, null, 10);
        Assertions.assertFalse(results.isEmpty());
        log.info("检索结果共[{}]条:", results.size());
        for(Map.Entry<School, Float> entry: results){
            log.info("学校名称[{}], 学校类型[{}]", entry.getKey().getName(), entry.getKey().getSchool_type());
        }
    }

    @Test
    public void chineseArticleQueryReturnsFixtureBackedResults() throws Exception {
        seedTencentArticles();

        List<Document> docs = idxService.queryByKw("新闻检索助手", 1, 10);

        Assertions.assertFalse(docs.isEmpty());
        Assertions.assertAll(
                () -> Assertions.assertTrue(docs.stream().anyMatch(doc -> "腾讯新闻推出新闻检索助手试点".equals(doc.get(ArticleIdxFields.TITLE)))),
                () -> Assertions.assertTrue(docs.stream().allMatch(doc -> doc.get(ArticleIdxFields.SOURCE_URL) != null)),
                () -> Assertions.assertTrue(docs.stream().allMatch(doc -> doc.get(ArticleIdxFields.SOURCE) != null))
        );
    }

    @Test
    public void queryControllerAppliesRealPaginationAndReturnsCanonicalFields() throws Exception {
        seedTencentArticles();

        QueryResponse<List<Map<String, String>>> firstPage = queryController.queryByKw("新闻检索助手", 1, 2);
        QueryResponse<List<Map<String, String>>> secondPage = queryController.queryByKw("新闻检索助手", 2, 2);

        Assertions.assertAll(
                () -> Assertions.assertTrue(firstPage.isSuccess()),
                () -> Assertions.assertTrue(secondPage.isSuccess()),
                () -> Assertions.assertEquals(2, firstPage.getData().size()),
                () -> Assertions.assertEquals(1, secondPage.getData().size()),
                () -> Assertions.assertNotEquals(firstPage.getData().get(0).get(ArticleIdxFields.ID), secondPage.getData().get(0).get(ArticleIdxFields.ID)),
                () -> Assertions.assertEquals("tencent-news", firstPage.getData().get(0).get(ArticleIdxFields.SOURCE)),
                () -> Assertions.assertNotNull(firstPage.getData().get(0).get(ArticleIdxFields.SOURCE_URL)),
                () -> Assertions.assertNotNull(firstPage.getData().get(0).get(ArticleIdxFields.TIME)),
                () -> Assertions.assertNotNull(firstPage.getData().get(0).get(ArticleIdxFields.AUTHOR)),
                () -> Assertions.assertNotNull(firstPage.getData().get(0).get(ArticleIdxFields.BYLINE))
        );
    }

    @Test
    public void repeatIngestOfSameCanonicalTencentArticleKeepsSingleLuceneDocument() throws Exception {
        Blog first = buildTencentArticle(
                "http://new.qq.com/rain/a/20240318a01ab000/?from=fixture",
                "腾讯新闻推出新闻检索助手试点",
                "北京信息科技大学在课堂上试点新闻检索助手，帮助学生整理新闻语料。\n教师在信息检索课程中演示了基于Lucene的关键词检索与摘要生成。\n",
                Instant.parse("2024-03-18T09:30:00Z"),
                Instant.parse("2024-03-18T10:00:00Z"));
        Blog second = buildTencentArticle(
                "https://news.qq.com/rain/a/20240318A01AB000#fragment",
                "腾讯新闻推出新闻检索助手试点",
                "北京信息科技大学在课堂上试点新闻检索助手，帮助学生整理新闻语料。\n教师在信息检索课程中演示了基于Lucene的关键词检索与摘要生成。\n",
                Instant.parse("2024-03-18T09:30:00Z"),
                Instant.parse("2024-03-18T10:05:00Z"));

        int beforeDocCount;
        try (Directory beforeDirectory = new MMapDirectory(Path.of(ARTICLE_TEST_HOME, "idx"));
             DirectoryReader beforeReader = DirectoryReader.open(beforeDirectory)) {
            beforeDocCount = beforeReader.numDocs();
        }

        Assertions.assertTrue(idxService.addDocument(ArticleIdxFields.ID, first.getDocId(), LucenePipeline.toDoc(first)));
        Assertions.assertTrue(idxService.addDocument(ArticleIdxFields.ID, second.getDocId(), LucenePipeline.toDoc(second)));

        try (Directory directory = new MMapDirectory(Path.of(ARTICLE_TEST_HOME, "idx"));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs docs = searcher.search(new TermQuery(new Term(ArticleIdxFields.ID, first.getDocId())), 10);
            Assertions.assertAll(
                    () -> Assertions.assertEquals(first.getDocId(), second.getDocId()),
                    () -> Assertions.assertEquals(1, docs.totalHits.value),
                    () -> Assertions.assertEquals(beforeDocCount, reader.numDocs()),
                    () -> Assertions.assertEquals("https://news.qq.com/rain/a/20240318A01AB000", searcher.doc(docs.scoreDocs[0].doc).get(ArticleIdxFields.SOURCE_URL))
            );
        }
    }


    private static Document toDoc(School school){
        Document doc = new Document();
        //添加ID字段
        doc.add(new StringField(ID.name(), String.valueOf(school.getId()), Field.Store.YES));
        //添加名称字段
        doc.add(new TextField(NAME.name(), school.getName(), Field.Store.YES));
        //添加电子邮件字段
        if(!StringUtil.isEmpty(school.getEmail())){
            doc.add(new StoredField(EMAIL.name(), school.getEmail()));
        }
        //添加校长姓名字段
        if(!StringUtil.isEmpty(school.getPrincipal())){
            doc.add(new TextField(PRINCIPAL.name(), school.getPrincipal(), Field.Store.YES));
        }
        //添加电子邮件字段
        if(!StringUtil.isEmpty(school.getWebsite())){
            doc.add(new StoredField(WEBSITE.name(), school.getWebsite()));
        }
        //添加所在城市字段
        if(!StringUtil.isEmpty(school.getCity())){
            doc.add(new TextField(CITY.name(), school.getCity(), Field.Store.YES));
        }
        //添加学校类型字段
        if(!StringUtil.isEmpty(school.getSchool_type())){
            doc.add(new StringField(TYPE.name(), school.getSchool_type(), Field.Store.YES));
        }
        //添加学校位置
        if(!StringUtil.isEmpty(school.getLatitude())&&!StringUtil.isEmpty(school.getLongitude())){
            doc.add(new LatLonPoint(LOCATION.name(),
                    Double.parseDouble(school.getLatitude()),
                    Double.parseDouble(school.getLongitude())));
            doc.add(new LatLonDocValuesField(LOCATION.name(),
                    Double.parseDouble(school.getLatitude()),
                    Double.parseDouble(school.getLongitude())));
            doc.add(new StoredField(LAT.name(), school.getLatitude()));
            doc.add(new StoredField(LON.name(), school.getLongitude()));
        }
        //添加学生人数
        if(!StringUtil.isEmpty(school.getTotal_school_roll())){
            doc.add(new IntPoint(ROLL.name(), Integer.parseInt(school.getTotal_school_roll())));
            doc.add(new StoredField(ROLL.name(), Integer.parseInt(school.getTotal_school_roll())));
            doc.add(new NumericDocValuesField(ROLL.name(), Integer.parseInt(school.getTotal_school_roll())));
        }
        return doc;
    }

    @AfterAll
    public static void destroy(){
        //测试完成后，清理索引目录
        FileUtils.deleteSubDirs(TEST_HOME);
        FileUtils.deleteSubDirs(ARTICLE_TEST_HOME);
        log.info("完成索引目录清理");
    }

    private void seedTencentArticles() throws Exception {
        for (Blog article : buildTencentArticles()) {
            Assertions.assertTrue(idxService.addDocument(ArticleIdxFields.ID, article.getDocId(), LucenePipeline.toDoc(article)));
        }
    }

    private List<Blog> buildTencentArticles() throws Exception {
        Files.readString(new ClassPathResource(ARTICLE_FIXTURE).getFile().toPath());
        Blog first = buildTencentArticle(
                "https://news.qq.com/rain/a/20240318A01AB000",
                "腾讯新闻推出新闻检索助手试点",
                "北京信息科技大学在课堂上试点新闻检索助手，帮助学生整理新闻语料。\n教师在信息检索课程中演示了基于Lucene的关键词检索与摘要生成。\n项目组表示会继续完善本地优先的教学实验环境。\n",
                Instant.parse("2024-03-18T09:30:00Z"),
                Instant.parse("2024-03-18T10:00:00Z"));

        Blog second = buildTencentArticle(
                "https://news.qq.com/rain/a/20240319A01CD000",
                "新闻检索助手扩展到信息检索实验课",
                "新闻检索助手继续服务北京信息科技大学的信息检索实验课，帮助学生完成中文关键词检索。\n课程团队继续完善Lucene索引。\n",
                Instant.parse("2024-03-19T09:30:00Z"),
                Instant.parse("2024-03-19T10:00:00Z"));

        Blog third = buildTencentArticle(
                "https://news.qq.com/rain/a/20240320A01EF000",
                "北京信息科技大学完善新闻检索助手体验",
                "学校继续完善新闻检索助手体验，加入中文检索分页与结果展示元数据。\n信息检索课程学生可以直接查询文章来源与作者。\n",
                Instant.parse("2024-03-20T09:30:00Z"),
                Instant.parse("2024-03-20T10:00:00Z"));

        return List.of(first, second, third);
    }

    private Blog buildTencentArticle(String sourceUrl,
                                     String title,
                                     String body,
                                     Instant publishTime,
                                     Instant crawlTime) {
        Blog blog = new Blog();
        blog.setSource("tencent-news");
        blog.setSourceUrl(sourceUrl);
        blog.setTitle(title);
        blog.setBody(body);
        blog.setPublishTime(publishTime);
        blog.setCrawlTime(crawlTime);
        blog.setSection("tech");
        blog.setAuthor("腾讯教育");
        blog.setByline("腾讯教育");
        blog.ensureCanonicalIdentity();
        return blog;
    }

    /**
     * 分页查询
     * @param query    查询条件
     * @param sorts    用于排序的字段，可以为空
     * @param pageNo   页码
     * @param pageSize 每页的记录个数
     * @return 检索结果
     */
    private List<Map.Entry<School, Float>> pagedSearch(Query query, List<SortField> sorts, int pageNo, int pageSize) throws IOException {
        Assertions.assertTrue(pageNo>=1);
        Assertions.assertTrue(pageSize>=1);
        int start = (pageNo-1)*pageSize;
        int end = pageNo*pageSize;
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(iWriter));
        TopDocs topDocs;
        if(sorts==null||sorts.isEmpty()){
            topDocs = searcher.search(query, end);
        }else{
            topDocs = searcher.search(query, end, new Sort(sorts.toArray(new SortField[]{})));
        }
        List<Map.Entry<School, Float>> schoolList = new ArrayList<>();
        for (int i = start; i < end && i < topDocs.scoreDocs.length; i++){
            ScoreDoc scoreDoc = topDocs.scoreDocs[i];
            schoolList.add(new AbstractMap.SimpleEntry<>(fromDoc(searcher.doc(scoreDoc.doc)), scoreDoc.score));
        }
        return schoolList;
    }

    /**
     * 在索引上执行检索
     * @param query 查询条件
     * @param sorts 用于排序的字段，可以为空
     * @param size  返回的结果数
     * @return 检索结果列表，以School对象和评分组成的二元组形式返回
     * @throws IOException 如果检索的过程中出现索引IO错误，则抛出异常
     */
    private List<Map.Entry<School, Float>> search(Query query,List<SortField> sorts, int size) throws IOException {
        IndexReader iReader = DirectoryReader.open(iWriter);
        IndexSearcher searcher = new IndexSearcher(iReader);
        TopDocs topDocs;
        if(sorts==null||sorts.isEmpty()){
            topDocs = searcher.search(query, size);
        }else{
            topDocs = searcher.search(query, size, new Sort(sorts.toArray(new SortField[]{})));
        }
        List<Map.Entry<School, Float>> schoolList = new ArrayList<>();
        for(ScoreDoc scoreDoc : topDocs.scoreDocs){
            schoolList.add(new AbstractMap.SimpleEntry<>(fromDoc(iReader.document(scoreDoc.doc)), scoreDoc.score));
        }
        return schoolList;
    }

    private School fromDoc(Document doc){
        School school = new School();
        school.setId(Integer.parseInt(doc.get(ID.name())));
        school.setName(doc.get(NAME.name()));
        school.setEmail(doc.get(EMAIL.name()));
        school.setPrincipal(doc.get(PRINCIPAL.name()));
        school.setWebsite(doc.get(WEBSITE.name()));
        school.setCity(doc.get(CITY.name()));
        school.setSchool_type(doc.get(TYPE.name()));
        school.setLatitude(doc.get(LAT.name()));
        school.setLongitude(doc.get(LON.name()));
        school.setTotal_school_roll(doc.get(ROLL.name()));
        return school;
    }
}
