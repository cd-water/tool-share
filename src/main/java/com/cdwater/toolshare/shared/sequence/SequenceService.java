package com.cdwater.toolshare.shared.sequence;

import com.cdwater.toolshare.shared.enums.ToolCategory;
import com.cdwater.toolshare.shared.web.BizException;
import com.cdwater.toolshare.shared.web.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis 原子序号：工具编码、租赁单号、报修单号。
 * <p>序号服务不可用时明确失败并提示重试，不回退随机数（随机数会破坏唯一性）。
 * <p>启动校准时只依赖 shared 内的 {@link JdbcTemplate}，不反向依赖各业务模块的 Mapper。
 *
 * <p>三类编号规则：工具编码按分类前缀、租赁单与报修单按日期前缀，序号
 * 均为四位补零。Redis INCR 保证同一 key 上的并发自增不会重复；不同
 * 日期或分类使用不同 key，天然隔离。</p>
 *
 * <p>启动校准读取数据库中已有最大编号，把 Redis 计数器抬高到该值，
 * 避免种子数据或历史数据导致新编号冲突。校准只升不降，不会重置
 * 正在使用的序列。</p>
 *
 * <p>序号耗尽四位补零后仍可继续增长（例如 10000），编号不会截断；
 * 四位只是展示格式，不是硬上限。</p>
 *
 * <p>为什么不用数据库自增：工具编码与单号需要带业务前缀，且要按分类/
 * 日期分段；Redis 原子自增比数据库序列表更轻量，也不会成为写热点。</p>
 *
 * <p>Redis 数据丢失时，下次启动校准会从数据库最大编号重新抬高计数器；
 * 若丢失发生在运行期间，可能产生重复编号，因此生产环境应开启 Redis
 * 持久化或使用高可用部署。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceService {

    /** 日期格式：yyyyMMdd，用于按天生成租赁单与报修单号。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Redis 客户端，提供原子 INCR。 */
    private final StringRedisTemplate redis;

    /** 轻量 JDBC 入口，仅用于启动校准读取最大编号。 */
    private final JdbcTemplate jdbc;

    /**
     * 启动时按数据库已有最大编号校准 Redis 序号，避免种子数据导致编码冲突。
     *
     * <p>工具按每个分类分别校准；租赁与报修按当天日期校准。任何异常
     * 都只记录错误日志，不阻止启动——序号服务会在首次使用时重试。</p>
     */
    @PostConstruct
    public void initSequence() {
        try {
            for (ToolCategory category : ToolCategory.values()) {
                // 工具编码格式：SQ-G-{分类码}-{四位序号}
                raiseTo("tool:seq:" + category.code(), maxCode("t_tool", "code",
                        "SQ-G-" + category.code() + "-"));
            }
            String day = LocalDate.now().format(DAY);
            // 租赁单号格式：SQ-ZY-{yyyyMMdd}-{四位序号}
            raiseTo("rental:seq:" + day, maxCode("t_rental", "order_no", "SQ-ZY-" + day + "-"));
            // 报修单号格式：SQ-BX-{yyyyMMdd}-{四位序号}
            raiseTo("damage:seq:" + day, maxCode("t_damage_report", "report_no", "SQ-BX-" + day + "-"));
        } catch (Exception e) {
            log.error("初始化 Redis 序号失败，单号生成可能在恢复前不可用，请检查 Redis 后重试", e);
        }
    }

    /**
     * 把 Redis 计数器抬高到至少给定值。
     *
     * <p>只升不降：已有值更大时保持不变，避免把正在使用的序列重置。
     * min 小于等于 0 表示数据库中还没有对应编号，无需处理。</p>
     *
     * @param key Redis 序号 key
     * @param min 最小可用序号
     */
    private void raiseTo(String key, long min) {
        if (min <= 0) {
            // 数据库中还没有该前缀的编号，无需校准。
            return;
        }
        String current = redis.opsForValue().get(key);
        if (current == null || Long.parseLong(current) < min) {
            // 直接 set 而不是 INCR，保证结果是 min 而不是旧值累加。
            redis.opsForValue().set(key, String.valueOf(min));
        }
    }

    /** 按前缀取当前最大编号；SQL 标识符来自本类固定常量，不接受外部输入。 */
    private long maxCode(String table, String column, String prefix) {
        // 按列倒序取第一条，即可得到当前最大编号。
        List<String> codes = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE " + column
                        + " LIKE ? ORDER BY " + column + " DESC LIMIT 1",
                String.class, prefix + "%");
        return codes.isEmpty() ? 0 : suffix(codes.get(0));
    }

    /**
     * 提取编号最后一段的数字后缀。
     *
     * <p>编号格式固定以连字符分隔，最后一段是序号。解析失败返回 0，
     * 让调用方按“无历史编号”处理。</p>
     *
     * @param code 完整编号
     * @return 数字后缀；无法解析时返回 0
     */
    private long suffix(String code) {
        try {
            return Long.parseLong(code.substring(code.lastIndexOf('-') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 生成下一个工具编码。
     *
     * @param category 工具分类代码，例如 {@code WX}
     * @return 形如 {@code SQ-G-WX-0001} 的编码
     * @throws BizException Redis 不可用时抛出
     */
    public String nextToolCode(String category) {
        long seq = next("tool:seq:" + category);
        return String.format("SQ-G-%s-%04d", category, seq);
    }

    /**
     * 生成下一个租赁单号。
     *
     * @return 形如 {@code SQ-ZY-20260101-0001} 的单号
     * @throws BizException Redis 不可用时抛出
     */
    public String nextRentalOrderNo() {
        String day = LocalDate.now().format(DAY);
        return String.format("SQ-ZY-%s-%04d", day, next("rental:seq:" + day));
    }

    /**
     * 生成下一个报修单号。
     *
     * @return 形如 {@code SQ-BX-20260101-0001} 的单号
     * @throws BizException Redis 不可用时抛出
     */
    public String nextDamageReportNo() {
        String day = LocalDate.now().format(DAY);
        return String.format("SQ-BX-%s-%04d", day, next("damage:seq:" + day));
    }

    /**
     * 原子自增并返回新值。
     *
     * <p>Redis 返回空值或抛出异常时统一转换为 500 业务异常，明确提示
     * 重试。绝不回退到随机数或本地计数，否则会破坏编号唯一性。</p>
     *
     * @param key Redis 序号 key
     * @return 自增后的序号
     * @throws BizException 序号服务不可用时抛出
     */
    private long next(String key) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null) {
                return value;
            }
            log.error("Redis 序号自增返回空值 key={}", key);
        } catch (Exception e) {
            log.error("Redis 序号自增失败 key={}", key, e);
        }
        // 失败路径统一抛出，调用方不应拿到可能重复的编号。
        throw new BizException(ResultCode.INTERNAL_SERVER_ERROR, "编号生成失败，请稍后重试");
    }
}
