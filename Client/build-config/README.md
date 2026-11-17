# GleanNote Android 构建配置基线（模板）

本目录是 **M0 建立 Android 工程时的配置模板**，不是可独立构建的工程。完整依据、实测数据与排障见 [`../docs/ENVIRONMENT.md`](../docs/ENVIRONMENT.md)。

## 来源

- 版本组合复刻自本机已成功构建的 `F:\资料\常州大学\课程\移动应用开发\MusicPlayer`（JDK 21 / Gradle 8.7 / AGP 8.4.2 / SDK 34）。
- `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle-wrapper.properties` 为该工程原文件，**已在本机验证可用**。
- 其余文件按 GleanNote 的 Kotlin + Jetpack Compose 需求调整，关键差异见 ENVIRONMENT.md 第 5 章。

## 文件清单

| 模板文件 | 复制到工程根后的目标 | 说明 |
|---|---|---|
| `build.gradle` | `build.gradle` | 根插件声明：AGP 8.4.2 + Kotlin 1.9.24 |
| `settings.gradle` | `settings.gradle` | 阿里云镜像 + `RepositoriesMode.FAIL_ON_PROJECT_REPOS` + `:app` |
| `gradle.properties` | `gradle.properties` | **关键**：覆盖失效的 `JAVA_HOME`，`useAndroidX=true` |
| `local.properties.example` | `local.properties` | **必须改名**，含本机 SDK 绝对路径，不入库 |
| `.gitignore.example` | `.gitignore` | **必须改名**，忽略 `local.properties` 与构建产物 |
| `app/build.gradle.example` | `app/build.gradle` | **必须改名**，Kotlin + Compose 最小可用配置 |
| `gradle/wrapper/gradle-wrapper.properties` | 同路径 | Gradle 8.7 发行包地址 |
| `gradle/wrapper/gradle-wrapper.jar` | 同路径 | Wrapper 可执行 jar |
| `gradlew` / `gradlew.bat` | 同路径 | Wrapper 启动脚本 |

## M0 复制步骤

1. 建立 Android 工程根（本期规划为 `Client/`），把本目录内容整体复制过去。
2. `local.properties.example` → `local.properties`，`.gitignore.example` → `.gitignore`，`app/build.gradle.example` → `app/build.gradle`。
3. 建立 `app/src/main/AndroidManifest.xml` 与 Kotlin 源码目录后，按 [ENVIRONMENT.md](../docs/ENVIRONMENT.md) 第 8 章执行验证序列。
4. 首次构建需联网下载 Kotlin Gradle Plugin 与 Compose 依赖（本机未缓存），见 ENVIRONMENT.md 第 9 章。

## 注意事项

- **不要**把 `local.properties` 提交到版本库；换机器只改其中 `sdk.dir` 一行。
- 系统级 `JAVA_HOME` 当前指向不存在的 `jdk-25.0.2`，构建依赖 `gradle.properties` 的 `org.gradle.java.home` 兜底，**不要删除该行**。
- 本机只装了 SDK Platform 34，`compileSdk` / `targetSdk` 固定 34；升级前先装新平台。
- `namespace` / `applicationId` 暂用 `com.example.gleannote`，正式建工程时确认。
