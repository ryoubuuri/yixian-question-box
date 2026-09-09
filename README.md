# 识屏搜题助手（Android / Kotlin）

悬浮窗截屏 → OCR 识别题目 → 本地题库 / AI 匹配 → **答案直接显示在悬浮窗内**，全程不离开当前答题 App。

## 一、工程结构

```
SearchHelper/
├── settings.gradle                  # 含 JitPack 仓库（EasyFloat）
├── build.gradle                     # AGP 8.1.4 / Kotlin 1.9.22
├── gradle/wrapper/gradle-wrapper.properties
├── scripts/verify_matching.py       # 匹配逻辑离线验证脚本（镜像 normalize/match，无需 Android SDK）
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/question_bank.csv       # 离线题库（题目,答案）
        ├── res/layout/activity_main.xml   # 授权首页 + AI 接口设置 + 识别区域模式
        ├── res/layout/float_search_box.xml# 悬浮窗（截屏/设置/关闭）
        ├── res/layout/dialog_column_picker.xml # 导入列选择对话框
        ├── res/layout/overlay_region_selector.xml # 截屏后手动框选覆盖层
        ├── res/xml/backup_rules.xml       # 备份排除（不上传 API Key）
        ├── res/xml/data_extraction_rules.xml
        └── java/com/example/searchhelper/
            ├── MyApplication.kt           # 初始化 AppContext + 题库
            ├── AppContext.kt / AppScope.kt
            ├── Config.kt                  # Endpoint / Key / Model + 识别区域模式 + 预设
            ├── MainActivity.kt            # AI 设置、权限申请、识别区域模式设置
            ├── ScreenCaptureService.kt    # MediaProjection + ImageReader 前台服务
            ├── QuestionSolver.kt          # 结构化 OCR（行提取）→ 题干/选项 → 题库 → AI
            ├── QuestionBank.kt            # 题库持有 + 汉字过滤匹配（内置 + 导入）
            ├── BankImporter.kt            # xlsx / csv / txt 文件解析
            ├── FloatWindow.kt             # 悬浮窗 + 搜索流程（全屏/框选/上次区域 分发）
            ├── RegionCanvasView.kt        # 手动框选控件（拖动新建/移动/四角缩放）
            └── RegionSelectOverlay.kt     # 框选覆盖层（WindowManager 全屏）
```

## 二、环境要求

| 项目 | 版本 |
| --- | --- |
| Android Studio | Hedgehog(2023.1.1) 及以上 |
| JDK | 17（AGP 8.x 强制） |
| Gradle | 8.2（wrapper 已指定） |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| 真机 | Android 7.0+，建议 Android 10+ 验证 MediaProjection 前台服务行为 |

## 三、编译与运行

1. Android Studio → `File > Open` → 选中 `SearchHelper` 目录。
2. 首次打开若提示 **Gradle wrapper 缺失**（本仓库未提交二进制 `gradle-wrapper.jar`）：
   - 方式 A：Terminal 执行 `gradle wrapper --gradle-version 8.2` 自动生成；
   - 方式 B：Android Studio 提示框点 `OK / Generate` 自动修复；
   - 方式 C：从任意可编译的 Android 工程复制 `gradle/wrapper/gradle-wrapper.jar` 与 `gradlew`、`gradlew.bat` 过来。
3. 确认 `local.properties` 中 `sdk.dir` 指向本机 Android SDK（Studio 通常自动写入）。
4. 等待 Gradle Sync 完成（会拉取 EasyFloat、ML Kit 中文模型包、OkHttp）。
5. 连真机运行（**模拟器也能跑，但 OCR 场景建议真机**）。

命令行编译：`./gradlew assembleDebug`（Windows 用 `gradlew.bat assembleDebug`）。

## 四、使用流程

0. （建议先做）首页 →【导入题库】→ 选择 xlsx/csv 题库文件 → 追加或覆盖导入。
1. 打开 App → 在「AI 接口设置」里选服务商预设（或手填接口地址 / 模型名称 / API Key）→ 点【保存配置】
   （可先点【测试连接】确认能通）→ 点【启动悬浮窗】。
