# 拾页笔记（GleanNote）Android 客户端开发环境基线

## 0. 文档信息

| 项目 | 内容 |
|---|---|
| 文档名称 | 拾页笔记 Android 客户端开发环境基线 |
| 文档编号 | GN-CLIENT-007 |
| 版本 | v0.1 |
| 状态 | 已实测基线（本机） |
| 更新日期 | 2026-11-11 |
| 实测日期 | 2026-11-11 |
| 适用范围 | Android 客户端的工程搭建、构建、模拟器联调与环境排障；不含业务实现 |
| 上级约束 | [CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md)（尤其 4.3、9.3、附录 B）、[CONVENTIONS.md](../../Server/docs/CONVENTIONS.md) 第 6 章 |
| 参考工程 | `F:\资料\常州大学\课程\移动应用开发\MusicPlayer`（本机已成功构建的 Android 课程项目） |
| 目标读者 | Android 客户端研发、课程答辩环境复现者、联调与运维人员 |

### 0.1 结论摘要

本机 Android 工具链**已具备且实测可运行**：JDK 21 + Gradle 8.7 + AGP 8.4.2 + Android SDK Platform 34 + API 34 模拟器，参考工程 `MusicPlayer` 已用这套组合在本机成功构建（存在 `.gradle/8.7` 构建缓存与 wrapper 产物）。

但有四点必须在工程配置中显式处理，否则新项目无法直接开工：

1. 系统级 `JAVA_HOME` 指向**不存在的** `D:\DevelopmentTools\Java\jdk-25.0.2`，必须用项目级 `org.gradle.java.home` 覆盖到真实的 `D:/DevelopmentTools/Java`。
2. `ANDROID_HOME` / `ANDROID_SDK_ROOT` 未生效，SDK 位置**必须**由 `local.properties` 的 `sdk.dir` 提供。
3. 本机**只安装了 SDK Platform 34 与 Build Tools 34.0.0**，故 `compileSdk` / `targetSdk` 上限为 34；升级到 35+ 需先装新平台。
4. 参考工程是纯 Java + XML 项目，`android.useAndroidX=false` 且无 Kotlin/Compose；GleanNote 用 Kotlin + Jetpack Compose，**必须改为 `true` 并补齐 Kotlin 与 Compose 插件**，否则无法编译。

### 0.2 修订记录

| 版本 | 日期 | 修订人 | 说明 |
|---|---|---|---|
| v0.1 | 2026-11-11 | 客户端架构组 | 首版：实测本机工具链、环境变量与模拟器；复刻参考工程配置；给出 GleanNote 构建基线与排障口径 |

---

## 1. 已验证的工具链基线

以下每一项均在本机执行过验证命令，不是推断值。

| 组件 | 实测版本 | 本机路径 | 验证方式 |
|---|---|---|---|
| JDK（Oracle） | **21.0.10+8-LTS**（`JAVA_RUNTIME_VERSION=21.0.10+8-LTS-217`） | `D:\DevelopmentTools\Java` | `release` 文件、`java.exe -version` |
| Gradle | **8.7**（Build 2024-03-22，内置 Kotlin 1.9.22 / Groovy 3.0.17） | `D:\DevelopmentTools\Gradle\gradle-8.7` | `gradle.bat --version`，输出 `JVM: 21.0.10` |
| Gradle 发行包（离线备用） | `gradle-8.7-bin.zip`（约 134 MB） | `D:\DevelopmentTools\Gradle\gradle-8.7-bin.zip` | 目录列举 |
| Android Gradle Plugin | **8.4.2** | `C:\Users\Rui\.gradle\caches\modules-2\files-2.1\com.android.tools.build\gradle\8.4.2` | 缓存目录已存在 |
| Android SDK 根 | — | `D:\DevelopmentTools\Android\Sdk` | 参考工程 `local.properties` |
| SDK Platform | **android-34**（rev 3） | `Sdk\platforms\android-34` | `sdkmanager --list_installed` |
| Build Tools | **34.0.0** | `Sdk\build-tools\34.0.0` | 同上 |
| Platform Tools / adb | **37.0.0** / adb 1.0.41 | `Sdk\platform-tools` | `adb.exe version` |
| Command-line Tools | `latest` | `Sdk\cmdline-tools\latest` | `sdkmanager.bat --list_installed` |
| Emulator | **37.1.11** | `Sdk\emulator` | `emulator.exe -version` |
| 系统镜像 | `android-34;google_apis;x86_64`（rev 14） | `Sdk\system-images\android-34\google_apis\x86_64` | `sdkmanager --list_installed` |
| SDK Licenses | `android-sdk-license` 已接受 | `Sdk\licenses` | 目录列举 |
| Debug Keystore | 已存在 | `D:\DevelopmentTools\.android\debug.keystore` | 目录列举 |
| AVD | `hw2avd`（Pixel 5，API 34，x86_64，RAM 1536M，google_apis） | `C:\Users\Rui\.android\avd` | `hw2avd.ini` + `config.ini` |
| Android Studio | **未检测到** | — | 常见安装目录检查与 `studio64.exe` 搜索均无结果 |

