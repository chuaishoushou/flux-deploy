package com.flux.deploy.deploy.gates;

/**
 * 版本记录 (update-note) 文件命名规则集中地。
 *
 * <p><b>canonical 命名</b>：{@code <包全名>_update_notes.txt}（复数，例：{@code tm10srv.war_update_notes.txt}）。
 * 本插件之后写入新文件时一律采用该命名。</p>
 *
 * <p><b>识别规则</b>：扫描包所在目录，凡是文件名同时满足以下条件即视为 note 候选：</p>
 * <ol>
 *   <li>以 stem(包名) 开头。stem = 去掉包名最后一个扩展名（无扩展名时 stem == 包名）。</li>
 *   <li>以 {@code .txt} 结尾。</li>
 * </ol>
 *
 * <p>中间字符不做约束（包括复数/单数、含或不含 {@code .war}/{@code .jar}、带日期或版本号后缀、
 * stem 后直接接字母如 {@code <stem>es.txt} 等），从而无需逐一枚举历史命名。</p>
 *
 * <p><b>前提</b>：包所在目录里 {@code .txt} 文件只用于版本记录，不会放别的 {@code .txt}
 * （否则会被误识别）。这条假设由部署侧约定保证。</p>
 *
 * <p>canonical 文件也满足该谓词；调用者按文件名等于 {@link #canonicalName(String)} 把它单独挑出。
 * 多个候选时按字节最多者原地追加，<b>不做任何合并、迁移或删除</b>。</p>
 *
 * @author xumanyi
 * @date 2026-05-11
 */
public final class NoteFileNames {

    private NoteFileNames() {}

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
     * <p>判定细则见 {@link NoteFileNames 类级 javadoc}。canonical 文件会通过该判定，
     * 调用者需要按 {@link #canonicalName(String)} 名称比较把 canonical 单独取出。</p>
     *
     * @param packageName 当前部署的包名（含扩展名）
     * @param fileName    待判定的同目录文件 base 名（不含路径）
     * @return 是 note 候选返回 true
     */
    public static boolean isNoteCandidate(String packageName, String fileName) {
        if (fileName == null || !fileName.endsWith(".txt")) {
            return false;
        }
        String stem = stripLastExt(packageName);
        return !stem.isEmpty() && fileName.startsWith(stem);
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

    /** 用于判断模板 .txt 的包名部分是否带扩展名（决定新文件用当前包全名还是 stem）。 */
    private static final java.util.List<String> KNOWN_PACKAGE_EXTS =
            java.util.Arrays.asList(".jar", ".war", ".ear", ".zip");

    /**
     * 当前包在目录下没有任何 note 文件时，参考目录里别的包的 note 命名风格，给当前包推一个同款新文件名。
     *
     * <p><b>分界规则</b>：模板 .txt 的"包名部分"和"后缀部分"以**第一个 {@code _}** 为界——
     * {@code <P>_<rest>.txt} → P 是包名部分，{@code _<rest>.txt} 是后缀模板。</p>
     *
     * <p><b>前缀风格判定</b>：</p>
     * <ul>
     *   <li>P 末尾是已知包扩展名（{@code .jar} / {@code .war} / {@code .ear} / {@code .zip}）
     *       → 模板用"全名前缀"，新文件 = 当前包全名 + 后缀</li>
     *   <li>否则视为"stem 前缀"，新文件 = 当前包 stem + 后缀</li>
     * </ul>
     *
     * <p>例：</p>
     * <ul>
     *   <li>模板 {@code scev6-utils-t22Hte_notes.txt}（P 无扩展）+ 当前 {@code scev6-utils-tms-9.0.0-SNAPSHOT.jar}
     *       → 新文件 {@code scev6-utils-tms-9.0.0-SNAPSHOT_notes.txt}</li>
     *   <li>模板 {@code scev6-utils-t22Hte.jar_notes.txt}（P 带 .jar）+ 当前 {@code scev6-utils-tms-9.0.0-SNAPSHOT.jar}
     *       → 新文件 {@code scev6-utils-tms-9.0.0-SNAPSHOT.jar_notes.txt}</li>
     * </ul>
     *
     * <p>不依赖目录里其他包的 jar/war 是否还在（客户经常会把别的包删掉只留 .txt）。</p>
     *
     * @param currentPkgFullName 当前部署的包全名（含扩展名）
     * @param dirFileNames       目录下所有文件的基名
     * @return 推断出的新文件名；目录里没有可参考的 {@code .txt} 时返回 null
     * @author xumanyi
     * @date 2026-05-12
     */
    public static String inferNoteFilenameForNewPackage(
            String currentPkgFullName,
            Iterable<String> dirFileNames) {
        String currentStem = stripLastExt(currentPkgFullName);
        for (String name : dirFileNames) {
            if (name == null || name.isEmpty()) continue;
            if (!name.endsWith(".txt")) continue;
            // 跳过看上去属于当前包的 .txt（理论上 pickPrimary 已处理；这里再防一道）
            if (!currentStem.isEmpty() && name.startsWith(currentStem)) continue;
            int underscore = name.indexOf('_');
            if (underscore <= 0) continue;
            String prefixPart = name.substring(0, underscore);
            String suffix = name.substring(underscore); // 含起始 _
            boolean fullStyle = false;
            String prefixLower = prefixPart.toLowerCase();
            for (String ext : KNOWN_PACKAGE_EXTS) {
                if (prefixLower.endsWith(ext)) {
                    fullStyle = true;
                    break;
                }
            }
            return (fullStyle ? currentPkgFullName : currentStem) + suffix;
        }
        return null;
    }
}