2. 首次会跳转授权页：①悬浮窗权限 ②通知权限（Android 13+）③屏幕录制权限 —— 依次允许。
3. 授权后 App 自动退到后台，屏幕右侧出现悬浮窗。
4. 打开任意答题 App，停在题目页 → 点【截屏搜题】：
   - 悬浮窗自动隐藏 → 截屏 →（若设为"每次手动框选"会先弹框选界面）→ OCR → 匹配 → 恢复显示并直接给出答案。
5. 悬浮窗上还有【设置】：一键回首页改 AI 配置与识别区域模式，不必去找桌面图标。
6. 不需要时点【关闭】；首页【停止服务并关闭悬浮窗】可彻底停掉前台服务。

### 识别区域：全屏 / 每次框选 / 记住上次区域

首页新增「识别区域」下拉框（在【启动悬浮窗】上方），三种模式：

| 模式 | 行为 | 适用 |
| --- | --- | --- |
| 全屏识别（默认） | 每次截整屏 OCR，弹窗提示最少，速度最快 | 原版行为，题目占满屏或不愿多一步操作 |
| 每次手动框选 | 每次截屏后**弹出框选界面**：拖动空白处新建选框、框内拖动整体移动、四角缩放，点【确定识别】只 OCR 框内区域；点【全屏】忽略选框；点【取消】放弃本次 | 屏上同时有 logo/进度/倒计时等干扰文字，只想识别题目区 |
| 记住上次框选区域 | 不弹框，直接用上次框选并保存的区域裁切后 OCR | 同一答题 App 布局固定时最省事 |

细节：
- 框选坐标按**屏幕比例（0~1）保存**在本地（`Config.lastRegionRect`，持久化），换分辨率/旋转后仍按同一相对位置生效。
- 「每次手动框选」确定后会自动把本次区域存为"上次区域"，因此随时可切到第三种模式免框选。
- 「记住上次框选区域」但从未保存过时，自动退化为全屏识别并提示。
- 首页有【清除已存区域】按钮；保存的区域也会在识别区域说明文字里显示成百分比，便于核对。

## 四·补 功能 → 入口对照表

全部已实现功能与其交互入口，用户无需改代码即可触达：

| 功能 | 入口位置 | 说明 |
| --- | --- | --- |
| 接口地址（API Base URL） | 首页「AI 接口设置」→【接口地址】输入框 | 可手填；选预设会自动带入 |
| 模型名称 | 首页「AI 接口设置」→【模型名称】输入框 | 同上 |
| API Key | 首页「AI 接口设置」→【API Key】输入框 | 默认密文显示，可勾选「显示 API Key（明文）」 |
| 保存配置 / 即时生效 | 【保存配置】按钮 | 校验通过后写入，下一次搜题立即生效 |
| 连通性自检 | 【测试连接】按钮 | 发一次最小请求，按 HTTP 码给出可读原因 |
| 恢复默认接口 | 【恢复默认接口（DeepSeek）】按钮 | 只重置地址与模型，不动 Key |
| 服务商预设 | 「服务商预设」下拉框 | DeepSeek / OpenAI / Kimi / 智谱 / 通义 + 自定义 |
| Key 状态查看 | Key 输入框下方状态框 | 只显示后 4 位，永不回显完整 Key |
| 启动悬浮窗 | 【启动悬浮窗】按钮 | 会顺带校验并保存 AI 配置；服务仍在运行时直接重新显示浮窗，不再重复申请录屏授权 |
| 停止服务 | 【停止服务并关闭悬浮窗】按钮 | 关浮窗 + 停前台服务 |
| 导入题库（追加 / 覆盖） | 【导入题库】按钮 → 方式选择对话框 | 之后进入列选择对话框 |
| 指定题目列 / 答案列 / 表头 | 导入流程中的「确认列对应关系」对话框 | 两个下拉框 + 表头勾选框 |
| 清空导入题库 | 【清空导入题库】按钮 → 二次确认 | 无导入文件时直接提示，不弹确认 |
| 截屏搜题 | 悬浮窗【截屏搜题】 | OCR → 本地题库 → AI；按识别区域模式截全屏/弹框选/复用上次区域 |
| 识别区域模式（全屏/框选/上次区域） | 首页「识别区域」下拉框 | 每次手动框选会先弹框选界面再 OCR |
| 清除已保存的框选区域 | 首页【清除已存区域】 | 「记住上次框选区域」无保存区域时自动退化为全屏 |
| 回到设置页 | 悬浮窗【设置】 | 无需退出答题 App 找图标 |
| 关闭悬浮窗 | 悬浮窗【关闭】 | 只关浮窗，前台服务仍在 |
| 拖拽悬浮窗 | 长按浮窗拖动 | EasyFloat 内置 |
| 屏幕分辨率 / 服务状态自检 | 首页底部状态栏（每次 onResume 刷新） | 排查「截屏失败」用 |
| 题库条数 / 当前模型 | 首页底部状态栏 | — |

