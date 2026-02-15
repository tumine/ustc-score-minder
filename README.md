# USTC Score Minder

**USTC Score Minder** 是一款 Android 应用，用于定时查询 [中国科学技术大学 (USTC)](https://www.ustc.edu.cn/) 教务系统的成绩信息。当检测到新成绩发布时，应用将自动发送系统通知提醒用户。

## ✨ 功能特性

- **🔐 安全登录** — 通过内嵌 WebView 完成 USTC CAS 统一身份认证登录，兼容复杂的 SPA 登录页面
- **🤖 智能重登** — 支持后台静默重登，当会话过期时自动使用加密凭证尝试重新登录，无需用户干预
- **🔒 加密存储** — 使用 Android `EncryptedSharedPreferences` (AES-256) 本地加密存储用户凭证
- **📊 成绩查看** — 按学期分组展示所有课程成绩，包含**学分**、**绩点**、**成绩**等详细信息
- **🔍 学期筛选** — 支持按学期筛选成绩，快速查看指定学期的课程信息
- **🔄 后台同步** — 基于 WorkManager 的定时后台任务，自动检查新成绩（默认间隔 30 分钟）
- **🔔 新成绩通知** — 检测到新成绩时，发送系统通知提醒用户
- **⬇️ 下拉刷新** — 支持下拉手势手动刷新成绩列表
- **⚙️ 设置管理** — 可自定义同步间隔（支持 15 分钟至 4 小时多种间隔，调试模式下支持更短间隔）、开关通知等

## 🏗️ 技术架构

### 技术栈

| 类别 | 技术方案 |
|------|----------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 依赖注入 | Hilt (Dagger) |
| 本地数据库 | Room |
| 后台任务 | WorkManager |
| 网络请求 | OkHttp |
| HTML 解析 | Jsoup |
| 凭证加密 | AndroidX Security Crypto |
| 构建工具 | Gradle (Kotlin DSL) |

### 项目结构

```
app/src/main/java/com/ustc/scoreminder/
├── MainActivity.kt                  # 主 Activity，入口
├── ScoreMinderApp.kt                # Application 类 (Hilt 入口)
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room 数据库定义
│   │   ├── CredentialsManager.kt    # 加密凭证管理
│   │   ├── dao/
│   │   │   └── GradeDao.kt          # 成绩数据访问对象
│   │   └── entity/
│   │       └── GradeEntity.kt       # 成绩数据库实体
│   ├── remote/
│   │   ├── BackgroundWebViewAuthenticator.kt # 后台 WebView 认证
│   │   ├── CryptoUtils.kt           # 加密工具
│   │   ├── GradeParser.kt           # 成绩页面 HTML 解析器
│   │   ├── JwAuthenticator.kt       # 教务系统认证（旧版）
│   │   ├── LoginScriptUtils.kt      # 登录脚本工具
│   │   ├── NetworkModule.kt         # 网络模块 (Hilt)
│   │   └── WebViewGradeFetcher.kt   # WebView 成绩抓取
│   └── repository/
│       └── GradeRepository.kt       # 成绩数据仓库
├── di/
│   ├── DatabaseModule.kt            # 数据库依赖注入模块
│   └── WorkManagerModule.kt         # WorkManager 依赖注入
├── domain/
│   ├── model/
│   │   └── Grade.kt                 # 成绩领域模型
│   └── usecase/
│       ├── LoginUseCase.kt          # 登录用例
│       └── SyncGradesUseCase.kt     # 成绩同步用例
├── ui/
│   ├── grades/
│   │   ├── GradeListScreen.kt       # 成绩列表页面
│   │   └── GradeListViewModel.kt    # 成绩列表 ViewModel
│   ├── login/
│   │   ├── LoginScreen.kt           # 登录页面
│   │   ├── LoginViewModel.kt        # 登录 ViewModel
│   │   └── WebViewLoginScreen.kt    # WebView 登录页面
│   ├── navigation/
│   │   └── NavGraph.kt              # 导航图
│   ├── settings/
│   │   ├── SettingsScreen.kt        # 设置页面
│   │   └── SettingsViewModel.kt     # 设置 ViewModel
│   └── theme/
│       └── Theme.kt                 # Material 3 主题
└── worker/
    ├── GradeSyncWorker.kt           # 后台成绩同步 Worker
    └── NotificationHelper.kt        # 通知辅助类
```

### 架构分层

应用采用 **Clean Architecture** 分层架构：

```
┌─────────────────────────────────┐
│           UI Layer              │  Compose Screens + ViewModels
├─────────────────────────────────┤
│         Domain Layer            │  Use Cases + Models
├─────────────────────────────────┤
│          Data Layer             │  Repository + Room + Network
└─────────────────────────────────┘
```

## 🔧 环境要求

- **Android Studio** Hedgehog (2023.1) 或更高版本
- **JDK** 17
- **Android SDK** — compileSdk 34, minSdk 26
- **Gradle** — Kotlin DSL

## 🚀 构建与运行

1. 克隆仓库：
   ```bash
   git clone https://github.com/your-username/ustc-score-minder.git
   cd ustc-score-minder
   ```

2. 使用 Android Studio 打开项目

3. 等待 Gradle 同步完成

4. 连接 Android 设备或启动模拟器，点击 **Run** 运行应用

或使用命令行构建：
```bash
./gradlew assembleDebug
```

## 📱 使用说明

1. **登录** — 首次启动时，应用将显示 USTC CAS 登录页面，输入统一身份认证账号密码完成登录
2. **查看成绩** — 登录成功后自动跳转至成绩列表页面，展示所有已出成绩
3. **刷新成绩** — 下拉刷新或等待后台自动同步
4. **设置** — 点击右上角设置图标，可自定义同步间隔、开关通知提醒
5. **退出登录** — 在设置页面点击「退出登录」清除本地凭证

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。
