package com.flux.deploy.deploy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    void noopToken_isNeverCancelled() {
        CancellationToken t = CancellationToken.NOOP;
        assertThat(t.isCancelled()).isFalse();
        t.throwIfCancelled(); // not throws
    }

    @Test
    void simpleToken_throwsAfterCancel() {
        CancellationToken.Simple t = new CancellationToken.Simple();
        assertThat(t.isCancelled()).isFalse();
        t.cancel();
        assertThat(t.isCancelled()).isTrue();
        assertThatThrownBy(t::throwIfCancelled).isInstanceOf(CancellationToken.CancellationException.class);
    }
}
