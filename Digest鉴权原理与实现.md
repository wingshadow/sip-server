# Digest 鉴权原理与实现（HTTP Digest / SIP 注册 Digest / GB/T 28181）

> 本文讲清 Digest 认证家族的来龙去脉：先介绍 **HTTP Digest（RFC 2617 / RFC 7616）** 的完整机制，再讲 **SIP 注册 Digest（RFC 3261 §22 + GB/T 28181）** 如何复用同一套公式，最后落到本项目的代码实现、配置与排坑。
>
> 阅读顺序建议：第 1 节建立框架 → 第 2 节 HTTP Digest（机制最完整的原型）→ 第 3 节异同对比 → 第 4 节起是本项目实现。

---

## 1. Digest 认证家族总览

Digest 认证的本质一句话：**挑战-应答（challenge-response）**。服务端先发一个随机挑战（`nonce`），客户端把"密码 + 挑战 + 请求信息"一起做 MD5 摘要回传；服务端用自己保存的密码重算同一摘要并比对。**密码本身从不在网络上传输**，抓包者拿到的是不可逆的摘要。

| 标准 | 场景 | 挑战头 | 应答头 | 本项目用途 |
|---|---|---|---|---|
| RFC 2617 / 7616 | HTTP | `WWW-Authenticate: Digest` | `Authorization: Digest` | 无直接使用（对照基准） |
| RFC 3261 §22 | SIP 请求（REGISTER 等） | `WWW-Authenticate`（401）/ `Proxy-Authenticate`（407） | `Authorization` / `Proxy-Authorization` | **设备注册鉴权（本文重点）** |
| GB/T 28181 | 国标设备 REGISTER | 同 SIP | 同 SIP | 与 RFC 3261 一致，设备侧普遍不用 `qop` |

三者共用同一套摘要公式，差异只在**参与计算的 `uri` 是什么**、**可选参数用不用**、**nonce 怎么管**。

---

## 2. HTTP Digest 认证（RFC 2617）完整介绍

### 2.1 交互流程

```text
① 客户端请求（无凭据）
   GET /protected HTTP/1.1

② 服务端挑战
   HTTP/1.1 401 Unauthorized
   WWW-Authenticate: Digest realm="Users@example.com",
                     qop="auth,auth-int",
                     nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093",
                     opaque="5ccc069c403ebaf9f0171e9517f40e41",
                     algorithm=MD5, stale=FALSE

③ 客户端带凭据重试
   GET /protected HTTP/1.1
   Authorization: Digest username="Mufasa",
                  realm="Users@example.com",
                  nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093",
                  uri="/protected",
                  response="6629fae49393a05397450978507c4ef1",
                  opaque="5ccc069c403ebaf9f0171e9517f40e41",
                  qop=auth, nc=00000001, cnonce="0a4f113b"

④ 服务端重算摘要并比对 → 通过则返回 200 + 内容
```

### 2.2 摘要公式（RFC 2617 §3.2.2）

```text
HA1 = MD5(username : realm : password)
      algorithm=MD5-sess 时：HA1 = MD5( HA1 : nonce : cnonce )

HA2 = MD5(method : digestURI)                    # qop=auth 或未指定 qop
      MD5(method : digestURI : H(entity-body))   # qop=auth-int（把请求体也纳入完整性保护）

response = MD5(HA1 : nonce : HA2)                              # 无 qop
response = MD5(HA1 : nonce : nc : cnonce : qop : HA2)          # 有 qop
```

### 2.3 参数速查

| 参数 | 方向 | 含义 | 要点 |
|---|---|---|---|
| `realm` | 双向 | 保护空间名称 | 必须原样回带；改了 realm 等于换密码空间 |
| `nonce` | 服务端发 | 挑战随机数 | 服务端自选格式（可嵌时间戳）；**服务端须校验它确实是自己发的** |
| `opaque` | 服务端发 | 不透明数据 | 客户端必须原样回带，服务端可借它传会话状态 |
| `uri` | 客户端发 | 本次请求的目标 URI | **服务端必须用客户端回带的这个值算 HA2**，不能拿实际路由到的 URI 替代（代理改写场景会不一致） |
| `qop` | 服务端选 | `auth`（只保护请求行）或 `auth-int`（额外保护请求体） | 客户端选一个回带 |
| `nc` | 客户端发 | nonce 计数器，十六进制 | 防重放：同一 nonce 下必须严格递增，服务端需记录已见值 |
| `cnonce` | 客户端发 | 客户端随机数 | qop 场景必填，防止服务端选到的 nonce 被预测 |
| `stale` | 服务端发 | nonce 过期标志 | `stale=true` 表示"用新 nonce 直接重试，别当认证失败" |
| `algorithm` | 服务端选 | `MD5`、`MD5-sess`、`SHA-256`、`SHA-256-sess`（RFC 7616 扩展） | 默认 MD5；`-sess` 后缀表示 HA1 再混入 nonce+cnonce |

