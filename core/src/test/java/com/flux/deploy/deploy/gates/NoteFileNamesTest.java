package com.flux.deploy.deploy.gates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NoteFileNames} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>canonical 始终带复数 s</li>
 *   <li>{@code isNoteCandidate} 在各类命名（含/不含扩展名、单数复数、stem 前缀、任意中间字符）下的判定</li>
 *   <li>跨包碰撞防护：stem 后必须是非字母数字</li>
 * </ul>
 *
 * @author xumanyi
 * @date 2026-05-11
 */
class NoteFileNamesTest {

    @Test
    void canonicalName_isPluralUpdateNotes() {
        assertEquals("tm10srv.war_update_notes.txt",
                NoteFileNames.canonicalName("tm10srv.war"));
        assertEquals("a30OubBizSrv.war_update_notes.txt",
                NoteFileNames.canonicalName("a30OubBizSrv.war"));
    }

    @Test
    void isNoteCandidate_acceptsCanonicalAndAllKnownLegacyNamings() {
        String pkg = "tm10srv.war";
        // canonical 也会通过谓词，调用者负责按名称单独挑出
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war_update_notes.txt"));
        // 历史 legacy 形态
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war_update_note.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_update_notes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_update_note.txt"));
    }

    @Test
    void isNoteCandidate_acceptsArbitraryMiddleContent() {
        // 切到首个 _ 后 base = tm10srv 即可命中（不再要求 _update_notes 这类固定后缀）
        String pkg = "tm10srv.war";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_README.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war_release_history.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_v2_update_notes_20260511.txt"));
        // 正好 <base>.txt 也算（无 _ 无 -<数字>，base 直接等）
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.txt"));
    }

    @Test
    void isNoteCandidate_rejectsSiblingWithDifferentBase() {
        // 新 fuzzy 规则：候选 base 若含「-非数字」拼接（兄弟包），不算同一包的 note
        String pkg = "tm10srv.war";
        // tm10srv-anything 的 base 是 tm10srv-anything（无 _ 无 -<数字>），与 pkg base tm10srv 不等
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "tm10srv-anything.txt"));
    }

    @Test
    void isNoteCandidate_acceptsStemFollowedByLetters() {
        // SNAPSHOTes.txt：candidate 切到 -9（首个 -<数字>）后 base = scev6-utils-tms，与 pkg base 等
        String pkg = "scev6-utils-tms-9.0.0-SNAPSHOT.jar";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOTes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOT.jar_update_notes.txt"));
    }

    @Test
    void isNoteCandidate_acceptsFuzzyMatchAcrossVersions() {
        // 核心场景：旧 note（无版本号）和新 canonical（带版本号）都该命中同一个包
        String pkg = "scev6-utils-tms-9.0.0-SNAPSHOT.jar";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms_update_notes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOT_update_notes.txt"));
    }

    @Test
    void isNoteCandidate_rejectsSiblingFamilyPackage() {
        // shared-tms 那个目录里的真实碰撞案例
        String pkg = "scev6-utils-9.0.0-SNAPSHOT.jar";
        // 兄弟包不能误中：scev6-utils（base）≠ scev6-utils-tms / scev6-utils-apps
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOT_update_notes.txt"));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms_update_notes.txt"));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-apps_update_notes.txt"));
        // 自家两种命名仍然命中
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils_update_notes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-9.0.0-SNAPSHOT_update_notes.txt"));
    }

    @Test
    void isNoteCandidate_rejectsNonTxt() {
        String pkg = "tm10srv.war";
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war_update_notes.log"));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war"));
    }

    @Test
    void isNoteCandidate_rejectsUnrelatedFiles() {
        String pkg = "tm10srv.war";
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "other_pkg.war_update_notes.txt"));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, "update_notes.txt"));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, ""));
        assertFalse(NoteFileNames.isNoteCandidate(pkg, null));
    }

    @Test
    void isNoteCandidate_handlesPackageWithoutExtension() {
        // 包名本身就没有扩展名时 stem == 包名
        String pkg = "myservice";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "myservice_update_notes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "myservice_README.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "myservice.txt"));
    }

    @Test
    void stripLastExt_behaviour() {
        assertEquals("a.tar", NoteFileNames.stripLastExt("a.tar.gz"));
        assertEquals("plainpkg", NoteFileNames.stripLastExt("plainpkg"));
        assertEquals(".hidden", NoteFileNames.stripLastExt(".hidden"));
    }
}
