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
    /** 是否为新建目标：远端尚无同名文件/目录，上传即首次投放（Vue 首次部署场景） */
    private final boolean createNew;

    /**
     * 是否为 Vue 模块目录目标：目标不是单个文件而是解包形态的模块目录
     * （如 {@code sec/A06SysBizWebVue/a0535}），更新 = 用本地 dist 产物逐文件覆盖该目录。
     * 此时 {@code targetName} = 模块号（如 a0535），{@code relativePath} = 模块目录相对系统根的路径。
     */
    private final boolean vueModuleDir;

    /**
     * 构造 FTP 目标选择（覆盖已有包的常规场景）
     *
     * @param project      项目名称
     * @param system       系统名称
     * @param targetName   目标包文件名
     * @param relativePath 包相对于系统目录的路径（含子目录）
     * @author xumanyi
     * @date 2026-03-27
     */
    public FtpTargetSelection(String project, String system, String targetName, String relativePath) {
        this(project, system, targetName, relativePath, false);
    }

    /**
     * 构造 FTP 目标选择
     *
     * @param project      项目名称
     * @param system       系统名称
     * @param targetName   目标包文件名
     * @param relativePath 包相对于系统目录的路径（含子目录）
     * @param createNew    是否为新建目标（远端尚无同名文件）
     * @author xumanyi
     * @date 2026-08-13
     */
    public FtpTargetSelection(String project, String system, String targetName, String relativePath,
                              boolean createNew) {
        this(project, system, targetName, relativePath, createNew, false);
    }

    /**
     * 构造 FTP 目标选择（完整参数）
     *
     * @param project      项目名称
     * @param system       系统名称
     * @param targetName   目标包文件名（目录目标时为模块号）
     * @param relativePath 相对于系统目录的路径（含子目录）
     * @param createNew    是否为新建目标（远端尚无同名文件/目录）
     * @param vueModuleDir 是否为 Vue 模块目录目标（解包形态，逐文件覆盖更新）
     * @author xumanyi
     * @date 2026-08-13
     */
    public FtpTargetSelection(String project, String system, String targetName, String relativePath,
                              boolean createNew, boolean vueModuleDir) {
        this.project = project;
        this.system = system;
        this.targetName = targetName;
        this.relativePath = relativePath;
        this.createNew = createNew;
        this.vueModuleDir = vueModuleDir;
    }

    /**
     * 是否为新建目标（远端尚无同名文件/目录，上传即首次投放）
     *
     * @return true 表示新建目标
     * @author xumanyi
     * @date 2026-08-13
     */
    public boolean isCreateNew() { return createNew; }

    /**
     * 是否为 Vue 模块目录目标（解包形态，逐文件覆盖更新）
     *
     * @return true 表示模块目录目标
     * @author xumanyi
     * @date 2026-08-13
     */
    public boolean isVueModuleDir() { return vueModuleDir; }

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
     * <p>系统名为空（项目把包直接放在项目根目录、没有系统层）时退化为项目根路径，
     * 避免拼出 {@code /开发/项目/null/} 这种非法路径。下游统一以
     * {@code getRemoteDir() + getRelativePath()} 拼绝对路径，故此处退化即可让整条部署链路支持无系统场景。</p>
     *
     * @return 远程目录（如 /开发/项目/系统/；系统为空时为 /开发/项目/）
     * @author xumanyi
     * @date 2026-03-27
     */
    public String getRemoteDir() {
        if (system == null || system.isEmpty()) {
            return "/开发/" + project + "/";
        }
        return "/开发/" + project + "/" + system + "/";
    }
}
