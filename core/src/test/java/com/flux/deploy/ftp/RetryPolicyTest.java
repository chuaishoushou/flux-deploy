package com.flux.deploy.ftp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetryPolicy 单元测试。
 *
 * @author claude
 * @date 2026-05-03
 */
class RetryPolicyTest {

    @Test
    void networkDefault_3AttemptsWithExponentialBackoff() {
        RetryPolicy p = RetryPolicy.networkDefault();
        assertEquals(3, p.maxAttempts());
        assertEquals(Duration.ofSeconds(2), p.backoffFor(1));
        assertEquals(Duration.ofSeconds(5), p.backoffFor(2));
        assertEquals(Duration.ofSeconds(10), p.backoffFor(3));
    }

    @Test
    void backoffFor_clampsToLastEntryWhenExceedsSchedule() {
        RetryPolicy p = RetryPolicy.networkDefault();
        assertEquals(Duration.ofSeconds(10), p.backoffFor(99));
    }

    @Test
    void backoffFor_clampsToFirstWhenAttemptIsZeroOrNegative() {
        RetryPolicy p = RetryPolicy.networkDefault();
        assertEquals(Duration.ofSeconds(2), p.backoffFor(0));
        assertEquals(Duration.ofSeconds(2), p.backoffFor(-5));
    }

    @Test
    void backoffFor_returnsZeroForEmptySchedule() {
        RetryPolicy p = new RetryPolicy(1, List.of());
        assertEquals(Duration.ZERO, p.backoffFor(1));
    }

    @Test
    void shouldRetry_networkAndServerLimitRetryable_authAndProtocolNot() {
        RetryPolicy p = RetryPolicy.networkDefault();
        assertTrue(p.shouldRetry(FtpErrorKind.NETWORK));
        // 421 too many：退避后并发槽位会释放，应该重试
        assertTrue(p.shouldRetry(FtpErrorKind.SERVER_LIMIT));
        // 凭证错与业务错：重试无意义
        assertFalse(p.shouldRetry(FtpErrorKind.AUTH));
        assertFalse(p.shouldRetry(FtpErrorKind.PROTOCOL));
        assertFalse(p.shouldRetry(null));
    }

    @Test
    void constructor_rejectsZeroOrNegativeAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, List.of(Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(-1, List.of(Duration.ofSeconds(1))));
    }

    @Test
    void constructor_rejectsNullSchedule() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, null));
    }

    @Test
    void backoffSchedule_isImmutable() {
        java.util.ArrayList<Duration> mutable = new java.util.ArrayList<>();
        mutable.add(Duration.ofSeconds(1));
        RetryPolicy p = new RetryPolicy(1, mutable);
        mutable.add(Duration.ofSeconds(99));
        assertEquals(1, p.backoffSchedule().size(), "RetryPolicy 应内部拷贝防止外部修改");
    }
}
