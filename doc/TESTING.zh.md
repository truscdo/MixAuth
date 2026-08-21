# MixAuth 测试指南

按运行时依赖从轻到重分为三层：**单元测试**（纯 JUnit，跨版本）、**GameTest**（真实服务器内集成）、**真实服务器登录链**（黑盒 E2E，跨版本 + 可选 mock）。各层覆盖不同粒度，共同守住整体质量。

---

## 1. 分层总览

| 层 | 位置 | 运行时依赖 | 版本策略 | 测试目标 |
| --- | --- | --- | --- | --- |
| **① 单元测试** | `src/test/java/.../`（除 `gametest` 包） | 无（编译期引用 MC classpath，运行期不需要） | 版本无关，可跨版本编译运行 | 纯逻辑/无状态单元 |
| **② GameTest** | `src/test/java/.../gametest/` | 完整 MC + NeoForge 运行时 | dev 单版本（1.21.1） | 服务器内事件总线与登录状态机集成 |
| **③ 真实服务器登录链** | `src/integrationTest` + `run-login-it.bat` | 真实服务器 + 无头客户端（MCC） | 跨 4 版本 + mock（在线预检） | 完整登录链黑盒 E2E |

---

## 2. 层①：单元测试

对不依赖 Minecraft 运行时的**纯逻辑、无状态**组件做单元测试，作为日常改动后的快速回归。

### 单元测试覆盖范围

- **密码处理**：BCrypt 往返、盐随机性、cost 编码、fail-closed，UTF-8 多字节密码与 72 字节上限。
- **策略校验**：密码长度/字节边界、黑名单命中。
- **缓存与密钥**：内存占用估算、RSA 密钥对预生成缓存（补货、阻塞、互不相同）。
- **离线 UUID 检测**：标准/PCL/正版 UUID 及边界输入。
- **profile 解析**：字段回退、非法结构兜底、`parseUndashedUuid`（含 1.21.11 不可变 properties 回归）。

### 单元测试运行方式

```bat
gradlew.bat test                                    REM dev 1.21.1 全量
gradlew.bat test --tests "io.github.truscdo.mixauth.validation.MojangProfileParserTest"
gradlew.bat test -Pminecraft_version=1.21.11 -Pneo_version=21.11.45   REM 跨版本
```

- 跨版本时**两个 `-P` 必须同时传**（源集与 classpath 才能匹配），否则编译错。
- 报告：`build/reports/tests/test/index.html`。

---

## 3. 层②：GameTest

在真实 Minecraft 服务器内（`gameTestServer`）验证登录链的事件驱动逻辑：进服路由、认证/注册流程、未登录隔离、缓存与 DB 一致性等运行时交互。

### 机制

- 由测试专用 mod `mixauth_tests` 承载，仅 test 源集编译，不污染生产代码。
- 用 `@TestHolder` + `@GameTest` + `@EmptyTemplate` 声明用例；基类 `AuthGameTestBase` 封装 mock 玩家（触发真实 `PlayerLoggedInEvent`）、假 IP、模式预置、断言与命令。
- 独立运行目录 `run-gametest/`（git 忽略），配置来自 `gametest-template/`，由 `syncGameTestTemplate` 自动同步。

### GameTest覆盖范围

- **冒烟**：testframework 加载与测试执行链路。
- **进服路由**：未注册 / OFFLINE→REGISTER / 信任窗口免密 / ONLINE 放行等分支。
- **注册与登录**：成功/失败、剩余次数提示、达上限临时封禁、改密/设密。
- **信任窗口**：UUID+IP 窗口内免密直进、窗外回退密码登录。
- **待认证隔离**：旁观者模式、失明、位置锁定、聊天/命令/交互拦截、登出清理。
- **状态机与缓存一致性**：状态迁移、关键/非关键写双通道一致、清表路径。

### GameTest运行方式

```bat
gradlew.bat runGameTestServer
```

- 结果 dump 在 `run-gametest/logs/tests/`（`SUMMARY_DUMP`）；也可游戏内 `/tests` 启停。

### 注意事项

