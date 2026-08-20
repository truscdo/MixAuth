# GameTest 运行目录模板

`run-gametest/` 是 `gameTestServer` 的独立运行目录（`gameDirectory = file('run-gametest')`），**整目录被 git 忽略**（见 `.gitignore` 的 `/run-gametest`）。其中三个**预写配置**文件的源存放在本模板目录，用于版本化与新克隆者复现测试环境。

| 模板文件 | 用途 |
| --- | --- |
| `eula.txt` | 接受 Minecraft EULA（`eula=true`） |
| `server.properties` | 平坦世界、`gamemode=survival`、`online-mode=false`、小视距（2）等小值配置 |
| `config/mixauth-server.toml` | MixAuth 小值安全配置（`max_login_attempts=3`、`temporary_block_minutes=5`、`bcrypt_cost=4`、`login_timeout_minutes=1`、`prompt_interval_seconds=1` 等），保证用例快速收敛 |

其余 `run-gametest/` 内容（`world/`、`logs/`、`crash-reports/`、`mods/`、`mixauth/` DB 与黑名单、`config/fml.toml`、`neoforge-*.toml`、`usernamecache.json` 等）均为 gameTestServer **运行时生成**，不属于模板。

## 用法（自动复制，通常无需手动操作）

运行 `gradlew.bat runGameTestServer` 时，Gradle 会自动把本模板中的三个配置文件（`eula.txt` / `server.properties` / `config/mixauth-server.toml`）同步到 `run-gametest/`，覆盖同名文件、不删除运行产物。新克隆仓库直接运行即可，无需手动复制。

如需**彻底重置**（世界 + DB + 配置），删除整个 `run-gametest/` 目录后重新运行 `gradlew.bat runGameTestServer`，配置会自动从模板重建（GameTest 用例幂等性依赖干净的 DB/信任记录）。
