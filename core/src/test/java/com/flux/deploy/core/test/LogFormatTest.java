package com.flux.deploy.core.test;

/**
 * {@link com.flux.deploy.plugin.toolwindow.LogSectionPanel#appendLog} 的格式行为快速校验。
 *
 * <p>不依赖 Swing UI；直接复刻 appendLog 中的"按 \n 拆行 + 每行带时间戳"逻辑，
 * 对几种典型消息进行转换并打印，肉眼核对是否符合预期。</p>
 *
 * @author xumanyi
 * @date 2026-04-19
 */
public class LogFormatTest {

    public static void main(String[] args) {
        // 用固定时间戳便于断言对比
        String ts = "13:00:00";

        check(ts, "普通消息",
                "[操作] 点击「打包并上传」");
        check(ts, "带前置空行（banner）",
                "\n╔══════════════════════════════╗\n║       ❌ 部署失败            ║\n╚══════════════════════════════╝");
        check(ts, "分隔线",
                "\n━━ 正在备份所有目标包 (2 个) ━━");
        check(ts, "只有一个 \\n 前缀",
                "\n[备份] 检查完成：无冲突");
        check(ts, "多行消息（首行非空 + 连续空行 + 末行非空）",
                "第一行\n\n第三行");
        check(ts, "末尾换行",
                "末尾有换行\n");
    }

    private static void check(String ts, String label, String message) {
        System.out.println("─── 用例：" + label + " ───");
        System.out.println("输入： " + java.util.Arrays.toString(
                message.replace("\n", "\\n").split("\\\\n", -1)));
        System.out.println("输出：");
        System.out.print(render(ts, message));
        System.out.println();
    }

    /** 复刻 LogSectionPanel.appendLog 的格式化逻辑 */
    private static String render(String ts, String message) {
        String[] lines = message == null ? new String[]{""} : message.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) sb.append('\n');
            else sb.append(ts).append("  ").append(line).append('\n');
        }
        return sb.toString();
    }
}