- 新增用例首跑可能因 `TEST_STORE` 欢迎消息顶掉末条消息而失败 → 用 `assertAnyMessage` 或重跑确认。
- H2 DB 跨运行持久化，信任记录残留会触发免密直进 → 用例开头 `resetPlayerData(uuid)`；信任用例用独立假 IP。
- `gameTestServer` 默认 CREATIVE → 断言恢复模式用「≠ SPECTATOR」而非「= SURVIVAL」。
- 偶发 JVM 崩溃（`Chunk::new` malloc 失败）属环境内存压力，重跑即可。

---

## 4. 层③：真实服务器登录链

用**真实服务器 + 真实无头客户端（MCC）**做黑盒 E2E，覆盖 GameTest 触达不到的联网分支与多版本适配，作为「登录链/适配器不崩」的守门。由 JUnit 5 驱动（独立源集 `src/integrationTest`），基类 `LoginChainITBase`（PER_CLASS）统一管理 mock、服务器与 MCC 子进程生命周期，逐场景做客户端/服务器双通道断言。

- **离线注册/登录链**（`OfflineChainIT`，`@Tag offline`，按序执行）：注册提示 → `/register` 自动登录 → 重进 `/login` → 正确密码放行 → 连续错误密码 → 达上限临时封禁断线 → 封禁中重进被拒。
- **在线预检**（`@Tag online`，本地 mock 覆盖 Mojang URL）：profile 查询返回 **429（限流）** / **畸形响应（解析失败）** 时的双通道表现；**在线加密握手**与 **hasJoined 500** 两场景暂 `@Disabled` 挂起（恢复条件见 `OnlineHandshakePendingIT` 源码注释）。

### 真实服务器运行方式

```bat
run-login-it.bat                          REM 默认两层全跑，全 4 版本（1.21.1/1.21.5/1.21.8/1.21.11）
run-login-it.bat --online                 REM 仅在线预检
run-login-it.bat --offline                REM 仅离线链
run-login-it.bat 1.21.5                   REM 单版本
run-login-it.bat 1.21.5 --online --tests "*OnlinePrefetch429IT*"   REM 单场景
REM 等价直连（按层给 -Plct.layer=online|offline|all）：
gradlew.bat integrationTest -Plct.layer=online -Pminecraft_version=1.21.5 ^
    -Pneo_version=21.5.98 -Pparchment_minecraft_version=1.21.5 ^
    -Pparchment_mappings_version=2025.06.15 -Pminecraft_version_range="[1.21.5]" --tests "*OnlinePrefetch429IT*"
```

### 说明

- **生命周期由 JUnit 管理**：`@BeforeAll` 起 mock + 服务器（devlaunch 直启，注入 mock URL 与 `-Dfml.modFolders`）→ 场景内 MCC 双通道断言 → `@AfterAll` RCON 停服 + jstack 采样 + 兜底强杀；失败自动归档日志。
- 默认端口：游戏 `25565` / RCON `25575` / mock `18080`（可用 `MCC_EXE`、`MCC_DIR`、`RCON_PW`、`JDK_EXE`/`JAVA_HOME` 等覆盖）。
- **唯一外部依赖是 MCC 二进制**（`MCC_EXE`/`MCC_DIR`），缺失时整层 **skip 而非失败**。
- 报告：`build/reports/integrationTest/`（HTML）、`build/test-results/integrationTest/`（XML）；失败归档：`build/reports/integrationTest/artifacts/<场景>/`。
- 统一入口为 `run-login-it.bat`（历史自研编排器已删除，git 可找回）。

---

## 5. 版本矩阵

| MC 版本 | `-Pminecraft_version` | `-Pneo_version` |
| --- | --- | --- |
| 1.21.1（dev） | `1.21.1` | `21.1.1` |
| 1.21.5 | `1.21.5` | `21.5.98` |
| 1.21.8 | `1.21.8` | `21.8.54` |
| 1.21.11 | `1.21.11` | `21.11.45` |

---

## 6. 推荐工作流

1. 日常改动：`gradlew.bat test` 快速回归。
2. 涉及版本适配：跨版本 JUnit，如 `gradlew.bat test -Pminecraft_version=1.21.11 -Pneo_version=21.11.45`。
3. 涉及登录链/事件总线/隔离：`gradlew.bat runGameTestServer`。
4. 涉及适配器：离线链 `run-login-it.bat 1.21.5 --offline`；在线预检 `run-login-it.bat 1.21.5 --online`。