**未缓存的关键依赖**：`C:\Users\Rui\.gradle\caches\modules-2\files-2.1` 中**没有** Kotlin Gradle Plugin、Compose Compiler 与任何 Compose 库。即本机只跑过 Java/XML 项目，GleanNote 首次构建**必须联网**下载 Kotlin 与 Compose 依赖（见第 9 章）。

### 1.1 版本组合判定

| 组合 | 要求 / 依据 | 结论 |
|---|---|---|
| AGP 8.4.2 ↔ Gradle 8.7 | AGP 8.4 需 Gradle 8.6+；参考工程已用该组合构建 | 可用，无需改动 |
| AGP 8.4.2 ↔ JDK 21 | AGP 8.4 以 JDK 17 为基线；Gradle 8.7 支持 JDK 21；本机实测 Gradle 运行于 JDK 21 | 可用，字节码目标统一设为 Java 17 |
| Kotlin 1.9.24 ↔ Compose Compiler 1.5.14 | 官方兼容表明确 `1.5.14` 对应 Kotlin `1.9.24`；Kotlin 2.0+ 改用 Compose Compiler Gradle 插件 | **采用此配对**，与 AGP 8.4 同期 |
| `compileSdk` / `targetSdk` 34 | 本机仅安装 Platform 34 | 固定 34；上架或升级再调整（第 12 章） |
| `minSdk` 26 | [CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md) 4.3 建议值；CameraX / Photo Picker / WorkManager / `ImageDecoder` 均需要较新 API | **不沿用参考工程的 21** |

Kotlin/Compose 版本配对依据：Android Developers《Compose 对应 Kotlin 的相容性清单》与 Compose Compiler 发布说明（`1.5.14` targeting Kotlin `1.9.24`）。

---

## 2. 环境变量现状与处置

| 变量 | 作用域 | 当前情况 | 问题 | 处置 |
|---|---|---|---|---|
| `JAVA_HOME` | Machine | `D:\DevelopmentTools\Java\jdk-25.0.2` | **该路径不存在**（实测 `Test-Path = False`），任何依赖 `JAVA_HOME` 的工具都会失败 | 项目级 `gradle.properties` 写死 `org.gradle.java.home=D:/DevelopmentTools/Java`；建议把系统 `JAVA_HOME` 一并修正为该路径 |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | 未生效 | 未设置或为无效占位值 | 命令行工具无法自动定位 SDK | 由 `local.properties` 的 `sdk.dir` 提供；执行 `sdkmanager` / `emulator` 前临时设置（第 10 章） |
| `ANDROID_AVD_HOME` | 未设置 | 默认走 `C:\Users\Rui\.android` | 无 | 该目录是 **NTFS 符号链接**，指向 `D:\DevelopmentTools\.android`，AVD 实际存放在 D 盘，无需额外配置 |
| `GRADLE_USER_HOME` | 未设置 | 默认 `C:\Users\Rui\.gradle` | 无 | AGP 8.4.2 缓存已在此；不要随意改写，否则会重新下载全部依赖 |
| 用户级 `PATH` | User | 无 `java` / `gradle` / `platform-tools` 条目 | 终端直接敲 `adb`、`gradle` 不可用 | 使用绝对路径，或用工程自带的 `gradlew` |

**必须**通过项目级配置而非系统环境变量保证可复现：换机器或重装系统后，只需修改 `local.properties` 与 `gradle.properties` 两处路径即可恢复构建。

