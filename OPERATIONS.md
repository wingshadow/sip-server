# sip-server 运维手册（OPERATIONS）

> 适用版本：v1.1+（含 P0 并发修复、P1 回放控制解锁与自动化测试、磁盘保留清理）。
> 定位：Linux 内网部署为主，Windows 作为开发/备选环境。投产前请逐项过 §7 清单。

---

## 1. 系统概览

### 1.1 架构

```
[摄像机/NVR] ──SIP 5060 + RTP/语音端口──> [sip-server 主机]
                                            │  HTTP 8080（建议仅绑 127.0.0.1）
                                       [Nginx TLS 443]（推荐）
                                            │
                                        [用户浏览器]
[ZLMediaKit 同机] <── 127.0.0.1:9092 ──┘（zlm 媒体模式时）
```

- 双媒体模式二选一：`local`（自研 RTP 收流 + ffmpeg）或 `zlm`（ZLMediaKit 接管媒体面），`sip.media.mode` 切换。
- **无外部存储依赖**（无 MySQL/Redis）：设备注册表与会话全内存，重启后会话断开、设备约 180s（心跳超时）内自动重注册自愈。

### 1.2 端口矩阵

| 端口 | 协议 | 用途 | 开放对象 |
|---|---|---|---|
| 5060 | UDP | SIP 信令 | 仅设备网段 |
| 30000-30099 | UDP | local 模式 RTP 收流（每会话独占端口） | 仅设备网段 |
| 30100-30150 | UDP + TCP | 语音对讲（UDP 收流 + RFC4571 TCP 承载） | 仅设备网段 |
| 8080 | TCP | 页面 + REST API + WebSocket | 经 Nginx；建议绑 127.0.0.1 |
| 443 / 80 | TCP | Nginx TLS 反代 | 用户网段 |
| 9092 | TCP | ZLM HTTP API（api-base 走 127.0.0.1）与 FLV/HLS 播放 | 本机 + 用户网段 |
| 30002 | UDP | ZLM openRtpServer 收流 | 仅设备网段 |
| 554 / 1935 | TCP | ZLM RTSP/RTMP | **不开放**（无外部消费者） |

### 1.3 运行期产物目录（相对 WorkingDirectory）

| 目录 | 内容 | 治理 |
|---|---|---|
| `logs/` | `sip.log`（Spring Boot 默认滚动 10MB×7 天） | 纳入巡检 |
| `downloads/` | 录像下载 MP4（`.part` 为下载中临时文件） | `app.retention` 自动清理 |
| `hls/` | HLS 切片 | `app.retention` 自动清理 |

**磁盘保留策略**（已内置，`DiskRetentionService`）：启动 30s 后首轮清理，之后每 `sweep-interval-hours`（默认 6h）一轮；删除修改时间超过 `max-age-days`（默认 7 天）的文件；正在写入的 `.part`/切片 mtime 持续更新不会误删。配置：

```yaml
app:
  retention:
    enabled: true          # false 完全关闭
    max-age-days: 7
    sweep-interval-hours: 6
```

---

## 2. 部署（Linux）

### 2.1 前置依赖

| 依赖 | 要求 | 安装 |
|---|---|---|
| JDK | 17+（Headless 即可） | `apt install openjdk-17-jre-headless` |
| ffmpeg | 仅 local 模式；PATH 可见或配绝对路径 | `apt install ffmpeg`（CentOS 需 EPEL/RPMFusion） |
| ZLMediaKit | 仅 zlm 模式；本机 9092 | 官方编译或 Docker，另行配 systemd |
| 运行用户 | 专用低权限账户 | `useradd -r -m -d /opt/sip -s /usr/sbin/nologin sip` |

代码跨平台干净：无硬编码盘符/Windows 命令，`ffmpeg` 走 PATH 命令名。

### 2.2 打包上传

```bash
mvn clean package -DskipTests          # Windows 开发机执行
scp target/sip-server-1.0.0.jar user@<LINUX_IP>:/tmp/
sudo mv /tmp/sip-server-1.0.0.jar /opt/sip/ && sudo chown sip:sip /opt/sip/sip-server-1.0.0.jar
```

### 2.3 生产配置 `/opt/sip/application-prod.yml`

**最常见部署事故：IP 通告错误（INVITE 成功但无画面）——3 处 IP 必须改为 Linux 服务器 IP。**

