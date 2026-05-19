package com.flux.deploy.ftp;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FtpLock TTL 相关行为的单元测试。
 *
 * @author xumanyi
 * @date 2026-05-03
 */
class FtpLockTtlTest {

    @Test
    void buildLockName_appendsTtlSuffix() {
        String name = FtpLock.buildLockName("app.jar", "alice", 15);
        assertTrue(name.contains("__LOCK__alice_"));
        assertTrue(name.endsWith("_TTL15"), "锁名应以 _TTL15 结尾，实际: " + name);
    }

    @Test
    void buildLockName_defaultUsesTenMinutes() {
        String name = FtpLock.buildLockName("app.jar", "alice");
        assertTrue(name.endsWith("_TTL10"), "默认应使用 TTL10，实际: " + name);
    }

    @Test
    void parseExpireAt_newFormat() {
        String name = "app.jar__LOCK__alice_20260503_120000_TTL15";
        LocalDateTime expire = FtpLock.parseExpireAt(name);
        assertEquals(LocalDateTime.of(2026, 5, 3, 12, 15, 0), expire);
    }

    @Test
    void parseExpireAt_legacyFormatFallsBackToDefaultTen() {
        String name = "app.jar__LOCK__alice_20260503_120000";
        LocalDateTime expire = FtpLock.parseExpireAt(name);
        assertEquals(LocalDateTime.of(2026, 5, 3, 12, 10, 0), expire);
    }

    @Test
    void parseExpireAt_returnsNullForGarbage() {
        assertNull(FtpLock.parseExpireAt(null));
        assertNull(FtpLock.parseExpireAt("not_a_lock"));
        assertNull(FtpLock.parseExpireAt("app.jar__LOCK__alice_BADTIME"));
    }

    @Test
    void parseLockInfo_strippsTtlSuffixAndStillReturnsOperator() {
        String name = "app.jar__LOCK__alice_20260503_120000_TTL15";
        String[] info = FtpLock.parseLockInfo(name);
        assertNotNull(info);
        assertEquals("alice", info[0]);
        assertEquals("20260503_120000", info[1]);
    }

    @Test
    void parseLockInfo_doesNotStripBusinessSuffixThatLooksLikeTtl() {
        // 后面跟非纯数字时不应被当作 TTL 剥除；不抛异常即可
        String name = "app.jar__LOCK__alice_20260503_120000_TTLabc";
        String[] info = FtpLock.parseLockInfo(name);
        assertNotNull(info);
        assertNotNull(info[0]);
        assertNotNull(info[1]);
    }

    @Test
    void buildLockName_rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> FtpLock.buildLockName("a.jar", "alice", 0));
        assertThrows(IllegalArgumentException.class,
                () -> FtpLock.buildLockName("a.jar", "alice", -1));
    }
}
