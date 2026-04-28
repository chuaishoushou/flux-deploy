package com.flux.deploy.deploy;

import com.flux.deploy.model.TargetPackage;

import java.io.IOException;

/**
 * 门禁接口
 *
 * <p>每个门禁代表部署流水线中的一个原子步骤。
 * 门禁执行成功才能进入下一步，失败则触发回滚。</p>
 *
 * @author xumanyi
 * @date 2026-03-26
 */
public interface Gate {

    /**
     * 门禁名称（用于日志和错误报告）
     *
     * @return 门禁名称字符串
     * @author xumanyi
     * @date 2026-03-26
     */
    String name();

    /**
     * 对单个目标包执行门禁检查/操作
     *
     * @param target 目标包
     * @throws IOException    FTP 操作失败
     * @throws GateException  业务逻辑校验失败
     * @author xumanyi
     * @date 2026-03-26
     */
    void execute(TargetPackage target) throws IOException, GateException;

    /**
     * 门禁失败异常
     */
    class GateException extends Exception {
        private final String gateName;

        /**
         * 创建门禁异常
         *
         * @param gateName 门禁名称
         * @param message  错误信息
         * @author xumanyi
         * @date 2026-03-26
         */
        public GateException(String gateName, String message) {
            super(message);
            this.gateName = gateName;
        }

        /**
         * 创建带原因的门禁异常
         *
         * @param gateName 门禁名称
         * @param message  错误信息
         * @param cause    原始异常
         * @author xumanyi
         * @date 2026-03-26
         */
        public GateException(String gateName, String message, Throwable cause) {
            super(message, cause);
            this.gateName = gateName;
        }

        /**
         * 获取门禁名称
         *
         * @return 门禁名称
         * @author xumanyi
         * @date 2026-03-26
         */
        public String getGateName() { return gateName; }
    }
}