## 五、答案来源：本地题库 与 AI

**匹配顺序：本地题库（离线、秒回） → 未命中才走 AI。**

### 1) 运行时导入题库（首页【导入题库】按钮，最常用）

**不需要重新编译**，装好 APK 后随时可换题库：

1. 首页 →【导入题库】→ 选择「追加导入」或「覆盖导入」。
2. 在系统文件选择器里选中题库文件（.xlsx / .csv / .txt 均可）。
3. **弹出"确认列对应关系"对话框**：显示读取到的行列数、前 3 行内容预览，两个下拉框分别指定
   「题目所在列」与「答案所在列」，并可勾选「首行是表头（不导入）」。确认后点导入。
4. 等 Toast 提示"导入成功，题库共 N 条"；首页底部同步显示"共 N 条（内置 X + 导入 Y）"。
5. 导入即时生效，不用重启服务；不需要时点【清空导入题库】。

**列识别规则**（不再固定取前两列）：

| 情况 | 默认选中 | 依据 |
| --- | --- | --- |
| 首行含「题目/题干/question」与「答案/正确选项/answer」等关键词 | 对应列，并自动勾选"首行是表头" | 表头关键词匹配 |
| 无表头或不匹配关键词 | 平均字符长度最长的列 = 题目；其余列中最短的 = 答案 | 内容长度启发式 |

两种都是**猜测值，可在下拉框里随意改**，所以任何列序（序号在前、选项在中间、答案在最后一列）都能正确导入。

| 项目 | 说明 |
| --- | --- |
| .xlsx | 解析第一个工作表的前两列（A 列题目、B 列答案）；共享字符串与内联字符串都支持 |
| .csv / .txt | 取前两列；分隔符自动识别（逗号/制表符/分号，取出现次数最多者）；支持双引号包裹 |
| 编码 | UTF-8 优先，非 UTF-8 自动按 GBK 解码 —— Excel 另存的 GBK 文件不会乱码 |
| 表头 | 首行若为"题目/答案"自动跳过；`#` 开头为注释行 |
| 去重 | 追加时按归一化题干自动跳过重复项 |
| 存储 | 写入 `filesDir/imported_bank.csv`，下次启动自动加载 |
| 格式识别 | 按文件头魔数（PK）判断 xlsx，不依赖文件扩展名，避免 SAF 返回的无后缀 Uri |
| 列数上限 | 只读取前 16 列（`BankImporter.MAX_COLUMNS`） |
| 空值 | 题目或答案为空的行自动跳过 |

> 已知限制：题目本身含换行（CSV 多行字段）暂不支持；xlsx 只取第一张工作表。

### 2) 打包内置题库（随 APK 发布）

编辑 `app/src/main/assets/question_bank.csv`，两列：第一列题目、第二列答案，**UTF-8 编码**。首行表头会被自动跳过，`#` 开头为注释。

```
"题目","答案"
"安全生产方针的内容是","安全第一、预防为主、综合治理"
```

不需要内置示例时，把该文件清成只剩表头行即可（内置题库不受【清空导入题库】影响）。