---

## 3. 模拟器与服务端联调

| 项 | 值 |
|---|---|
| AVD 名称 | `hw2avd` |
| 设备 / 镜像 | Pixel 5 / `android-34;google_apis;x86_64` |
| RAM | 1536M（`config.ini` 的 `hw.ramSize`） |
| 启动 | `D:\DevelopmentTools\Android\Sdk\emulator\emulator.exe -avd hw2avd` |
| 宿主服务端地址 | 模拟器内用 `http://10.0.2.2:8080`；真机用宿主机局域网 IP |
| 服务端启动 | 见 [Server/README.md](../../Server/README.md)：`docker compose up -d --build`，默认 `http://127.0.0.1:8080` |
| 调试包联网 | `debug` 需 `network_security_config` 允许明文到 `10.0.2.2`；`release` 仅 HTTPS（API.md 2.1、[CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md) 4.3） |

内存提示：AVD 仅 1536M，联调 20 张图片压缩与长文预览时可能触发内存压力，建议在 AVD 配置中提高到 2048M 或使用真机验证图片链路（[CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md) R-C01 / R-C02）。

---

## 4. 参考工程 MusicPlayer 配置实录

路径：`F:\资料\常州大学\课程\移动应用开发\MusicPlayer`。以下为其**实际值**，作为本机可复现基线的证据。

| 文件 | 关键内容 |
|---|---|
| `build.gradle`（根） | `com.android.application` version `8.4.2` `apply false` |
| `settings.gradle` | `pluginManagement` 与 `dependencyResolutionManagement` 均配置阿里云镜像（google / public / gradle-plugin）+ `google()` / `mavenCentral()` / `gradlePluginPortal()`；`RepositoriesMode.FAIL_ON_PROJECT_REPOS`；`include ':app'` |
| `gradle.properties` | `org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8`；`android.useAndroidX=false`；`android.overridePathCheck=true`；`org.gradle.java.home=D:/DevelopmentTools/Java` |
| `local.properties` | `sdk.dir=D\:\\DevelopmentTools\\Android\\Sdk` |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip`；`networkTimeout=10000`；`validateDistributionUrl=true` |
| `app/build.gradle` | `compileSdk 34`、`minSdk 21`、`targetSdk 34`、Java 17 兼容、无依赖 |
| 源码 | `app/src/main/res/layout/activity_main.xml` 等，纯 Java + XML，无 Kotlin、无 Compose |

阿里云镜像（**必须保留**，国内网络环境下 Google Maven 直连通常不可用）：

- `https://maven.aliyun.com/repository/google`
- `https://maven.aliyun.com/repository/public`
- `https://maven.aliyun.com/repository/gradle-plugin`

---

## 5. GleanNote 必须调整的差异

| 配置项 | MusicPlayer | GleanNote | 理由 |
|---|---|---|---|
| `android.useAndroidX` | `false` | **`true`** | Compose、Room、WorkManager、Navigation 全部是 AndroidX；`false` 直接导致依赖解析失败 |
| Kotlin 插件 | 无 | `org.jetbrains.kotlin.android` 1.9.24 | 既定技术背景 Kotlin（PRD 0 节） |
| Compose | 无 | `buildFeatures { compose true }` + `composeOptions { kotlinCompilerExtensionVersion '1.5.14' }` | 既定技术背景 Jetpack Compose（PRD 0 节） |
| `kotlinOptions.jvmTarget` | 无 | `'17'` | 与 `sourceCompatibility` / `targetCompatibility` 17 对齐 |
| `minSdk` | 21 | **26**（[建议]） | CameraX / Photo Picker / WorkManager / `ImageDecoder`；见主文档 4.3 |
| `compileSdk` / `targetSdk` | 34 / 34 | **34 / 34** | 本机仅装 Platform 34，保持一致 |
| `org.gradle.jvmargs` | `-Xmx1536m` | **`-Xmx2048m`**（[建议]） | 多模块 + Compose + Kotlin 编译，1.5G 偏紧 |
| `org.gradle.java.home` | `D:/DevelopmentTools/Java` | **保留不变** | 覆盖失效的系统 `JAVA_HOME` |
| `sdk.dir` | `D:\DevelopmentTools\Android\Sdk` | **保留不变** | 本机 SDK 唯一位置 |
| 仓库镜像 | 阿里云三件套 | **保留不变** | 国内依赖解析 |
| `local.properties` | 已提交（教学工程） | **必须加入 `.gitignore`** | 含本机绝对路径，换机器即失效 |
| `applicationId` / `namespace` | `com.example.musicplayer` | `com.example.gleannote`（[建议]，建工程时确认） | 与参考工程隔离 |
| Gradle DSL | Groovy（`build.gradle`） | **沿用 Groovy**（[建议]） | 与课程参考工程一致，降低迁移成本；Version Catalog 作为 M0 可选增强 |

