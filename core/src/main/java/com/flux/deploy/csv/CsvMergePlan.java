package com.flux.deploy.csv;

import java.nio.file.Path;
import java.util.Map;

/**
 * CSV 增量合并输入计划
 *
 * <p>由插件层在部署启动时收集（core 模块不依赖 Git4Idea，git 内容以注入方式传入）：</p>
 * <ul>
 *   <li>业务主键字典（工程内 tablefieldlist-*.csv 解析结果）</li>
 *   <li>每个"有未提交变更的 .csv 源文件" → 其 git HEAD 版内容（改动前的基线，
 *       用于算出本次到底加了哪些行 / 删了哪些行 / 改了哪些行）</li>
 * </ul>
 *
 * <p>未注入本计划的调用方（CLI patch-local、本地目标模式）自动保持原有的
 * 整份覆盖行为——增量合并的生效范围由"是否注入"天然控制，core 无需模式分支。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvMergePlan {

    /** 业务主键字典 */
    private final CsvTableKeyRegistry registry;

    /** 规范化源文件绝对路径 → git HEAD 版内容 */
    private final Map<String, String> baseContents;

    /**
     * 构造合并计划
     *
     * @param registry     业务主键字典
     * @param baseContents 规范化源文件绝对路径 → git HEAD 版内容
     * @author xumanyi
     * @date 2026-07-10
     */
    public CsvMergePlan(CsvTableKeyRegistry registry, Map<String, String> baseContents) {
        this.registry = registry;
        this.baseContents = Map.copyOf(baseContents);
    }

    /** 获取业务主键字典 */
    public CsvTableKeyRegistry getRegistry() { return registry; }

    /** 计划是否为空（没有任何 CSV 需要增量合并） */
    public boolean isEmpty() { return baseContents.isEmpty(); }

    /** 计划内 CSV 数量（日志展示用） */
    public int size() { return baseContents.size(); }

    /**
     * 查找源文件的 git HEAD 版内容
     *
     * @param sourceFile 源文件路径
     * @return HEAD 版内容；该文件不在计划内（git 查不到变更等）时返回 null
     * @author xumanyi
     * @date 2026-07-10
     */
    public String findBaseContent(Path sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        return baseContents.get(normalizePathKey(sourceFile));
    }

    /**
     * 路径规范化为跨平台一致的匹配键（绝对化 + 归一化 + 正斜杠）
     *
     * <p>收集侧与消费侧必须使用同一规范化规则，否则匹配失败会静默退化成整份覆盖。</p>
     *
     * @param path 任意路径
     * @return 规范化键
     * @author xumanyi
     * @date 2026-07-10
     */
    public static String normalizePathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
