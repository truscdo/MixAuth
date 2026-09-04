# 登录身份路由流程图

## 设计原则

- `canonicalOfflineProfile` 在收到 Login Start 后只创建一次，其 UUID 固定由
  `OfflinePlayer:username` 生成。
- ONLINE 记录使用 Mojang UUID；OFFLINE 记录和离线密码、临时封禁均使用
  `canonicalOfflineProfile.id`。
- 正版握手或 `hasJoined` 失败后直接拒绝连接，不降级到离线登录。

## 纯文本流程

```text
读取 username / clientUuid
        ↓
校验 username / clientUuid
        ├─ 无效 → 拒绝连接
        └─ 有效
             ↓
创建 canonicalOfflineProfile
canonicalOfflineProfile.id= UUID(OfflinePlayer:username)
             ↓
查询 clientUuid 对应的完整 known_players
        ├─ ONLINE → 正版握手
        ├─ OFFLINE 且 clientUuid = canonicalOfflineProfile.id → OfflineGate
        └─ 未命中或 clientUuid != canonicalOfflineProfile.id
             ↓ 
查询 canonicalOfflineProfile.id 和 clientUuid 对应的 offline_client_aliases
        ├─ 命中 → OfflineGate
        └─ 未命中 → OfflineModeDetector
                          ├─ CONFIRMED → OfflineGate
                          └─ NEEDS_VERIFICATION → Mojang 预检查
                                                   ├─ ONLINE → 正版握手
                                                   ├─ OFFLINE → OfflineGate
                                                   └─ API 或数据异常 → 拒绝连接

OfflineGate
        ↓
按 canonicalOfflineProfile.id 检查离线临时封禁
        ├─ 已封禁 → 拒绝连接
        └─ 未封禁
             ↓
写入或刷新 known_players(canonicalOfflineProfile.id, username, OFFLINE) 以及 offline_client_aliases(username, clientUuid, canonicalOfflineProfile.id, updated_at)
        ↓
使用同一个 canonicalOfflineProfile 进入离线注册或登录流程

正版握手
        ↓
完成加密握手和 hasJoined 验证
        ├─ 失败 → 拒绝连接，不降级为离线
        └─ 成功
             ↓
写入或刷新 known_players(mojangUuid, resolvedUsername, ONLINE)
        ↓
使用 Mojang 返回的正版 Profile 进入游戏
```

## 冲突与记录规则

```text
一旦进入OFFLINE流程(OfflineGate)，不再信任clientUuid，一切以canonicalOfflineProfile.id为准

任何路径判定为 OFFLINE：
    先检查 canonical UUID 的临时封禁；
    再记录 known_players 和 offline_client_aliases；
    最后使用 canonicalOfflineProfile 继续登录。

任何路径判定为 ONLINE：
    必须完成加密握手和 hasJoined；
    只记录 hasJoined 返回的 UUID 和最新用户名；
    失败时不得降级为 OFFLINE。
```