> 说明：主文档 4.3 把 Version Catalog 列为 `[建议]`。本机基线采用与参考工程一致的 Groovy DSL + 直接版本声明；若 M0 决定引入 `libs.versions.toml`，须同步更新主文档 4.3 与本文第 7 章，不得两处口径冲突。

---

## 6. 建议依赖基线

以下为首次构建需解析的坐标，全部标为 `[建议]`；本机缓存中**尚不存在**，首次构建需联网（第 9 章）。

| 类别 | 坐标 | 建议版本 | 对应主文档 |
|---|---|---|---|
| Compose BOM | `androidx.compose:compose-bom` | 2024.06.00 | 4.1 |
| Compose UI | `androidx.compose.ui:ui` / `ui-graphics` / `ui-tooling-preview` / `androidx.compose.material3:material3` | 由 BOM 管理 | 4.1 |
| Activity | `androidx.activity:activity-compose` | 1.9.0 | 4.1 |
| Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` / `lifecycle-viewmodel-compose` | 2.8.2 | 3.5 |
| Navigation | `androidx.navigation:navigation-compose` | 2.7.7 | 4.1 |
| Room | `androidx.room:room-runtime` / `room-ktx` | 2.6.1 | 5.2 |
| DataStore | `androidx.datastore:datastore-preferences` | 1.1.1 | 4.1 |
| WorkManager | `androidx.work:work-runtime-ktx` | 2.9.0 | 6.12 |
| 网络 | `com.squareup.retrofit2:retrofit` / `com.squareup.okhttp3:okhttp` / `org.jetbrains.kotlinx:kotlinx-serialization-json` | 2.11.0 / 4.12.0 / 1.6.3 | 4.1 |
| 图片 | `io.coil-kt:coil-compose` | 2.6.0 | 4.1 |
| 相机 | `androidx.camera:camera-camera2` / `camera-lifecycle` / `camera-view` | 1.3.3 | 4.1、6.2 |
| Markdown 解析 | `org.commonmark:commonmark-java` | 0.22.0 | 4.2 |
| 测试 | `junit:junit` / `androidx.compose.ui:ui-test-junit4` / `org.jetbrains.kotlinx:kotlinx-coroutines-test` / `com.squareup.okhttp3:mockwebserver` | 4.13.2 / BOM / 1.8.1 / 4.12.0 | 9.1 |

Hilt 需要 KSP 插件，且 KSP 版本必须与 Kotlin 1.9.24 严格对齐（如 `1.9.24-1.0.20`）；为降低首次构建风险，**M0 可不引入 Hilt**，先用手写装配或 Koin，待工具链跑通后再评估（主文档 4.1 允许替换）。

---

## 7. 构建配置基线（可复制）

完整文件已落在项目目录 [`Client/build-config/`](../build-config/README.md)，下表给出关键片段与约定；M0 建立工程时直接复制该目录内容到 Android 工程根。

### 7.1 `settings.gradle`（仓库与模块）

```groovy
pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        google()
        mavenCentral()
    }
}
rootProject.name = "GleanNote"
include ':app'
```

### 7.2 `build.gradle`（根）

```groovy
plugins {
    id 'com.android.application' version '8.4.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.24' apply false
}
```

### 7.3 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.java.home=D:/DevelopmentTools/Java
android.useAndroidX=true
android.nonTransitiveRClass=true
android.overridePathCheck=true
kotlin.code.style=official
```

`org.gradle.java.home` 使用正斜杠，避免 Groovy/Properties 把反斜杠当转义符。

### 7.4 `local.properties`（不入库）

```properties
sdk.dir=D\:\\DevelopmentTools\\Android\\Sdk
```

