package com.flux.deploy.csv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSV 行级增量合并引擎
 *
 * <p>目的：增量部署时，CSV 不再整份覆盖目标包中的同名条目，而是只把
 * "本次未提交改动涉及的行"施加到目标包 CSV 上——防止把目标包里
 * 其他同事尚未提交 git 的行数据覆盖删除。</p>
 *
 * <p>三份输入：</p>
 * <ul>
 *   <li>base —— 改动前的基线（git HEAD 版），仅用于算出本次加/删/改了哪些行</li>
 *   <li>mine —— 本地工作区当前内容（用户的 CSV，一切以它为准）</li>
 *   <li>target —— 目标包内现有 CSV 条目</li>
 * </ul>
 *
 * <p>行级语义（按业务主键匹配，主键来自 {@link CsvTableKeyRegistry}，1~N 列自适应）：</p>
 * <ul>
 *   <li>新增行：目标包无该键→按"我的 CSV 中的位置"锚定插入（锚=我文件中前一行的键；
 *       锚找不到→追加至末尾）；目标包已有该键→原位整行替换（重复部署天然幂等）</li>
 *   <li>修改行：目标包有该键→原位整行替换；无该键→按新增规则插入</li>
 *   <li>删除行：目标包有该键→删除；无该键→跳过</li>
 *   <li>未改动的行：一律不碰，原始字节保留（不重排、不重新加引号）</li>
 * </ul>
 *
 * <p>文件级兜底（增量在数学上不可行时退整份覆盖，绝不中断部署）：
 * 编码非 UTF-8 / 表头认不出 / 三方表头不一致 / 主键未识别 /
 * 任何未预期异常。引擎只施加本次新增 / 修改 / 删除，不做 CSV 内容质量校验。</p>
 *
 * <p>本类为无状态纯函数，不依赖任何 IDE / VCS API，git 内容由调用方注入。</p>
 *
 * @author xumanyi
 * @date 2026-07-10
 */
public final class CsvMergeEngine {

    /** 复合主键各列值的拼接分隔符（不可见字符，避免与业务数据冲突） */
    private static final char KEY_SEPARATOR = '\u0001';

    private CsvMergeEngine() {}

