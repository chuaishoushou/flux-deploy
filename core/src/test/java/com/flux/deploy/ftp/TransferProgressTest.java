package com.flux.deploy.ftp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫 {@link TransferProgress.Tracker} 的节流与文案契约
 *
 * <p>锁住三条行为：大文件开始时立即打"开始"头行；进度行按时间间隔节流
 * （小文件/快传输一条进度行都不打）；进度行携带 已传/总量/百分比/速率/预计剩余。</p>
 */
class TransferProgressTest {

    /** 手动可拨的纳秒时钟 */
    private final AtomicLong nanos = new AtomicLong(0);

    private TransferProgress.Tracker newTracker(List<String> lines, long initial, long total) {
        return new TransferProgress.Tracker("[上传] a.war", initial, total,
                lines::add, 5_000, nanos::get);
    }

    @Test
    void announcesStartForLargeTotal() {
        List<String> lines = new ArrayList<>();
        newTracker(lines, 0, 64L * 1024 * 1024);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("[上传] a.war 开始传输", "64.0 MB");
    }

    @Test
    void announcesResumeOffset() {
        List<String> lines = new ArrayList<>();
        newTracker(lines, 10L * 1024 * 1024, 64L * 1024 * 1024);
        assertThat(lines.get(0)).contains("从 10.0 MB 处续传");
    }

    @Test
    void silentForSmallTotal() {
        List<String> lines = new ArrayList<>();
        TransferProgress.Tracker t = newTracker(lines, 0, 1024 * 1024);
        t.onBytes(1024 * 1024);
        assertThat(lines).isEmpty();
    }

    @Test
    void throttlesByInterval() {
        List<String> lines = new ArrayList<>();
        long total = 100L * 1024 * 1024;
        TransferProgress.Tracker t = newTracker(lines, 0, total);
        lines.clear();

        // 间隔未到：不输出
        t.onBytes(10L * 1024 * 1024);
        assertThat(lines).isEmpty();

        // 拨过 5 秒：输出一条带 总量/已传/剩余/百分比/速率/预计还需 的进度行
        nanos.set(5_000_000_000L);
        t.onBytes(10L * 1024 * 1024);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("共 100.0 MB", "已传 20.0 MB", "(20%)", "剩余 80.0 MB", "/s", "预计还需");

        // 同一间隔内再传：不再输出
        t.onBytes(1024);
        assertThat(lines).hasSize(1);
    }

    @Test
    void tracksTransferredIncludingInitialOffset() {
        List<String> lines = new ArrayList<>();
        TransferProgress.Tracker t = newTracker(lines, 5, 100);
        t.onBytes(7);
        assertThat(t.transferred()).isEqualTo(12);
    }

    /**
     * 批量进度：满 5 秒间隔才汇报一次，行内带 文件数 + 总量 / 已传 / 剩余
     */
    @Test
    void batchProgress_throttlesAndReportsFilesAndBytes() {
        List<String> lines = new ArrayList<>();
        TransferProgress.BatchProgress p = new TransferProgress.BatchProgress(
                "INFO  [上传]", 100L * 1024 * 1024, 10, lines::add, 5_000, nanos::get);

        p.onFileDone(10L * 1024 * 1024);
        assertThat(lines).isEmpty();

        nanos.set(5_000_000_000L);
        p.onFileDone(10L * 1024 * 1024);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("INFO  [上传] 进度 2/10 个文件",
                "共 100.0 MB", "已传 20.0 MB", "(20%)", "剩余 80.0 MB", "/s");
    }

    /**
     * 批量进度：最后一个文件完成时无论间隔都补一条收尾行，小批量也能看到总量汇报
     */
    @Test
    void batchProgress_alwaysReportsOnLastFile() {
        List<String> lines = new ArrayList<>();
        TransferProgress.BatchProgress p = new TransferProgress.BatchProgress(
                "INFO  [备份]", 2048, 2, lines::add, 5_000, nanos::get);

        p.onFileDone(1024);
        assertThat(lines).isEmpty();

        p.onFileDone(1024);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("进度 2/2 个文件", "已传 2.0 KB", "(100%)", "剩余 0 B");
        // 传完不再提"预计还需"
        assertThat(lines.get(0)).doesNotContain("预计还需");
    }

    @Test
    void formatsEta() {
        assertThat(TransferProgress.formatEta(45)).isEqualTo("45 秒");
        assertThat(TransferProgress.formatEta(192)).isEqualTo("3 分 12 秒");
    }
}