**必须**加入 `.gitignore`；换机器时只改这一行。

### 7.5 `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

离线备用：本机已有 `gradle-8.7-bin.zip`，可把该文件复制到 `gradle/wrapper/` 并把 `distributionUrl` 改为 `gradle-8.7-bin.zip` 的相对文件 URL，或直接用本地解压版执行 `D:\DevelopmentTools\Gradle\gradle-8.7\bin\gradle.bat`。

### 7.6 `app/build.gradle`（Kotlin + Compose 最小可用）

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.gleannote'
    compileSdk 34

    defaultConfig {
        applicationId 'com.example.gleannote'
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName '0.1.0'
        testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { compose true }
    composeOptions { kotlinCompilerExtensionVersion '1.5.14' }
}

dependencies {
    implementation platform('androidx.compose:compose-bom:2024.06.00')
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.activity:activity-compose:1.9.0'
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.2'
    debugImplementation 'androidx.compose.ui:ui-tooling'
    testImplementation 'junit:junit:4.13.2'
}
```

其它依赖按第 6 章逐个加入，并同步主文档 4.1 选型表。`namespace` / `applicationId` 为 `[建议]`，正式建工程时确认。

### 7.7 `.gitignore`（工程根）

```gitignore
*.iml
.gradle/
.idea/
local.properties
build/
app/build/
captures/
.externalNativeBuild
.cxx/
.DS_Store
```

---

## 8. 首次构建与验证顺序

按顺序执行，任一步失败先查第 11 章：

```bash
# 1) 用真实 JDK 覆盖失效的 JAVA_HOME，并验证 Gradle 可运行
JAVA_HOME="D:/DevelopmentTools/Java" \
  "D:/DevelopmentTools/Gradle/gradle-8.7/bin/gradle.bat" --version

# 2) 进入客户端工程根目录，验证 SDK 组件（需显式指定 ANDROID_HOME）
ANDROID_HOME="D:/DevelopmentTools/Android/Sdk" \
  "D:/DevelopmentTools/Android/Sdk/cmdline-tools/latest/bin/sdkmanager.bat" --list_installed

# 3) 首次同步依赖（Gradle 会联网下载 KGP / Compose，见第 9 章）
./gradlew tasks --no-daemon

# 4) 构建 debug 包
./gradlew assembleDebug

# 5) 启动模拟器并安装
"D:/DevelopmentTools/Android/Sdk/emulator/emulator.exe" -avd hw2avd
"D:/DevelopmentTools/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

第 1 步已在本机实测通过，输出 `Gradle 8.7` 与 `JVM: 21.0.10`；第 2 步已实测输出第 1 章列出的 6 个已安装组件。第 3–5 步为 GleanNote 工程建立后的验证动作。

---

## 9. 首次构建的网络要求

| 事实 | 影响 | 处置 |
|---|---|---|
| Gradle 8.7 与 AGP 8.4.2 已在本机缓存 / 本地解压 | wrapper 无需重新下载 Gradle | 用本地 `gradle-8.7-bin.zip` 作为离线兜底 |
| Kotlin Gradle Plugin 1.9.24、Compose Compiler 1.5.14、Compose BOM 与全部 AndroidX 依赖**未缓存** | 首次 `gradlew tasks` 会联网解析，耗时较长 | 保留阿里云镜像；首次构建在稳定网络下完成，之后可加 `--offline` 验证缓存完整性 |
| Google Maven / Maven Central 直连不稳定 | 可能出现 `Could not resolve ...` | 确认 `settings.gradle` 中 `FAIL_ON_PROJECT_REPOS` 生效且镜像位于仓库列表首位 |
| `validateDistributionUrl=true` | 离线改用本地 zip 时可能被校验拦截 | 使用官方 URL 或改为本地文件 URL 后复核 wrapper 行为 |

**不得**把 `~/.gradle` 缓存目录写死进项目配置，`GRADLE_USER_HOME` 保持默认即可（第 2 章）。

---

## 10. 常用命令速查

