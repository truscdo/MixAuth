# MixAuth 测试指南

MixAuth 的测试按「运行时环境依赖」从轻到重分为三层：**纯 JUnit 单元测试**（不依赖 Minecraft 运行时，跨版本）、**GameTest 集成测试**（真实服务器内、dev 单版本）、**真实服务器登录链冒烟测试**（黑盒 E2E，跨版本 + 可选 mock）。每层覆盖不同粒度，共同守住整体质量。

---

## 1. 分层总览

| 层 | 位置 | 运行时依赖 | 版本策略 | 测试目标 |
| --- | --- | --- | --- | --- |
| **① Pure JUnit** | `src/test/java/.../`（除 `gametest` 包） | 无（部分类编译期引用 Minecraft classpath，运行期不需要） | 版本无关，可跨版本编译运行 | 纯逻辑/无状态单元 |
| **② GameTest** | `src/test/java/.../gametest/` | 完整 Minecraft + NeoForge 运行时 | dev 单版本（1.21.1） | 服务器内事件总线与登录状态机集成 |
| **③ 真实服务器 L2/L3** | `testutil/RunLoginChain.java` + 脚本 | 真实服务器 + 真实无头客户端（MCC） | 跨 4 版本 + 可选 mock | 完整登录链，主要用于验证版本适配器工作正常 |

---

## 2. 层①：纯 JUnit 单元测试

### 2.1 测试目标

对不依赖 Minecraft 运行时的**纯逻辑、无状态**组件做单元测试，作为日常改动后的快速测试。

### 2.2 覆盖范围

- **密码处理**：BCrypt hash/verify 往返、盐随机性、cost 编码、失败关闭（fail-closed）处理，以及 UTF-8 多字节密码与 72 字节上限。
- **策略校验**：密码长度/字节边界、黑名单命中。
- **缓存与密钥**：内存占用估算、RSA 密钥对预生成缓存（补货、阻塞、互不相同）。
- **离线 UUID 检测**：标准离线 UUID、PCL 专有离线 UUID、正版 v4 UUID 及边界/异常输入。
- **profile 解析**：Mojang profile JSON 解析、缺失字段回退、非法结构兜底、`parseUndashedUuid`（含 1.21.11 不可变 properties 的回归用例）。

### 2.3 运行方式

```bat
gradlew.bat test                                    # dev 1.21.1 全量
gradlew.bat test --tests "io.github.truscdo.mixauth.validation.MojangProfileParserTest"
gradlew.bat test -Pminecraft_version=1.21.11 -Pneo_version=21.11.45   # 跨版本
```

- 跨版本运行时**两个 `-P` 必须同时传**（`-Pneo_version` 决定 authlib/Minecraft classpath，`-Pminecraft_version` 决定源集选择），否则「源集 1.21.11 + classpath 1.21.1」错配报编译错。
- 报告：`build/reports/tests/test/index.html`。

---

## 3. 层②：GameTest 集成测试

### 3.1 测试目标

在**真实 Minecraft 服务器内**（`gameTestServer`）验证登录链的事件驱动逻辑：进服路由、认证/注册流程、未登录隔离、缓存与 DB 一致性等，覆盖 JUnit 覆盖不到的服务端运行时交互。

### 运行机制

- 由**测试专用 mod `mixauth_tests`** 承载，仅在 test 源集编译，不污染生产代码。
- 使用 `@TestHolder` + `@GameTest` + `@EmptyTemplate` 声明用例；基类 `AuthGameTestBase` 封装 mock 玩家创建（触发真实 `PlayerLoggedInEvent`）、假 IP 注入、模式预置、断言与命令执行。
- 独立运行目录 `run-gametest/`（git 忽略），预写配置来自 `gametest-template/`，Gradle 的 `syncGameTestTemplate` 运行前自动同步。

### 3.2 覆盖范围

