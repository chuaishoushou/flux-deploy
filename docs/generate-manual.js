const fs = require("fs");
const path = require("path");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell, ImageRun,
  Header, Footer, AlignmentType, HeadingLevel, BorderStyle, WidthType,
  ShadingType, PageNumber, PageBreak, LevelFormat
} = require("docx");

const DIR = __dirname;
const PW = 11906, PH = 16838, MG = 1440, CW = PW - 2 * MG;
const C = { pri: "000000", hdr: "C00000", wht: "FFFFFF", blk: "000000",
  gry: "CCCCCC", tgry: "666666", code: "F5F5F5", bdr: "D4A0A0", warn: "E65100" };
const F = "Microsoft YaHei";

const bd = (c = C.bdr) => ({ style: BorderStyle.SINGLE, size: 1, color: c });
const bds = c => ({ top: bd(c), bottom: bd(c), left: bd(c), right: bd(c) });
const h1 = t => new Paragraph({ heading: HeadingLevel.HEADING_1, spacing: { before: 300, after: 150 }, children: [new TextRun({ text: t, font: F, size: 36, bold: true, color: C.pri })] });
const h2 = t => new Paragraph({ heading: HeadingLevel.HEADING_2, spacing: { before: 240, after: 120 }, children: [new TextRun({ text: t, font: F, size: 30, bold: true, color: C.pri })] });
const h3 = t => new Paragraph({ heading: HeadingLevel.HEADING_3, spacing: { before: 180, after: 100 }, children: [new TextRun({ text: t, font: F, size: 24, bold: true, color: C.pri })] });
const p = t => new Paragraph({ spacing: { after: 120 }, children: [new TextRun({ text: t, font: F, size: 22 })] });
const B = t => new Paragraph({ spacing: { after: 100 }, children: [new TextRun({ text: t, font: F, size: 22, bold: true })] });
const code = t => new Paragraph({ spacing: { after: 50 }, shading: { fill: C.code, type: ShadingType.CLEAR }, indent: { left: 300 }, children: [new TextRun({ text: t, font: "Consolas", size: 18 })] });
const codes = ls => ls.map(l => code(l));
const bul = (t, lv = 0) => new Paragraph({ numbering: { reference: "bul", level: lv }, spacing: { after: 60 }, children: [new TextRun({ text: t, font: F, size: 22 })] });
const num = (t, lv = 0) => new Paragraph({ numbering: { reference: "num", level: lv }, spacing: { after: 60 }, children: [new TextRun({ text: t, font: F, size: 22 })] });
const shot = cap => new Paragraph({ spacing: { before: 150, after: 150 }, alignment: AlignmentType.CENTER, border: bds(C.gry), children: [new TextRun({ text: `[ 截图：${cap} ]`, font: F, size: 22, color: C.tgry, italics: true })] });
const warn = t => new Paragraph({ spacing: { before: 120, after: 120 }, border: { left: { style: BorderStyle.SINGLE, size: 12, color: C.warn } }, indent: { left: 200 }, children: [new TextRun({ text: "  " + t, font: F, size: 22 })] });

function tbl(hdrs, rows) {
  const w = Math.floor(CW / hdrs.length);
  const headerRow = new TableRow({ tableHeader: true, children: hdrs.map(h => new TableCell({ width: { size: w, type: WidthType.DXA }, shading: { fill: C.pri, type: ShadingType.CLEAR }, borders: bds(C.bdr), margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ text: h, font: F, size: 22, bold: true, color: C.wht })] })] })) });
  const bodyRows = rows.map((r, i) => new TableRow({ children: r.map(c => new TableCell({ width: { size: w, type: WidthType.DXA }, shading: i % 2 === 1 ? { fill: "F5F5F5", type: ShadingType.CLEAR } : undefined, borders: bds(C.bdr), margins: { top: 80, bottom: 80, left: 120, right: 120 }, children: [new Paragraph({ children: [new TextRun({ text: c, font: F, size: 22 })] })] })) }));
  return new Table({ width: { size: CW, type: WidthType.DXA }, rows: [headerRow, ...bodyRows] });
}

// 根据 PNG 头读取真实尺寸，按原比例缩放高度；并显式指定 type，避免 python-docx
// 报 "no content type for partname '...undefined'" 错。
function img(fn, maxW = 380) {
  const fp = path.join(DIR, fn);
  if (!fs.existsSync(fp)) return p(`[ 图片缺失：${fn} ]`);
  const ext = path.extname(fn).slice(1).toLowerCase();
  const buf = fs.readFileSync(fp);
  let h = Math.floor(maxW * 0.75);
  if (ext === "png" && buf.length > 24 && buf.readUInt32BE(0) === 0x89504E47) {
    const realW = buf.readUInt32BE(16);
    const realH = buf.readUInt32BE(20);
    if (realW > 0) h = Math.floor(maxW * realH / realW);
  }
  return new Paragraph({
    alignment: AlignmentType.CENTER,
    spacing: { before: 150, after: 150 },
    children: [new ImageRun({ data: buf, type: ext, transformation: { width: maxW, height: h } })],
  });
}