### 2·1) 匹配策略：仅比对汉字（忽略数字/符号/括号/空格等非汉字）

OCR 对数字、字母、标点的识别远不如汉字稳定（`1`↔`l`、`0`↔`O`、全角半角、括号错位很常见）。
因此本地匹配前会先把题目文本做**汉字过滤**：只保留 CJK 汉字（U+4E00-U+9FFF 及扩展 A
U+3400-U+4DBF），其余一律丢弃。规则与边界：

| 类别 | 处理 | 示例 |
| --- | --- | --- |
| 汉字 | 保留 | "安全生产" → "安全生产" |
| 数字（半角/全角） | 丢弃 | "第3条" 匹配用 "第条" |
| 拉丁字母 | 丢弃 | "A．选项" → "选项" |
| 标点 / 括号 / 空格 / 选项标记 / emoji | 丢弃 | "（2026）？" 全部消失 |

边界与例外（务必知悉）：
- **纯英文/公式/代码题**过滤后为空 → 不参与本地匹配，直接走 AI（AI 看到的是原始文本，不受影响）。
- 题库**去重**与**匹配**用的是同一套汉字串，所以只差数字/符号的两道题（如"第3条"与"第5条"）
  会被视为同一题，导入时后者被跳过。若题库存在此类题目，请把数字写成汉字（第三/第五）以区分。
- 过滤后不足 4 个汉字的查询不参与匹配（太短无区分度）。

**匹配顺序**：精确 → 双向包含 → 二元组 Dice 相似度。

- 精确：汉字串完全一致，秒回。
- 双向包含：OCR 文本包含整条题库题干，或题干包含 OCR 文本（OCR 截断/粘连时救场）。
- Dice 兜底：二元组相似度，**阈值按汉字长度自适应**（<10 字 ≥0.72、10~24 字 ≥0.66、≥25 字 ≥0.60），
  短句一个字错就掉分多，所以门槛更高；同时要求**第一名与第二名分差 ≥0.05**，
  两名次太接近视为"歧义"，宁可直接丢给 AI，避免答错。

离线验证：仓库内 `scripts/verify_matching.py` 用 Python 镜像了新旧两套
`normalize`/`match`，对真实题库跑自命中回归与"整屏噪声注入"对比（本机运行
`python scripts/verify_matching.py`，无需 Android SDK）。

### 2·2) OCR 结构化：把"整屏一段字"拆成 题干 + 选项 + 噪声

`QuestionSolver` 不再把整屏 OCR 拍平成一个字符串，而是先按行（带坐标）提取，再按
版面与文字特征归类，保证进题库的查询是**干净的题干**而非夹杂 logo/进度/倒计时的整屏文字：

| 归类 | 判定依据 | 处理 |
| --- | --- | --- |
| 题干 stem | 既不是选项行、也不是噪声行 | 作为本地匹配的主查询；空则走 AI |
| 选项 options | 行首匹配 `A. A、① (1) A：` 等标记，且 ≤80 字 | 不进本地查询；拼给 AI 提示词（"选项：…"），让模型直接选字母 |
| 噪声行 | 「上一题/下一题/倒计时/得分/交卷…」等 ≤12 字，或 `第 3 题 / 3:45 / 3/20` 等进度模式 | 直接丢弃，避免污染题干与查询 |
| 干扰项 | 不存在的干扰（如错误选项文字）天然落在"选项"类 | 本地匹配只看题干，选项干扰不影响；AI 提示中选项一并给出由模型判断 |

行为边界：题干与选项仅按**行**区分，若答题 App 把整题渲染成一行（无换行），选项行
判定会失效，退化为整段文字匹配——这正是"全屏模式 + 双向包含"仍保留兜底的原因。
选项字母 `A` 若被 OCR 误识成 `4`，该行不会命中选项标记，但只会进入题干，不影响
题库命中（汉字过滤后字母本就丢弃）。

### 3) AI 接口（题库未命中时兜底）

默认 DeepSeek，OpenAI 兼容协议。**三项参数全部在首页「AI 接口设置」区直接改，不需要改代码、不需要重新编译。**