    /**
     * 执行 CSV 行级增量合并
     *
     * <p>任何异常都被捕获并转为整份覆盖兜底，保证部署流程在任何输入下都能走完。</p>
     *
     * @param targetBytes 目标包内现有 CSV 条目字节
     * @param mineBytes   本地工作区 CSV 字节（用户的 CSV）
     * @param baseContent git HEAD 版内容（改动前基线）
     * @param fileName    CSV 文件名（用于表识别与日志）
     * @param registry    业务主键字典
     * @return 合并结果（MERGED 或 FALLBACK_OVERWRITE，绝不抛异常）
     * @author xumanyi
     * @date 2026-07-10
     */
    public static CsvMergeOutcome merge(byte[] targetBytes, byte[] mineBytes, String baseContent,
                                        String fileName, CsvTableKeyRegistry registry) {
        try {
            return doMerge(targetBytes, mineBytes, baseContent, fileName, registry);
        } catch (Exception e) {
            // 防御性兜底：合并引擎的任何意外都不允许中断部署，退回原有整份覆盖行为
            return CsvMergeOutcome.fallback("合并过程异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    /**
     * 合并主流程（异常由外层 {@link #merge} 统一兜底）
     *
     * @param targetBytes 目标包 CSV 字节
     * @param mineBytes   本地 CSV 字节
     * @param baseContent 基线内容
     * @param fileName    文件名
     * @param registry    主键字典
     * @return 合并结果
     * @author xumanyi
     * @date 2026-07-10
     */
    private static CsvMergeOutcome doMerge(byte[] targetBytes, byte[] mineBytes, String baseContent,
                                           String fileName, CsvTableKeyRegistry registry) throws Exception {
        if (registry == null || registry.isEmpty()) {
            return CsvMergeOutcome.fallback("业务主键字典不可用（工程内未找到 tablefieldlist-*.csv）");
        }
        if (baseContent == null || baseContent.isBlank()) {
            return CsvMergeOutcome.fallback("无改动前基线内容");
        }

        // ===== 1. 解析三份文档（严格 UTF-8，失败即兜底） =====
        InitdataCsvDocument target;
        InitdataCsvDocument mine;
        try {
            target = InitdataCsvDocument.parse(targetBytes);
        } catch (java.nio.charset.CharacterCodingException e) {
            return CsvMergeOutcome.fallback("目标包 CSV 不是合法 UTF-8 编码");
        }
        try {
            mine = InitdataCsvDocument.parse(mineBytes);
        } catch (java.nio.charset.CharacterCodingException e) {
            return CsvMergeOutcome.fallback("本地 CSV 不是合法 UTF-8 编码");
        }
        InitdataCsvDocument base = InitdataCsvDocument.parse(baseContent);

        if (!mine.hasHeader() || !target.hasHeader() || !base.hasHeader()) {
            return CsvMergeOutcome.fallback("表头无法识别（文件为空或全部是注释行）");
        }

        // ===== 2. 表头一致性（行级替换的准入条件，列对不上必错位） =====
        if (!mine.headerMatches(target)) {
            return CsvMergeOutcome.fallback("本地与目标包的表头列不一致（目标包可能是旧结构版本）");
        }
        if (!mine.headerMatches(base)) {
            return CsvMergeOutcome.fallback("本次改动包含表头结构变更");
        }

        // ===== 3. 解析业务主键列 =====
        List<String> keyColumns = registry.resolveKeyColumns(fileName, mine.getHeaderFields());
        if (keyColumns.isEmpty()) {
            return CsvMergeOutcome.fallback("未识别该 CSV 的业务主键（tablefieldlist 中查不到对应表）");
        }
        int[] keyIdx = new int[keyColumns.size()];
        for (int i = 0; i < keyColumns.size(); i++) {
            keyIdx[i] = mine.getHeaderFields().indexOf(keyColumns.get(i));
        }

        // ===== 4. 构建基线 / 本地的键索引（重复键按文件顺序后者覆盖前者，不做内容校验） =====
        Map<String, CsvRecord> baseByKey = new LinkedHashMap<>();
        String baseIndexError = indexByKey(base, keyIdx, "改动前基线", baseByKey);
        if (baseIndexError != null) {
            return CsvMergeOutcome.fallback(baseIndexError);
        }
        Map<String, CsvRecord> mineByKey = new LinkedHashMap<>();
        String mineIndexError = indexByKey(mine, keyIdx, "本地 CSV", mineByKey);
        if (mineIndexError != null) {
            return CsvMergeOutcome.fallback(mineIndexError);
        }

        // ===== 5. 行级施加 =====
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();
        List<CsvRecord> out = new ArrayList<>(target.getRecords());
        int headerIdx = target.getHeaderIndex();
        String terminator = target.getDominantTerminator();
        int targetDataCount = target.getDataRecords().size();

        int replaced = 0;
        int added = 0;
        int deleted = 0;
        int appendedAtEnd = 0;

        // 5a. 删除：基线有、本地已删的键
        for (Map.Entry<String, CsvRecord> e : baseByKey.entrySet()) {
            String key = e.getKey();
            if (mineByKey.containsKey(key)) {
                continue;
            }
            List<Integer> indices = findDataRowsByKey(out, headerIdx, keyIdx, key);
            if (indices.isEmpty()) {
                infos.add("删除行 [" + displayKey(key) + "] 在目标包中已不存在，跳过");
                continue;
            }
            for (int i = indices.size() - 1; i >= 0; i--) {
                out.remove((int) indices.get(i));
            }
            deleted += indices.size();
        }

        // 5b. 顺序遍历本地数据行：修改原位替换 / 新增锚定插入（重复部署幂等）
        String anchorKey = null;
        for (CsvRecord mineRec : mine.getDataRecords()) {
            String key = keyOf(mineRec, keyIdx);
            CsvRecord baseRec = baseByKey.get(key);
            if (baseRec != null && baseRec.getFields().equals(mineRec.getFields())) {
                // 未改动的行：不碰目标包
                anchorKey = key;
                continue;
            }

            CsvRecord newRec = mineRec.withTerminator(terminator);
            List<Integer> indices = findDataRowsByKey(out, headerIdx, keyIdx, key);
            if (!indices.isEmpty()) {
                // 目标包已有该键：只做更新，不做重复内容校验或清洗；命中多行就逐行替换。
                for (Integer index : indices) {
                    out.set(index, newRec);
                }
                if (baseRec == null) {
                    infos.add("新增行 [" + displayKey(key) + "] 在目标包中已存在，按替换处理（重复部署幂等）");
                }
                replaced += indices.size();
            } else {
                // 目标包没有该键：按"我的 CSV 中的位置"锚定插入
                int insertAt;
                if (anchorKey == null) {
                    // 本地文件的第一条数据行：插到目标包数据区最前
                    insertAt = firstDataRowIndex(out, headerIdx);
                } else {
                    List<Integer> anchorIndices = findDataRowsByKey(out, headerIdx, keyIdx, anchorKey);
                    if (!anchorIndices.isEmpty()) {
                        insertAt = anchorIndices.get(anchorIndices.size() - 1) + 1;
                    } else {
                        insertAt = afterLastDataRowIndex(out, headerIdx);
                        appendedAtEnd++;
                        infos.add("行 [" + displayKey(key) + "] 未找到原位置，已追加至数据区末尾");
                    }
                }
                out.add(insertAt, newRec);
                if (baseRec != null) {
                    infos.add("修改行 [" + displayKey(key) + "] 在目标包中不存在，已按新增插入");
                }
                added++;
            }
            anchorKey = key;
        }

        // 5c. 注释增量：本次新增的头部注释行（如版本记录注释）带到目标包注释块末尾
        Set<String> baseComments = new HashSet<>(base.getLeadingCommentLines());
        Set<String> targetComments = new HashSet<>(target.getLeadingCommentLines());
        int commentOffset = 0;
        for (String comment : mine.getLeadingCommentLines()) {
            if (!baseComments.contains(comment) && !targetComments.contains(comment)) {
                out.add(headerIdx + commentOffset, new CsvRecord(List.of(comment), comment, terminator));
                commentOffset++;
            }
        }

        // ===== 6. 重建输出（未改动行原始字节保真） =====
        byte[] mergedBytes = rebuild(out, target.hasBom(), terminator);

        int kept = targetDataCount - replaced - deleted;
        return CsvMergeOutcome.merged(mergedBytes, added, replaced, deleted,
                appendedAtEnd, Math.max(kept, 0), warnings, infos);
    }

    /**
     * 将文档数据行按业务主键建索引
     *
     * @param doc     文档
     * @param keyIdx  主键列下标
     * @param docName 文档名称（用于兜底原因描述）
     * @param result  键 → 记录 归集容器（保持文件顺序）
     * @return null=成功；非 null=兜底原因（存在无键行）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String indexByKey(InitdataCsvDocument doc, int[] keyIdx, String docName,
                                     Map<String, CsvRecord> result) {
        for (CsvRecord rec : doc.getDataRecords()) {
            String key = keyOf(rec, keyIdx);
            if (key == null) {
                return docName + "存在主键列为空的数据行，无法按行匹配";
            }
            result.put(key, rec);
        }
        return null;
    }

    /**
     * 提取记录的业务主键值（多列以不可见分隔符拼接）
     *
     * @param rec    数据记录
     * @param keyIdx 主键列下标
     * @return 键串；主键列值全部为空时返回 null（无键行）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String keyOf(CsvRecord rec, int[] keyIdx) {
        List<String> fields = rec.getFields();
        StringBuilder sb = new StringBuilder();
        boolean allBlank = true;
        for (int idx : keyIdx) {
            String v = idx < fields.size() ? fields.get(idx).trim() : "";
            if (!v.isEmpty()) {
                allBlank = false;
            }
            sb.append(v).append(KEY_SEPARATOR);
        }
        return allBlank ? null : sb.toString();
    }

    /**
     * 键串转为可读形式（日志展示用）
     *
     * @param key 键串
     * @return 可读键（多列以 " / " 连接）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static String displayKey(String key) {
        String readable = key.replace(String.valueOf(KEY_SEPARATOR), " / ").trim();
        return readable.endsWith("/") ? readable.substring(0, readable.length() - 1).trim() : readable;
    }

    /**
     * 在输出记录列表中查找指定主键的全部数据行下标
     *
     * @param out       输出记录列表
     * @param headerIdx 表头下标（只在其后查找）
     * @param keyIdx    主键列下标
     * @param key       目标键
     * @return 命中下标列表（升序）
     * @author xumanyi
     * @date 2026-07-10
     */
    private static List<Integer> findDataRowsByKey(List<CsvRecord> out, int headerIdx,
                                                   int[] keyIdx, String key) {
        List<Integer> indices = new ArrayList<>();
        for (int i = headerIdx + 1; i < out.size(); i++) {
            CsvRecord rec = out.get(i);
            if (rec.isData() && key.equals(keyOf(rec, keyIdx))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * 数据区第一行的下标（无数据行时为表头之后）
     *
     * @param out       输出记录列表
     * @param headerIdx 表头下标
     * @return 插入位置
     * @author xumanyi
     * @date 2026-07-10
     */
    private static int firstDataRowIndex(List<CsvRecord> out, int headerIdx) {
        for (int i = headerIdx + 1; i < out.size(); i++) {
            if (out.get(i).isData()) {
                return i;
            }
        }
        return headerIdx + 1;
    }

    /**
     * 数据区最后一行之后的下标（追加位置；无数据行时为列表末尾）
     *
     * @param out       输出记录列表
     * @param headerIdx 表头下标
     * @return 追加位置
     * @author xumanyi
     * @date 2026-07-10
     */
    private static int afterLastDataRowIndex(List<CsvRecord> out, int headerIdx) {
        for (int i = out.size() - 1; i > headerIdx; i--) {
            if (out.get(i).isData()) {
                return i + 1;
            }
        }
        return out.size();
    }

    /**
     * 重建输出字节（未改动行原文写回，行终止符缺失处补主导终止符防止粘行）
     *
     * @param out                输出记录列表
     * @param withBom            是否输出 UTF-8 BOM（跟随目标包原状）
     * @param dominantTerminator 主导行终止符
     * @return 最终字节
     * @author xumanyi
     * @date 2026-07-10
     */
    private static byte[] rebuild(List<CsvRecord> out, boolean withBom, String dominantTerminator) {
        StringBuilder sb = new StringBuilder();
        if (withBom) {
            sb.append('\uFEFF');
        }
        for (int i = 0; i < out.size(); i++) {
            CsvRecord rec = out.get(i);
            sb.append(rec.getRawText());
            if (i < out.size() - 1) {
                // 非末条记录必须有行终止符（原末行无换行、其后又插入了行时补主导终止符）
                sb.append(rec.getTerminator().isEmpty() ? dominantTerminator : rec.getTerminator());
            } else {
                sb.append(rec.getTerminator());
            }
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