const PB = new Paragraph({ children: [new PageBreak()] });
const pgProps = { page: { size: { width: PW, height: PH }, margin: { top: MG, right: MG, bottom: MG, left: MG } } };
const hf = {
  headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [new TextRun({ text: "FLUX 客服更新工具 使用手册", font: F, size: 18, color: C.tgry })] })] }) },
  footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: ["第 ", PageNumber.CURRENT, " 页 / 共 ", PageNumber.TOTAL_PAGES, " 页"], font: F, size: 18, color: C.tgry })] })] }) },
};

// =============================================================================
// 章节内容：Part1 / Part2 / Part3 / 附录
// =============================================================================

const cover = [
  new Paragraph({ spacing: { before: 3600 } }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 400 }, children: [new TextRun({ text: "FLUX 客服更新工具", font: F, size: 56, bold: true, color: C.pri })] }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 }, children: [new TextRun({ text: "使用手册", font: F, size: 44, color: C.tgry })] }),
  new Paragraph({ alignment: AlignmentType.CENTER, border: { bottom: { style: BorderStyle.SINGLE, size: 4, color: C.pri } }, spacing: { after: 600 }, children: [new TextRun({ text: " ", size: 10 })] }),
  new Paragraph({ spacing: { before: 600 } }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 80 }, children: [new TextRun({ text: "版本：V1.1.2    更新日期：2026-04-25    FLUX 项目组", font: F, size: 24, color: C.tgry })] }),
  new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 200 }, children: [new TextRun({ text: "—— IDEA 插件 / 命令行 CLI / AI Skill 三套入口，同一套部署流水线 ——", font: F, size: 22, color: C.tgry, italics: true })] }),
];

const toc = [
  h1("目录"),
  p("第一部分：IDEA 插件使用"),
  bul("1.1 概述与功能特性"), bul("1.2 安装与首次使用"), bul("1.3 三种使用入口"),
  bul("1.4 主面板布局"), bul("1.5 更新模式（整包 / 增量）"),
  bul("1.6 备份与回滚"), bul("1.7 本地模式"), bul("1.8 前置校验"),
  bul("1.9 多主目标与备份冲突"), bul("1.10 更新记录格式"), bul("1.11 常见问题"),
  p(""),
  p("第二部分:CLI 命令行使用"),
  bul("2.1 环境准备"), bul("2.2 子命令速查"), bul("2.3 凭据管理（credential）"),
  bul("2.4 部署（deploy）"), bul("2.5 查看字段定义（schema）"),
  bul("2.6 清理残留锁（unlock）"), bul("2.7 历史回滚（rollback）"),
  bul("2.8 本地打补丁（patch-local）"), bul("2.9 浏览 FTP（browse）"),
  p(""),
  p("第三部分：AI Skill 协作"),
  bul("3.1 工作流概述"), bul("3.2 触发条件与凭据约束"),
  bul("3.3 信息收集与执行清单"), bul("3.4 退出码处理与异常恢复"),
  p(""),
  p("附录"),
  bul("A. 目录与配置文件位置"), bul("B. 退出码与错误排查"),
];