### 2.4 HTTP Digest 的安全性结论（为什么线上越来越少用）

- **优点**：密码不明文传输；防重放有 `nc` 机制；无需 TLS 也能避免密码泄露。
- **缺点**：
  - **服务端必须存明文密码**（或等价的 HA1）才能重算摘要——数据库拖库即全员裸奔；
  - MD5 已被攻破，`algorithm=MD5` 形同虚设，RFC 7616 才补上 SHA-256；
  - `qop=auth` 只保护请求行，**头部、请求体完整性依赖 auth-int 或 TLS**；
  - 无会话管理、无注销、无多因子扩展，现代 Web 已全面转向 token/OAuth2。
- 现实定位：**仍活在"哑终端/嵌入式设备"场景**——IP 摄像头、打印机、NAS 的 Web 登录框。GB28181 摄像头管理面即是典型。

---

## 3. HTTP Digest vs SIP/GB28181 注册 Digest

SIP（RFC 3261 §22）几乎照搬 RFC 2617 的公式，差异集中在语义映射：

| 维度 | HTTP Digest | SIP 注册 Digest（GB/T 28181） |
|---|---|---|
| 挑战响应码 | 401 Unauthorized | 同为 401 Unauthorized（REGISTER 场景）；代理链上用 407 |
| `uri` 取值 | 请求的资源路径，如 `/protected` | **SIP Request-URI / AOR**，如 `sip:34020000002000000001@3402000000`——格式随设备而異，**必须以设备回带值为准** |
| `realm` 取值 | 服务端自定义 | 通常是 SIP 服务器域名/国标平台编码，如 `3402000000` |
| `qop` | 浏览器普遍使用 | **国标设备基本不用**（无 qop 的三段式是主流），实现必须两条公式都支持 |
| `nc`/`cnonce` | qop 场景必填 | 设备不带 qop 时一并消失 |
| nonce 管理 | 服务端自管，可嵌时间戳 | 本项目按设备编号记账 + TTL + `stale` 重挑战（见 5.2） |
| 触发时机 | 每次访问受保护资源 | 注册/续注册（设备周期 3600s）；注销（Expires=0）同链路 |
| 防重放 | `nc` 严格递增 | 靠 nonce 时效 + 归属校验（单次注册语义，天然一次性） |

**记忆锚点**：SIP Digest = HTTP Digest 的公式 + SIP 世界的 `uri`/`realm` 语义 + 国标设备"去 qop 化"的简化。

---

## 4. GB28181 设备注册 Digest 交互流程（本项目）

```text
设备 → 平台  REGISTER（首次，无 Authorization）
平台 → 设备  401 Unauthorized
             WWW-Authenticate: Digest realm="3402000000", nonce="<32位hex>", stale=false
             （nonce 按设备编号入账，TTL 默认 300s）

设备 → 平台  REGISTER（带 Authorization: Digest username=..., realm=..., nonce=..., uri=..., response=...）
             设备侧用登记密码重算：
               HA1 = MD5(username : realm : password)
               HA2 = MD5("REGISTER" : uri)
               response = MD5(HA1 : nonce : HA2)

平台 → 设备  200 OK（Date + Expires）——注册成功，DeviceRegistry 登记设备地址
```

平台校验链：**nonce 归属（防伪造挑战）→ nonce 时效 → 用设备回带的 `uri`/`realm` 重算 → 常量时间比对**。任何一步失败回 401；nonce 过期时带 `stale=true`，设备免等下一轮、立即用新 nonce 重试。

注销（`Expires=0`）与注册共用同一条链路，通过后走 `registry.unregister` 并清除该设备 nonce 记录。

---

## 5. 本项目代码实现（行号锚点）

### 5.1 文件地图

