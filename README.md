# 一贤题匣

Android 学习辅助工具：截图 → 离线中文 OCR → 用户题库检索 → AI 参考。

## 第一版

- 悬浮窗支持全屏、框选、复用上次区域，可停止屏幕采集。
- 内置少量演示题；支持 CSV、XLSX（首个工作表）、JSONL 导入、预览、列映射、追加或替换。
- 保存数字、运算符、英文标识与否定信息；近似匹配只作辅助，冲突答案明确展示。
- 结构化选项按正文映射当前字母，避免选项重排导致答案错位。
- 无 API Key 时仍可离线识别和检索；未命中后可用自配 AI 接口。AI 结果明确标为参考。

OCR 使用随应用分发的 ML Kit 中文模型，不需要另外提供 OCR Key。AI 需要自行提供服务地址、模型名和 Key，服务费用由对应提供方收取。仅识别出的题目文字会发给配置的 AI 服务；本地命中时不请求 AI。

## 题库格式

见 [examples/sample-bank.jsonl](examples/sample-bank.jsonl)。每行一个 JSON：

```json
{"q":"题干","a":["选项甲","选项乙"],"ans":"B","explanation":"解析","source":"自编题库"}
```

`a` 可省略，`ans` 可为答案正文；有选项时纯 A–H 字母串表示选项序号，支持多选如 AC。CSV 首行为标题时可用 `题干,答案,选项1,选项2,解析`；没有选项列的旧题库仍兼容，但不保证答案字母与屏幕选项一致。仅导入你有权使用的内容。

JSONL 解析错误的行会在预览中提示；导入前检查提示。文件最大 16 MiB。多题截图、复杂公式、选项标记识别失败和题干缺失仍可能影响匹配，请框选单题并核对原文。当前未实现语义向量检索、题库编辑、历史收藏、多题分割或图像大模型兜底。

## 构建与发布

JDK 17、Gradle 8.2、Android SDK 34。运行 `gradle testDebugUnitTest lintDebug`，构建使用 `gradle assembleRelease`。

GitHub Actions → Android release → Run workflow，输入新的 `vX.Y.Z`。需配置 `ANDROID_KEYSTORE_BASE64` 和 `ANDROID_STORE_PASSWORD` 两个仓库 Secret，别名 `yixian`。CI 通过单元测试和 lint 后签名打包，上传 APK 和 SHA256SUMS.txt 到对应 Release，再清理构建目录与临时签名文件；不上传 Actions artifact，不启用 Gradle 缓存。现有 Release 不覆盖。

签名密钥不能提交仓库，必须妥善备份以便后续更新安装。应用包名 `com.yixian.questionbox`，与参考应用并存。

## 来源

基于 [zwk104/-SearchHelper](https://github.com/zwk104/-SearchHelper) 的 MIT 开源代码（起始提交 c1ad6b9），保留原许可证和提交历史。产品机制参考仅用于独立实现，未复制大学搜题酱、小包搜题的代码、模型、私有 API 或题库。
