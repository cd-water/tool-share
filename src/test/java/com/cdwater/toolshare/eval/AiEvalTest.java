package com.cdwater.toolshare.eval;

import com.cdwater.toolshare.AbstractIntegrationTest;
import com.cdwater.toolshare.module.ai.AiChatVO;
import com.cdwater.toolshare.module.ai.AiService;
import com.cdwater.toolshare.module.knowledge.KnowledgeDoc;
import com.cdwater.toolshare.module.knowledge.KnowledgeDocMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 固定评测集（issue #22）：用测试库种子知识 + 确定性关键词检索跑一遍固定问题，
 * 输出检索命中率与引用准确率。低于阈值时测试失败（Maven 返回非 0 退出码）。
 *
 * <p>本测试以 {@code @Tag("ai-eval")} 标记，默认 surefire 排除；只能通过
 * {@code ./ops/ai-eval.sh} 或 {@code ./mvnw -Pai-eval test} 运行，不进入默认测试门禁。
 * 测试基座排除了 Qdrant 与 OpenAI 自动配置，因此检索必然走关键词降级路径，
 * 回答为确定性的知识库兜底文案，不访问任何外部服务。
 *
 * <p>局限：无大模型时兜底文案会引用全部检索来源，引用准确率等价于「检索来源的精确率」；
 * 真实模型的引用忠实度仍需在联调环境人工抽查。评测问题也偏向关键词检索可命中的措辞。
 */
@Tag("ai-eval")
@SpringBootTest
class AiEvalTest extends AbstractIntegrationTest {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final double DEFAULT_MIN_HIT_RATE = 0.8;
    private static final double DEFAULT_MIN_CITATION_ACCURACY = 0.8;

    @Autowired
    private AiService aiService;

    @Autowired
    private KnowledgeDocMapper knowledgeDocMapper;

    @Test
    void fixed_eval_set_meets_hit_and_citation_thresholds() throws IOException {
        List<String> docTitles = knowledgeDocMapper.selectList(null).stream()
                .map(KnowledgeDoc::getTitle).toList();
        assertTrue(docTitles.size() >= 3, "评测依赖测试库种子知识库，当前文档: " + docTitles);

        JsonNode root = new ObjectMapper().readTree(loadEvalSet());
        double totalHit = 0;
        double totalCitation = 0;
        int caseCount = 0;
        int failedCases = 0;

        System.out.println("\n=== AI 评测结果 ===");
        for (JsonNode caseNode : root.get("cases")) {
            String id = caseNode.get("id").asString();
            String question = caseNode.get("question").asString();
            List<ExpectedSource> expected = expectedSources(caseNode.get("expectedSources"));

            AiChatVO reply = aiService.chat(question);
            List<AiChatVO.SourceVO> sources = reply.getSources();
            double hit = hitRate(expected, sources);
            double citation = citationAccuracy(expected, sources, reply.getAnswer());
            totalHit += hit;
            totalCitation += citation;
            caseCount++;
            if (hit < 1.0 || citation < 1.0) {
                failedCases++;
            }
            System.out.printf(Locale.ROOT, "[%s] hit=%.2f citation=%.2f 问题=%s 返回来源=%s%n",
                    id, hit, citation, question, titles(sources));
        }

        double hitRate = totalHit / caseCount;
        double citationAccuracy = totalCitation / caseCount;
        double minHitRate = threshold("ai.eval.min-hit-rate", "AI_EVAL_MIN_HIT_RATE", DEFAULT_MIN_HIT_RATE);
        double minCitation = threshold("ai.eval.min-citation-accuracy",
                "AI_EVAL_MIN_CITATION_ACCURACY", DEFAULT_MIN_CITATION_ACCURACY);

        System.out.printf(Locale.ROOT, "用例数: %d，非满分用例: %d%n", caseCount, failedCases);
        System.out.printf(Locale.ROOT, "检索命中率(hit@k): %.3f（阈值 %.3f）%n", hitRate, minHitRate);
        System.out.printf(Locale.ROOT, "引用准确率: %.3f（阈值 %.3f）%n", citationAccuracy, minCitation);
        System.out.println("====================\n");

        assertTrue(hitRate >= minHitRate,
                String.format(Locale.ROOT, "检索命中率 %.3f 低于阈值 %.3f", hitRate, minHitRate));
        assertTrue(citationAccuracy >= minCitation,
                String.format(Locale.ROOT, "引用准确率 %.3f 低于阈值 %.3f", citationAccuracy, minCitation));
    }

    /** hit@k：期望来源中出现在返回来源列表里的比例。 */
    private static double hitRate(List<ExpectedSource> expected, List<AiChatVO.SourceVO> sources) {
        if (expected.isEmpty()) {
            return 1.0;
        }
        long matched = expected.stream().filter(e -> e.matches(sources)).count();
        return (double) matched / expected.size();
    }

    /** 引用准确率：回答正文标注的来源中，属于期望来源的比例。 */
    private static double citationAccuracy(List<ExpectedSource> expected,
                                           List<AiChatVO.SourceVO> sources, String answer) {
        List<AiChatVO.SourceVO> cited = citedSources(answer, sources);
        if (cited.isEmpty()) {
            return expected.isEmpty() ? 1.0 : 0.0;
        }
        long correct = cited.stream().filter(source -> expected.stream().anyMatch(e -> e.matches(source))).count();
        return (double) correct / cited.size();
    }

    /** 将回答正文中的 [n] 映射回本次检索返回的来源；越界编号不计入已引用来源。 */
    private static List<AiChatVO.SourceVO> citedSources(String answer, List<AiChatVO.SourceVO> sources) {
        List<AiChatVO.SourceVO> cited = new ArrayList<>();
        if (answer == null) {
            return cited;
        }
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            if (index >= 0 && index < sources.size()) {
                cited.add(sources.get(index));
            }
        }
        return cited;
    }

    private static List<ExpectedSource> expectedSources(JsonNode nodes) {
        List<ExpectedSource> expected = new ArrayList<>();
        for (JsonNode node : nodes) {
            String contains = node.has("contains") ? node.get("contains").asString() : null;
            expected.add(new ExpectedSource(node.get("title").asString(), contains));
        }
        return expected;
    }

    private static List<String> titles(List<AiChatVO.SourceVO> sources) {
        return sources.stream().map(AiChatVO.SourceVO::getTitle).toList();
    }

    private static String loadEvalSet() throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), "docs", "ai", "eval", "questions.json");
        assertTrue(Files.exists(path), "评测集不存在: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    private static double threshold(String property, String env, double fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }

    private record ExpectedSource(String title, String contains) {
        boolean matches(AiChatVO.SourceVO source) {
            if (source == null || !title.equals(source.getTitle())) {
                return false;
            }
            return contains == null || (source.getSnippet() != null && source.getSnippet().contains(contains));
        }

        boolean matches(List<AiChatVO.SourceVO> sources) {
            return sources.stream().anyMatch(this::matches);
        }
    }
}