| 文件 | 职责 |
|---|---|
| `src/main/java/com/example/sip/sip/SipDigestAuth.java` | 纯计算：HA1/HA2/response/responseWithQop/matches，与 RFC 2617 公式一一对应 |
| `src/main/java/com/example/sip/handler/RegisterHandler.java` | 注册鉴权编排：挑战发放、摘要校验、nonce 账本、过期/注销清理 |
| `src/main/java/com/example/sip/sip/SipResponseBuilder.java` | 401（带 `WWW-Authenticate`）与 200 OK（带 `Date`/`Expires`）构造 |
| `src/main/java/com/example/sip/handler/SipUtil.java` | `extractAuthParam`：Authorization 头参数提取（带引号/裸值两种形态） |
| `src/main/java/com/example/sip/config/SipServerProperties.java` | 密码配置解析：`password`/`device-passwords`/`auth-enabled`/`nonce-ttl-seconds` |

### 5.2 关键锚点

**摘要计算（`SipDigestAuth.java`）**

```text
:35   HA1 = MD5(username:realm:password)
:40   HA2 = MD5(method:uri)                    注册场景 method 固定 "REGISTER"
:45   response = MD5(HA1:nonce:HA2)            无 qop（国标设备主流）
:50   response = MD5(HA1:nonce:nc:cnonce:qop:HA2)   qop=auth
:75   matches()：MessageDigest.isEqual 常量时间比对，null 安全
```

**挑战与校验（`RegisterHandler.java`）**

```text
:69    handle() 入口
:91    resolvePassword(deviceId) 返回 null → 未配置密码，直接放行注册（跳过鉴权）
:93-98 无 Authorization → buildUnauthorized + issueNonce，回 401 挑战
:147   verifyDigest()：提取 7 个参数 → 必要参数/algorithm 校验（仅 MD5）
:165-174 nonce 三重校验：记录存在 → 未过期(TTL) → 值匹配（equalsIgnoreCase）
:176   realm 缺省回落 props.getDomain()（设备不带 realm 时）
:180-187 按有无 qop 分流计算；qop 模式缺 nc/cnonce 判失败
:188   常量时间比对，失败原因带摘要前缀/user/realm/uri 便于排障（完整摘要仅 debug，防重放材料入日志）
:196-202 issueNonce：UUID 去横线 32 位 hex，同设备再次挑战覆盖旧记录
:205-208 cleanExpiredNonces：顺注册请求惰性清理，无需定时任务
:109/:115 鉴权通过/注销时清除 nonce 记录
```

**401 报文（`SipResponseBuilder.buildUnauthorized:156-171`）**

```text
WWW-Authenticate: Digest realm="<domain>",nonce="<nonce>",stale=<true|false>
```

**200 OK（`buildRegisterOk:120-135`）**：回显 `Date`（RFC 1123，国标设备依赖它校时）与 `Expires`，空 body。

### 5.3 设计要点（与 RFC 最小实现的差异）

| 要点 | 做法 | 原因 |
|---|---|---|
| nonce 按设备编号记账 | `ConcurrentHashMap<deviceId, NonceEntry>` | 校验设备回带 nonce 确由本平台发出，防伪造挑战/重放 |
| `stale=true` 重挑战 | nonce 过期/失效时回带 stale | 设备免二次往返立即重试，避免"平台重启后一轮注册空档" |
| `uri`/`realm` 以设备回带值为准 | 不自行拼装 | 国标设备 uri 形态不统一（`sip:平台ID@域名` 常见），自行假设必然摘要不匹配 |
| 仅支持 `algorithm=MD5` | 其余显式拒绝并回 401 | 国标设备无 MD5-sess/SHA-256；明确失败优于静默误算 |
| 常量时间比对 | `MessageDigest.isEqual` | 防时序侧信道（与页面登录、X-API-Key 同一约定） |
| 注销清 nonce | Expires=0 时 `nonces.remove` | 防设备换密码重注册时复用旧 nonce |

---

## 6. 配置（`application.yml` → `sip.server`）

```yaml
sip:
  server:
    password: 12345678        # 全局设备密码；留空=跳过鉴权（默认行为）
    # device-passwords:       # 按设备编号覆盖，key=20 位国标编码，"*"=通配默认
    #   34020000001320000002: "pwd-a"
    #   "*": "pwd-default"
    # auth-enabled: true      # 可选；不配则按 password 是否为空自动判断
    # nonce-ttl-seconds: 300  # nonce 有效期，超时以 stale=true 重新挑战
```