// =============================================================================
// Part 1 - IDEA 插件使用
// =============================================================================
const part1 = [
  h1("第一部分：IDEA 插件使用"),

  h2("1.1 概述与功能特性"),
  p("FLUX 客服更新插件是 IntelliJ IDEA 集成工具，让开发人员可以在 IDE 中直接将代码变更部署到客户 FTP 或本地包，实现零切换的开发体验。"),
  p("v1.1.2 起，工程拆分为三个 Gradle 模块（core / plugin / cli）：core 是纯业务，plugin 是 IDEA 壳，cli 输出独立 fat-jar。同一套业务代码同时支撑 IDE 内插件、命令行和 AI Skill。"),
  tbl(["功能", "说明"], [
    ["工程树形选择", "点击工程下拉框，树形展示所有 Maven 模块，单击叶子节点选中"],
    ["两种更新模式", "整包更新 / 增量更新（Git 变更默认勾选 + 用户可调）"],
    ["文件树形勾选", "多级目录树，支持全选 / 单选，显示「已选 N / 总数 M」"],
    ["FTP 三级联动", "项目 → 系统 → 目标包，自动精确匹配产物"],
    ["WAR lib 对齐", "全量更新 WAR 时自动以远程包的 WEB-INF/lib 为准"],
    ["多主目标", "同名 JAR 跨子目录并行备份/加锁/上传，互不干扰"],
    ["本地模式", "不登录 FTP，直接对本地 jar/war 打补丁输出新包"],
    ["安全部署流程", "备份 → 加锁 → 上传 → SHA256 校验 → 解锁"],
    ["一键回滚", "失败自动回滚，成功后可手动回滚到备份版本"],
    ["实时日志 + 文件保存", "实时输出，按日期保存到 .flux-deploy-log/"],
  ]),

  h2("1.2 安装与首次使用"),
  h3("安装步骤"),
  num("获取插件 ZIP 包：flux-deploy-plugin-1.1.2.zip"),
  num("IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk"),
  num("选择 ZIP 文件，重启 IDEA"),
  num("右侧栏出现「FLUX 客服更新」工具窗口"),
  shot("插件安装 - Settings > Plugins"),

  h3("首次连接 FTP"),
  p("插件启动时自动尝试加载缓存凭据。首次使用时点「账号 ▾ → 添加新账号」，输入："),
  tbl(["字段", "示例", "说明"], [
    ["主机", "47.102.153.34", "FTP 服务器 IP 或域名"],
    ["端口", "18080", "默认 FTP 端口"],
    ["用户名", "xumanyi", "FTP 账号"],
    ["密码", "********", "AES-256-GCM 加密保存"],
  ]),
  p("连接成功后凭据自动保存到 ~/.flux-deploy/credentials.toml，后续无需再次输入。"),
  shot("首次连接 - FTP 登录对话框"),

  h2("1.3 三种使用入口"),
  tbl(["入口", "位置", "说明"], [
    ["工具窗口", "右侧栏「FLUX 客服更新」", "常规入口，完整面板"],
    ["右键菜单", "项目树中右键 Maven 模块或具体文件", "快速触发并自动预选"],
    ["本地模式", "工具窗口切换到「本地模式」", "对本地 jar/war 打补丁，不登录 FTP"],
  ]),
  h3("右键菜单项"),
  tbl(["菜单项", "切换到的模式", "触发条件", "预填行为"], [
    ["整包更新", "FULL", "右键模块目录", "—"],
    ["自动检测", "INCREMENTAL", "右键模块目录", "自动跑 Git 检测，把变更项默认勾选"],
    ["手动选择", "INCREMENTAL", "右键具体文件", "把右键选中的文件预先勾选"],
    ["打开更新面板", "-", "任何位置", "—"],
  ]),
  p("「自动检测」与「手动选择」都进入同一个增量更新模式，只是两种快捷预填方式。进面板后用户仍可任意调整勾选。"),
  shot("右键菜单 - FLUX 客服更新子菜单"),

  h2("1.4 主面板布局"),
  p("面板从上到下分四个区域：源 → 目标 → 信息 → 按钮栏，底部是日志区。"),
  shot("主面板 - 工具窗口全貌"),

  h3("源 Section"),
  tbl(["控件", "操作", "说明"], [
    ["工程下拉框", "点击弹出树", "树形展示所有 Maven 模块，单击叶子选中"],
    ["产物显示", "自动", "从 pom.xml 解析，优先扫描 target/ 实际产物"],
    ["更新模式", "下拉选择", "整包更新 / 增量更新"],
    ["文件树", "勾选", "多级目录树，右上角显示「已选 N / 总数 M」"],
    ["全选 / 刷新", "勾选 / 点 ↻", "一键全选；刷新按当前模式重新扫描"],
  ]),
  p("文件状态标识：[A] 新增（绿色）、[M] 修改（蓝色）、[D] 删除（橙色）。"),
  shot("源 Section - 增量更新模式（文件树 + Git 变更预选）"),

  h3("目标 Section"),
  tbl(["控件", "操作", "说明"], [
    ["FTP 状态 / 账号 ▾", "点击", "切换 / 添加 / 删除账号"],
    ["项目下拉框", "下拉", "对应 /开发/{项目}/，支持搜索"],
    ["系统下拉框", "下拉", "对应 /开发/{项目}/{系统}/"],
    ["目标包树", "勾选", "列出 JAR / WAR；与源产物同名的包自动勾选并锁定"],
    ["全选 WAR", "勾选", "一键勾选所有 WAR（用于 JAR 嵌入更新）"],
    ["刷新 ↻", "点击", "重连 FTP + 按当前层级重新拉取"],
  ]),
  p("产物匹配规则：本地产物文件名必须与远程包完全一致（含版本号）。不同版本不会自动匹配。"),
  shot("目标 Section - FTP 三级联动"),

  h3("信息 Section"),
  tbl(["字段", "必填", "说明"], [
    ["更新版本记录", "否", "勾选后显示任务号 / 客服号输入框"],
    ["任务号", "勾选后任务 / 客服至少填一项", "示例 20260328155300000001"],
    ["客服号", "同上", "示例 20260328155300000001A"],
    ["开发", "勾选备份或版本记录时必填", "英文 ID，如 xumanyi；自动缓存上次值"],
  ]),
  shot("信息 Section - 任务 / 客服 / 操作人"),

  h3("按钮栏与日志"),
  tbl(["按钮", "位置", "功能"], [
    ["预检", "左", "仅检查 FTP 状态和远端包，不编译、不上传、不修改"],
    ["打包并上传", "左", "完整执行：编译 → 备份 → 加锁 → 上传 → SHA256 校验 → 解锁"],
    ["回滚", "左", "恢复上次备份版本；仅成功部署后可用，回滚后置灰"],
    ["清空日志", "右", "清屏幕日志，不影响已保存的日志文件"],
    ["重置", "右", "重置所有表单字段、文件列表、目标选择"],
  ]),
  p("日志区位于面板底部，实时显示每步详情。日志自动保存到 项目根/.flux-deploy-log/YYYY-MM-DD/HH-mm-ss_update.log，同次部署的预检 / 部署 / 回滚追加到同一文件。"),
  shot("按钮栏 + 日志区"),

  h2("1.5 更新模式（整包 / 增量）"),
  p("v1.1.2 起，更新模式从原来的三档（整包 / 手动选择 / 自动检测变更）合并为两档。「文件来源」（手选 / Git 检测）不再是模式选项，而是同一个「增量更新」模式下的两种预填方式。"),

  h3("整包更新（FULL）"),
  p("mvn clean package 编译整个模块，完整替换远程包。"),
  bul("适用：大版本更新、整体变更"),
  bul("WAR 特殊处理：自动以远程 WEB-INF/lib 为准对齐"),
  bul("无文件清单：选这个模式后右侧只展示「将替换整个远程包」提示"),

  h3("增量更新（INCREMENTAL）"),
  p("列出模块下全部可部署文件，Git 变更项默认勾选，用户可在此基础上自由增删勾选。"),
  bul("适用：日常迭代、针对性 bug 修复"),
  bul("状态标识：[A] 新增（绿）、[M] 修改（蓝）、[D] 删除（橙）"),
  bul("三种快捷入口都进这个模式："),
  bul("下拉切换 → 默认按 Git 变更勾选", 1),
  bul("右键模块「自动检测」→ 同上，等价于直接进面板", 1),
  bul("右键文件「手动选择」→ 把右键选中的文件预先勾选", 1),
  bul("所有勾选都可以人工再调整（取消默认勾选 / 加 Git 没识别到的文件）"),

  h3("刷新按钮"),
  p("「更新模式」下拉右侧的 ↻ 按钮，点击后根据当前模式重新拉取："),
  bul("整包更新：仅输出提示（无列表可刷新）"),
  bul("增量更新：重新扫描模块文件 + 重跑 Git 变更检测；用户已勾选项会被尽量保留"),

  h2("1.6 备份与回滚"),
  p("勾选「执行备份」后，上传前会把远端原包备份到 backup/yyyyMMdd_{开发}/。更新失败会自动回滚。更新成功后「回滚」按钮可用，可手动回滚到备份版本。"),
  warn("不备份的情况下失败无法自动恢复，请谨慎使用。"),

  h3("安全机制一览"),
  tbl(["机制", "作用", "实现"], [
    ["备份", "恢复点", "加锁前自动备份到 backup/YYYYMMDD_op/，保留相对目录结构"],
    ["文件锁", "防并发", "rename 为 __LOCK__ 格式"],
    ["SHA256", "完整性", "上传后重新下载比对哈希"],
    ["自动回滚", "失败恢复", "任何步骤失败自动从备份恢复"],
    ["路径校验", "防误操作", "必须在 /开发/ 下，禁止 .."],
    ["线程安全", "连接稳定", "FTP 操作加锁串行化"],
    ["凭据加密", "密码安全", "AES-256-GCM 加密存储"],
  ]),

  h3("部署执行流程图"),
  p("完整的部署执行流程（任一步骤失败自动回滚）："),
  img("flow_deploy.png", 380),

  h2("1.7 本地模式"),
  p("用户手里有一个本地 jar/war 包，想把本地编译的变更打进去，输出新包。不登录 FTP、不备份、不写版本记录、不可回滚。"),

  h3("三种分流"),
  p("按「源工程产物类型 × 用户提供包类型」自动判定："),
  bul("jar → jar：源工程 jar 模块，用户提供 jar 包；校验包名前缀 + 版本号严格一致后直接替换 jar 内条目"),
  bul("war → war：源工程 war 模块，用户提供 war 包；校验后替换 WEB-INF/classes/ 下的类"),
  bul("jar → war/lib：源工程 jar，用户提供 war 包；在 WEB-INF/lib/ 下定位匹配的 jar，版本严格一致后抽出 → 打补丁 → 回写 war"),

  h3("输出与交互"),
  bul("默认输出到 <包所在目录>/patched_{yyyyMMdd_HHmmss}/，同名文件。可手动改"),
  bul("支持从 Finder 或项目树拖拽 jar/war 进来"),
  bul("点「本地打包」先预检 → 弹出变更清单（修改/新增/删除）→ 确认后编译 + 打包"),
  bul("成功后弹卡片：打开所在目录 / 复制路径 / 关闭"),
  shot("本地模式 - 拖放卡片"),

  h2("1.8 前置校验"),
  p("点击「打包并上传」或「打包不上传」按钮时，插件会先做一次硬性前置校验。以下任一条件缺失都会弹窗阻断："),
  bul("未选择工程"),
  bul("FTP 未连接"),
  bul("未选择目标包（主目标或嵌入目标）"),
  bul("未勾选任何待更新文件（整包更新模式豁免）"),
  bul("勾选了「更新版本记录」但任务和客服都为空"),
  bul("勾选了「更新版本记录」或「执行备份」但开发为空"),
  shot("前置校验 - 缺失字段提示"),

  h2("1.9 多主目标与备份冲突"),
  p("同一 jar 出现在多个子目录（典型：scev6-utils-tms 同时被 shared-tms / shared-edp 引用）时，插件并行处理每个主目标，并按相对目录在备份目录下镜像，避免同名覆盖。"),
  p("如果同一个备份目录已存在同名文件，弹窗询问：覆盖、追加序号、取消。"),
  shot("备份冲突 - 选择策略对话框"),

  h2("1.10 更新记录格式"),
  p("勾选「更新版本记录」后，部署成功会在远程包旁的 *_update_note.txt 末尾追加两行："),
  ...codes([
    "20260424 [T-12345] [C-67890] xumanyi",
    "  - 修复客服 X 反馈的 Y 问题（变更文件：A.java, B.xml）",
  ]),
  p("note 文件随包同步备份到 backup/{date}_{operator}/ 下，可对照查询历史改动。"),

  h2("1.11 常见问题"),
  B("Q：FTP 连接超时？"),         p("A：点击 FTP 状态行的刷新按钮自动重连。"),
  B("Q：更新失败丢数据？"),       p("A：不会。备份在加锁前完成，失败自动回滚。"),
  B("Q：同时更新多个包？"),       p("A：可以。JAR 更新时勾选多个 WAR，会同步嵌入更新。"),
  B("Q：工程树加载慢？"),         p("A：首次扫描 pom.xml 后缓存，后续秒开。"),
  B("Q：备份包在哪？"),           p("A：FTP 系统目录下 backup/YYYYMMDD_operator/ 目录。"),
  B("Q：WAR lib 处理？"),         p("A：全量更新自动以远程 lib 对齐，增量更新不触碰 lib。"),
];