```yaml
server:
  address: 127.0.0.1        # 仅绑回环，HTTP 面收编给 Nginx（无 Nginx 时删除此行）
  port: 8080

sip:
  server:
    id: 34020000002000000001
    host: 0.0.0.0
    port: 5060
    ip: <LINUX_IP>           # ① SIP Contact + SDP c= 通告地址
    domain: 3402000000
    password: ${SIP_DEVICE_PASSWORD}
    # 多厂商混部建议按设备单独配密码：
    # device-passwords:
    #   "34020000001320000002": "dev1-pwd"
  media:
    mode: local              # 或 zlm
    local:
      ffmpeg-path: /usr/bin/ffmpeg
    zlm:
      api-base: http://127.0.0.1:9092
      secret: ${ZLM_SECRET}
      media-ip: <LINUX_IP>   # ② 摄像头 RTP 推流目标
      http-port: 9092
      rtp-port: 30002
  openapi:
    enabled: true
    api-keys:
      - ${OPENAPI_KEY}       # ⚠ 未配置任何 key 时鉴权自动放行，环境变量必须设置！
app:
  auth:
    password: ${APP_AUTH_PASSWORD}
  hls-dir: ./hls
  retention:
    enabled: true
    max-age-days: 7
    sweep-interval-hours: 6
```

### 2.4 凭据 `/opt/sip/sip.env`（`chmod 600 root:sip`）

```bash
SIP_DEVICE_PASSWORD=<强密码，禁用 12345678>
ZLM_SECRET=<ZLM config.ini 的 api.secret>
APP_AUTH_PASSWORD=<强密码>
OPENAPI_KEY=<openssl rand -hex 16 生成>
```

### 2.5 systemd `/etc/systemd/system/sip-server.service`

```ini
[Unit]
Description=GB28181 SIP Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=sip
Group=sip
WorkingDirectory=/opt/sip
ExecStart=/usr/bin/java -Xms256m -Xmx1g -jar /opt/sip/sip-server-1.0.0.jar --spring.profiles.active=prod
EnvironmentFile=/opt/sip/sip.env
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now sip-server
```

5060/8080 均 >1024，非 root 运行无特权问题。

### 2.6 Windows 备选（nssm）

```powershell
nssm install sip-server "C:\Program Files\Java\jdk-17\bin\java.exe" "-jar D:\sip\sip-server-1.0.0.jar --spring.profiles.active=prod"
nssm set sip-server AppDirectory D:\sip
nssm set sip-server AppEnvironmentExtra APP_AUTH_PASSWORD=xxx ZLM_SECRET=xxx SIP_DEVICE_PASSWORD=xxx OPENAPI_KEY=xxx
nssm set sip-server Start SERVICE_AUTO_START
nssm start sip-server
```

---

## 3. 安全策略

### 3.1 应用层鉴权（代码已内置）

| 层 | 机制 | 配置 |
|---|---|---|
| 管理页面 | 账密登录 + token Cookie（默认 12h 过期） | `app.auth.*` |
| Open API `/api/v1/**` | `X-API-Key` 请求头匹配 | `sip.openapi.*`（**空列表=放行，必须配置**） |
| 设备注册 | GB/T 28181 Digest，401 挑战 + nonce（TTL 300s）防重放 | `sip.server.password` / `device-passwords` |
| Actuator | 显式白名单 health/info/metrics（无 shutdown） | `management.*`，建议 §3.4 再收一层 |

### 3.2 凭据治理（必做）

生产环境**禁止明文落盘**，一律经环境变量注入（见 §2.4）。变更流程：改 `sip.env` → `systemctl restart sip-server`。轮换时注意设备侧注册密码需同步修改。

### 3.3 防火墙（白名单制，拒绝默认放行）

```bash
# firewalld（CentOS/Rocky）
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<设备网段> port port=5060 protocol=udp accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<设备网段> port port=30000-30099 protocol=udp accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<设备网段> port port=30100-30150 protocol=udp accept'
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=<设备网段> port port=30100-30150 protocol=tcp accept'
sudo firewall-cmd --permanent --add-service=https --add-service=http
sudo firewall-cmd --reload

# ufw（Ubuntu）示例：sudo ufw allow from <设备网段> to any port 5060 proto udp

# Windows（netsh）
netsh advfirewall firewall add rule name="GB28181-SIP" dir=in action=allow protocol=UDP localport=5060 remoteip=<设备网段>
```

### 3.4 Nginx TLS 反向代理（推荐）

前端 WebSocket/FLV 均为相对路径，HTTPS 下浏览器自动升级 wss/https，**前端零改动**。

