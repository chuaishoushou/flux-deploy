package com.flux.deploy.csv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * initdata 表业务主键字典
 *
 * <p>数据来源为 FLUX 工程内的 {@code tablefieldlist-*.csv}（数据库表字段清单，
 * 每行描述"某表的某字段"，{@code keyFlag=Y} 标记主键成员，字段名即 initdata CSV
 * 的驼峰列名，无需命名映射）。多份清单（tms / bms / sec）合并加载。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>加载 tablefieldlist 内容，建立 表名 → 主键列集合 映射</li>
 *   <li>把一个 initdata CSV（文件名 + 表头）解析为它的业务主键列——
 *       先按文件名识别表（含引擎/版本后缀变体与 {@code XxxInitData} 驼峰命名），
 *       识别不了再按"表头列集合 ⊆ 表字段集合"反查（唯一命中才生效）</li>
 * </ul>
 *
 * <p>主键列剔除规则：{@code keyFlag} 存在个别脏标记（把描述字段也标成 Y），
 * 凡列名以 Descr 结尾或含多语言后缀 '~' 的一律不作为主键成员。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvTableKeyRegistry {

    /** XxxInitData 风格文件名（PascalCase + InitData 后缀） */
    private static final Pattern INITDATA_STYLE = Pattern.compile("^[A-Z][A-Za-z0-9]*InitData$");

    /** 文件名中的版本尾巴（如 _V3.9_20250707） */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("_V\\d.*$");

    /** 归一化后缀匹配的最短候选长度（过短的串做 endsWith 撞名风险高） */
    private static final int MIN_NORMALIZED_SUFFIX_LENGTH = 4;

    /** 表名(大写) → 主键列集合（keyFlag=Y，已剔除描述/多语言列） */
    private final Map<String, LinkedHashSet<String>> tableKeyColumns;

    /** 表名(大写) → 全部字段名集合（列集合反查用） */
    private final Map<String, Set<String>> tableAllColumns;

    /** 归一化表名（大写去下划线）→ 原表名列表（归一化匹配用，理论上可能多值） */
    private final Map<String, List<String>> normalizedTableIndex;

    private CsvTableKeyRegistry(Map<String, LinkedHashSet<String>> tableKeyColumns,
                                Map<String, Set<String>> tableAllColumns) {
        this.tableKeyColumns = tableKeyColumns;
        this.tableAllColumns = tableAllColumns;
        Map<String, List<String>> normalized = new HashMap<>();
        for (String table : tableKeyColumns.keySet()) {
            normalized.computeIfAbsent(normalizeToken(table), k -> new ArrayList<>()).add(table);
        }
        this.normalizedTableIndex = normalized;
    }

    /**
     * 表名/候选名归一化：大写并去掉全部下划线
     *
     * <p>解决文件名与真实表名之间的三类系统性偏差（真实工程实测的失败模式）：
     * 驼峰分词歧义（BsmCodeType → BSM_CODETYPE 而非 BSM_CODE_TYPE）、
     * 模块前缀缺失（FunctionAction → BSM_FUNCTION_ACTION）、
     * 下划线风格差异。归一化后按"精确相等"或"表名以候选结尾"匹配，均要求唯一命中。</p>
     *
     * @param s 表名或候选名
     * @return 大写去下划线形式
     * @author xumanyi
     * @date 2026-07-14
     */
    private static String normalizeToken(String s) {
        return s.toUpperCase(Locale.ROOT).replace("_", "");
    }

    /**
     * 加载多份 tablefieldlist 内容，合并建立主键字典
     *
     * <p>单份内容解析失败（表头认不出、缺必需列）时跳过该份并继续，
     * 不让个别脏文件影响整体可用性。</p>
     *
     * @param tablefieldlistContents 各份 tablefieldlist-*.csv 的完整文本
     * @return 主键字典（可能为空字典，调用方按未识别兜底处理）
     * @author xumanyi
     * @date 2026-07-10
     */
    public static CsvTableKeyRegistry load(List<String> tablefieldlistContents) {
        Map<String, LinkedHashSet<String>> keyCols = new HashMap<>();
        Map<String, Set<String>> allCols = new HashMap<>();

        for (String content : tablefieldlistContents) {
            if (content == null || content.isBlank()) {
                continue;
            }
            try {
                loadSingle(content, keyCols, allCols);
            } catch (RuntimeException ignored) {
                // 单份清单异常不影响其余清单；未建入字典的表走文件级兜底
            }
        }
        return new CsvTableKeyRegistry(keyCols, allCols);
    }

    /**
     * 解析单份 tablefieldlist 内容并归集进字典
     *
     * @param content 单份完整文本
     * @param keyCols 主键列归集容器
     * @param allCols 全字段归集容器
     * @author xumanyi
     * @date 2026-07-10
     */
    private static void loadSingle(String content,
                                   Map<String, LinkedHashSet<String>> keyCols,
                                   Map<String, Set<String>> allCols) {
        InitdataCsvDocument doc = InitdataCsvDocument.parse(content);
        if (!doc.hasHeader()) {
            return;
        }
        List<String> header = doc.getHeaderFields();
        int tableIdx = header.indexOf("tableName");
        int fieldIdx = header.indexOf("fieldName");
        int keyIdx = header.indexOf("keyFlag");
        if (tableIdx < 0 || fieldIdx < 0 || keyIdx < 0) {
            return; // 结构不符，跳过该份
        }

        for (CsvRecord row : doc.getDataRecords()) {
            List<String> fields = row.getFields();
            String table = valueAt(fields, tableIdx).trim();
            String field = valueAt(fields, fieldIdx).trim();
            // "*" 是公共审计字段字典项，不是真实表
            if (table.isEmpty() || "*".equals(table) || field.isEmpty()) {
                continue;
            }
            String tableUpper = table.toUpperCase(Locale.ROOT);
            allCols.computeIfAbsent(tableUpper, k -> new LinkedHashSet<>()).add(field);

            String keyFlag = valueAt(fields, keyIdx).trim();
            if ("Y".equalsIgnoreCase(keyFlag) && !isDescriptiveColumn(field)) {
                keyCols.computeIfAbsent(tableUpper, k -> new LinkedHashSet<>()).add(field);
            }
        }
    }

    /**
     * 判断列名是否为描述类列（不允许作为主键成员）
     *
     * @param column 列名
     * @return true=描述类列（Descr 结尾或含多语言 '~' 后缀）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static boolean isDescriptiveColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return lower.endsWith("descr") || column.indexOf('~') >= 0;
    }

    /**
     * 安全取值（下标越界返回空串，容忍列数参差的行）
     *
     * @param fields 字段列表
     * @param idx    下标
     * @return 字段值（越界为空串）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String valueAt(List<String> fields, int idx) {
        return idx < fields.size() ? String.valueOf(fields.get(idx)) : "";
    }

    /** 字典是否为空（一张表都没加载到） */
    public boolean isEmpty() {
        return tableKeyColumns.isEmpty();
    }

    /** 获取已加载的表数量（日志展示用） */
    public int tableCount() {
        return tableKeyColumns.size();
    }

    /**
     * 解析 initdata CSV 的业务主键列
     *
     * <p>返回"该表主键列 ∩ 该 CSV 实际存在的列"（按表头顺序）。
     * 逻辑主键的部分列可能在物理 CSV 中省略（如 organizationId 列不存在），
     * 取交集后即为可用于行匹配的键。</p>
     *
     * @param csvFileName  CSV 文件名（不含路径，可含 .csv 后缀）
     * @param headerFields CSV 的 trim 后表头字段名
     * @return 业务主键列（按表头顺序）；无法识别表或交集为空时返回空列表
     * @author xumanyi
     * @date 2026-07-10
     */
    public List<String> resolveKeyColumns(String csvFileName, List<String> headerFields) {
        Set<String> tableKeys = findTableKeyColumns(csvFileName, headerFields);
        if (tableKeys == null || tableKeys.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (String col : headerFields) {
            if (tableKeys.contains(col)) {
                resolved.add(col);
            }
        }
        return resolved;
    }

    /**
     * 定位 CSV 对应表的主键列集合（先文件名识别，后列集合反查）
     *
     * @param csvFileName  CSV 文件名
     * @param headerFields trim 后表头字段名
     * @return 主键列集合；识别不了返回 null
     * @author xumanyi
     * @date 2026-07-10
     */
    private Set<String> findTableKeyColumns(String csvFileName, List<String> headerFields) {
        List<String> candidates = tableNameCandidates(csvFileName);

        // 第一层：候选名与字典表名精确匹配
        for (String candidate : candidates) {
            LinkedHashSet<String> keys = tableKeyColumns.get(candidate);
            if (keys != null && !keys.isEmpty() && containsAny(headerFields, keys)) {
                return keys;
            }
        }

        // 第二层：归一化匹配（大写去下划线后精确相等 / 表名以候选结尾，均须唯一命中）
        for (String candidate : candidates) {
            Set<String> keys = lookupByNormalizedName(candidate, headerFields);
            if (keys != null) {
                return keys;
            }
        }

        // 第三层：列集合反查
        return reverseLookupByColumns(headerFields);
    }

    /**
     * 归一化表名匹配：先精确相等，再"表名以候选结尾"，都要求唯一命中（歧义不猜）
     *
     * @param candidate    表名候选
     * @param headerFields trim 后表头字段名（校验主键列确实存在于该 CSV）
     * @return 唯一命中表的主键列集合；无法唯一命中返回 null
     * @author xumanyi
     * @date 2026-07-14
     */
    private Set<String> lookupByNormalizedName(String candidate, List<String> headerFields) {
        String normalized = normalizeToken(candidate);
        if (normalized.length() < MIN_NORMALIZED_SUFFIX_LENGTH) {
            return null;
        }

        // 归一化精确相等（如 BSMCODETYPE == BSM_CODETYPE 归一化）
        List<String> exact = normalizedTableIndex.get(normalized);
        if (exact != null && exact.size() == 1) {
            LinkedHashSet<String> keys = tableKeyColumns.get(exact.get(0));
            if (keys != null && !keys.isEmpty() && containsAny(headerFields, keys)) {
                return keys;
            }
        }

        // 归一化后缀匹配（如 BSMFUNCTIONACTION endsWith FUNCTIONACTION，补上缺失的模块前缀）
        String matchedTable = null;
        for (Map.Entry<String, List<String>> e : normalizedTableIndex.entrySet()) {
            if (!e.getKey().endsWith(normalized)) {
                continue;
            }
            for (String table : e.getValue()) {
                if (matchedTable != null && !matchedTable.equals(table)) {
                    return null; // 多表命中，歧义不猜
                }
                matchedTable = table;
            }
        }
        if (matchedTable == null) {
            return null;
        }
        LinkedHashSet<String> keys = tableKeyColumns.get(matchedTable);
        return (keys != null && !keys.isEmpty() && containsAny(headerFields, keys)) ? keys : null;
    }

    /**
     * 由文件名推导表名候选（按优先级排列）
     *
     * <p>覆盖两种命名风格：</p>
     * <ul>
     *   <li>{@code initData/TABLE_NAME.csv}：文件名即表名，可能带引擎后缀
     *       （{@code T_RUL_DBSQL-oracle}）或版本尾巴（{@code X_V3.9_20250707}）</li>
     *   <li>{@code initdata_bin/XxxInitData.csv}：PascalCase 去掉 InitData 后
     *       转 UPPER_SNAKE（{@code BsmFunctionInitData → BSM_FUNCTION}）</li>
     * </ul>
     *
     * @param csvFileName CSV 文件名
     * @return 表名候选列表（大写，去重保序）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static List<String> tableNameCandidates(String csvFileName) {
        String name = csvFileName == null ? "" : csvFileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (name.isBlank()) {
            return new ArrayList<>(candidates);
        }

        String upper = name.toUpperCase(Locale.ROOT);
        candidates.add(upper);

        // 引擎后缀变体：T_RUL_DBSQL-oracle → T_RUL_DBSQL
        int dash = upper.indexOf('-');
        if (dash > 0) {
            candidates.add(upper.substring(0, dash));
        }

        // 版本尾巴：RUL_UPLOAD_CONFIG_V3.9_20250707 → RUL_UPLOAD_CONFIG
        String noVersion = VERSION_SUFFIX.matcher(dash > 0 ? upper.substring(0, dash) : upper)
                .replaceFirst("");
        if (!noVersion.isBlank()) {
            candidates.add(noVersion);
        }

        // PascalCase InitData 风格：BsmFunctionInitData → BSM_FUNCTION
        if (INITDATA_STYLE.matcher(name).matches()) {
            String base = name.substring(0, name.length() - "InitData".length());
            candidates.add(camelToUpperSnake(base));
        }

        // 通用 initData 尾巴剥离（大小写不敏感，兼容 ARCHIVE_DETAILS_initData 这类混合风格），
        // 剥掉尾部 initData 及连接下划线后作为候选，供精确/归一化两层匹配使用
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("initdata")) {
            String stripped = name.substring(0, name.length() - "initdata".length());
            while (stripped.endsWith("_")) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
            if (!stripped.isBlank()) {
                candidates.add(stripped.toUpperCase(Locale.ROOT));
                candidates.add(camelToUpperSnake(stripped));
            }
        }
        return new ArrayList<>(candidates);
    }

    /**
     * 驼峰转下划线大写（BsmFunction → BSM_FUNCTION）
     *
     * @param camel 驼峰串
     * @return UPPER_SNAKE 形式
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String camelToUpperSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * 列集合反查表：CSV 数据列全部落在某表字段集合内且唯一命中时采纳
     *
     * <p>比较前剔除 SEQNUM（文件物理序号列，非表字段）、多语言 '~' 列与空列名。
     * 命中 0 个或多于 1 个表都视为无法识别（歧义不猜）。</p>
     *
     * @param headerFields trim 后表头字段名
     * @return 唯一命中表的主键列集合；无法唯一识别返回 null
     * @author xumanyi
     * @date 2026-07-10
     */
    private Set<String> reverseLookupByColumns(List<String> headerFields) {
        Set<String> csvColumns = new LinkedHashSet<>();
        for (String col : headerFields) {
            if (col.isBlank() || "SEQNUM".equalsIgnoreCase(col) || col.indexOf('~') >= 0) {
                continue;
            }
            csvColumns.add(col);
        }
        if (csvColumns.isEmpty()) {
            return null;
        }

        String matchedTable = null;
        for (Map.Entry<String, Set<String>> e : tableAllColumns.entrySet()) {
            if (e.getValue().containsAll(csvColumns)) {
                if (matchedTable != null) {
                    return null; // 多表命中，歧义不猜
                }
                matchedTable = e.getKey();
            }
        }
        if (matchedTable == null) {
            return null;
        }
        LinkedHashSet<String> keys = tableKeyColumns.get(matchedTable);
        return (keys == null || keys.isEmpty()) ? null : keys;
    }

    /**
     * 判断表头中是否含有键集合中的任一列
     *
     * @param headerFields 表头字段名
     * @param keys         键列集合
     * @return true=至少一列存在
     * @author xumanyi
     * @date 2026-07-10
     */
    private static boolean containsAny(List<String> headerFields, Set<String> keys) {
        for (String col : headerFields) {
            if (keys.contains(col)) {
                return true;
            }
        }
        return false;
    }
}
