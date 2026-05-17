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
        String pkg = "tm10srv.war";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_README.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.war_release_history.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv-anything.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv_v2_update_notes_20260511.txt"));
        // 正好 <stem>.txt 也算
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "tm10srv.txt"));
    }

    @Test
    void isNoteCandidate_acceptsStemFollowedByLetters() {
        // 中间字符不做约束：stem 紧跟字母也算（如 SNAPSHOTes.txt 这种客户手写的命名）
        String pkg = "scev6-utils-tms-9.0.0-SNAPSHOT.jar";
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOTes.txt"));
        assertTrue(NoteFileNames.isNoteCandidate(pkg, "scev6-utils-tms-9.0.0-SNAPSHOT.jar_update_notes.txt"));
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

    @Test
    void inferNoteFilename_stemTemplate_appliesStemStyle() {
        // 模板 P=scev6-utils-t22Hte（无扩展） → stem 风格
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "scev6-utils-tms-9.0.0-SNAPSHOT.jar",
                java.util.Arrays.asList("scev6-utils-t22Hte_notes.txt"));
        assertEquals("scev6-utils-tms-9.0.0-SNAPSHOT_notes.txt", result);
    }

    @Test
    void inferNoteFilename_fullnameTemplate_appliesFullStyle() {
        // 模板 P=scev6-utils-t22Hte.jar（带 .jar） → 全名风格
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "scev6-utils-tms-9.0.0-SNAPSHOT.jar",
                java.util.Arrays.asList("scev6-utils-t22Hte.jar_notes.txt"));
        assertEquals("scev6-utils-tms-9.0.0-SNAPSHOT.jar_notes.txt", result);
    }

    @Test
    void inferNoteFilename_warExtension_isAlsoFullStyle() {
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList("pkgB.war_update_notes.txt"));
        assertEquals("pkgA.war_update_notes.txt", result);
    }

    @Test
    void inferNoteFilename_stemPrefix_warCase() {
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList("pkgB_update_notes.txt"));
        assertEquals("pkgA_update_notes.txt", result);
    }

    @Test
    void inferNoteFilename_takesFirstMatchingTemplate() {
        // 多个候选时按迭代顺序取第一个能解析的
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList(
                        "pkgB.war_update_notes.txt",
                        "pkgC_update_note.txt"));
        assertEquals("pkgA.war_update_notes.txt", result);
    }

    @Test
    void inferNoteFilename_returnsNullWhenNoTxtAtAll() {
        assertNull(NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList("pkgA.war")));
    }

    @Test
    void inferNoteFilename_returnsNullWhenTxtHasNoUnderscore() {
        // 没有 _ 无法分界
        assertNull(NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList("readme.txt")));
    }

    @Test
    void inferNoteFilename_skipsTxtsBelongingToCurrentPackage() {
        // 当前包 stem 开头的 .txt 跳过（防御性，正常 pickPrimary 已处理）
        String result = NoteFileNames.inferNoteFilenameForNewPackage(
                "pkgA.war",
                java.util.Arrays.asList(
                        "pkgA_old.txt",
                        "pkgB_update_notes.txt"));
        assertEquals("pkgA_update_notes.txt", result);
    }
}