| 厂商 | endpoint | model |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/chat/completions` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `gpt-4o-mini` |
| Kimi | `https://api.moonshot.cn/v1/chat/completions` | `moonshot-v1-8k` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | `glm-4-flash` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | `qwen-plus` |

上面 5 家已做成「服务商预设」下拉项，选中即自动填入 endpoint + model；两项都可继续手改（改完下拉框自动跳到「自定义」）。

**保存与即时生效**：点【保存配置】→ 校验通过即写入并 Toast「AI 配置已保存，立即生效」。
`Config` 的三个值都在每次发请求时现读，所以无需重启服务，下一次点【截屏搜题】就用新配置。

**校验规则与错误提示**（任一不通过则拒绝保存，错误直接标在对应输入框下方 + 状态栏汇总）：

| 字段 | 规则 | 提示示例 |
| --- | --- | --- |
| 接口地址 | 非空；必须以 `http://` 或 `https://` 开头；能解析出主机名 | 「接口地址不完整，解析不到主机名（示例：…）」 |
| 模型名称 | 非空；不含空格/换行 | 「模型名称不能包含空格或换行」 |
| API Key | 可留空（题库命中时不需要）；不得含空格/换行；长度 ≥ 8 | 「API Key 不能包含空格或换行，请检查是否复制多了字符」 |

**【测试连接】**：先自动保存，再发一次 `max_tokens=8` 的最小请求。失败时按 HTTP 码翻译成可读原因 ——
401/403「Key 无效或没有该模型的权限」、404「接口地址不存在或模型名不可用」、429「请求过于频繁或余额不足」，
并把服务端返回原文截断附在后面，方便对照。

**API Key 的安全处理**：

| 措施 | 做法 |
| --- | --- |
| 加密存储 | 单独文件 `search_helper_secure.xml`，`EncryptedSharedPreferences`（值 AES-256-GCM / 键 AES-256-SIV），主密钥由 Android Keystore 持有 |
| 与普通配置隔离 | 地址、模型放 `search_helper.xml`；Key 不写入该文件 |
| 遮罩显示 | 输入框 `textPassword`，须勾选「显示 API Key（明文）」才可见；状态区只回显后 4 位 |
| 不进备份 | `res/xml/backup_rules.xml` + `data_extraction_rules.xml` 排除两个 prefs 文件，Key 不随云备份/换机上传 |
| 不进日志 | 全程无 `Log` 输出 Key |

新增依赖：`androidx.security:security-crypto:1.1.0-alpha06`（见 `app/build.gradle`）。

解析逻辑在 `QuestionSolver.askAi(screen)`（`screen` 携带题干 `stem` 与选项 `options`），读取标准
`choices[0].message.content`。若接自建题库服务，把该函数替换为你的 POST + 解析即可（例如
`resObj.getJSONArray("data").getJSONObject(0).getString("answer")`）。

## 六、与原始技术方案的差异（均为"不改就跑不起来/跑不稳"的必要修正）