// =============================================================================
// Part 2 - CLI 命令行使用
// =============================================================================
const part2 = [
  h1("第二部分：CLI 命令行使用"),
  p("flux-deploy-cli 是与 IDEA 插件共享业务代码的命令行工具，输出独立 fat-jar，可在脚本 / 自动化流水线 / AI Skill 中调用。"),

  h2("2.1 环境准备"),
  tbl(["要求", "说明"], [
    ["Java", "JDK 17 或以上，确保 java -version 可用"],
    ["CLI 工具", "flux-deploy-cli-1.1.2-all.jar（约 776 KB fat-jar，自包含所有依赖）"],
    ["网络", "能访问目标 FTP 服务器"],
  ]),

  h3("获取与部署"),
  ...codes([
    "./gradlew :cli:shadowJar",
    "cp cli/build/libs/flux-deploy-cli-1.1.2-all.jar ~/bin/flux-deploy-cli.jar",
  ]),
  p("或设置环境变量 FLUX_DEPLOY_CLI 指向 jar 绝对路径。Skill 按以下顺序查找：$FLUX_DEPLOY_CLI > ~/bin/flux-deploy-cli.jar > $FLUX_DEPLOY_HOME/cli/build/libs/*-all.jar。"),

  h2("2.2 子命令速查"),
  tbl(["子命令", "用途", "典型调用方"], [
    ["deploy", "按 JSON 配置执行一次完整的 FTP 部署流水线", "Skill / 脚本"],
    ["schema", "输出 deploy 配置的 JSON Schema，便于工具理解字段", "Skill 启动时"],
    ["credential", "管理本机凭据缓存：set / list / delete（密码只在终端与 CLI 间流动）", "用户终端"],
    ["unlock", "扫描并恢复残留锁文件（中断部署留下的 *__LOCK__*）", "Skill / 用户"],
    ["rollback", "从指定备份目录恢复 FTP 上的包", "Skill / 用户"],
    ["patch-local", "对本地 jar/war 打补丁（不涉及 FTP）", "Skill / 用户"],
    ["browse", "列出 FTP 目录结构 / 扫描可部署包", "Skill"],
  ]),
  p("所有子命令都支持 --help / -h 查看详细参数。"),

  h2("2.3 凭据管理（credential）"),
  p("凭据由 CLI 拥有和管理。AI 全程不接触密码——只能通过 credential 子命令让 CLI 代为操作。"),
  h3("首次设置"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar credential set \\",
    "  --host 47.102.153.34 --port 18080 --user xumanyi",
    "# 提示 Enter password: 用户输入，CLI AES-256-GCM 加密后保存",
  ]),
  shot("终端 - credential set 凭据录入"),

  h3("查看 / 删除"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar credential list",
    "java -jar ~/bin/flux-deploy-cli.jar credential delete \\",
    "  --host 47.102.153.34 --port 18080 --user xumanyi",
  ]),
  p("凭据存储位置：~/.flux-deploy/credentials.toml（文件权限 600）。密钥由当前机器 user.name + user.home + 固定盐派生——换机器或换用户无法解密（安全隔离）。"),

  h2("2.4 部署（deploy）"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar deploy --config <json文件>",
    "  [--password-file <密码文件>]   # 可选，覆盖缓存",
    "  [--dry-run]                    # 可选，仅预检不改动远程",
    "  [--verbose]                    # 可选，调试信息到 stderr",
  ]),

  h3("更新模式（mode 字段）"),
  p("CLI 与插件 UI 一致，只支持两种更新模式："),
  tbl(["mode", "含义", "必填字段", "CLI 编译"], [
    ["full（默认）", "整包替换，上传 localFile。CLI 不参与编译，由用户提前 mvn package", "targets[].localFile", "不编译"],
    ["incremental", "下载远端包 → 按 files 打补丁 → 上传", "projectDir, files, targets[].name", "强制 mvn clean package"],
  ]),
  p("注意：v1.1.2 起删除了 mode=auto。「文件来源」（手选 / Git 检测）由调用方负责——插件 UI 用预填清单组件，Skill 用 git status 后向用户确认；CLI 不内置 git 检测。同时删除了 skipCompile 字段——incremental 模式必编译，避免「改了源码忘编译就上线」。"),

  h3("JSON 配置字段"),
  tbl(["字段", "必填", "说明"], [
    ["ftp.host", "是", "纯 IP 或主机名，不含 ftp://"],
    ["ftp.port", "否", "默认 18080"],
    ["ftp.username", "是", "登录用户名，同时用作凭据缓存匹配键"],
    ["remoteDir", "是", "FTP 登录后 cwd 的目标路径"],
    ["mode", "否", "full / incremental，默认 full"],
    ["projectDir", "条件", "incremental 模式必填，模块根（含 pom.xml）"],
    ["files", "条件", "incremental 必填；条目示例 \"M src/main/java/Foo.java\""],
    ["mavenHome / javaHome", "否", "覆盖 mvn / JDK 路径（仅 incremental 用）"],
    ["targets[].localFile", "条件", "full 模式必填；incremental 留空（CLI 自动生成）"],
    ["targets[].name", "条件", "full 可省略（自动扫描）；incremental 必填"],
    ["targets[].relativePath", "否", "远程相对路径；有多级目录时必填"],
    ["meta.taskId / customerId / operator", "否", "版本记录相关；要求备份时 operator 必填"],
    ["options.skipBackup", "否", "默认 false；想跳过备份时设 true"],
    ["options.skipNote", "否", "默认 false；不写版本记录时设 true"],
    ["options.outputFormat", "否", "json / text，默认 text"],
  ]),
  p("完整字段定义（含 embedTargets 等）用 schema 子命令获取。"),

  h3("示例 1：扫描模式，无备份无版本记录"),
  ...codes([
    "cat > /tmp/deploy.json <<'EOF'",
    "{",
    "  \"ftp\":       { \"host\": \"47.102.153.34\", \"port\": 18080, \"username\": \"xumanyi\" },",
    "  \"remoteDir\": \"/开发/1测试项目/TMS V9.0-P7/\",",
    "  \"targets\":   [ { \"localFile\": \"/path/to/scev6-utils-tms-9.0.0-SNAPSHOT.jar\" } ],",
    "  \"options\":   { \"skipBackup\": true, \"skipNote\": true, \"outputFormat\": \"json\" }",
    "}",
    "EOF",
    "java -jar ~/bin/flux-deploy-cli.jar deploy --config /tmp/deploy.json",
  ]),
  p("CLI 自动扫描 remoteDir 下所有同名 jar 并并行更新。"),

  h3("示例 2：增量更新（调用方提供 files 列表）"),
  ...codes([
    "{",
    "  \"ftp\":       { \"host\": \"47.102.153.34\", \"port\": 18080, \"username\": \"xumanyi\" },",
    "  \"remoteDir\": \"/开发/1测试项目/TMS V9.0-P7/\",",
    "  \"mode\":      \"incremental\",",
    "  \"projectDir\": \"/Users/xumanyi/scev6/v6_000_utils/scev6-utils-tms\",",
    "  \"files\":     [ \"M src/main/java/Foo.java\", \"A src/main/resources/new.xml\" ],",
    "  \"targets\":   [ { \"name\": \"scev6-utils-tms-9.0.0-SNAPSHOT.jar\" } ],",
    "  \"options\":   { \"skipBackup\": true, \"skipNote\": true, \"outputFormat\": \"json\" }",
    "}",
  ]),
  p("CLI 拿到 files 后自动 mvn clean package，再按 jar 内条目映射打补丁。"),

  h2("2.5 查看字段定义（schema）"),
  ...codes(["java -jar ~/bin/flux-deploy-cli.jar schema"]),
  p("输出标准 JSON Schema（draft-07）到 stdout，包含全部字段定义、默认值、枚举值与退出码。AI Skill 启动时跑一次该命令获取最新字段约束，避免与 CLI 实现脱节。"),
  shot("终端 - schema 命令输出"),

  h2("2.6 清理残留锁（unlock）"),
  p("中断的部署会留下 <pkg>__LOCK__<user>_<timestamp> 文件。Lock 门禁启动时检测到残留锁会直接拒绝部署。"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar unlock \\",
    "  --host <host> --port <port> --user <user> --remote-dir <dir>",
    "  [--delete-orphans]   # 活跃包已存在的孤立锁直接删除（高风险，需用户确认）",
  ]),
  p("默认行为：同目录无活跃包 → rename 回原名（恢复）；同目录有活跃包 → 列为冲突并跳过。"),

  h2("2.7 历史回滚（rollback）"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar rollback \\",
    "  --host <host> --port <port> --user <user> \\",
    "  --remote-dir <systemRoot> \\",
    "  --backup-dir <backupDirName> \\",
    "  --operator <operator> \\",
    "  [--dry-run] [--no-safety-backup]",
  ]),
  p("默认会先把当前生产状态做安全备份到 backup/<YYYYMMDD>_rollback_<HHmmss>/，再上传备份内容。整套门禁（加锁 / SHA256 / 解锁）完整执行，任一失败自动回滚到回滚前。"),
  warn("回滚是高风险操作，建议先 --dry-run 把映射表展示给用户确认，再实跑。"),

  h2("2.8 本地打补丁（patch-local）"),
  p("不涉及 FTP，纯本地操作。给用户在本地 jar/war 上打补丁，输出新包到 patched_<时间戳>/。"),
  ...codes([
    "java -jar ~/bin/flux-deploy-cli.jar patch-local \\",
    "  --project-dir <模块根> \\",
    "  --local-package <本地 jar/war> \\",
    "  --mode full|incremental|auto \\",
    "  [--files \"f1,f2\"]   # incremental 必填",
    "  [--git-root <path>]  # auto 模式可选；默认向上查找 .git",
  ]),
  p("注意：patch-local 子命令保留 mode=auto 作为命令行场景的「git 自动检测」便利开关——这是 CLI 内部值，不影响 deploy 子命令和插件 UI（它们已合并为两档）。"),

  h2("2.9 浏览 FTP（browse）"),
  p("AI 不知道目标 FTP 有哪些项目 / 包时先浏览。输出 JSON，便于 Skill 解析后决定 remoteDir / targets[].name / targets[].relativePath。"),
  ...codes([
    "# 列子目录（项目 / 系统 / 子系统）",
    "java -jar ~/bin/flux-deploy-cli.jar browse \\",
    "  --host X --port Y --user Z --list-dirs \"/开发/1测试项目/\"",
    "",
    "# 递归扫描可部署包",
    "java -jar ~/bin/flux-deploy-cli.jar browse \\",
    "  --host X --port Y --user Z --scan-packages \"/开发/1测试项目/TMS V9.0-P7/\"",
  ]),
];

