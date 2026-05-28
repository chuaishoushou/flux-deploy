package com.flux.deploy.plugin.util;

import com.flux.deploy.plugin.model.FtpTargetSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 目标包"误勾备份目录"安全校验器（纯函数，无 IDE 依赖）。
 *
 * <p>用户在目标树上勾选目标包时，可能不小心勾到 FTP 上常规的备份目录
 * （如 {@code backup/foo.jar}、{@code system/bak/v1.0/foo.jar}）。
 * 这些位置通常存放历史包，覆盖会破坏回滚链。本类负责扫描勾选项的
 * {@code relativePath} 各级目录段，命中任何备份关键字即返回风险列表，
 * 由 UI 层在用户确认部署后弹窗二次确认。</p>
 *
 * <p>判定规则：</p>
 * <ul>
 *   <li>把 {@code relativePath} 按 {@code /} 拆段（最后一段是文件名，跳过）</li>
 *   <li>任意一段（去 trim、转小写）等于 {@link #DEFAULT_BACKUP_NAMES} 中任意一项即命中</li>
 *   <li>大小写不敏感；包含但不等于（如 {@code backup-2024}）暂不命中，避免误伤</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-08
 */
public final class BackupTargetGuard {

    /** 默认识别的备份目录名（小写、精确匹配）。命中即视为风险勾选 */
    public static final Set<String> DEFAULT_BACKUP_NAMES = Set.of("backup", "backups", "bak");

    /**
     * 备份目录"日期前缀"模式：8 位日期（yyyyMMdd 或 yyyy-MM-dd / yyyy_MM_dd）+ 可选的下划线/横线分隔符 + 任意后缀。
     *
     * <p>覆盖常见手工备份命名：</p>
     * <ul>
     *   <li>{@code 20250113_zhangsan}</li>
     *   <li>{@code 20250113-xumy}</li>
     *   <li>{@code 2025-01-13_bak}</li>
     *   <li>{@code 20250113}（纯日期）</li>
     * </ul>
     */
    private static final Pattern DATE_PREFIX_PATTERN = Pattern.compile(
            "^(\\d{4}[-_]?\\d{2}[-_]?\\d{2})([-_].+)?$");

    /**
     * 备份目录"日期后缀"模式：任意前缀 + 下划线/横线分隔符 + 8 位日期。
     *
     * <p>覆盖常见手工备份命名：</p>
     * <ul>
     *   <li>{@code zhangsan_20250113}</li>
     *   <li>{@code release-20250113}</li>
     *   <li>{@code bak_2025-01-13}</li>
     * </ul>
     */
    private static final Pattern DATE_SUFFIX_PATTERN = Pattern.compile(
            "^.+[-_](\\d{4}[-_]?\\d{2}[-_]?\\d{2})$");

    private BackupTargetGuard() {}

    /**
     * 命中条目：单个被识别为"位于备份目录"的勾选项 + 命中的备份段名。
     *
     * @author xumanyi
     * @date 2026-05-08
     */
    public static final class Hit {
        /** 勾选项的远程完整路径（含项目/系统/相对路径/包名），仅用于弹窗展示 */
        public final String remoteFullPath;
        /** 命中的备份段名（小写） */
        public final String matchedSegment;

        Hit(String remoteFullPath, String matchedSegment) {
            this.remoteFullPath = remoteFullPath;
            this.matchedSegment = matchedSegment;
        }
    }

    /**
     * 扫描勾选项，找出位于备份目录下的目标包。
     *
     * <p>{@code mainTargets} 与 {@code embedTargets} 一并扫描；任一为 {@code null} 视为空。</p>
     *
     * @param mainTargets  主目标包勾选
     * @param embedTargets 内嵌目标包勾选（war 内 jar）
     * @return 命中列表；为空表示全部安全，可继续部署
     * @author xumanyi
     * @date 2026-05-08
     */
    public static List<Hit> scan(List<FtpTargetSelection> mainTargets,
                                  List<FtpTargetSelection> embedTargets) {
        List<Hit> hits = new ArrayList<>();
        collect(mainTargets, hits);
        collect(embedTargets, hits);
        return hits;
    }

    /**
     * 早停版扫描：在主目标 + 嵌入目标中找到第一个命中即返回，不再继续遍历。
     *
     * <p>用于点击「执行更新」时的首要前置校验：用户可能勾选了 backup 根目录、连锁选中了
     * 几千个子包，此时无需把全部列举出来——找到任何一个就可以弹窗中止后续 FTP 备份冲突检测，
     * 避免几千次空跑的网络往返。</p>
     *
     * @param mainTargets  主目标包勾选
     * @param embedTargets 内嵌目标包勾选
     * @return 第一个命中；全部安全时返回 {@code null}
     * @author xumanyi
     * @date 2026-05-26
     */
    public static Hit findFirstHit(List<FtpTargetSelection> mainTargets,
                                    List<FtpTargetSelection> embedTargets) {
        Hit hit = findFirstIn(mainTargets);
        if (hit != null) return hit;
        return findFirstIn(embedTargets);
    }

    private static Hit findFirstIn(List<FtpTargetSelection> list) {
        if (list == null || list.isEmpty()) return null;
        for (FtpTargetSelection t : list) {
            Hit hit = toHit(t);
            if (hit != null) return hit;
        }
        return null;
    }

    private static void collect(List<FtpTargetSelection> list, List<Hit> hits) {
        if (list == null || list.isEmpty()) return;
        for (FtpTargetSelection t : list) {
            Hit hit = toHit(t);
            if (hit != null) hits.add(hit);
        }
    }

    /** 单条勾选项 → Hit；未命中返回 null。collect / findFirstIn 共用的路径拼接逻辑。 */
    private static Hit toHit(FtpTargetSelection t) {
        String matched = findBackupSegment(t.getRelativePath());
        if (matched == null) return null;
        // relativePath 在不同来源下可能含/不含文件名结尾，只在缺失时补 targetName，
        // 避免出现 "....jarxxx.jar" 这样的重复拼接
        String dir = t.getRemoteDir() == null ? "" : t.getRemoteDir();
        String rel = t.getRelativePath() == null ? "" : t.getRelativePath();
        String name = t.getTargetName() == null ? "" : t.getTargetName();
        String full = dir + rel;
        if (!name.isEmpty() && !full.endsWith(name)) {
            if (!full.isEmpty() && !full.endsWith("/")) full += "/";
            full += name;
        }
        return new Hit(full, matched);
    }

    /**
     * 在相对路径的目录段中查找备份关键字（精确等值、忽略大小写）。
     *
     * <p>最后一段视为文件名跳过；空路径返回 {@code null}。</p>
     *
     * @param relativePath FtpTargetSelection.relativePath（可能以 {@code /} 结尾）
     * @return 命中的备份段名（小写）；未命中返回 {@code null}
     * @author xumanyi
     * @date 2026-05-08
     */
    private static String findBackupSegment(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String trimmed = relativePath.replace('\\', '/').trim();
        // 去掉首尾 /，统一拆段
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return null;

        String[] segments = trimmed.split("/");
        // relativePath 在 FtpTargetSelection 语义里是"包相对系统目录的路径（含子目录）"，
        // 通常以 / 结尾、不含文件名；保险起见，若最后一段含 . 视为文件名跳过
        int lastIdx = segments.length;
        if (lastIdx > 0 && segments[lastIdx - 1].contains(".")) {
            lastIdx--;
        }
        for (int i = 0; i < lastIdx; i++) {
            String seg = segments[i].trim().toLowerCase(Locale.ROOT);
            if (DEFAULT_BACKUP_NAMES.contains(seg)) {
                return seg;
            }
            if (DATE_PREFIX_PATTERN.matcher(seg).matches() && isPlausibleDate(extractLeadingDigits(seg))) {
                return seg;
            }
            if (DATE_SUFFIX_PATTERN.matcher(seg).matches() && isPlausibleDate(extractTrailingDigits(seg))) {
                return seg;
            }
        }
        return null;
    }

    /**
     * 对 8 位日期数字串做基本合法性校验，避免把 {@code 99999999_x} 之类的纯数字目录误判。
     *
     * <p>仅校验"年/月/日"取值范围（年 1900-2999、月 1-12、日 1-31），不做"二月有没有 30 号"这类强校验。</p>
     *
     * @param digits 已抽取出的 8 位纯数字字符串；长度不足直接返回 false
     * @return true 表示日期合理，可视作备份目录
     * @author xumanyi
     * @date 2026-05-08
     */
    private static boolean isPlausibleDate(String digits) {
        if (digits == null || digits.length() != 8) return false;
        int year = Integer.parseInt(digits.substring(0, 4));
        int month = Integer.parseInt(digits.substring(4, 6));
        int day = Integer.parseInt(digits.substring(6, 8));
        return year >= 1900 && year <= 2999
                && month >= 1 && month <= 12
                && day >= 1 && day <= 31;
    }

    /**
     * 从字符串开头连续提取数字（跳过日期分隔符 {@code -} / {@code _}），最多 8 位。
     *
     * @param seg 目录段
     * @return 前 8 位日期数字；不足 8 位时返回截断结果（由 {@link #isPlausibleDate} 拒绝）
     * @author xumanyi
     * @date 2026-05-08
     */
    private static String extractLeadingDigits(String seg) {
        StringBuilder digits = new StringBuilder(8);
        for (int i = 0; i < seg.length() && digits.length() < 8; i++) {
            char c = seg.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != '-' && c != '_') {
                break;
            }
        }
        return digits.toString();
    }

    /**
     * 从字符串末尾向前连续提取数字（跳过日期分隔符 {@code -} / {@code _}），最多 8 位。
     *
     * @param seg 目录段
     * @return 末尾 8 位日期数字；不足 8 位时返回截断结果（由 {@link #isPlausibleDate} 拒绝）
     * @author xumanyi
     * @date 2026-05-08
     */
    private static String extractTrailingDigits(String seg) {
        StringBuilder reversed = new StringBuilder(8);
        for (int i = seg.length() - 1; i >= 0 && reversed.length() < 8; i--) {
            char c = seg.charAt(i);
            if (c >= '0' && c <= '9') {
                reversed.append(c);
            } else if (c != '-' && c != '_') {
                break;
            }
        }
        return reversed.reverse().toString();
    }

}
