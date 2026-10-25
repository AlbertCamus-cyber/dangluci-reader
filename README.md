# 挡路词阅读器 v0.6.1

读英文文章时只查挡路的词：点一下给最常用释义，不打断阅读，想留的词一键进生词本。

## 本版已跑通
- 导入：粘贴文本 / 选择 txt（也支持在文件管理器里“用…打开”txt）
- 点词查词：点词 → 底部卡片显示音标 + 最常用 1–2 个释义；变形词自动还原（ran → run）
- 生词本（v0.2 起）：按文章分组，组和组内都按加入时间倒序，可删除；每条保留原句，点开可「回到原文继续读」跳回当初位置并高亮
- 复现统计（v0.2）：导入时按词头建本地索引；查词卡片一行小字「这个词你已经在 N 篇文章里见过」；生词详情里列出出现过的文章及次数，点文章跳到首次出现处
- 离线：Manifest 不申请 INTERNET 权限，词库随 APK 打包
- 阅读进度：按段落 + 偏移保存，重开接着读

## 不做
整句翻译、账号、云同步、社区、iOS、游戏化、SRS、URL/RSS 抓取。

## 构建
需要 JDK 17 + Android SDK 34。
```
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构
```
app/src/main/java/com/dangluci/reader/
  ReaderApp.kt / AppContainer.kt   手写依赖容器，换实现只改这里
  MainActivity.kt                  入口，接收外部 txt
  data/dict/Dictionary.kt          词典接口 + DictEntry（扩展口：多词典、例句）
  data/dict/EcdictDictionary.kt    ECDICT 精简库本地 SQLite 查词
  data/local/UserDb.kt             用户库（文章、进度、生词本），按 VERSION 迁移
  data/local/ArticleRepository.kt
  data/local/VocabRepository.kt    生词本，按文章锚定（以后加复习另起表关联 vocab_entries.id）
  data/local/CorpusRepository.kt   复现统计：occurrences(headword, article_id, count, 首次位置)
  domain/Tokenizer.kt              分词、取句子、切段落
  domain/Lemmatizer.kt             查不到时的词形还原兜底
  ui/AppRoot.kt                    极简栈式导航
  ui/library  ui/importer  ui/reader  ui/vocab  ui/theme
tools/build_dict.py                从 ecdict.csv 生成 assets/ecdict_brief.db
```

## 词典与许可证
词库来自 ECDICT（https://github.com/skywind3000/ECDICT），MIT License，许可证原文在
`app/src/main/assets/licenses/ECDICT-LICENSE.txt`。精简规则：只保留常用词或有原形信息的词，
每词最多 2 个词性行、每行 2 个义项，丢弃 [网络] 释义，约 10.4 万词条、7 MB。
重新生成：`python3 tools/build_dict.py ecdict.csv app/src/main/assets/ecdict_brief.db`，
并把 `EcdictDictionary.DB_FILE` 的版本号 +1。

## 数据库版本
- v1: articles, vocab
- v2: articles.indexed；occurrences；vocab → vocab_entries（article_id + paragraph + char_start/end + surface）。旧生词自动迁移，位置记 -1，回跳时按原句定位；旧文章启动时后台补建索引。
- v3: articles.temporary（临时阅读：不进列表，离开阅读页即删，启动时清理残留）；vocab_entries.from_temp（临时阅读加的生词归「临时阅读」组，不能回跳）
- v4: folders（一层文件夹，可新建/重命名/删除，删文件夹时文章退回首页）；articles.folder_id
- v0.5.0: epub 导入（data/importer/Epub.kt，按 spine 抽正文拼成一篇；DRM 书不支持）；正文分段读取避开 CursorWindow 2MB 限制；临时阅读不填标题
- v0.6.0: 备份导出/恢复（data/Backup.kt）；epub 按章节拆成文件夹（articles.position，DB v5）+ 下一章；字号/主题（data/Settings.kt）；词组查询与「更多释义」（词库 v2 加 full 列和 4.8 万词组）；文章/生词搜索；文章改标题

## 开源构建说明
本仓库不包含以下三个二进制文件（推送通道只接受纯文本文件），按下面办法补齐即可构建：
- 词典数据库 `app/src/main/assets/ecdict_brief.db`：从 ECDICT（https://github.com/skywind3000/ECDICT）取 ecdict.csv，运行 `python3 tools/build_dict.py ecdict.csv app/src/main/assets/ecdict_brief.db` 生成；词典许可证见 `app/src/main/assets/licenses/ECDICT-LICENSE.txt`。
- `gradle/wrapper/gradle-wrapper.jar`：用系统 Gradle 直接构建，或从 Gradle 官方下载对应版本的 wrapper jar 放入 `gradle/wrapper/`。
- `keystore/debug.keystore`：构建配置指向 `keystore/debug.keystore`（标准 debug 签名，密码 android）；没有的话用 JDK 的 keytool 生成一个 debug keystore 放到该位置，或把 `~/.android/debug.keystore` 复制过来。