| 原始方案 | 本工程 | 原因 |
| --- | --- | --- |
| `EasyFloat:1.3.4` | `EasyFloat:2.0.4` | 2.0 起**无需 Application init**，且为 AndroidX 版本，适配新版 Gradle |
| `SidePattern.EDGE_CLOSE` | `SidePattern.RESULT_HORIZONTAL` | 库中**不存在** EDGE_CLOSE 枚举，照抄会编译失败 |
| `text-recognition-chinese:16.0.0` | `16.0.1` | 当前官方最新版 |
| 无 POST_NOTIFICATIONS | 已加入并在首页动态申请 | Android 13+ 不授权则前台服务通知不显示，易被用户误判为"没启动" |
| `startForeground(id, notification)` | Android 10+ 追加 `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` | Android 14 强制，否则抛 SecurityException |
| 未注册 MediaProjection.Callback | 已注册 | Android 14 要求使用前注册回调 |
| `buffer` 未 rewind | `buffer.rewind()` 后再 copyPixelsFromBuffer | 不 rewind 会读到错误像素/抛 BufferUnderflow |
| `acquireLatestImage()` 取一次 | 轮询等待最多 15×80ms | 隐藏浮窗后新帧尚未产出，首取常为 null，直接判失败会"截屏失败" |
| 延迟 300ms 后手工调 `initMediaProjection` | 在 `onServiceConnected` 中调用 | 绑定时机不确定，固定延时不可靠 |
| 题库只有 assets 静态文件、无导入入口 | 新增 `BankImporter.kt` + 首页【导入题库】/【清空导入题库】 | 运行时换题库是刚需，不能每次改 assets 重新编译；xlsx 直接解析，无需 POI 等重型依赖 |
| 匹配归一化保留数字/字母/符号 | `normalize` 只保留汉字（CJK 基本区 + 扩展 A） | OCR 对数字/字母/标点识别不稳，汉字串对比能滤掉最常见的识别误差（详见 2·1） |
| 全屏 OCR 拍平为一段字、直接去匹配 | `recognizeLines` 按行提取 → `buildQuestion` 拆成 题干/选项/噪声，题库查询用题干 | 整屏文字含 logo/进度/倒计时等噪声，稀释 Dice 相似度也污染 AI 提示（详见 2·2） |
| Dice 固定阈值 0.62、只取第一名 | 按汉字长度自适应阈值（0.72/0.66/0.60）+ 与第二名分差 ≥0.05 才采纳 | 短题干与长题干对误差的容忍度不同；两名接近=歧义，交给 AI 更稳 |
| 只能全屏截图识别 | 识别区域三种模式：全屏 / 每次手动框选 / 记住上次区域 | 框选只 OCR 题目区，天然剔除屏上其它文字，识别更快更准 |
| AI 提示只发整屏文字 | 提示词改为「题干 + 选项列表」 | 模型聚焦题目本体，可输出"选 A"式答案，减少被无关文字干扰 |
| 无本地题库 | 新增 `QuestionBank.kt` + CSV | 你的需求是"题库或 AI"，本地优先可离线秒回并省 token |
| 硬编码 API Key | `Config.kt` 持久化 + 首页输入 | 避免改代码重新编译 |
| 只有 Key 能改，地址与模型写死在 `Config.kt` | 首页「AI 接口设置」三项全可改 + 服务商预设 + 测试连接 | 换厂商/换模型属于高频需求，不该改代码 |
| API Key 明文存 SharedPreferences | `EncryptedSharedPreferences` + 独立文件 + 排除备份 + 遮罩 | `allowBackup="true"` 下明文 Key 会被云备份带走 |
| 【清空导入题库】点了就删、无确认 | 先判 `hasImported()`，无文件直接提示；有文件才二次确认 | 误触代价高；顺带让死代码 `hasImported()` 有了真实调用 |
| `screenSize()` 写了没人调用 | 首页状态栏显示运行时分辨率，排查「截屏失败」 | 死代码；同时补上分辨率自检入口 |
| 悬浮窗只有【截屏搜题】【关闭】 | 增加【设置】一键回首页 | 改 AI 配置不必回桌面找图标 |
| `FloatWindow.show()` 以「tag 是否注册」为判据 | 改为「视图是否真的 attach 且 VISIBLE」，未真正显示就先 `dismiss` 再重建 | EasyFloat 的内部注册表在 `dismiss()` 后可能残留，`getFloatView() != null` 会让重建被永久短路 —— 表现为「关掉浮窗后再也打不开，只能杀进程」 |
| 关浮窗后重启仍需重新申请录屏授权，且只能靠 `onServiceConnected` 显示浮窗 | 新增 `startOrShowFloat()`：服务 `isReady()` 时直接重建浮窗；已绑定时把新授权直接交给服务，不等二次绑定回调 | 对已存在的绑定，`bindService` 可能不再回调 `onServiceConnected`，导致点了没反应 |
| 截屏异常时 `EasyFloat.show()` 被跳过 | `try/finally` 保证浮窗一定恢复 | 一次异常就让浮窗永久消失 |
| 依赖含 Retrofit | 未引入 | 源码用 OkHttp 直接调用，无 Retrofit 代码；需要时自行添加依赖即可 |
| `@style/Theme.MaterialComponents...NoActionBar` 直接引用 | 自定义 `Theme.SearchHelper` | MaterialComponents 主题需要显式 colorPrimary/colorAccent，否则运行时崩溃 |
| 悬浮窗逻辑写在 Activity 的 lifecycleScope | 抽到 `FloatWindow.kt` + `AppScope` | Activity 被回收后 lifecycleScope 取消，浮窗按钮会失效 |

