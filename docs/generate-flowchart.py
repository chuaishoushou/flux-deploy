#!/usr/bin/env python3
"""
生成两张流程图供使用手册嵌入：

- flow_deploy.png  完整部署执行流程（v1.1.2 现状：含两模式、凭据缓存、SHA256 校验、自动回滚）
- flow_skill.png   AI Skill 协作工作流（v1.1.2 现状：含 schema 加载、执行清单确认、退出码分支）

依赖：graphviz CLI（dot）+ python graphviz 包
中文字体：macOS 用 PingFang SC / Microsoft YaHei，需要本机已安装

用法：python3 generate-flowchart.py
"""
from pathlib import Path
from graphviz import Digraph

DIR = Path(__file__).parent
FONT = "PingFang SC"

C_PRIMARY = "#C00000"
C_NODE = "#F5F5F5"
C_NODE_BORDER = "#666666"
C_HIGHLIGHT = "#FFF8E1"
C_DECISION = "#FFE0B2"
C_FAIL = "#FFCDD2"
C_OK = "#C8E6C9"


def make_graph(name: str) -> Digraph:
    g = Digraph(name, format="png")
    g.attr("graph", rankdir="TB", bgcolor="white",
           fontname=FONT, fontsize="14",
           pad="0.4", nodesep="0.4", ranksep="0.55")
    g.attr("node", shape="box", style="rounded,filled",
           fillcolor=C_NODE, color=C_NODE_BORDER,
           fontname=FONT, fontsize="13",
           margin="0.2,0.12")
    g.attr("edge", fontname=FONT, fontsize="11",
           color=C_NODE_BORDER)
    return g


def node(g: Digraph, name: str, label: str, **kw) -> None:
    g.node(name, label, **kw)


def build_deploy_flow() -> Digraph:
    g = make_graph("flow_deploy")

    node(g, "start", "用户触发部署\n（插件 / CLI deploy / Skill）",
         shape="oval", fillcolor=C_PRIMARY, fontcolor="white", color=C_PRIMARY)
    node(g, "end_ok", "部署完成", shape="oval",
         fillcolor=C_OK, color="#2E7D32")
    node(g, "end_fail", "失败 + 自动回滚\n（保留备份 / 解锁）", shape="oval",
         fillcolor=C_FAIL, color="#C62828")

    node(g, "load_cfg", "读取 JSON 配置\n（mode / targets / meta / options）")
    node(g, "load_pwd", "解析密码\n（--password-file > ftp.password >\n凭据缓存 ~/.flux-deploy/credentials.toml）",
         fillcolor=C_HIGHLIGHT)
    node(g, "mode_check", "更新模式？", shape="diamond",
         fillcolor=C_DECISION, color="#FB8C00")
    node(g, "compile", "mvn clean package\n（按 files 列表打补丁）",
         fillcolor=C_HIGHLIGHT)
    node(g, "ftp_connect", "FTP 连接 + cwd remoteDir")

    with g.subgraph(name="cluster_gates") as gs:
        gs.attr(label="部署门禁（按顺序，任一失败 → 触发回滚）",
                style="rounded,filled", fillcolor="#FAFAFA",
                color=C_PRIMARY, fontname=FONT, fontsize="12",
                fontcolor=C_PRIMARY, labeljust="l")
        gs.attr("node", shape="box", style="rounded,filled",
                fillcolor=C_NODE, color=C_NODE_BORDER,
                fontname=FONT, fontsize="13")
        node(gs, "g_pre", "PreCheckGate\n远端包存在性 / 残留锁检测")
        node(gs, "g_backup", "BackupGate\n备份到 backup/<YYYYMMDD_op>/")
        node(gs, "g_lock", "LockGate\nrename 为 *__LOCK__*（独占锁）")
        node(gs, "g_upload", "UploadGate\n上传暂存包")
        node(gs, "g_verify", "VerifyGate\n下载远端 + SHA256 校验")
        node(gs, "g_note", "NoteGate\n追加 *_update_note.txt")
        node(gs, "g_unlock", "UnlockGate\nrename 回原名 + 删除锁后缀")

    g.edge("start", "load_cfg")
    g.edge("load_cfg", "load_pwd")
    g.edge("load_pwd", "mode_check")
    g.edge("mode_check", "compile", label="incremental")
    g.edge("mode_check", "ftp_connect", label="full")
    g.edge("compile", "ftp_connect")
    g.edge("ftp_connect", "g_pre")
    g.edge("g_pre", "g_backup")
    g.edge("g_backup", "g_lock")
    g.edge("g_lock", "g_upload")
    g.edge("g_upload", "g_verify")
    g.edge("g_verify", "g_note")
    g.edge("g_note", "g_unlock")
    g.edge("g_unlock", "end_ok")

    for gate in ("g_pre", "g_backup", "g_lock", "g_upload", "g_verify", "g_note"):
        g.edge(gate, "end_fail", style="dashed", color="#C62828",
               fontcolor="#C62828", label="失败" if gate == "g_pre" else "")

    return g


