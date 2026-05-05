package com.flux.deploy.ftp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FtpErrorClassifierTest {

    @Test
    void classify_421TooManyConnections_isServerLimit() {
        IOException src = new IOException("FTP 421 Too many connections from this IP");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.SERVER_LIMIT);
        assertThat(FtpErrorClassifier.isGlobal(FtpErrorKind.SERVER_LIMIT)).isTrue();
    }

    @Test
    void classify_421MaxClients_isServerLimit() {
        IOException src = new IOException("响应: 421 max clients reached");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.SERVER_LIMIT);
    }

    @Test
    void classify_530LoginFailed_isAuth() {
        IOException src = new IOException("FTP 认证失败，用户名或密码错误");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.AUTH);
        assertThat(FtpErrorClassifier.isGlobal(FtpErrorKind.AUTH)).isTrue();
    }

    @Test
    void classify_530Code_isAuth() {
        IOException src = new IOException("响应: 530 Login incorrect");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.AUTH);
    }

    @Test
    void classify_connectException_isNetwork() {
        IOException src = new ConnectException("Connection refused");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.NETWORK);
        assertThat(FtpErrorClassifier.isGlobal(FtpErrorKind.NETWORK)).isTrue();
    }

    @Test
    void classify_socketTimeout_isNetwork() {
        IOException src = new SocketTimeoutException("read timed out");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.NETWORK);
    }

    @Test
    void classify_diskFull_isNetwork() {
        IOException src = new IOException("No space left on device");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.NETWORK);
    }

    @Test
    void classify_otherIOException_isProtocol_andSinglePackage() {
        IOException src = new IOException("550 File not found");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.PROTOCOL);
        assertThat(FtpErrorClassifier.isGlobal(FtpErrorKind.PROTOCOL)).isFalse();
    }

    @Test
    void classify_wrappedIOException_unwraps() {
        IOException inner = new IOException("421 too many users logged in");
        Exception outer = new Exception("backup wrapper", inner);
        assertThat(FtpErrorClassifier.classify(outer)).isEqualTo(FtpErrorKind.SERVER_LIMIT);
    }

    @Test
    void classify_unknownThrowable_isProtocolFallback() {
        RuntimeException src = new RuntimeException("some weird state");
        assertThat(FtpErrorClassifier.classify(src)).isEqualTo(FtpErrorKind.PROTOCOL);
    }

    @Test
    void classify_nullThrowable_isProtocolFallback() {
        assertThat(FtpErrorClassifier.classify(null)).isEqualTo(FtpErrorKind.PROTOCOL);
    }

    @Test
    void suggestionFor_serverLimit_advisesLowerParallelism_withoutNumber() {
        String suggestion = FtpErrorClassifier.suggestionFor(FtpErrorKind.SERVER_LIMIT);
        assertThat(suggestion).contains("降低");
        // 不应包含具体数字（用户明确要求）
        assertThat(suggestion).doesNotMatch(".*\\b\\d+\\b.*");
    }

    @Test
    void suggestionFor_auth_advisesCheckCredentials() {
        assertThat(FtpErrorClassifier.suggestionFor(FtpErrorKind.AUTH))
                .contains("认证").contains("凭证");
    }

    @Test
    void suggestionFor_network_advisesNetworkCheck() {
        assertThat(FtpErrorClassifier.suggestionFor(FtpErrorKind.NETWORK))
                .contains("网络");
    }

    @Test
    void suggestionFor_protocol_returnsNull() {
        // PROTOCOL 是单包错误，没有针对性建议
        assertThat(FtpErrorClassifier.suggestionFor(FtpErrorKind.PROTOCOL)).isNull();
    }
}