## 七、可选优化点

- **区域记忆默认化**：若目标答题 App 布局长期固定，可把首页识别区域默认值改成
  `REGION_MODE_LAST`（`Config.kt`），首次框选后基本免操作。
- **只识别题目区域已内置**：用首页「识别区域 → 每次手动框选 / 记住上次框选区域」即可，
  无需改代码。
- **降采样**：高分辨率机型可 `Bitmap.createScaledBitmap(bitmap, w/2, h/2, true)`，ML Kit 对 1080p 以内文本识别率已足够。
- **OCR 精度**：生僻符号/公式可换 PaddleOCR Android，替换 `QuestionSolver.recognizeLines()`
  内部实现即可，上层（题干/选项归类、匹配、AI）不受影响。
- **连续搜题节流**：在 `FloatWindow.search()` 开头加时间戳判断（如 1.5s 内忽略重复点击），避免误触刷屏。
- **干扰行词表可调**：噪声行判定词表在 `QuestionSolver.CHROME_WORDS`，若某个 App 的
  固定 UI 文字（如"安全知识竞赛"标题）反复污染题干，往表里加词即可。

## 八、已知限制与排查

| 现象 | 原因 / 处理 |
| --- | --- |
| 关闭悬浮窗后再点【启动悬浮窗】没反应 | **已修复**（见下）。若仍出现：先看底部状态栏「悬浮窗：显示中/未显示」，再点【停止服务并关闭悬浮窗】→【启动悬浮窗】重新授权 |
| 点【截屏搜题】后悬浮窗消失不回来 | 已修复：截屏异常时用 `try/finally` 保证恢复显示 |
| 点【截屏搜题】提示"截屏服务未运行" | 进程被系统杀死或清理后台，回首页重新点【启动悬浮窗】并再次授权录屏 |
| 提示"截屏失败，未获取到图像缓冲区数据" | MediaProjection 授权失效（Android 14 每次授权仅对当前 session 有效），重启服务 |
| 识别结果为空 | 页面文字过小/背景过花；切到「每次手动框选」把识别区域对准题目，或降低采样后重试 |
| 题库明明有却没命中 | 题干被 OCR 截断或含错别字；先看识别状态区显示的"题目已识别"是否完整，若不完整就换框选模式对准题目；或在题库中补充常见变体 |
| 只差数字/符号的题目互相串题 | 汉字过滤后"第3条/第5条"无法区分；题库数字请写汉字（第三/第五） |
| 英文/公式题永远走 AI | 汉字过滤后为空，属预期行为；本地题库不支持这类题 |
| 选了"记住上次框选区域"却仍全屏识别 | 从未保存过区域（需先切「每次手动框选」确定一次），或点过【清除已存区域】 |
| 框选界面出现但点按钮无反应 | 悬浮窗/后台弹出界面权限被系统收回；回首页重新授权悬浮窗后重试 |
| 界面上找不到【导入题库】 | 未同步到最新代码；该按钮在首页【启动悬浮窗】下方，与【清空导入题库】并排 |
| 点【保存配置】无反应 | 三项中有不合法的：看输入框下方的红色提示与底部状态栏；改对后重试 |
| 【测试连接】报 404 | 接口地址路径不对，或该模型名在当前服务商下不存在；对照上表核对 endpoint |
| 【测试连接】报 401/403 | Key 无效、过期，或该 Key 没有目标模型的权限 |
| 【测试连接】报 429 | 请求过频或额度/余额不足 |
| 换手机/重装后 Key 没了 | 正常：Key 被排除在云备份之外（安全考虑），重新填一次即可 |
| 升级到本版后 Key 输入框空了 | 期望行为：Key 从明文 `search_helper.xml` 迁到了加密的 `search_helper_secure.xml`，旧值不迁移，重填一次即可 |
| 导入后提示"未解析到任何题目" | 文件的题目不在第一列、答案不在第二列；或选错文件。确认 A 列题目、B 列答案后重试 |
| 导入成功但条数偏少 | 追加模式会自动跳过与现有题库重复的题干；可用"覆盖导入"重置 |
| 列预览里看到的列数不对 | CSV 分隔符按**首行**统计判定；若首行是表头且分隔符与数据行不一致，请先把文件另存为逗号分隔 |
| 导入后答案错位（题目变成答案） | 在"确认列对应关系"对话框里核对两个下拉框，选反了改回来即可 |
| xlsx 导入失败 | 只支持 .xlsx（zip 格式）。旧版 .xls 请先用 Excel/WPS 另存为 .xlsx 或 .csv |
| 导入中文乱码 | 已做 UTF-8/GBK 自动判定；仍乱码请在 Excel 选择"另存为 → CSV UTF-8(逗号分隔)" |
| 悬浮窗在部分机型不显示 | 国产 ROM 需在系统设置中手动开启"悬浮窗/后台弹出界面"权限（EasyFloat 会自动跳授权页） |
| 编译报 JDK 版本错 | Gradle 需 JDK 17：`File > Settings > Build Tools > Gradle > Gradle JDK` |