**证书（内网无公网域名，签 IP SAN 自签）：**

```bash
cd /etc/nginx/certs
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout ca.key -out ca.crt -subj "/CN=SIP-Server Root CA"
openssl req -newkey rsa:2048 -nodes -keyout sip.key -out sip.csr -subj "/CN=sip.internal"
openssl x509 -req -in sip.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days 1825 -out sip.crt -extfile <(printf "subjectAltName=IP:<LINUX_IP>")
# ca.crt 分发用户浏览器导入"受信任的根证书"；试点期可临时 curl -k / 浏览器忽略告警
```

**`/etc/nginx/conf.d/sip.conf`：**

```nginx
upstream sip_backend {
    server 127.0.0.1:8080;
    keepalive 32;
}
limit_req_zone $binary_remote_addr zone=login:10m rate=5r/m;

server {
    listen 443 ssl;
    http2 on;
    server_name _;

    ssl_certificate     /etc/nginx/certs/sip.crt;
    ssl_certificate_key /etc/nginx/certs/sip.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;
    ssl_session_cache   shared:SSL:10m;

    server_tokens off;
    add_header X-Frame-Options        SAMEORIGIN always;
    add_header X-Content-Type-Options nosniff   always;
    add_header Strict-Transport-Security "max-age=31536000" always;

    client_max_body_size 64m;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # 登录限流：防爆破
    location = /api/login {
        limit_req zone=login burst=3 nodelay;
        proxy_pass http://sip_backend;
    }

    # 语音对讲 WebSocket（wss 升级）
    location /ws/ {
        proxy_pass http://sip_backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    # FLV 直播/回放长连接流：禁缓冲（否则直播延迟堆积数十秒）
    location ~ \.flv$ {
        proxy_pass http://sip_backend;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 1h;
        proxy_set_header Connection "";
        add_header X-Accel-Buffering no;
    }

    # MP4 下载：流式直通不经 nginx 临时盘
    location /api/download/file {
        proxy_pass http://sip_backend;
        proxy_max_temp_file_size 0;
        proxy_read_timeout 30m;
        proxy_set_header Connection "";
    }

    # Actuator 仅运维网段
    location /actuator/ {
        allow <运维网段/32>;
        deny  all;
        proxy_pass http://sip_backend;
    }

    location / {
        proxy_pass http://sip_backend;
        proxy_read_timeout 300s;
        proxy_set_header Connection "";
    }
}

server {
    listen 80;
    return 301 https://$host$request_uri;
}
```

未启用 CSP：页面为单文件内联 JS，CSP 需 `unsafe-inline` 白名单、增益有限；TLS 1.2+ / HSTS / nosniff / X-Frame-Options 已覆盖主要面。

### 3.5 公网暴露红线

- SIP 5060 勿直接暴露公网（GB28181 平台被扫描注册是常见攻击面）；跨公网走 VPN/信令网关。
- 仅在内网部署时允许 HTTP 直连 8080；任何跨网段/公网访问必须经 §3.4 TLS 反代。
- Actuator 若与业务同端口，必须像 §3.4 一样加 IP 白名单，或改绑独立端口只对运维网段开放。

---

## 4. 日常运维

### 4.1 启停与状态

```bash
systemctl status sip-server
sudo systemctl restart sip-server          # 升级/变更后重启
sudo journalctl -u sip-server -f           # 实时日志（应用自身日志另见 /opt/sip/logs/sip.log）
```

### 4.2 健康检查

```bash
curl -s http://127.0.0.1:8080/actuator/health        # {"status":"UP"}
curl -s http://127.0.0.1:8080/actuator/metrics       # sip.* 业务 gauge（注册设备/会话数等）
sudo ss -ulnp | grep -E '5060|30000|30100'           # 信令与媒体端口监听
```

建议外部拨测：每分钟 `curl /actuator/health`，非 200 告警。

### 4.3 磁盘巡检

```bash
df -h /opt/sip
du -sh /opt/sip/{downloads,hls,logs}
```

保留策略自动治理产物；若出现异常膨胀（如设备故障导致大量 `.part`），先查 `grep RETENTION logs/sip.log`。

### 4.4 升级流程

1. 选择业务空闲窗口（重启后会话全断，设备约 180s 内自动重注册恢复，下载中的文件会留在 `.part` 待清理）。
2. `systemctl stop sip-server` → 备份旧 jar → 替换 jar → `start`。
3. 升级后执行 §7.2 回归项。

### 4.5 备份

