# TV直播 - Android TV 电视台直播应用

基于 Media3 ExoPlayer + Leanback 的 Android TV 电视台直播应用，支持央视、卫视、地方台、港澳台、国际频道等。

## 功能特性

### 核心功能
- **多源直播**：内置 7 个公开免费 IPTV 源，支持自定义添加
- **M3U/M3U8 解析**：支持标准 M3U 和简单 TXT 格式播放列表
- **HLS 直播播放**：基于 Media3 ExoPlayer，支持 HLS 自适应码率
- **智能分类**：自动识别央视、卫视、地方、港澳台、国际频道
- **频道搜索**：按频道名称实时搜索

### TV 端交互
- **遥控器切台**：上/下键快速切换频道（循环切换）
- **频道列表**：确认键或左右键呼出频道列表面板
- **数字选台**：数字键直接输入频道号跳转（1.5秒超时自动确认）
- **频道信息**：切换频道显示频道名/号/分组，4秒自动隐藏
- **收藏管理**：长按频道卡片收藏，播放页按收藏键切换
- **播放历史**：自动记录最近 50 条观看历史
- **自动重连**：播放失败自动重试（最多 3 次）

### 界面
- 6 列频道网格，焦点放大动效
- 顶部 Tab 分类切换（全部/央视/卫视/地方/港澳台/国际/收藏/历史）
- 暗色主题，适配大屏电视
- 频道卡片显示台标、名称、分组标签、频道号

## 内置直播源

| 源名称 | 地址 | 说明 |
|--------|------|------|
| iptv-org 中国频道 | `https://iptv-org.github.io/iptv/countries/cn.m3u` | GitHub 最大公开 IPTV 集合，每日自动检测 |
| iptv-org 全球频道 | `https://iptv-org.github.io/iptv/index.m3u` | 全球数千个免费频道 |
| zbds 每日更新源 | `https://live.zbds.top/tv/iptv4.m3u` | 国内直连，每6小时更新 |
| joevess 央视卫视源 | `https://raw.githubusercontent.com/joevess/IPTV/main/home.m3u8` | 央视+卫视+地方台 |
| yuanzl77 国内直播源 | `https://raw.githubusercontent.com/yuanzl77/IPTV/main/live.m3u` | CCTV+各省卫视+地方台 |
| Free-TV 全球免费 | `https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8` | 世界各地免费频道 |
| Collect-IPTV 精选合集 | `https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u` | 自动更新精选合集 |

EPG 节目单：`https://iptv-org.github.io/epg/guides/cn.xml`

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 播放器 | androidx.media3 (ExoPlayer) | 1.2.1 |
| TV UI | androidx.leanback + tv-material | 1.0.0 |
| 数据库 | androidx.room | 2.6.1 |
| 网络 | OkHttp + Retrofit | 4.12 / 2.9 |
| 图片 | Glide | 4.16.0 |
| 异步 | Kotlin Coroutines | 1.7.3 |
| 最低SDK | minSdk | 21 (Android 5.0) |
| 目标SDK | targetSdk | 34 (Android 14) |

## 项目结构

```
TVLive/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/tvlive/app/
│       │   ├── TvLiveApp.kt              # Application 入口
│       │   ├── data/
│       │   │   ├── model/Models.kt       # 数据模型 (Channel, Source, EpgProgram)
│       │   │   ├── DefaultSources.kt     # 内置直播源配置
│       │   │   ├── M3UParser.kt          # M3U/M3U8/TXT 解析器
│       │   │   ├── ChannelRepository.kt  # 数据仓库
│       │   │   └── db/                   # Room 数据库 + DAO
│       │   ├── player/
│       │   │   ├── TvPlayerManager.kt    # ExoPlayer 播放管理
│       │   │   └── EpgParser.kt          # EPG XMLTV 解析
│       │   └── ui/
│       │       ├── MainViewModel.kt      # 主 ViewModel
│       │       ├── main/                 # 主界面 (频道网格)
│       │       ├── player/               # 播放界面
│       │       └── settings/             # 设置 & 源管理
│       └── res/
│           ├── layout/                   # 布局文件
│           ├── drawable/                 # 图标 & 背景
│           ├── values/                   # 颜色/字符串/主题
│           └── xml/                      # 网络安全配置
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/
```

## 构建方法

### 环境要求
- Android Studio Hedgehog (2023.1) 或更高
- JDK 17
- Android SDK 34
- Gradle 8.5

### 命令行构建

```bash
# 生成 Debug APK
./gradlew assembleDebug

# 生成 Release APK
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release-unsigned.apk
```

### Android Studio 构建
1. 用 Android Studio 打开项目根目录
2. 等待 Gradle Sync 完成
3. 点击 Run 或 Build > Build APK

## 遥控器操作指南

| 按键 | 功能 |
|------|------|
| 上键 / 频道+ | 上一个频道 |
| 下键 / 频道- | 下一个频道 |
| 左键 / 右键 | 显示频道列表 |
| 确认键 | 显示/隐藏频道列表（列表中确认=选中播放） |
| 数字键 0-9 | 输入频道号选台（1.5秒超时自动确认） |
| 菜单键 | 显示频道信息 |
| 收藏键 | 收藏/取消收藏当前频道 |
| 返回键 | 依次关闭：数字输入→频道列表→频道信息→退出 |

## 添加自定义直播源

1. 进入 设置 > 直播源管理
2. 点击右上角"+"按钮
3. 输入源名称和 M3U/M3U8/TXT 地址
4. 确认后自动加载并刷新

支持的格式：
- **M3U**：`#EXTM3U` 开头，`#EXTINF` 描述频道信息
- **TXT**：`频道名,URL` 每行一个频道

## 免责声明

本应用仅作为播放器工具，所有直播源均来自互联网公开免费资源。应用的内置源均来自 GitHub 开源项目（iptv-org 等），不包含任何盗版付费内容。频道 availability 取决于源服务器状态，可能随时失效。请确保遵守当地法律法规使用。
