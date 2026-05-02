package com.flux.deploy.plugin.toolwindow;

import com.flux.deploy.ftp.FtpOperations;
import com.flux.deploy.ftp.FtpSession;
import com.flux.deploy.plugin.service.FtpBrowseService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.IOException;
import java.util.List;

/**
 * 备份位置选择对话框
 *
 * <p>提供 FTP 目录树浏览与选定，作为 customBackupRoot 写入
 * {@code PluginSettingsService.State.customBackupRoots}。</p>
 *
 * <p>核心规则：所选即所得 —— 用户在树上选定的目录就是备份根，
 * 下游 {@code DeployExecutionService.preBackupAll} 直接以此为父目录
 * 创建 {@code yyyyMMdd_{operator}/} 子目录写入。无任何拼接、无 toggle、无名字检测。</p>
 *
 * <p>默认选中策略（按优先级）：</p>
 * <ol>
 *   <li>当前已存的 customBackupRoot → 展开并选中该路径</li>
 *   <li>FTP 上 {@code contextDir/backup/} 已存在 → 选中它（与默认派生体感一致）</li>
 *   <li>否则 → 选中 contextDir</li>
 * </ol>
 *
 * @author xumanyi
 * @date 2026-05-02
 */
public class BackupLocationDialog extends DialogWrapper {

    /** FTP 主机 */
    private final String host;
    /** FTP 端口 */
    private final int port;
    /** FTP 用户名 */
    private final String username;
    /** FTP 密码 */
    private final String password;
    /** 当前 FTP 上下文路径（如 /开发/客户A/系统B/） */
    private final String contextDir;
    /** 当前已存的 customBackupRoot，未自定义时为 null */
    private final String currentCustomRoot;

    /** 目录树 */
    private Tree tree;
    /** 树模型 */
    private DefaultTreeModel treeModel;
    /** "已选"路径标签 */
    private JBLabel selectedPathLabel;
    /** FTP 浏览服务（对话框生命周期内复用） */
    private FtpBrowseService browseService;

    /** 用户最终选定的备份根；点恢复默认或取消时为 null */
    private String resultBackupRoot;
    /** 用户是否点了"恢复默认"，对外区分"取消"与"清除自定义" */
    private boolean restoreDefault;

