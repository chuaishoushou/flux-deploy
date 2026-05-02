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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;

/**
 * 备份位置选择对话框
 *
 * <p>限制在当前项目根（{@code /开发/{project}/}）下浏览，看不到其他项目的目录树。
 * 选定的目录直接作为备份根，下游 {@code DeployExecutionService.preBackupAll}
 * 在其下创建 {@code yyyyMMdd_{operator}/} 子目录写入。所选即所得，无任何拼接。</p>
 *
 * <p>默认选中策略（按优先级）：已存自定义 → contextDir/backup/ 已存在 → contextDir。</p>
 *
 * <p>UI 极简：1 行提示 + 树 + 确认/取消。新建子目录通过树节点右键菜单触发。
 * 恢复默认通过外部主面板 ✕ 小按钮触发，不在本对话框内。</p>
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
    /** 项目根目录（树根，限制范围） */
    private final String projectDir;
    /** 当前 FTP 上下文路径（含系统层级，用于默认选中） */
    private final String contextDir;
    /** 当前已存的 customBackupRoot，未自定义时为 null */
    private final String currentCustomRoot;

    /** 目录树 */
    private Tree tree;
    /** 树模型 */
    private DefaultTreeModel treeModel;
    /** FTP 浏览服务（对话框生命周期内复用） */
    private FtpBrowseService browseService;

    /** 用户最终选定的备份根；点取消时为 null */
    private String resultBackupRoot;

    /**
     * 创建备份位置选择对话框
     *
     * @param project           当前 IDEA 项目
     * @param host              FTP 主机
     * @param port              FTP 端口
     * @param username          FTP 用户名
     * @param password          FTP 密码
     * @param projectDir        项目根（树根，限制范围；如 {@code /开发/客户A/}）
     * @param contextDir        当前 FTP 上下文路径（项目+系统 或 仅项目；用于默认选中）
     * @param currentCustomRoot 当前已存的 customBackupRoot；未自定义时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    public BackupLocationDialog(Project project, String host, int port,
                                 String username, String password,
                                 String projectDir, String contextDir,
                                 @Nullable String currentCustomRoot) {
        super(project, false);
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.projectDir = projectDir;
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
        root.setPreferredSize(new Dimension(520, 420));

        JBLabel hint = new JBLabel("选定目录将作为备份根");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setToolTipText("备份按 yyyyMMdd_{开发}/ 子目录写入选定目录。右键节点可新建子目录。");
        root.add(hint, BorderLayout.NORTH);

        // 目录树根 = 项目根，不再从 / 起遍历
        String projectName = nameOf(projectDir);
        FtpDirNode rootData = new FtpDirNode(projectDir, projectName);
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

        // 右键菜单：新建子目录
        installRightClickMenu();

        JBScrollPane treeScroll = new JBScrollPane(tree);
        root.add(treeScroll, BorderLayout.CENTER);

        // 异步执行默认展开 + 选中策略，不阻塞 UI 渲染
        SwingUtilities.invokeLater(this::applyDefaultSelection);

        return root;
    }

    /**
     * 安装树节点的右键弹出菜单（"新建子目录..."）
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void installRightClickMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem newDirItem = new JMenuItem("新建子目录...");
        newDirItem.addActionListener(e -> onNewFolder());
        popup.add(newDirItem);

        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = tree.getRowForLocation(e.getX(), e.getY());
                if (row < 0) return;
                tree.setSelectionRow(row);
                Object obj = tree.getPathForRow(row).getLastPathComponent();
                if (obj instanceof DefaultMutableTreeNode
                        && ((DefaultMutableTreeNode) obj).getUserObject() instanceof FtpDirNode) {
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
    }

    // ==================== 默认选中 ====================

    /**
     * 按优先级应用默认选中策略
     *
     * @author xumanyi
     * @date 2026-05-02
     */
    private void applyDefaultSelection() {
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) treeModel.getRoot();
        tree.expandPath(new TreePath(rootNode.getPath()));

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String targetPath;
            if (currentCustomRoot != null && !currentCustomRoot.isBlank()) {
                targetPath = currentCustomRoot.endsWith("/") ? currentCustomRoot : currentCustomRoot + "/";
            } else if (contextDir != null && !contextDir.isBlank()) {
                String backupCandidate = contextDir + "backup/";
                boolean backupExists = false;
                try {
                    FtpBrowseService svc = ensureBrowseService();
                    List<String> subs = svc.listSubdirectories(contextDir);
                    backupExists = subs.contains("backup");
                } catch (IOException ignored) { }
                targetPath = backupExists ? backupCandidate : contextDir;
            } else {
                targetPath = projectDir;
            }

            final String finalTarget = targetPath;
            SwingUtilities.invokeLater(() -> expandToPath(finalTarget));
        });
    }

    /**
     * 在树中展开并选中指定 FTP 绝对路径对应的节点。路径必须位于 projectDir 之下。
     *
     * @param ftpPath 目标 FTP 路径（含尾部 /）
     * @author xumanyi
     * @date 2026-05-02
     */
    private void expandToPath(String ftpPath) {
        if (ftpPath == null || ftpPath.isBlank()) return;
        String relative;
        if (ftpPath.equals(projectDir) || (ftpPath + "/").equals(projectDir)) {
            relative = "";
        } else if (ftpPath.startsWith(projectDir)) {
            relative = ftpPath.substring(projectDir.length());
        } else {
            relative = "";
        }
        relative = relative.replaceAll("^/+", "").replaceAll("/+$", "");

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        if (relative.isEmpty()) {
            TreePath rp = new TreePath(root.getPath());
            tree.setSelectionPath(rp);
            tree.scrollPathToVisible(rp);
            return;
        }
        String[] segments = relative.split("/");
        expandSegmentRecursive(root, segments, 0);
    }

    /**
     * 递归展开 / 选中：先确保当前节点已加载子目录，再向下匹配 segments[index]
     *
     * @param node     当前节点
     * @param segments 路径段数组
     * @param index    当前要匹配的段下标
     * @author xumanyi
     * @date 2026-05-02
     */
    private void expandSegmentRecursive(DefaultMutableTreeNode node, String[] segments, int index) {
        if (index >= segments.length) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            return;
        }

        Object userObj = node.getUserObject();
        if (!(userObj instanceof FtpDirNode)) return;
        FtpDirNode dirNode = (FtpDirNode) userObj;

        Runnable afterLoad = () -> {
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
            TreePath cur = new TreePath(node.getPath());
            tree.setSelectionPath(cur);
            tree.scrollPathToVisible(cur);
        };

        if (dirNode.loaded) {
            afterLoad.run();
            return;
        }

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
     * @param node     树节点
     * @param dirNode  绑定的 FTP 目录数据
     * @author xumanyi
     * @date 2026-05-02
     */
    private void loadChildrenIfNeeded(DefaultMutableTreeNode node, FtpDirNode dirNode) {
        if (dirNode.loaded) return;
        dirNode.loaded = true;

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

    // ==================== OK / New Folder ====================

    /**
     * 确认按钮处理：把树当前选中路径作为 customBackupRoot 写入对话框结果
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
        commitResultAndClose(selected);
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
     * 新建子目录：在当前选中节点下创建空目录（右键菜单触发）
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
                "新建子目录", Messages.getQuestionIcon());
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
     * 取 FTP 路径的最后一段名字（不含尾部 /）
     *
     * @param ftpPath 完整路径
     * @return 目录名；根路径或空时返回 "/"
     * @author xumanyi
     * @date 2026-05-02
     */
    private static String nameOf(String ftpPath) {
        if (ftpPath == null || ftpPath.isEmpty() || "/".equals(ftpPath)) return "/";
        String trimmed = ftpPath.endsWith("/") ? ftpPath.substring(0, ftpPath.length() - 1) : ftpPath;
        int idx = trimmed.lastIndexOf('/');
        return idx < 0 ? trimmed : trimmed.substring(idx + 1);
    }

    // ==================== 对外结果 ====================

    /**
     * 获取用户最终选定的备份根
     *
     * @return 用户选定的备份根；点取消时为 null
     * @author xumanyi
     * @date 2026-05-02
     */
    @Nullable
    public String getResultBackupRoot() { return resultBackupRoot; }

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
