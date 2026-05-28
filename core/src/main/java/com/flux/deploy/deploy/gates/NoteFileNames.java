package com.flux.deploy.deploy.gates;

/**
 * 版本记录 (update-note) 文件命名规则集中地。
 *
 * <p><b>canonical 命名</b>：{@code <包全名>_update_notes.txt}（复数，例：{@code tm10srv.war_update_notes.txt}）。
 * 本插件之后写入新文件时一律采用该命名。</p>
 *
 * <p><b>识别规则（模糊匹配 + 对称切版本）</b>：对包名和候选 .txt 各自计算 base，base 相等即视为命中。</p>
 * <ul>
 *   <li>包名 base：剥末尾 {@code .jar}/{@code .war}/{@code .ear}/{@code .zip} → 截到 first {@code -<数字>}
 *       （如 {@code -9.0.0-SNAPSHOT}）；没有就截到 first {@code _}；都没有就原样。再剥一次扩展名。</li>
 *   <li>候选 base：剥末尾 {@code .txt} → 同样规则切版本/下划线 → 再剥扩展名。</li>
 * </ul>
 *
 * <p>例：包 {@code scev6-utils-tms-9.0.0-SNAPSHOT.jar}，base = {@code scev6-utils-tms}。</p>
 * <ul>
 *   <li>命中：{@code scev6-utils-tms_update_notes.txt}（旧文件）、
 *       {@code scev6-utils-tms-9.0.0-SNAPSHOT_update_notes.txt}（新 canonical）</li>
 *   <li>不命中：兄弟包 {@code scev6-utils-tms-apps_update_notes.txt}（base 是 {@code scev6-utils-tms-apps}）</li>
 * </ul>
 *
 * <p><b>前提</b>：包所在目录里 {@code .txt} 文件只用于版本记录，不会放别的 {@code .txt}
 * （否则会被误识别）。这条假设由部署侧约定保证。</p>
 *
 * <p><b>命中处理</b>：0 个 → 按 canonical 新建；1 个 → 就地追加；≥2 个属于严重冲突，
 * 由 plugin 层预检阶段提前拦截并要求用户手动清理，不在此处自动选择。</p>
 *
 * @author xumanyi
 * @date 2026-05-11
 */
public final class NoteFileNames {

    private NoteFileNames() {}

    /** 包扩展名集合，用于 base 计算时剥扩展。 */
    private static final java.util.List<String> PACKAGE_EXTS =
            java.util.Arrays.asList(".jar", ".war", ".ear", ".zip");

    /**
     * 返回当前标准的 canonical 文件名（带复数 s）。
     *
     * @param packageName 包文件名（含扩展名，例 {@code tm10srv.war}；无扩展名也可）
     * @return canonical 文件名，例 {@code tm10srv.war_update_notes.txt}
     */
    public static String canonicalName(String packageName) {
        return packageName + "_update_notes.txt";
    }

    /**
     * 判断给定基名文件是否是 {@code packageName} 的 note 候选（用于目录扫描后筛选）。
     *
     * <p>规则见 {@link NoteFileNames 类级 javadoc}：对称剥 base 等值比较。</p>
     *
     * @param packageName 当前部署的包名（含扩展名）
     * @param fileName    待判定的同目录文件 base 名（不含路径）
     * @return 是 note 候选返回 true
     */
    public static boolean isNoteCandidate(String packageName, String fileName) {
        if (fileName == null || !fileName.endsWith(".txt")) {
            return false;
        }
        String pkgBase = computeBase(packageName, false);
        String fileBase = computeBase(fileName, true);
        return !pkgBase.isEmpty() && pkgBase.equals(fileBase);
    }

    /**
     * 去掉文件名最后一个扩展名。
     * 例如 {@code tm10srv.war} → {@code tm10srv}。无扩展名或点在开头时原样返回。
     *
     * @param name 文件名
     * @return 去掉最后一个扩展名后的文件名
     */
    public static String stripLastExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    /**
     * 计算 base：剥扩展名 → 截到 first {@code -<数字>} 或 first {@code _} → 再剥一次扩展名。
     *
     * @param name      原始文件名（包名 或 候选 .txt 名）
     * @param isTxtFile {@code true} 时先剥 {@code .txt}；{@code false} 时剥包扩展名
     * @return 算出的 base，null 输入返回空串
     * @author xumanyi
     * @date 2026-05-26
     */
    private static String computeBase(String name, boolean isTxtFile) {
        if (name == null) return "";
        String s = name;
        if (isTxtFile) {
            if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
        } else {
            s = stripPackageExt(s);
        }
        // 截到 first -<数字> 或 first _，谁先到取谁
        int cut = -1;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '_') { cut = i; break; }
            if (c == '-' && Character.isDigit(s.charAt(i + 1))) { cut = i; break; }
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        // 再剥一次扩展名（候选文件名里嵌的 .jar/.war 形态，例 tm10srv.war_update_notes.txt 截到 _ 后剩 tm10srv.war）
        return stripPackageExt(s);
    }

    /**
     * 若 {@code s} 末尾匹配 {@link #PACKAGE_EXTS} 中任意扩展名（忽略大小写），剥掉返回，否则原样返回。
     *
     * @param s 待处理字符串
     * @return 剥扩展名后的字符串
     * @author xumanyi
     * @date 2026-05-26
     */
    private static String stripPackageExt(String s) {
        String lower = s.toLowerCase();
        for (String ext : PACKAGE_EXTS) {
            if (lower.endsWith(ext)) {
                return s.substring(0, s.length() - ext.length());
            }
        }
        return s;
    }
}