    /**
     * 创建备份位置选择对话框
     *
     * @param project           当前 IDEA 项目
     * @param host              FTP 主机
     * @param port              FTP 端口
     * @param username          FTP 用户名
     * @param password          FTP 密码
     * @param contextDir        当前 FTP 上下文路径（项目+系统 或 仅项目）
     * @param currentCustomRoot 当前已存的 customBackupRoot；未自定义时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public BackupLocationDialog(Project project, String host, int port,
                                 String username, String password,
                                 String contextDir, @Nullable String currentCustomRoot) {
        super(project, false);
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.contextDir = contextDir;
        this.currentCustomRoot = currentCustomRoot;

        setTitle("选择备份位置");
        setOKButtonText("确认");
        setCancelButtonText("取消");
        init();
    }

    /**
     * 创建对话框中央内容面板
     *
     * @return 中央内容面板
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(JBUI.Borders.empty(10, 14));
        root.setPreferredSize(new Dimension(560, 460));

        JBLabel hint = new JBLabel("<html>提示：选定目录将作为备份根，"
                + "备份按 <code>{yyyyMMdd}_{开发}/</code> 子目录写入。</html>");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        root.add(hint, BorderLayout.NORTH);

        // 目录树：根节点带占位子节点，让它显示展开图标
        FtpDirNode rootData = new FtpDirNode("/", "/");
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(rootData);
        rootNode.add(new DefaultMutableTreeNode("加载中..."));
        treeModel = new DefaultTreeModel(rootNode);
        tree = new Tree(treeModel);
        tree.setRootVisible(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        // 懒加载监听器：节点展开前异步拉取子目录
        tree.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
            @Override public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
                Object last = event.getPath().getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) last;
                    Object userObj = node.getUserObject();
                    if (userObj instanceof FtpDirNode) {
                        loadChildrenIfNeeded(node, (FtpDirNode) userObj);
                    }
                }
            }
            @Override public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) { }
        });
        // 选中变化：同步底部"已选"路径
        tree.addTreeSelectionListener(e -> updateSelectedPathLabel());

        JBScrollPane treeScroll = new JBScrollPane(tree);
        root.add(treeScroll, BorderLayout.CENTER);

        // 底部"已选"路径标签
        JPanel bottom = new JPanel(new BorderLayout());
        selectedPathLabel = new JBLabel("已选：（未选择）");
        bottom.add(selectedPathLabel, BorderLayout.WEST);
        root.add(bottom, BorderLayout.SOUTH);

        // 异步执行默认展开 + 选中策略，不阻塞 UI 渲染
        SwingUtilities.invokeLater(this::applyDefaultSelection);

        return root;
    }

    /**
     * 创建左下角的"新建文件夹"和"恢复默认"按钮
     *
     * @return 左侧操作按钮数组
     * @author xumanyi
     * @date 2026-05-02
     */
    @Override
    protected Action @Nullable [] createLeftSideActions() {
        Action newFolderAction = new AbstractAction("新建文件夹") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                onNewFolder();
            }
        };
        Action restoreAction = new AbstractAction("恢复默认") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                onRestoreDefault();
            }
        };
        return new Action[]{newFolderAction, restoreAction};
    }

    // ==================== 默认选中 ====================

    /**
     * 按优先级应用默认选中策略
     *
     * <p>contextDir 为 null 时（项目未选）只展开根节点。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void applyDefaultSelection() {
        // 第一步：展开根节点触发懒加载
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) treeModel.getRoot();
        tree.expandPath(new TreePath(rootNode.getPath()));

        if (contextDir == null) return;

        // 异步链式展开：根 → 开发 → 项目 → 系统？ → backup？
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String targetPath;
            if (currentCustomRoot != null && !currentCustomRoot.isBlank()) {
                targetPath = currentCustomRoot.endsWith("/") ? currentCustomRoot : currentCustomRoot + "/";
            } else {
                // 探测 contextDir/backup/ 是否存在
                String backupCandidate = contextDir + "backup/";
                boolean backupExists = false;
                try {
                    FtpBrowseService svc = ensureBrowseService();
                    List<String> subs = svc.listSubdirectories(contextDir);
                    backupExists = subs.contains("backup");
                } catch (IOException ignored) { }
                targetPath = backupExists ? backupCandidate : contextDir;
            }

            final String finalTarget = targetPath;
            SwingUtilities.invokeLater(() -> expandToPath(finalTarget));
        });
    }

    /**
     * 在树中展开并选中指定 FTP 绝对路径对应的节点
     *
     * <p>路径必须以 / 开头，可不以 / 结尾。逐段展开，节点不存在时停在最深可达祖先。</p>
     *
     * @param ftpPath 目标 FTP 路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private void expandToPath(String ftpPath) {
        if (ftpPath == null || ftpPath.isBlank()) return;
        String trimmed = ftpPath.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.isEmpty()) return;
        String[] segments = trimmed.split("/");
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        expandSegmentRecursive(root, segments, 0);
    }

    /**
     * 递归展开 / 选中：先确保当前节点已加载子目录，再向下匹配 segments[index]
     *
     * @param node     当前节点
     * @param segments 路径段数组（已去前后 /）
     * @param index    当前要匹配的段下标
     * @author xumanyi
     * @date 2026-05-02
     */
    private void expandSegmentRecursive(DefaultMutableTreeNode node, String[] segments, int index) {
        if (index >= segments.length) {
            // 到达目标节点，选中
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            updateSelectedPathLabel();
            return;
        }

        Object userObj = node.getUserObject();
        if (!(userObj instanceof FtpDirNode)) return;
        FtpDirNode dirNode = (FtpDirNode) userObj;

        Runnable afterLoad = () -> {
            // 在子节点中找匹配段
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                Object cuo = child.getUserObject();
                if (cuo instanceof FtpDirNode && segments[index].equals(((FtpDirNode) cuo).displayName)) {
                    TreePath childPath = new TreePath(child.getPath());
                    tree.expandPath(childPath);
                    expandSegmentRecursive(child, segments, index + 1);
                    return;
                }
            }
            // 没找到，选中当前节点为终点
            TreePath cur = new TreePath(node.getPath());
            tree.setSelectionPath(cur);
            tree.scrollPathToVisible(cur);
            updateSelectedPathLabel();
        };

        if (dirNode.loaded) {
            afterLoad.run();
            return;
        }

        // 未加载：先加载，加载完后续展开
        dirNode.loaded = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> subDirs;
            try {
                subDirs = ensureBrowseService().listSubdirectories(dirNode.absPath);
            } catch (IOException e) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                node.removeAllChildren();
                for (String sub : subDirs) {
                    FtpDirNode childData = new FtpDirNode(dirNode.absPath + sub + "/", sub);
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(childData);
                    child.add(new DefaultMutableTreeNode("加载中..."));
                    node.add(child);
                }
                if (node.getChildCount() == 0) {
                    node.add(new DefaultMutableTreeNode("（空）"));
                }
                treeModel.reload(node);
                afterLoad.run();
            });
        });
    }

    // ==================== 懒加载 ====================

    /**
     * 懒加载指定节点的子目录
     *
     * <p>已加载过则直接返回；否则后台线程拉取 FTP 子目录列表，回到 EDT 渲染。
     * 失败时把节点 user object 标记 loaded=true 并显示一个错误占位节点，不弹错误对话框。</p>
     *
     * @param node     树节点
     * @param dirNode  绑定的 FTP 目录数据
     * @author xumanyi
     * @date 2026-05-02
     */
    private void loadChildrenIfNeeded(DefaultMutableTreeNode node, FtpDirNode dirNode) {
        if (dirNode.loaded) return;
        dirNode.loaded = true;

        // 立即清掉占位节点
        node.removeAllChildren();
        DefaultMutableTreeNode loadingPlaceholder = new DefaultMutableTreeNode("加载中...");
        node.add(loadingPlaceholder);
        treeModel.reload(node);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            FtpBrowseService svc;
            try {
                svc = ensureBrowseService();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode("（连接失败：" + e.getMessage() + "）"));
                    treeModel.reload(node);
                });
                return;
            }

            List<String> subDirs;
            try {
                subDirs = svc.listSubdirectories(dirNode.absPath);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode("（列目录失败：" + e.getMessage() + "）"));
                    treeModel.reload(node);
                });
                return;
            }

            SwingUtilities.invokeLater(() -> {
                node.removeAllChildren();
                for (String sub : subDirs) {
                    FtpDirNode childData = new FtpDirNode(dirNode.absPath + sub + "/", sub);
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(childData);
                    // 子节点也加占位节点，让它显示可展开
                    child.add(new DefaultMutableTreeNode("加载中..."));
                    node.add(child);
                }
                if (node.getChildCount() == 0) {
                    node.add(new DefaultMutableTreeNode("（空）"));
                }
                treeModel.reload(node);
            });
        });
    }

    /**
     * 获取或创建 FTP 浏览服务（对话框生命周期内复用一个连接）
     *
     * @return FtpBrowseService 实例
     * @throws IOException FTP 连接失败
     * @author xumanyi
     * @date 2026-05-02
     */
    private synchronized FtpBrowseService ensureBrowseService() throws IOException {
        if (browseService == null || !browseService.isConnected()) {
            browseService = new FtpBrowseService(host, port, username, password);
        }
        return browseService;
    }

    // ==================== 选中态同步 ====================

    /**
     * 同步树当前选中节点的路径到底部"已选"标签
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void updateSelectedPathLabel() {
        String selected = getSelectedFtpPath();
        if (selected == null) {
            selectedPathLabel.setText("已选：（未选择）");
        } else {
            selectedPathLabel.setText("已选：" + selected);
        }
    }

    /**
     * 获取当前树中选中节点对应的 FTP 路径
     *
     * @return FTP 绝对路径（含尾部 /）；非 FtpDirNode 节点或未选中时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    private String getSelectedFtpPath() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode)) return null;
        Object userObj = ((DefaultMutableTreeNode) last).getUserObject();
        if (!(userObj instanceof FtpDirNode)) return null;
        return ((FtpDirNode) userObj).absPath;
    }

    // ==================== OK / Restore Default / New Folder ====================

    /**
     * 确认按钮处理：把树当前选中路径作为 customBackupRoot 写入对话框结果
     *
     * <p>路径在 FTP 上不存在时弹"是否创建"二次确认；同意则后台 mkdirs 后写入。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    @Override
    protected void doOKAction() {
        String selected = getSelectedFtpPath();
        if (selected == null) {
            Messages.showWarningDialog(this.getContentPanel(),
                    "请先在树中选择一个目录作为备份根。", "未选择目录");
            return;
        }

        // 检查路径是否存在；不存在弹创建确认
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean exists;
            try {
                FtpBrowseService svc = ensureBrowseService();
                String parent = parentOf(selected);
                String name = nameOf(selected);
                if (parent == null) {
                    exists = true; // 根目录默认存在
                } else {
                    List<String> sibs = svc.listSubdirectories(parent);
                    exists = sibs.contains(name);
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(this.getContentPanel(),
                        "检查目录失败：" + e.getMessage(), "FTP 错误"));
                return;
            }

            if (exists) {
                SwingUtilities.invokeLater(() -> commitResultAndClose(selected));
            } else {
                SwingUtilities.invokeLater(() -> {
                    int choice = Messages.showYesNoDialog(this.getContentPanel(),
                            "目录不存在：\n" + selected + "\n是否创建？",
                            "目录不存在", Messages.getQuestionIcon());
                    if (choice == Messages.YES) {
                        createDirAndClose(selected);
                    }
                });
            }
        });
    }

    /**
     * 后台创建目录后提交结果关闭对话框
     *
     * @param ftpPath 待创建的 FTP 路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private void createDirAndClose(String ftpPath) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                FtpSession session = new FtpSession(host, port);
                session.connect(username, password);
                try {
                    FtpOperations ops = new FtpOperations(session);
                    ops.mkdirs(ftpPath);
                } finally {
                    session.close();
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(this.getContentPanel(),
                        "创建目录失败：" + e.getMessage(), "FTP 错误"));
                return;
            }
            SwingUtilities.invokeLater(() -> commitResultAndClose(ftpPath));
        });
    }

    /**
     * 写入 result 字段并调用父类关闭流程
     *
     * @param ftpPath 用户选定的备份根
     * @author xumanyi
     * @date 2026-05-02
     */
    private void commitResultAndClose(String ftpPath) {
        this.resultBackupRoot = ftpPath.endsWith("/") ? ftpPath : ftpPath + "/";
        super.doOKAction();
    }

    /**
     * 恢复默认：清除当前 (host, contextDir) 的 customBackupRoot 设置
     *
     * <p>仅设置内部标记位，关闭对话框后由 InfoSectionPanel 检查 isRestoreDefault()
     * 调用 state.customBackupRoots.remove(key)。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void onRestoreDefault() {
        if (currentCustomRoot == null || currentCustomRoot.isBlank()) {
            Messages.showInfoMessage(this.getContentPanel(),
                    "当前未设置自定义备份位置，无需恢复。", "提示");
            return;
        }
        int choice = Messages.showYesNoDialog(this.getContentPanel(),
                "确认清除当前自定义备份位置，恢复到默认派生路径？",
                "恢复默认", Messages.getQuestionIcon());
        if (choice != Messages.YES) return;
        this.restoreDefault = true;
        this.resultBackupRoot = null;
        super.doOKAction(); // 关闭对话框，但走 OK 通道（让外层用 showAndGet() 拿到 true）
    }

    /**
     * 新建文件夹：在当前选中节点下创建空目录
     *
     * <p>名字校验：非空、不含 /、长度 ≤ 100。后台创建后回到 EDT 在树中选中新目录。</p>
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void onNewFolder() {
        String parent = getSelectedFtpPath();
        if (parent == null) {
            Messages.showWarningDialog(this.getContentPanel(),
                    "请先选中一个父目录。", "未选择父目录");
            return;
        }

        String name = Messages.showInputDialog(this.getContentPanel(),
                "在以下目录创建子文件夹：\n" + parent,
                "新建文件夹", Messages.getQuestionIcon());
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) {
            Messages.showWarningDialog(this.getContentPanel(), "文件夹名不能为空", "无效名字");
            return;
        }
        if (name.contains("/")) {
            Messages.showWarningDialog(this.getContentPanel(), "文件夹名不能含 /", "无效名字");
            return;
        }
        if (name.length() > 100) {
            Messages.showWarningDialog(this.getContentPanel(), "文件夹名过长（>100 字符）", "无效名字");
            return;
        }
        final String newDirAbs = parent + name + "/";

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                FtpSession session = new FtpSession(host, port);
                session.connect(username, password);
                try {
                    FtpOperations ops = new FtpOperations(session);
                    ops.mkdirIfAbsent(newDirAbs);
                } finally {
                    session.close();
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> Messages.showErrorDialog(this.getContentPanel(),
                        "创建目录失败：" + e.getMessage(), "FTP 错误"));
                return;
            }
            // 创建成功：刷新父节点子目录、选中新建目录
            SwingUtilities.invokeLater(() -> reloadAndSelect(parent, newDirAbs));
        });
    }

    /**
     * 重新加载父节点的子目录列表，并选中指定的新建目录
     *
     * @param parentAbs 父目录 FTP 路径
     * @param newDirAbs 新建目录的 FTP 路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private void reloadAndSelect(String parentAbs, String newDirAbs) {
        DefaultMutableTreeNode parentNode = findNodeByPath(parentAbs);
        if (parentNode == null) return;
        Object uo = parentNode.getUserObject();
        if (uo instanceof FtpDirNode) {
            ((FtpDirNode) uo).loaded = false;
        }
        loadChildrenAndSelect(parentNode, newDirAbs);
    }

    /**
     * 后台重载父节点子目录后选中目标
     *
     * @param parentNode  父树节点
     * @param targetAbs   要选中的子节点 FTP 路径
     * @author xumanyi
     * @date 2026-05-02
     */
    private void loadChildrenAndSelect(DefaultMutableTreeNode parentNode, String targetAbs) {
        Object uo = parentNode.getUserObject();
        if (!(uo instanceof FtpDirNode)) return;
        FtpDirNode dirNode = (FtpDirNode) uo;
        dirNode.loaded = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> subDirs;
            try {
                subDirs = ensureBrowseService().listSubdirectories(dirNode.absPath);
            } catch (IOException e) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                parentNode.removeAllChildren();
                DefaultMutableTreeNode toSelect = null;
                for (String sub : subDirs) {
                    FtpDirNode childData = new FtpDirNode(dirNode.absPath + sub + "/", sub);
                    DefaultMutableTreeNode child = new DefaultMutableTreeNode(childData);
                    child.add(new DefaultMutableTreeNode("加载中..."));
                    parentNode.add(child);
                    if (childData.absPath.equals(targetAbs)) {
                        toSelect = child;
                    }
                }
                treeModel.reload(parentNode);
                if (toSelect != null) {
                    TreePath path = new TreePath(toSelect.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    updateSelectedPathLabel();
                }
            });
        });
    }

    /**
     * 在树中递归查找指定 FTP 路径对应的节点
     *
     * @param ftpPath FTP 绝对路径，含尾部 /
     * @return 匹配节点；未找到时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    private DefaultMutableTreeNode findNodeByPath(String ftpPath) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        return findNodeRecursive(root, ftpPath);
    }

    /**
     * 递归实现：DFS 找匹配 FtpDirNode.absPath 的节点
     *
     * @param node    当前节点
     * @param ftpPath 目标路径
     * @return 匹配节点；未找到时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    private DefaultMutableTreeNode findNodeRecursive(DefaultMutableTreeNode node, String ftpPath) {
        Object uo = node.getUserObject();
        if (uo instanceof FtpDirNode && ftpPath.equals(((FtpDirNode) uo).absPath)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode r = findNodeRecursive(child, ftpPath);
            if (r != null) return r;
        }
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 取 FTP 路径的父目录（含尾部 /）
     *
     * @param ftpPath 完整路径，含尾部 /
     * @return 父目录路径；根目录或无父时返回 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    private static String parentOf(String ftpPath) {
        String trimmed = ftpPath.endsWith("/") ? ftpPath.substring(0, ftpPath.length() - 1) : ftpPath;
        int idx = trimmed.lastIndexOf('/');
        if (idx <= 0) return null;
        return trimmed.substring(0, idx + 1);
    }

    /**
     * 取 FTP 路径的最后一段名字（不含尾部 /）
     *
     * @param ftpPath 完整路径
     * @return 目录名
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String nameOf(String ftpPath) {
        String trimmed = ftpPath.endsWith("/") ? ftpPath.substring(0, ftpPath.length() - 1) : ftpPath;
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1);
    }

    // ==================== 对外结果 ====================

    /**
     * 获取用户最终选定的备份根
     *
     * @return 用户选定的备份根；点取消或恢复默认时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    public String getResultBackupRoot() { return resultBackupRoot; }

    /**
     * 是否点了"恢复默认"（用于区分"取消"和"清除自定义"）
     *
     * @return true=点了恢复默认；false=取消或确认
     * @author xumanyi
     * @date 2026-05-02
     */
    public boolean isRestoreDefault() { return restoreDefault; }

    /**
     * 释放 FTP 浏览服务连接
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    @Override
    protected void dispose() {
        if (browseService != null) {
            browseService.disconnect();
            browseService = null;
        }
        super.dispose();
    }

    // ==================== 内部类 ====================

    /**
     * 目录树节点用户对象，绑定 FTP 路径
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    static class FtpDirNode {
        /** FTP 绝对路径，含尾部 / */
        final String absPath;
        /** 显示名（即目录名） */
        final String displayName;
        /** 是否已加载子目录 */
        boolean loaded;

        FtpDirNode(String absPath, String displayName) {
            this.absPath = absPath;
            this.displayName = displayName;
            this.loaded = false;
        }

        @Override public String toString() { return displayName; }
    }
}
