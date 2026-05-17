package com.flux.deploy.plugin.util;

import java.time.LocalDateTime;

/**
 * 一次部署执行的 header 元数据，用于写入日志文件首部并构造文件路径。
 *
 * @param packageFileName 远端包文件名（含扩展名，如 tm10srv.war），同时也是日志子目录名
 * @param operator        操作者（如 xumanyi）
 * @param remoteTarget    远端目标描述（如 ftp://host/项目/系统/，本地模式可写本地路径）
 * @param ideVersion      IDE 版本（如 IntelliJ IDEA 2024.3）
 * @param pluginVersion   插件版本（如 flux-deploy-plugin 1.2.7）
 * @param startTime       部署开始时间，决定文件名的时间戳部分
 * @author xumanyi
 * @date 2026-05-07
 */
public record DeployRunMeta(
        String packageFileName,
        String operator,
        String remoteTarget,
        String ideVersion,
        String pluginVersion,
        LocalDateTime startTime
) {
}