无数据库，仅需备份配置与凭据：`/opt/sip/application-prod.yml`、`/opt/sip/sip.env`（凭据文件注意保密存储）。

---

## 5. 故障排查速查

| 症状 | 高概率原因 | 处置 |
|---|---|---|
| 设备不上线（无 REGISTER） | 防火墙未放行 5060；设备侧服务器地址未改 | §3.3；摄像头侧改 SIP 服务器 IP/端口 |
| 注册 401 循环 | 设备注册密码与平台不一致 | 核对 `SIP_DEVICE_PASSWORD` 与设备侧配置；realm 须为编码前 10 位 |
| INVITE 200 OK 但无画面 | **SDP 通告 IP 错误**（`sip.server.ip`/`zlm.media-ip` 仍为旧机 IP）；RTP 端口被防火墙拦 | §2.3 三处 IP；放行 30000-30099 |
| 点播卡顿延迟大 | Nginx 对 `.flv` 开了缓冲 | 确认 `proxy_buffering off` 存在 |
| 语音对讲无声/断续 | `/ws/` 未做 WS 升级；30100-30150 未放行（TCP 也要） | §3.4 WS 配置；§3.3 防火墙 |
| 下载卡在进度上限 | 设备静默停推（已有 15s 空闲兜底）；或下载中服务被重启残留 `.part` | 查日志 `[ZLM]`/`ffmpeg`；残留 `.part` 由保留策略 7 天内清理 |
| Open API 401 | X-API-Key 缺失/不匹配 | 请求头 `X-API-Key` 与 `OPENAPI_KEY` 核对 |
| 页面 401 循环 | token 过期（12h）或 Cookie 未随 HTTPS 携带 | 重新登录；确认经 https 访问 |
| 磁盘持续增长 | retention 被关闭或目录含其他来源文件 | `app.retention.enabled: true`；du 定位 |
| 重启后设备长时间离线 | 心跳超时窗口内设备未重发注册 | 等待 ≤180s 自愈；否则查设备侧网络 |

---

## 6. 监控建议

- **可用性**：外部拨测 `/actuator/health`（1min）。
- **业务指标**：`/actuator/metrics` 中 `sip.*` gauge（设备在线数、活跃会话数）；接入 Prometheus 时配置 `management.metrics.export`。
- **日志告警关键字**：`ERROR`、`[ZLM] 下载 ffmpeg 优雅退出超时`、`下载文件收尾失败`、`鉴权失败`（设备密码错误被扫描的信号）。
- **资源**：磁盘水位 80% 告警；ffmpeg 进程数（local 模式 = 活跃下载/转码会话数）。

---

## 7. 投产前检查清单

### 7.1 配置与安全

- [ ] 4 个凭据全部环境变量化，`sip.env` 权限 600，弱密码已更换
- [ ] `sip.server.ip` / `zlm.media-ip` 与实际部署机一致（三处 IP 校对）
- [ ] `sip.openapi.api-keys` 非空（空 = 放行）
- [ ] 防火墙白名单按 §3.3 生效（默认拒绝，仅设备/用户网段）
- [ ] systemd 启用（`enable --now`），`Restart=always` 验证：`kill` 进程后 5s 拉起
- [ ] Nginx TLS 生效，8080 仅绑 127.0.0.1，Actuator 白名单
- [ ] 摄像头侧 SIP 服务器地址/密码已指向新平台

### 7.2 功能回归（部署后必测）

- [ ] 点播 / 回放 / 下载 / 语音对讲四功能各一次（双模式如均使用）
- [ ] 设备拒绝语音广播（4xx）→ 日志立即出现拒绝并停止会话（不再等 60s）
- [ ] 双通道并发点播，第二路 FLV 获取不卡顿
- [ ] 下载中途点击停止 → 接口立即返回，≤60s 日志出现收尾完成，`.part` 正确改名
- [ ] 下载自然完成 → `.part → done`，无重复收尾告警
- [ ] ZLM 模式回放控制真机首测：暂停/恢复/倍速/拖动各一次；同区间重复回放日志出现"复用已有会话"
- [ ] 语音对讲验收：对讲中调 `GET /api/voice/sessions`，`upstreamPeak`/`downstreamPeak` 均 > 0
- [ ] `mvn test` 19 个用例全绿（发版前 CI 或本机执行）
- [ ] 24h 长跑：设备在线稳定、磁盘无异常增长、日志无 ERROR 堆积

---

*本文档由部署方案整合生成；应用层行为细节见 `README.md` 与 `TECH.md`。*