// =============================================================================
// Part 3 - AI Skill 协作
// =============================================================================
const part3 = [
  h1("第三部分：AI Skill 协作"),
  p("flux-customer-service-update Skill 让 AI 助手通过自然语言完成 FTP 客服更新。Skill 只负责收集参数、拼 JSON 配置、调 CLI、按退出码反馈；所有远程变更走 CLI，密码全程不经过 AI（由 CLI 内部读取 ~/.flux-deploy/credentials.toml 自动解密）。"),
  img("flow_skill.png", 380),

  h2("3.1 工作流概述"),
  num("AI 检查 CLI jar 与 java 可用性（按 $FLUX_DEPLOY_CLI > ~/bin > 工程目录顺序查找）"),
  num("跑 schema 子命令，加载当前 CLI 字段定义"),
  num("一次性向用户收齐缺项（FTP / 远程目录 / 本地包 / 任务号 / 客服号 / 操作人）"),
  num("生成临时 JSON 配置（mktemp，不含密码）"),
  num("【强制】向用户展示执行清单 → 等待确认"),
  num("用户确认后调 deploy 子命令执行"),
  num("按退出码 0/64/70 分支处理结果"),
  num("清理临时 JSON（无论成败）"),

  h2("3.2 触发条件与凭据约束"),
  h3("触发关键词"),
  bul("用户直接说「客服更新」"),
  bul("要求更新客户 FTP 包"),
  bul("要求对 jar / war 做增量更新"),
  bul("要求更新位于 war WEB-INF/lib 中的 jar"),
  bul("要求在上传前先备份客户包"),

  h3("AI 行为硬约束"),
  warn("AI 不得用 Bash / FTP 工具直接执行上传、备份、rename、扫描等任何远程操作——所有远程变更必须走 CLI。"),
  warn("AI 不得接触密码——不得读取 credentials.toml、不得向用户索要明文密码后写到文件、不得把 ftp.password 写进 JSON。"),
  warn("编译失败时严禁自动修改任何源文件——遇到 mvn 编译错误，把报错原文展示给用户，提示「请修复后重试」，任务中止。"),

  h2("3.3 信息收集与执行清单"),
  p("AI 一次性收齐参数后，必须先打印执行清单，等用户确认后才能调 deploy。即使所有参数都齐、即使用户已经说过「开始执行」，也必须先确认清单。"),

  h3("清单格式（按实际配置裁剪）"),
  ...codes([
    "=== 执行清单（请确认）===",
    "模式      : full | incremental",
    "源工程    : /Users/.../scev6-utils-tms       (incremental 时显示)",
    "本地包    : /path/to/scev6-utils-tms-9.0.0-SNAPSHOT.jar  (full 时显示)",
    "",
    "变更文件  : (仅 incremental 显示，逐条列出)",
    "  [M] src/main/java/com/flux/Foo.java",
    "  [A] src/main/java/com/flux/Bar.java",
    "  [D] src/main/resources/old.xml",
    "",
    "目标 FTP  :",
    "  47.102.153.34:18080  (用户 xumanyi)",
    "  /开发/1测试项目/TMS V9.0-P7/",
    "",
    "更新目标  :",
    "  → /开发/1测试项目/TMS V9.0-P7/shared-edp/scev6-utils-tms-9.0.0-SNAPSHOT.jar",
    "  → /开发/1测试项目/TMS V9.0-P7/shared-tms/scev6-utils-tms-9.0.0-SNAPSHOT.jar",
    "",
    "执行备份  : 是 / 否",
    "版本记录  : 是 / 否",
    "  - 任务号 : T-12345",
    "  - 客服号 : C-67890",
    "  - 操作人 : xumanyi",
    "",
    "是否开始执行？(请回复\"确认\" / \"取消\" / 修改某字段)",
  ]),

  h3("incremental 模式：files 列表怎么来"),
  bul("用户已明确给出文件列表 → 直接放进 files"),
  bul("用户说「把未提交的 git 变更发上去」 → AI 先跑 git -C <projectDir> status --porcelain，整理为 [M] 相对路径 形式后同时展示给用户 + 写入 files（同一份列表，保证「展示」与「执行」完全一致）"),
  bul("用户说「重新整包发布」 → 走 full 模式，让用户给 jar/war 路径"),

  h2("3.4 退出码处理与异常恢复"),
  tbl(["code", "含义", "Skill 行为"], [
    ["0", "成功", "解析 stdout JSON，向用户汇报每个目标的 backupPath / localSha256 / remoteSha256 / verified / noteUpdated"],
    ["64", "用法 / 配置错误", "展示 CLI stderr 第一行；若含「缺少密码」/「Tag mismatch」，提示用户跑 credential set；不要盲目重试"],
    ["70", "执行期错误", "CLI 已触发回滚，解析 rollback 字段汇报；若 lock 门禁因残留锁失败，引导用户跑 unlock 子命令"],
  ]),
  p("stdout JSON 顶层结构：success / targets[] / rollback / errors[]。"),
];

