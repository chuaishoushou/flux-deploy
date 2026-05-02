package com.flux.deploy.plugin.util;

import com.github.promeg.pinyinhelper.Pinyin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 拼音模糊匹配工具
 *
 * <p>把候选文本预转成两条索引：</p>
 * <ul>
 *   <li><b>全拼串</b>：每个汉字按拼音全写拼接（如"上海客服"→"shanghaikefu"），非汉字按原样小写保留。</li>
 *   <li><b>首字母串</b>：每个汉字取拼音首字母（如"上海客服"→"shkf"），非汉字按原样小写保留。</li>
 * </ul>
 *
 * <p>匹配时关键字小写后，命中下列任一即可：</p>
 * <ol>
 *   <li>原始文本 contains 关键字（普通子串匹配，保持原有行为）。</li>
 *   <li>全拼串 contains 关键字（如"shanghai"命中"上海"）。</li>
 *   <li>首字母串 contains 关键字（如"sh"命中"上海"）。</li>
 * </ol>
 *
 * <p>每个候选文本的索引会被缓存，避免每次按键都重新转换。</p>
 *
 * @author xumanyi
 * @date 2026-04-29
 */
public final class PinyinMatcher {

    /** 候选文本 -&gt; [全拼串, 首字母串] 缓存，避免重复转换 */
    private static final Map<String, String[]> INDEX_CACHE = new HashMap<>();

    private PinyinMatcher() {
    }

    /**
     * 判断 candidate 是否匹配 keyword（支持拼音）。
     *
     * @param candidate 候选文本（如项目名、文件路径），为 {@code null} 视为空串
     * @param keyword   用户输入的关键字，已 trim；为空时一律返回 true 由调用方决定
     * @return true 表示命中
     */
    public static boolean matches(String candidate, String keyword) {
        if (keyword == null || keyword.isEmpty()) return true;
        if (candidate == null || candidate.isEmpty()) return false;

        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        if (lowerCandidate.contains(lowerKeyword)) return true;

        String[] indices = indexFor(candidate);
        return indices[0].contains(lowerKeyword) || indices[1].contains(lowerKeyword);
    }

    /**
     * 按拼音/首字母把候选文本转成两条索引串。
     *
     * @param text 候选文本
     * @return 长度为 2 的数组：[0] 全拼串，[1] 首字母串，均为小写
     */
    private static String[] indexFor(String text) {
        String[] cached = INDEX_CACHE.get(text);
        if (cached != null) return cached;

        StringBuilder fullPinyin = new StringBuilder(text.length() * 4);
        StringBuilder initials = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Pinyin.isChinese(c)) {
                String py = Pinyin.toPinyin(c).toLowerCase(Locale.ROOT);
                fullPinyin.append(py);
                if (!py.isEmpty()) initials.append(py.charAt(0));
            } else {
                char lower = Character.toLowerCase(c);
                fullPinyin.append(lower);
                initials.append(lower);
            }
        }
        String[] result = { fullPinyin.toString(), initials.toString() };
        // 缓存项数与项目数/文件数线性相关（千级），无需额外淘汰策略
        INDEX_CACHE.put(text, result);
        return result;
    }
}
