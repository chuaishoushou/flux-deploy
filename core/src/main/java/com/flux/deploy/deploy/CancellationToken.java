package com.flux.deploy.deploy;

/**
 * 取消令牌：在每个 Gate 入口调用 throwIfCancelled 检查。
 *
 * <p>NOOP 永不取消；Simple 由前端层（IDE ProgressIndicator / CLI SIGINT handler）调 cancel()。</p>
 *
 * @author xumanyi
 * @date 2026-04-28
 */
public interface CancellationToken {

    /** 永不取消 */
    CancellationToken NOOP = new CancellationToken() {
        @Override public boolean isCancelled() { return false; }
        @Override public void throwIfCancelled() { /* no-op */ }
    };

    boolean isCancelled();

    /** 已取消则抛 CancellationException */
    void throwIfCancelled() throws CancellationException;

    /** 简单的标志位实现（CLI / 测试用） */
    final class Simple implements CancellationToken {
        private volatile boolean cancelled = false;
        public void cancel() { this.cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void throwIfCancelled() {
            if (cancelled) throw new CancellationException();
        }
    }

    /** 取消异常（unchecked，便于在 Gate 内部冒出） */
    class CancellationException extends RuntimeException {
        public CancellationException() { super("已取消"); }
    }
}