密码解析优先级（`SipServerProperties.resolvePassword:150`）：

```text
device-passwords[设备编号] → device-passwords["*"] → password
```

**设备侧**：把摄像头「注册密码」配成与平台一致（海康：网络 → 高级配置 → GB28181 → 密码；大华同位置）。多设备场景推荐 `device-passwords` 按编号分别配置，避免一改全改。

---

## 7. 排坑指南

| 日志关键词 | 含义 | 处置 |
|---|---|---|
| `摘要不匹配(expect=abc123..., actual=def456...)` | 公式重算结果与设备回带不同 | ① 两侧密码是否一致（注意大小写/首尾空格）；② realm 是否和设备侧显示一致；③ 极少见：设备 uri 形态特殊，把日志级别调到 DEBUG 拿到完整 `expect/actual`，再用抓包值手工 MD5 复核 |
| `nonce 非本平台发出（或已被清除）` | 平台重启后设备持旧 nonce 重试 | 自愈：等设备下一轮注册（周期 3600s）；或重启设备 |
| `nonce 已过期(ttl=300s)` | 设备应答超过 nonce TTL | 平台已带 `stale=true` 重挑战，设备下一包即恢复；持续出现可调大 `nonce-ttl-seconds` |
| `不支持的 algorithm=...` | 设备带了 MD5-sess/SHA-256 | 当前仅 MD5；让设备侧改用 MD5 后重注册 |
| `qop=... 模式缺少 nc/cnonce` | 设备声明 qop 但参数不全 | 设备侧兼容性问题，可让设备关掉 qop |
| 401 循环不断 | 设备密码始终不对 | 平台侧临时 `auth-enabled: false` 验证链路，再逐项核对密码/realm |

**抓包自证**（Wireshark 过滤器 `sip.Method == "REGISTER"`）：把 DEBUG 日志里的 `expect` 与抓到的 `Authorization` 中 `response=` 对比，再拿设备密码手工执行：

```powershell
# 三段式复核（PowerShell）：username/realm/password/uri/nonce 均填日志或抓包中的实际值
$username = "34020000001320000001"; $realm = "3402000000"; $password = "12345678"
$uri = "sip:34020000002000000001@3402000000"; $nonce = "<设备回带的 nonce>"
$md5 = { param($s) [System.BitConverter]::ToString([System.Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($s))).Replace("-","").ToLower() }
$ha1 = & $md5 "$username`:$realm`:$password"
$ha2 = & $md5 "REGISTER`:$uri"
& $md5 "$ha1`:$nonce`:$ha2"   # 输出应与设备 Authorization 中的 response= 一致
```

---

## 8. 本项目鉴权体系全景（Digest 的坐标）

Digest 只是本项目四种鉴权手段之一，各自解决的问题不同：

| 场景 | 机制 | 实现 | 为何不用 Digest |
|---|---|---|---|
| 设备注册（SIP） | **Digest 401 挑战** | `RegisterHandler` + `SipDigestAuth`（本文） | — |
| 管理页面登录（HTTP） | 用户名密码换 token，Cookie 持有 | `AuthService`（token→过期时间内存 Map）、`LoginController` | 浏览器表单场景 token 更顺手；且页面密码本就经 HTTPS 之外的内网传输，服务端可安全比对 |
| 内部 API（`/api/**`、`/live/**`） | 无鉴权 | — | 内网部署假设，依赖网络隔离 |
| 对外 Open API（`/api/v1/**`） | `X-API-Key` 请求头，常量时间比对 | `OpenApiAuthInterceptor` | 机器对机器集成要的是可轮换的静态密钥，非交互挑战 |

**共同约定**：所有密钥/摘要比对一律 `MessageDigest.isEqual` 常量时间算法，防时序侧信道。

---

## 9. 相关文档

- `README.md` 第 4 章（配置项）与 11.16（SIP 注册鉴权 Digest，排障索引）
- `代码阅读指南.md` 4.1（设备注册与心跳，含 Digest 链行号锚点）
- `GB-T28181技术参考手册.md`（国标信令格式背景）
- RFC 2617（HTTP Digest 原始规范）/ RFC 7616（SHA-256 扩展）/ RFC 3261 §22（SIP Digest）