def build_skill_flow() -> Digraph:
    g = make_graph("flow_skill")

    node(g, "user_say", "用户：客服更新 / 增量更新 / ...",
         shape="oval", fillcolor=C_PRIMARY, fontcolor="white", color=C_PRIMARY)
    node(g, "done", "汇报结果 + 清理临时文件",
         shape="oval", fillcolor=C_OK, color="#2E7D32")

    node(g, "find_cli", "Skill：定位 CLI jar\n($FLUX_DEPLOY_CLI > ~/bin > 工程目录)")
    node(g, "load_schema", "Skill：跑 `flux-deploy-cli schema`\n加载当前字段定义", fillcolor=C_HIGHLIGHT)
    node(g, "collect", "Skill：一次性收齐参数\n（FTP / remoteDir / 本地包 / meta / files）")
    node(g, "build_json", "Skill：写临时 JSON\n（mktemp，不含密码）", fillcolor=C_HIGHLIGHT)

    node(g, "checklist", "Skill：展示执行清单\n（模式 / 路径 / 目标 / 备份 / 版本记录）",
         fillcolor=C_DECISION, color="#FB8C00")
    node(g, "user_confirm", "用户确认？", shape="diamond",
         fillcolor=C_DECISION, color="#FB8C00")
    node(g, "abort", "中止 + 清理临时文件",
         shape="oval", fillcolor=C_FAIL, color="#C62828")

    node(g, "run_deploy", "CLI：deploy --config $CFG\n（密码 CLI 自管，AI 不接触）",
         fillcolor=C_HIGHLIGHT)

    node(g, "exit_branch", "退出码？", shape="diamond",
         fillcolor=C_DECISION, color="#FB8C00")
    node(g, "ok_parse", "解析 stdout JSON\n汇报每目标 SHA256 / backupPath",
         fillcolor=C_OK)
    node(g, "fix_64", "stderr → 用户\n若「缺少密码」→ 提示跑 credential set")
    node(g, "rollback_70", "CLI 已自动回滚\n汇报 rollback 字段 / 询问手工清理")

    g.edge("user_say", "find_cli")
    g.edge("find_cli", "load_schema")
    g.edge("load_schema", "collect")
    g.edge("collect", "build_json")
    g.edge("build_json", "checklist")
    g.edge("checklist", "user_confirm")
    g.edge("user_confirm", "run_deploy", label="确认")
    g.edge("user_confirm", "abort", label="取消", style="dashed", color="#C62828")
    g.edge("run_deploy", "exit_branch")
    g.edge("exit_branch", "ok_parse", label="0（成功）", color="#2E7D32")
    g.edge("exit_branch", "fix_64", label="64（配置错）", color="#FB8C00")
    g.edge("exit_branch", "rollback_70", label="70（执行错）", color="#C62828")
    g.edge("ok_parse", "done")
    g.edge("fix_64", "done")
    g.edge("rollback_70", "done")

    return g


def main() -> None:
    deploy_g = build_deploy_flow()
    skill_g = build_skill_flow()

    deploy_path = DIR / "flow_deploy"
    skill_path = DIR / "flow_skill"

    deploy_g.render(filename=str(deploy_path), format="png", cleanup=True)
    skill_g.render(filename=str(skill_path), format="png", cleanup=True)

    for p in (deploy_path.with_suffix(".png"), skill_path.with_suffix(".png")):
        size = p.stat().st_size
        print(f"  生成 {p.name} ({size // 1024} KB)")


if __name__ == "__main__":
    main()