// =============================================================================
// 附录
// =============================================================================
const appendix = [
  h1("附录"),

  h2("A. 目录与配置文件位置"),
  tbl(["路径", "用途", "说明"], [
    ["~/bin/flux-deploy-cli.jar", "CLI fat-jar 默认位置", "用户手动 cp，不要放进 git / Skill 目录"],
    ["~/.flux-deploy/credentials.toml", "凭据缓存", "AES-256-GCM 加密，文件权限 600；目录权限 700；CLI 自管，AI 不读"],
    ["~/.claude/skills/flux-customer-service-update/", "Claude AI Skill 安装位置", "包含 SKILL.md + references/，跨 AI 工具复用时复制到对应位置"],
    ["项目根/.flux-deploy-log/", "插件运行日志", "按日期归档"],
    ["FTP 目录/backup/<YYYYMMDD_operator>/", "FTP 端备份", "失败时自动从此目录恢复"],
    ["FTP 目录/<file>__LOCK__<user>_<ts>", "FTP 端锁文件", "中断的部署会留下，用 unlock 子命令清理"],
  ]),

  h2("B. 退出码与错误排查"),
  h3("CLI 退出码"),
  tbl(["code", "含义", "处置"], [
    ["0", "成功", "解析 stdout JSON 汇报"],
    ["64", "用法 / 配置错误（EX_USAGE）", "看 stderr 第一行，修正配置；不要盲目重试"],
    ["70", "执行期错误（EX_SOFTWARE）", "CLI 已尝试回滚，看 rollback 字段；必要时手工清理 FTP 残留"],
  ]),

  h3("常见错误与对策"),
  B("Q：缺少密码 / Tag mismatch"),
  p("A：凭据缓存失效或换机器后无法解密。提示用户跑 credential set 重新录入。"),
  B("Q：FTP 连接失败：Connection refused"),
  p("A：检查主机 / 端口 / 网络（VPN）；不是密码问题，不要清缓存。"),
  B("Q：发现残留锁包"),
  p("A：上次部署中断留下的锁文件。先跑 unlock 子命令清理，再重试 deploy。"),
  B("Q：mvn 编译失败"),
  p("A：把 stderr 的报错原文展示给用户。AI 不要自动改源码——让用户人工修复后重试。"),
  B("Q：targets[].localFile 不存在"),
  p("A：full 模式要求本地 jar/war 已编译好。让用户先在 IDE 或命令行跑一次 mvn package。"),
  B("Q：projectDir 内未找到 pom.xml"),
  p("A：incremental 模式 projectDir 必须是 Maven 模块根。检查路径或换成正确目录。"),
];

