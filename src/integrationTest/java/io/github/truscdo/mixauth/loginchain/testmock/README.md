# mock-sessionserver

模拟 Mojang sessionserver 两个端点（测试专用，无第三方依赖，仅 JDK 标准库）：

- `GET /minecraft/profile/lookup/name/{name}` — pre-login profile 预检查
- `GET /session/minecraft/hasJoined?username=...&serverId=...` — 在线会话校验

## 运行

JDK 单文件源码启动（无需编译）：

```bash
java MockSessionServer.java --port 18080 --profile-mode online --hasjoined-mode online
```

## 参数

| 参数 | 值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `--port` | 端口 | 8080 | 仅监听 127.0.0.1 |
| `--profile-mode` | `online`/`404`/`429`/`500`/`malformed`/`empty` | `online` | profile lookup 响应模式 |
| `--hasjoined-mode` | `online`/`404`/`500`/`malformed` | `online` | hasJoined 响应模式 |
| `--profile-uuid` | UUID（可带横线） | `00000000-0000-0000-0000-000000000001` | profile 返回的 UUID |

## 模式说明

- `online`：HTTP 200，**含非空 `properties` 数组**（真实 Mojang 的 textures）——复现 authlib 7.0.61（1.21.11）不可变 PropertyMap 回归必需
- `404` / `429` / `500`：对应 HTTP 状态码（离线路由 / 限流 / 服务端错误）
- `malformed`：HTTP 200 + 非法 JSON（解析失败 → mod 判 malformed）
- `empty`：HTTP 200 + 空 properties（复现"空数组不进 put 循环、bug 复现不出来"的反例）

## 与 MixAuth 对接

服务器 JVM 经 system property 指向 mock（`MojangClient` 默认值不变、生产零影响）：

```bash
-Dmixauth.profile_lookup_url=http://127.0.0.1:18080/minecraft/profile/lookup/name/
-Dmixauth.has_joined_url=http://127.0.0.1:18080/session/minecraft/hasJoined
```

## 验证

```bash
curl -s http://127.0.0.1:18080/minecraft/profile/lookup/name/TestPlayer
curl -s "http://127.0.0.1:18080/session/minecraft/hasJoined?username=TestPlayer&serverId=abc"
```
