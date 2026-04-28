package com.flux.deploy.plugin.model;

/**
 * FTP 目标选择：项目 → 系统 → 目标包
 *
 * <p>封装用户在目标面板中选择的 FTP 路径信息，
 * 包括项目名、系统名、目标包名和相对路径。</p>
 *
 * @author xumanyi
 * @date 2026-03-27
 */
public class FtpTargetSelection {

    private final String project;
    private final String system;
    private final String targetName;
    private final String relativePath;

    /**
     * 构造 FTP 目标选择
     *
     * @param project      项目名称
     * @param system       系统名称
     * @param targetName   目标包文件名
     * @param relativePath 包相对于系统目录的路径（含子目录）
     * @author xumanyi
     * @date 2026-03-27
     */
    public FtpTargetSelection(String project, String system, String targetName, String relativePath) {
        this.project = project;
        this.system = system;
        this.targetName = targetName;
        this.relativePath = relativePath;
    }

    /**
     * 获取项目名称
     *
     * @return 项目名称
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getProject() { return project; }

    /**
     * 获取系统名称
     *
     * @return 系统名称
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getSystem() { return system; }

    /**
     * 获取目标包文件名
     *
     * @return 目标包文件名
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getTargetName() { return targetName; }

    /**
     * 获取包相对于系统目录的路径
     *
     * @return 包相对于系统目录的路径
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getRelativePath() { return relativePath; }

    /**
     * 构建完整远程目录路径
     *
     * @return 远程目录（如 /开发/项目/系统/）
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getRemoteDir() {
        return "/开发/" + project + "/" + system + "/";
    }
}