- **冒烟**：testframework 加载与测试执行链路。
- **进服路由**：未注册 / OFFLINE→REGISTER / 信任窗口免密 / ONLINE 放行等判定分支。
- **注册与登录流程**：成功/失败、剩余次数提示、连续错误达上限→临时封禁、改密/设密。
- **信任窗口**：UUID+IP 窗口内免密直进、窗外回退密码登录。
- **待认证隔离**：旁观者模式、失明、位置锁定、聊天/命令/交互拦截、登出清理。
- **服务状态机与缓存一致性**：状态迁移、关键/非关键写双通道一致、清表路径。

### 3.3 运行方式

```bat
gradlew.bat runGameTestServer
```

- 结果 dump 在 `run-gametest/logs/tests/`（`SUMMARY_DUMP`），服务日志在 `run-gametest/logs/`；也可游戏内 `/tests` 启停。

### 注意事项

- 新增用例后首跑可能因 `TEST_STORE` 欢迎消息顶掉末条消息而失败 → 用存在检查 `assertAnyMessage` 或重跑确认。
- H2 DB 跨运行持久化，信任记录残留会触发免密直进 → 用例开头 `resetPlayerData(uuid)`；信任用例用独立假 IP。
- `gameTestServer` 默认游戏模式为 CREATIVE → 断言恢复模式用「≠ SPECTATOR」而非「= SURVIVAL」。
- 偶发 JVM 崩溃（`Chunk::new` malloc 失败）属环境内存压力，重跑即可。

---

## 4. 层③：真实服务器登录链冒烟测试

### 4.1 测试目标

用**真实服务器 + 真实无头客户端（MCC）**做黑盒 E2E，验证完整登录链的常见路径，作为「登录链/适配器不崩」的守门——覆盖 GameTest 触达不到的联网分支与多版本适配。跨平台编排器为 `testutil/RunLoginChain.java`（JEP 330 单文件，JDK 21+，零第三方依赖）。

### 4.2 覆盖范围

- **离线登录链，默认**：注册提示 → `/register` 自动登录 → 重进 `/login` → 正确密码 → 错误密码累计 → 达上限临时封禁断线 → 封禁中重进被拒。
- **在线预检分支，`--mock`**：本地 mock sessionserver（Mojang URL 覆盖）下，profile lookup 429（限流）与畸形响应（解析失败）的客户端/服务器双通道表现。

### 4.3 运行方式

```bat
testutil\run-login-chain.bat 1.21.5         
testutil\run-login-chain.bat 1.21.5 --mock   
```

- 常用选项：`--mock`、`--trusted`（信任窗口）、`--user NAME`、`--no-build`（跳过 Gradle 准备）、`--scenario ID`（单场景调试）。
- 默认端口：游戏 `25565` / RCON `25575` / mock `18080`（可用 `MCC_EXE`、`MCC_DIR`、`RCON_PW` 等环境变量覆盖）。
- **唯一外部依赖是 MCC 二进制**，用 `MCC_EXE`/`MCC_DIR` 指定，缺失时编排器打印期望路径指引。
- 请从项目根经 `run-login-chain.bat` / wrapper 运行（避免 `user.dir` 解析问题）。

## 5. 版本矩阵速查

| MC 版本 | `-Pminecraft_version` | `-Pneo_version` |
| --- | --- | --- |
| 1.21.1（dev） | `1.21.1` | `21.1.1` |
| 1.21.5 | `1.21.5` | `21.5.98` |
| 1.21.8 | `1.21.8` | `21.8.54` |
| 1.21.11 | `1.21.11` | `21.11.45` |

---

## 6. 推荐工作流

1. **日常改动后**：`gradlew.bat test`（层①，dev 1.21.1 全量 JUnit）快速回归。
2. **涉及版本适配**：追加跨版本 JUnit，如 `gradlew.bat test -Pminecraft_version=1.21.11 -Pneo_version=21.11.45`。
3. **涉及登录链/事件总线/隔离逻辑**：`gradlew.bat runGameTestServer`（层② GameTest 全量）。
4. **涉及适配器**：`testutil\run-login-chain.bat 1.21.5`（层③ ），或加 `--mock`。
