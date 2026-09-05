# mock-sessionserver

模拟 Mojang/Yggdrasil 服务端点（测试专用，无第三方依赖，仅 JDK 标准库）：

- `GET /minecraft/profile/lookup/name/{name}` — pre-login profile 预检查
- `GET /session/minecraft/hasJoined?username=...&serverId=...` — 在线会话校验
- `POST /authserver/authenticate` — 测试 Yggdrasil 登录
- `POST /sessionserver/session/minecraft/join` — 测试 Yggdrasil 会话加入

## 运行

JDK 标准库直接编译启动：

```bash
javac -d out Mock*.java
java -cp out io.github.truscdo.mixauth.loginchain.testmock.MockSessionServer \
  --port 18080 --profile-mode online --hasjoined-mode online
```

## 参数

| 参数 | 值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `--port` | 端口 | 8080 | 仅监听 127.0.0.1 |
| `--profile-mode` | `online`/`404`/`429`/`500`/`malformed`/`empty` | `online` | profile lookup 响应模式 |
| `--hasjoined-mode` | `online`/`404`/`500`/`malformed` | `online` | hasJoined 响应模式 |

所有成功 profile 响应的 UUID 都按 `UUID.nameUUIDFromBytes(("YggdrasilTest:" + username).getBytes(UTF_8))` 生成。

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

MCC 的 Yggdrasil `AuthServerUrl` 指向 `http://127.0.0.1:18080/`，因此认证和 join 请求也由同一个 mock 接收。

## 验证

```bash
curl -s http://127.0.0.1:18080/minecraft/profile/lookup/name/TestPlayer
curl -s "http://127.0.0.1:18080/session/minecraft/hasJoined?username=TestPlayer&serverId=abc"
```