// =============================================================================
// 文档组装与输出
// =============================================================================

const doc = new Document({
  styles: { default: { document: { run: { font: F, size: 22 } } }, paragraphStyles: [
    { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 36, bold: true, font: F, color: C.pri }, paragraph: { spacing: { before: 300, after: 150 }, outlineLevel: 0 } },
    { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 30, bold: true, font: F, color: C.pri }, paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 1 } },
    { id: "Heading3", name: "Heading 3", basedOn: "Normal", next: "Normal", quickFormat: true, run: { size: 24, bold: true, font: F, color: C.pri }, paragraph: { spacing: { before: 180, after: 100 }, outlineLevel: 2 } },
  ] },
  numbering: { config: [
    { reference: "bul", levels: [
      { level: 0, format: LevelFormat.BULLET, text: "•", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } },
      { level: 1, format: LevelFormat.BULLET, text: "◦", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 1440, hanging: 360 } } } },
    ] },
    { reference: "num", levels: [
      { level: 0, format: LevelFormat.DECIMAL, text: "%1.", alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 720, hanging: 360 } } } },
    ] },
  ] },
  sections: [
    { properties: pgProps, children: cover },
    { properties: { ...pgProps, ...hf }, children: [
      ...toc,
      PB,
      ...part1,
      PB,
      ...part2,
      PB,
      ...part3,
      PB,
      ...appendix,
    ] },
  ],
});

const OUT = path.join(DIR, "FLUX客服更新工具使用手册.docx");
Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync(OUT, buf);
  console.log("Generated: " + OUT + " (" + (buf.length / 1024).toFixed(0) + " KB)");
});