---

## 六、UI 重设计（2026-09，仅视觉层，功能零改动）

首页 / 选列对话框 / 悬浮窗 / 框选操作条已按新设计系统整体换皮，**所有控件 ID、功能入口、操作逻辑与旧版完全一致**。

### 配色与视觉体系

| Token | 值 | 用途 |
|---|---|---|
| 渐变主色 | `#4F46E5 → #7C3AED`（135°） | 头卡、主 CTA「启动悬浮窗」、保存配置、悬浮窗【截屏搜题】 |
| 强调橙 | `#FF6B35`（浅底 `#FFF0EA`） | 清空导入题库、悬浮窗【关闭】 |
| 命中绿 | `#10B981`（浅底 `#E6F7F1`） | colorAccent（CheckBox/Spinner 着色） |
| 面板底 | `#F4F5FA` | 窗口背景、状态栏 |
| 卡片 | 白底 16dp 圆角 + elevation 2dp | 各功能区卡片 |
| 输入框 | `#F7F8FC` 填充 + `#ECEDF4` 描边 12dp 圆角 | EditText、Spinner 容器 |
| 悬浮窗 | `#171826`（96%）玻璃卡 / 内区 `#1F2033` / 按钮 `#262840` | float_search_box |

### 资源结构

- `values/colors.xml`：全部色板 token（含旧名 `primary/accent/danger/float_bg` 兼容保留）
- `values/styles.xml`：`Button.CTA / Primary / Outline / TonalIndigo / TonalDanger / TonalGray`、`Card`、`Text.*`
- `drawable/bg_*.xml`：渐变、卡片、输入框、悬浮窗深色系背景
- 按钮 = `AppCompatButton` + 自绘背景（主题为 MaterialComponents，`<Button>` 会被自动升级为 MaterialButton 而忽略 `android:background`，故全部显式用 AppCompatButton）

### 动效建议（未内置，属可选增强）

- 按钮按压 scale 0.96 + 波纹：可后续用 `MaterialButton` + ripple drawable 替换
- 页面切换共享轴滑动：需 Activity 切换动画 API
- 状态点 / CTA 呼吸光晕：可用 `ObjectAnimator` 循环 alpha

### 已知限制

- 中文字体沿用系统默认（设计稿为 Noto Sans SC）；如需一致，可在 `res/font/` 放入 NotoSansSC 并在 `Text.*` 样式中加 `android:fontFamily`
- 状态栏浅色仅 Android 6.0+ 生效（`windowLightStatusBar`，minSdk 24 无影响）
- 框选画布（RegionCanvasView）为自绘控件，保持原样未动
