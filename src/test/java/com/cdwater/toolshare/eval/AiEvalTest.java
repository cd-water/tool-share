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

    // 引用标记形如 [1]、[2]，数字对应本次检索返回来源的序号。
    // 使用预编译 Pattern 避免在逐用例解析时重复编译。
    // 只匹配方括号内的数字，不误伤正文中的普通数字。
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    // 默认检索命中率阈值：至少 80% 的期望来源要出现在返回结果中。
    private static final double DEFAULT_MIN_HIT_RATE = 0.8;
    // 默认引用准确率阈值：回答引用的来源中至少 80% 属于期望来源。
    private static final double DEFAULT_MIN_CITATION_ACCURACY = 0.8;

    @Autowired
    // 被测 AI 服务；测试基座排除了外部模型与向量库，走确定性降级路径。
    // 因此评测结果可重复，不依赖网络与模型随机性。
    private AiService aiService;

    @Autowired
    // 用于确认测试库已加载种子知识文档，评测集依赖这些文档才能命中。
    // 若文档缺失，后续命中率断言会失去意义。
    private KnowledgeDocMapper knowledgeDocMapper;

    /**
     * 场景：用固定评测集逐条调用 AI 服务，统计检索命中率与引用准确率。
     * 前置条件：测试库至少加载 3 篇种子知识文档，评测集文件位于 docs/ai/eval/questions.json。
     * 断言目标：两项指标分别不低于可配置阈值，否则测试失败并阻断发布。
     * 无外部模型时检索走关键词降级路径，结果确定且可重复。
     * 该用例以 ai-eval 标签隔离，不进入默认测试门禁。
     * 评测集变更需与代码一起评审，避免通过改题降低标准。
     */
    @Test
    void fixed_eval_set_meets_hit_and_citation_thresholds() throws IOException {
        // 读取全部种子知识文档标题，作为评测环境的前置检查。
        // 只取标题字段，减少无意义的数据库传输。
        List<String> docTitles = knowledgeDocMapper.selectList(null).stream()
                .map(KnowledgeDoc::getTitle).toList();
        // 至少 3 篇文档才能覆盖评测集中的多来源问题。
        // 失败信息直接列出当前标题，便于定位种子数据未加载的问题。
        // 这是环境前置断言，不属于评测指标本身。
        // 文档数量不足时继续执行只会得到误导性的低命中率。
        // 种子文档是评测的检索语料，缺失会让命中率无意义地接近 0。
        // 该检查先于任何 AI 调用执行，避免把环境问题误判为模型问题。
        // 文档数量阈值与评测集覆盖的主题数相匹配。
        assertTrue(docTitles.size() >= 3, "评测依赖测试库种子知识库，当前文档: " + docTitles);

        // 从 docs/ai/eval/questions.json 读取固定评测集。
        // 评测集随代码版本管理，保证不同环境跑的是同一组问题。
        // 解析成 JsonNode 后可逐用例遍历，无需定义额外 DTO。
        // 评测集根节点包含 cases 数组，每个元素是一条独立问题。
        // 读取失败会直接抛出 IOException，测试不会带着空集合继续。
        // 评测集内容不依赖数据库，只依赖仓库文件。
        JsonNode root = new ObjectMapper().readTree(loadEvalSet());
        // 累加每个用例的 hit@k，最后除以用例数得到平均命中率。
        // 使用 double 保留小数，阈值比较更精确。
        // 初始值为 0，逐用例累加。
        double totalHit = 0;
        // 累加每个用例的引用准确率。
        double totalCitation = 0;
        // 统计实际执行的用例数，作为计算平均值的分母。
        int caseCount = 0;
        // 记录未达到满分的用例数，用于输出可读的诊断信息。
        // 该计数不参与阈值判断，只帮助定位问题。
        int failedCases = 0;

        // 输出分隔标题，便于在 CI 日志中快速定位评测结果。
        System.out.println("\n=== AI 评测结果 ===");
        // 逐条执行评测集中的问题。
        // 每个用例包含 id、question 与 expectedSources 三个字段。
        // 用例之间互不依赖，顺序不影响指标。
        // 单条用例失败不会中断整体评测，最后统一比较阈值。
        // 遍历顺序与 JSON 中的书写顺序一致，日志因此可稳定对比。
        // 每条用例独立调用 AI 服务，不使用跨用例缓存。
        // 这样可以避免前一条回答影响后一条的检索结果。
        for (JsonNode caseNode : root.get("cases")) {
            // 用例 ID 用于在输出与失败报告中定位具体问题。
            String id = caseNode.get("id").asString();
            // 问题文本直接送入 AI 服务，模拟真实用户提问。
            String question = caseNode.get("question").asString();
            // 解析期望来源，支持可选的 contains 片段约束。
            List<ExpectedSource> expected = expectedSources(caseNode.get("expectedSources"));

            // 调用 AI 服务获取回答与检索来源。
            // 无外部模型时返回确定性的知识库兜底文案。
            // 评测只关心来源列表与引用标记，不评判自然语言质量。
            // chat 返回回答正文与来源列表，是评测的两个观察对象。
            // 无外部模型时走关键词检索 + 兜底文案，结果确定。
            // 若未来接入真实模型，本用例仍可作为回归基线。
            AiChatVO reply = aiService.chat(question);
            // 本次检索返回的来源列表，顺序对应回答中的 [n] 编号。
            List<AiChatVO.SourceVO> sources = reply.getSources();
            // 计算当前用例的检索命中率。
            // 命中率只关心「该找到的来源是否找到」，不评价排序。
            // 计算逻辑抽到私有方法，便于单独演进指标定义。
            double hit = hitRate(expected, sources);
            // 计算当前用例的引用准确率。
            // 需要同时传入期望来源、实际来源与回答正文。
            // 引用准确率检查回答正文中的 [n] 是否指向期望来源。
            // 它衡量的是引用忠实度，而不是回答流畅度。
            // 与命中率分开统计，可定位是检索问题还是生成问题。
            double citation = citationAccuracy(expected, sources, reply.getAnswer());
            // 累加命中率，供循环结束后求平均。
            totalHit += hit;
            // 累加引用准确率。
            totalCitation += citation;
            // 每成功执行一条用例，分母加一。
            caseCount++;
            // 任一指标未满分即视为非满分用例。
            // 这里只做统计，不立即失败，以便一次输出全部问题。
            // 满分标准较严格：任一指标未达 1.0 就记录为非满分。
            // 该计数只用于观察，不直接决定测试成败。
            // 真正的门禁是下方的平均值阈值。
            if (hit < 1.0 || citation < 1.0) {
                // 记录非满分用例数量，便于判断整体质量分布。
                failedCases++;
            }
            // 每条用例输出一行：ID、两项指标、问题原文与返回来源标题。
            // 使用 Locale.ROOT 保证小数点格式在 CI 环境稳定。
            // 该日志是评测结果的主要人工排查入口。
            // 单行日志包含定位所需的全部信息，避免翻查多条输出。
            // 返回来源只打印标题，保持日志紧凑可读。
            // 该输出在 CI 中可被人工快速扫描。
            System.out.printf(Locale.ROOT, "[%s] hit=%.2f citation=%.2f 问题=%s 返回来源=%s%n",
                    id, hit, citation, question, titles(sources));
        }

        // 平均检索命中率，作为最终阈值比较的左值。
        // 使用算术平均而非加权，保证每个评测问题权重相同。
        // caseCount 至少为 1，评测集为空时会在解析阶段暴露。
        // 平均值与阈值比较，避免单条偶然失败直接阻断门禁。
        double hitRate = totalHit / caseCount;
        // 平均引用准确率。
        double citationAccuracy = totalCitation / caseCount;
        // 允许通过系统属性或环境变量覆盖阈值，便于联调时放宽。
        // 未配置时使用代码中的默认值。
        // 阈值可调但评测集固定，避免既改问题又改标准。
        double minHitRate = threshold("ai.eval.min-hit-rate", "AI_EVAL_MIN_HIT_RATE", DEFAULT_MIN_HIT_RATE);
        // 引用准确率阈值同样支持外部覆盖。
        // 两个阈值独立配置，可分别观察检索与引用质量。
        double minCitation = threshold("ai.eval.min-citation-accuracy",
                "AI_EVAL_MIN_CITATION_ACCURACY", DEFAULT_MIN_CITATION_ACCURACY);

        // 输出用例总数与未满分数量，便于快速判断问题规模。
        System.out.printf(Locale.ROOT, "用例数: %d，非满分用例: %d%n", caseCount, failedCases);
        // 输出平均命中率与阈值，失败时可直接对照。
        System.out.printf(Locale.ROOT, "检索命中率(hit@k): %.3f（阈值 %.3f）%n", hitRate, minHitRate);
        // 输出平均引用准确率与阈值。
        System.out.printf(Locale.ROOT, "引用准确率: %.3f（阈值 %.3f）%n", citationAccuracy, minCitation);
        // 结束分隔线，方便在日志中截取本次评测结果。
        System.out.println("====================\n");

        // 命中率低于阈值时测试失败，Maven 返回非 0 退出码。
        // 失败信息包含实际值与阈值，便于快速定位退化。
        // 该断言是评测集进入发布门禁的核心。
        // 命中率门禁：低于阈值说明检索退化或语料缺失。
        // 失败信息使用格式化字符串，保留三位小数便于对比。
        // 该断言使评测结果能够阻断发布流水线。
        assertTrue(hitRate >= minHitRate,
                String.format(Locale.ROOT, "检索命中率 %.3f 低于阈值 %.3f", hitRate, minHitRate));
        // 引用准确率同样必须达到阈值。
        // 引用错误会误导用户，因此与命中率分开把关。
        // 两项都通过才说明检索与引用链路同时健康。
        // 引用准确率门禁：防止回答引用无关来源误导用户。
        // 与命中率使用不同的阈值配置，可独立放宽或收紧。
        // 两项同时通过才认为评测达标。
        assertTrue(citationAccuracy >= minCitation,
                String.format(Locale.ROOT, "引用准确率 %.3f 低于阈值 %.3f", citationAccuracy, minCitation));
    }

    /** hit@k：期望来源中出现在返回来源列表里的比例。 */
    private static double hitRate(List<ExpectedSource> expected, List<AiChatVO.SourceVO> sources) {
        // 没有期望来源的用例视为满分，避免空集合导致除零。
        // 这类用例通常只验证回答不臆造来源。
        if (expected.isEmpty()) {
            return 1.0;
        }
        // 逐项检查期望来源是否出现在本次返回结果中。
        // contains 片段用于区分同名文档的不同段落。
        // 对每个期望来源检查是否在实际来源中出现。
        // 同一实际来源可以匹配多个期望项，但计数以期望项为准。
        // 该方法不修改输入集合。
        long matched = expected.stream().filter(e -> e.matches(sources)).count();
        // 命中比例 = 匹配数 / 期望数，取值范围 0 到 1。
        return (double) matched / expected.size();
    }

    /** 引用准确率：回答正文标注的来源中，属于期望来源的比例。 */
    private static double citationAccuracy(List<ExpectedSource> expected,
                                           List<AiChatVO.SourceVO> sources, String answer) {
        // 先从回答正文解析出被引用的来源。
        // 引用编号越界时不会计入。
        List<AiChatVO.SourceVO> cited = citedSources(answer, sources);
        // 回答未引用任何来源时：若本就没有期望来源则视为正确，否则判零分。
        // 这样可以区分「无需引用」与「该引用却未引用」。
        if (cited.isEmpty()) {
            return expected.isEmpty() ? 1.0 : 0.0;
        }
        // 统计被引用来源中属于期望集合的数量。
        long correct = cited.stream().filter(source -> expected.stream().anyMatch(e -> e.matches(source))).count();
        return (double) correct / cited.size();
    }

    /** 将回答正文中的 [n] 映射回本次检索返回的来源；越界编号不计入已引用来源。 */
    private static List<AiChatVO.SourceVO> citedSources(String answer, List<AiChatVO.SourceVO> sources) {
        // 保存回答中实际引用到的来源。
        List<AiChatVO.SourceVO> cited = new ArrayList<>();
        // 空回答没有可解析的引用标记，返回空列表。
        if (answer == null) {
            return cited;
        }
        // 在回答正文中查找所有 [n] 形式的引用标记。
        Matcher matcher = CITATION.matcher(answer);
        // 逐个处理引用标记，允许同一来源被多次引用。
        while (matcher.find()) {
            // 引用编号从 1 开始，转换为来源列表的零基下标。
            int index = Integer.parseInt(matcher.group(1)) - 1;
            // 越界编号可能来自模型幻觉，直接忽略而不抛异常。
            if (index >= 0 && index < sources.size()) {
                // 记录有效引用对应的来源对象。
                cited.add(sources.get(index));
            }
        }
        return cited;
    }

    /**
     * 辅助方法：把评测集中的期望来源节点转换为强类型对象。
     *
     * <p>每个节点必须包含 title；contains 为可选的片段约束，
     * 用于区分同名文档的不同段落，避免仅凭标题误判命中。
     *
     * @param nodes 评测集里 expectedSources 数组
     * @return 期望来源列表，顺序与评测集一致
     */
    private static List<ExpectedSource> expectedSources(JsonNode nodes) {
        // 按评测集顺序构建期望来源列表。
        List<ExpectedSource> expected = new ArrayList<>();
        // 逐个读取来源节点。
        for (JsonNode node : nodes) {
            // contains 缺省时表示只校验标题，不限制片段内容。
            String contains = node.has("contains") ? node.get("contains").asString() : null;
            // 标题为必填字段，缺失会在解析时暴露评测集格式问题。
            expected.add(new ExpectedSource(node.get("title").asString(), contains));
        }
        return expected;
    }

    /**
     * 辅助方法：提取来源标题，便于在评测日志中紧凑展示。
     *
     * @param sources 本次检索返回的来源
     * @return 与来源顺序一致的标题列表
     */
    private static List<String> titles(List<AiChatVO.SourceVO> sources) {
        // 只保留标题，避免日志被摘要与元数据淹没。
        return sources.stream().map(AiChatVO.SourceVO::getTitle).toList();
    }

    /**
     * 辅助方法：从仓库固定路径读取评测集 JSON。
     *
     * <p>路径相对于工作目录，保证在 Maven 与 IDE 中运行结果一致。
     * 文件缺失时直接断言失败，提示评测集未随代码发布。
     *
     * @return 评测集 JSON 文本
     * @throws IOException 读取文件失败
     */
    private static String loadEvalSet() throws IOException {
        // 使用 user.dir 而非 classpath，确保评测集与代码版本同步。
        // 该文件是评测标准的一部分，需纳入版本控制。
        Path path = Path.of(System.getProperty("user.dir"), "docs", "ai", "eval", "questions.json");
        // 文件缺失属于环境问题，直接给出绝对路径便于排查。
        assertTrue(Files.exists(path), "评测集不存在: " + path.toAbsolutePath());
        // 使用 UTF-8 读取，保证中文问题与期望片段不乱码。
        return Files.readString(path);
    }

    /**
     * 辅助方法：读取可覆盖的评测阈值。
     *
     * <p>优先级为系统属性 > 环境变量 > 代码默认值，
     * 便于本地调试与 CI 使用不同严格度，而无需修改代码。
     *
     * @param property 系统属性名
     * @param env 环境变量名
     * @param fallback 未配置时的默认阈值
     * @return 实际使用的阈值
     */
    private static double threshold(String property, String env, double fallback) {
        // 系统属性优先级最高，适合单次命令行覆盖。
        String value = System.getProperty(property);
        // 系统属性未配置或为空时回退到环境变量。
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        // 两者都为空时使用默认值；否则解析为 double。
        // 非法数字会直接抛异常，避免静默使用错误阈值。
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }

    /**
     * 期望来源：标题必填，contains 为可选的片段约束。
     *
     * <p>命中判定同时考虑标题与片段，降低同名文档造成的误判。
     * 使用 record 保持不可变，评测过程中不会被意外修改。
     */
    private record ExpectedSource(String title, String contains) {
        // 单个来源的匹配规则。
        boolean matches(AiChatVO.SourceVO source) {
            // 空来源或标题不同直接判定不匹配。
            if (source == null || !title.equals(source.getTitle())) {
                return false;
            }
            // 未配置片段约束时，标题相同即视为命中。
            // 配置了约束则还要求摘要包含指定片段。
            return contains == null || (source.getSnippet() != null && source.getSnippet().contains(contains));
        }

        // 判断期望来源是否出现在整批实际来源中。
        boolean matches(List<AiChatVO.SourceVO> sources) {
            // 任一来源匹配即可，不要求顺序一致。
            return sources.stream().anyMatch(this::matches);
        }
    }
}
