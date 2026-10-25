# 挡路词阅读器 (Dangluci Reader) v0.6.1

> **读英文文章时只查真正挡路的词。**  
> 点击即出最常用 1–2 个核心释义，绝不打断阅读心流；一键收录生词，智能统计词汇复现。

[![GitHub release](https://img.shields.io/badge/release-v0.6.1-blue.svg)](https://github.com/AlbertCamus-cyber/dangluci-reader/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)](https://developer.android.com)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline-orange.svg)](#隐私与权限)

---

## 📱 核心特性

- **轻量即时查词**：正文单击单词即在底部弹卡展示音标与最简释义，双击/长按支持查词组；内置词形还原算法（如 `ran` 自动识别为 `run`）。
- **完全离线运行**：**Manifest 零网络权限（`INTERNET` 权限未申请）**，杜绝任何数据外泄；内置精简版 14MB ECDICT 词典库。
- **生词本上下文溯源**：生词按文章归档并保留完整原句上下文；在生词详情中一键「回到原文」，瞬间跳转回当初阅读位置并高亮单词。
- **真实语料复现统计**：导入文章时全自动建立本地词频倒排索引；查词时贴心提示*「这个词你已经在 N 篇文章里见过」*，掌握自己的真实词汇盲区。
- **深度格式支持**：
  - 支持直接粘贴纯文本、打开 `.txt` 文本；
  - 支持 `.epub` 电子书导入（智能按章节自动拆分为阅读文件夹，支持无缝“下一章”）。
- **数据安全与自定义**：支持本地完整数据备份与恢复、深浅色主题切换、正文字号自适应调节。

---

## 📥 下载安装

可以直接在 [**Releases 页面**](https://github.com/AlbertCamus-cyber/dangluci-reader/releases) 下载最新编译好的安装包：

- 📦 **[下载 dangluci-reader-0.6.1-debug.apk](https://github.com/AlbertCamus-cyber/dangluci-reader/releases/download/v0.6.1/dangluci-reader-0.6.1-debug.apk)** (约 22 MB)

> *系统要求：Android 8.0 (API 26) 及以上系统。*

---

## 🚫 产品设计克制原则（不做事项）

为了保证极致纯粹的沉浸式英语阅读体验，本项目坚决不做以下功能：
- ❌ 不做整句/全文机器翻译（避免产生阅读惰性）
- ❌ 不做账号体系与云端同步（保护个人隐私，随时即开即用）
- ❌ 不做社区社交、排行榜与打卡游戏化机制
- ❌ 不申请联网权限，不抓取未经清洗的网络 RSS/URL

---

## 🛠️ 项目结构与本地构建

本项目采用手写轻量依赖容器，依赖清晰纯粹：

```text
app/src/main/java/com/dangluci/reader/
  ├── ReaderApp.kt / AppContainer.kt   // 手写单例依赖容器，解耦各层实现
  ├── MainActivity.kt                  // 入口 Activity，支持接收外部文件打开意图
  ├── data/
  │   ├── dict/                        // 词典引擎：EcdictDictionary 本地 SQLite 查词
  │   ├── local/                       // Room/SQLite 用户库：文章、进度、生词与复现统计
  │   └── importer/                    // EPUB 解析与文本清洗器
  ├── domain/                          // Tokenizer 分词切句、Lemmatizer 词形还原
  └── ui/                              // Jetpack Compose 现代化界面与沉浸式阅读器
```

### 编译源码

源码已包含完整的本地精简词典（`ecdict_brief.db`）与签名配置，拉取后即可一键构建：

```bash
git clone https://github.com/AlbertCamus-cyber/dangluci-reader.git
cd dangluci-reader

# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# 构建产物位于: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📖 词典来源与开源许可

- **本地词库**：基于 [ECDICT](https://github.com/skywind3000/ECDICT)（简明英汉字典增强版）精简构建，采用 MIT License，许可证文本见 `app/src/main/assets/licenses/ECDICT-LICENSE.txt`。
- **开源协议**：本项目基于 [MIT License](LICENSE) 开源。
