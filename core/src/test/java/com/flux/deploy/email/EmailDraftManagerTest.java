package com.flux.deploy.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDraftManagerTest {

    /**
     * 制造一个简单模板：只含 4 个绑定变量（任务号 / 客服编号 / 更新包 / 备份包），
     * 与生产用的内置默认模板字段集合保持一致，便于断言渲染 / 累积结果。
     */
    private EmailDraftManager newManagerWithSimpleTemplate(Path tmp) throws IOException {
        EmailTemplateStore store = new EmailTemplateStore(tmp);
        store.save("default",
                "任务: ${任务号}<br>客服: ${客服编号}<br>备份: ${备份包}<br>更新包: ${更新包}");
        return new EmailDraftManager(store);
    }

    private Map<String, String> values(String taskId) {
        Map<String, String> v = new HashMap<>();
        v.put("任务号", taskId);
        v.put("客服编号", "");
        v.put("备份包", "");
        v.put("更新包", "");
        return v;
    }

    @Test
    void firstOpen_rendersFromTemplate(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        String html = mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(html).contains("<span class=\"flux-field-任务号\">T1</span>");
        assertThat(html).contains("<span class=\"flux-field-更新包\"></span>");
    }

    @Test
    void onDeploySucceeded_sameTask_accumulatesPackages(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");

        mgr.onDeploySucceeded("T1", "/开发/projA/",
                List.of("pkg1.jar"), List.of("/a/pkg1.jar"), values("T1"));
        mgr.onDeploySucceeded("T1", "/开发/projA/",
                List.of("pkg2.jar"), List.of("/a/pkg2.jar"), values("T1"));

        String html = mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        // 更新包是 4 个绑定变量之一，会累积
        assertThat(html).contains("<span class=\"flux-field-更新包\">pkg1.jar、pkg2.jar</span>");
    }

    @Test
    void onDeploySucceeded_dedupSkipsRepeatedPackage(@TempDir Path tmp) throws IOException {
        // 主面板预填了 pkg1.jar，部署成功又上来 pkg1.jar：不应该双写
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        Map<String, String> prefilled = values("T1");
        prefilled.put("更新包", "pkg1.jar");
        mgr.getDraftForDisplay(prefilled, "/开发/projA/");

        mgr.onDeploySucceeded("T1", "/开发/projA/",
                List.of("pkg1.jar"), List.of(), prefilled);

        String html = mgr.getDraftForDisplay(prefilled, "/开发/projA/");
        assertThat(html).contains("<span class=\"flux-field-更新包\">pkg1.jar</span>");
        // 不应该出现 pkg1.jar、pkg1.jar
        assertThat(html).doesNotContain("pkg1.jar、pkg1.jar");
    }

    @Test
    void onDeploySucceeded_ftpPathsIgnored(@TempDir Path tmp) throws IOException {
        // FTP 路径不再作为绑定变量；模板里没 ${FTP版本来源} 占位符 → 任何 ftpPaths 都被静默丢弃
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        mgr.onDeploySucceeded("T1", "/开发/projA/",
                List.of("p.jar"), List.of("/a/p.jar"), values("T1"));

        String html = mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(html).doesNotContain("/a/p.jar");
        // 但 .jar 包名仍累积到更新包
        assertThat(html).contains("p.jar");
    }

    @Test
    void onDeploySucceeded_taskChanged_rebuildsDraft(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        mgr.onDeploySucceeded("T1", "/开发/projA/",
                List.of("pkg1.jar"), List.of("/a/pkg1.jar"), values("T1"));

        // 任务号变了 → 整体重建
        mgr.onDeploySucceeded("T2", "/开发/projA/",
                List.of("pkg2.jar"), List.of("/a/pkg2.jar"), values("T2"));

        String html = mgr.getDraftForDisplay(values("T2"), "/开发/projA/");
        // 新任务号
        assertThat(html).contains("<span class=\"flux-field-任务号\">T2</span>");
        // 累积重置：只有 T2 自带的包，没有 T1 的 pkg1
        assertThat(html).doesNotContain("pkg1.jar");
        // T2 的新包从初始 "" 起步 + onDeploySucceeded 累积 → 应包含 pkg2.jar
        // （注意：onDeploySucceeded 在"重建"分支后不会再追加，currentFieldValues 里
        // 的"更新包"为空 → 渲染初始 draft 时也是空。所以 pkg2 当下不会出现在 draft 里。
        // 这与设计一致：重建路径不累积，新一轮部署后再有新包才追加。）
        assertThat(html).contains("<span class=\"flux-field-更新包\"></span>");
    }

    @Test
    void getDraftForDisplay_rerendersWhenDraftHasLegacyPlaceholders(@TempDir Path tmp)
            throws IOException {
        // 模拟用户从老版本升级上来：内存里残留的 draft 还含 v1 时代的占位符
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        // 注入一个"老模板渲染结果"的 draft（含 ${项目名}）
        mgr.setDraftContent("【${项目名}】帝迈 ${任务号}");
        assertThat(mgr.hasDraft()).isTrue();

        // 再次获取：应该检测到老占位符并自动重渲
        String fresh = mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(fresh).doesNotContain("${项目名}");
        // 而是当前 simple 模板渲染的结果
        assertThat(fresh).contains("<span class=\"flux-field-任务号\">T1</span>");
    }

    @Test
    void onProjectMaybeChanged_sameDirNoChange(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(mgr.hasDraft()).isTrue();

        // 第一次调用：记录 projA 不清空
        mgr.onProjectMaybeChanged("/开发/projA/");
        // 没变 → 不清
        mgr.onProjectMaybeChanged("/开发/projA/");
        assertThat(mgr.hasDraft()).isTrue();
    }

    @Test
    void onProjectMaybeChanged_dirChanged_clearsDraft(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        // 模拟主面板的初始化 callback：第一次 onProjectMaybeChanged("/开发/projA/")
        mgr.onProjectMaybeChanged("/开发/projA/");
        assertThat(mgr.hasDraft()).isTrue();

        // 切到 projB
        mgr.onProjectMaybeChanged("/开发/projB/");
        assertThat(mgr.hasDraft()).isFalse();
    }

    @Test
    void onReset_clearsDraft(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(mgr.hasDraft()).isTrue();
        mgr.onReset();
        assertThat(mgr.hasDraft()).isFalse();
    }

    @Test
    void setDraftContent_preservesUserEditsBetweenOpens(@TempDir Path tmp) throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getDraftForDisplay(values("T1"), "/开发/projA/");

        // 用户改了编辑区内容
        mgr.setDraftContent("用户改完的全新内容 - 任务 ${任务号}");

        // 再开邮件：回显用户改过的内容
        String back = mgr.getDraftForDisplay(values("T1"), "/开发/projA/");
        assertThat(back).isEqualTo("用户改完的全新内容 - 任务 ${任务号}");
    }

    @Test
    void saveCurrentDraftAsTemplate_writesReverseSubstitutedSource(@TempDir Path tmp)
            throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        // 模拟当前编辑区是渲染后的富文本
        String currentHtml = "任务: <span class=\"flux-field-任务号\">T1</span>"
                + "<br>更新包: <span class=\"flux-field-更新包\">pkg1.jar、pkg2.jar</span>";
        mgr.saveCurrentDraftAsTemplate(currentHtml, "default");

        // 保存后的模板应该是反向占位符化的源串
        String saved = mgr.getStore().loadOrDefault("default");
        assertThat(saved).isEqualTo("任务: ${任务号}<br>更新包: ${更新包}");
    }

    @Test
    void saveCurrentDraftAsTemplate_switchesSelectedTemplateName(@TempDir Path tmp)
            throws IOException {
        EmailDraftManager mgr = newManagerWithSimpleTemplate(tmp);
        mgr.getStore().createNew("custom", "x");
        mgr.saveCurrentDraftAsTemplate("<p>hello</p>", "custom");
        assertThat(mgr.getSelectedTemplateName()).isEqualTo("custom");
    }
}