| 用途 | 命令 |
|---|---|
| 查看 Gradle / JVM | `JAVA_HOME="D:/DevelopmentTools/Java" "D:/DevelopmentTools/Gradle/gradle-8.7/bin/gradle.bat" --version` |
| 已安装 SDK 组件 | `ANDROID_HOME="D:/DevelopmentTools/Android/Sdk" ".../cmdline-tools/latest/bin/sdkmanager.bat" --list_installed` |
| 安装新平台 | `sdkmanager.bat "platforms;android-35" "build-tools;35.0.0"`（需联网） |
| 已连接设备 | `D:/DevelopmentTools/Android/Sdk/platform-tools/adb.exe devices` |
| 启动模拟器 | `D:/DevelopmentTools/Android/Sdk/emulator/emulator.exe -avd hw2avd` |
| 模拟器内访问宿主服务 | `http://10.0.2.2:8080`（`/healthz`、`/readyz` 仅回环开放，见 API.md 5.12） |
| 清理重建 | `./gradlew clean assembleDebug --no-daemon` |
| 依赖树 | `./gradlew :app:dependencies` |

---

## 11. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `JAVA_HOME is set to an invalid directory` / `Could not determine java version` | 系统 `JAVA_HOME` 指向不存在的 `jdk-25.0.2` | 确认 `gradle.properties` 中 `org.gradle.java.home=D:/DevelopmentTools/Java`；或修正系统变量 |
| `SDK location not found` | 无生效的 `ANDROID_HOME`，且 `local.properties` 缺失 | 复制 `local.properties.example` 为 `local.properties`，确认 `sdk.dir` 路径 |
| `Could not find com.android.tools.build:gradle:8.4.2` | 插件仓库未生效或镜像顺序错误 | 检查 `pluginManagement.repositories` 阿里云三项是否齐全 |
| `Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24` | 本机无缓存且未联网 | 联网首次同步；确认 `maven.aliyun.com/repository/public` 可达 |
| `Compose compiler requires Kotlin 1.9.24` / 版本不匹配 | `composeOptions` 与 KGP 版本不配对 | 固定 Kotlin 1.9.24 + Compose Compiler 1.5.14；升级 Kotlin 2.x 须改用 Compose Compiler Gradle 插件并同步 AGP |
| `android.useAndroidX` 相关冲突 / `This project uses AndroidX dependencies` | 沿用了参考工程的 `false` | 改为 `true`，且**不要**开启 `enableJetifier` |
| `minSdk 21` 与 CameraX / WorkManager 不兼容 | 沿用参考工程配置 | 改为 26（主文档 4.3） |
| AVD 找不到或路径异常 | `C:\Users\Rui\.android` 是符号链接 | 不要删除该链接；如需迁移 AVD，同步更新链接目标与 `ANDROID_AVD_HOME` |
| 模拟器内服务端请求失败 | 用了 `127.0.0.1`（指向模拟器自身）或 debug 未放开明文 | 改用 `10.0.2.2:8080`，并配置 `network_security_config`（主文档 4.3 / 9.3） |
| 构建 OOM 或明显缓慢 | 参考工程 `-Xmx1536m` 偏小 | 提升到 `-Xmx2048m`；关闭并行构建验证瓶颈 |

---

## 12. 变更与升级约束

1. `compileSdk` / `targetSdk` 提升前**必须**先用 `sdkmanager` 安装对应 Platform 与 Build Tools，并同步更新本文第 1 章与 [CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md) 附录 B 的速查值。
2. AGP、Gradle、JDK、Kotlin、Compose Compiler 是联动关系：**不得单独升级其中一项**。升级顺序为 JDK → Gradle → AGP → Kotlin/Compose，每一步都要跑第 8 章的验证序列。
3. 若后续接入 Android Studio，需在 IDE 中把 `JDK` 指向 `D:\DevelopmentTools\Java`、`SDK` 指向 `D:\DevelopmentTools\Android\Sdk`；IDE 生成的 `.idea/` 与 `local.properties` 不得入库。
4. 本机路径属环境事实，变更时只改 `Client/build-config/` 模板与本文；`Client/docs/CLIENT-ARCHITECTURE.md` 只保留引用，不重复维护具体版本号。
5. 2026 年应用的商店上架通常要求更高的 `targetSdk`；本项目为课程项目，以本机 API 34 为准。如需上架，按第 1 条整体升级后再评估（[CLIENT-ARCHITECTURE.md](CLIENT-ARCHITECTURE.md) R-C13）。
