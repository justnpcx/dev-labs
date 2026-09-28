# 通过 Cloudflare Tunnel 暴露仪表盘

## ✅ 已完成（2026-09-27）

| 项 | 值 |
|---|---|
| 公网域名 | **https://jvmlab.justnpc.com** |
| 隧道 ingress | `jvmlab.justnpc.com` → `http://jvm-lab:8080`（config version=19） |
| 鉴权 | **Cloudflare Access**（team `justnpc`，策略：Allow + Include Emails） |
| 源站 | 容器内 `172.20.0.20`，宿主端口仍只绑 `127.0.0.1:8081` |

验证结果（全部通过）：

```
GET /                   302 → justnpc.cloudflareaccess.com/cdn-cgi/access/login/jvmlab.justnpc.com
GET /status             302 → Access
GET /actuator/heapdump  302 → Access   （配置前：匿名可下 32.4MB）
GET /oom/heap           302 → Access   （配置前：匿名可触发 OOM）
```

Access 下发的 meta JWT 解码确认策略绑定正确：

```
hostname     = jvmlab.justnpc.com     ← 绑的就是这个域名
auth_status  = NONE                    ← 未登录
redirect_url = /                       ← 登录后回首页
```

绕过测试（明文 HTTP / 不存在路径 / 静态资源 / 查询串 / 路径穿越 / HEAD / POST）
全部被拦；明文 HTTP 的 301 只是 HTTPS 强制跳转，第二跳照样进 Access。

**注意事项**：登录后的会话有效期按 Access 应用的 Session Duration 走，
过期需要重新验证邮箱。别把域名分享出去。

---

## 以下是配置过程留档

## 前提：服务端已就绪

这些我已经做完并验证过了，你不用管：

| 项 | 状态 |
|---|---|
| jvm-lab 接入 `ai-stack_ai-net` | ✅ 与 cf-tunnel 同网络（`172.20.0.20`） |
| 从该网络按容器名访问 | ✅ `http://jvm-lab:8080/status` 和首页均返回 200 |
| `/actuator/env` 关闭 | ✅ 返回 404（避免配置泄露） |
| 宿主端口 | 仍只绑 `127.0.0.1:8081`，**没有**暴露到 `0.0.0.0` |

隧道是 **token 驱动的远程管理型**（`tunnel run` + `TUNNEL_TOKEN`，无本地 `config.yml`），
所以 ingress 规则**只能**在 Cloudflare 后台加 —— 服务器侧无法新增路由。

---

## 步骤 A：添加 Public Hostname

1. 打开 <https://one.dash.cloudflare.com/>（Zero Trust 后台）
2. **Networks → Tunnels** → 找到你这台机器对应的隧道 → 右侧 **Edit**（或点进去）
3. 切到 **Public Hostname** 标签页 → **Add a public hostname**
4. 按下表填写：

| 字段 | 填什么 | 说明 |
|---|---|---|
| Subdomain | `jvmlab` | 随便取，别和你已有的子域冲突 |
| Domain | 你托管在 Cloudflare 的域名 | 下拉选 |
| Path | **留空** | |
| Type | **HTTP** | 容器内是明文 HTTP，TLS 由 Cloudflare 边缘终结 |
| URL | **`jvm-lab:8080`** | ★ 见下方注意事项 |

5. **Save**

### ⚠ 最容易填错的一格：URL

**必须填 `jvm-lab:8080`（容器名 + 容器内端口），不能填 `localhost:8081`。**

原因：cloudflared 自己跑在容器里，它的 `localhost` 是它自己的网络命名空间，
而 jvm-lab 监听的是**容器内的 8080**。`8081` 是宿主侧的映射端口，容器里不存在。

| 填法 | 结果 |
|---|---|
| `jvm-lab:8080` | ✅ 正确。走 Docker 内网 DNS 解析 |
| `localhost:8081` | ❌ 502 Bad Gateway（cloudflared 容器里没有这个服务） |
| `127.0.0.1:8081` | ❌ 同上 |
| `172.20.0.20:8080` | ⚠️ 能通，但容器重建后 IP 会变，别用 |

保存后 Cloudflare 会自动创建对应的 DNS CNAME 记录，不用手动加。

---

## 步骤 B：加 Access 策略（**别跳过这一步**）

不做这步的话，**任何人拿到域名都能**：

- 点一下按钮就把服务打崩（5 个 OOM 端点就是 5 个远程 DoS 开关）
- 下载**整个堆内存快照**（`/actuator/heapdump`，实测 8 秒能拉走 32MB）
- 读取线程栈和内部指标

### 配置

1. Zero Trust 后台 → **Access → Applications** → **Add an application**
2. 选 **Self-hosted**
3. 填：
   - **Application name**：`JVM 演练场`
   - **Session Duration**：`24 hours`（按需）
   - **Public hostname**：选你上一步加的 `jvmlab.<你的域名>`
4. **Next** → 添加策略（Add policy）：

| 字段 | 填什么 |
|---|---|
| Policy name | `只允许我` |
| Action | **Allow** |
| Include | **Emails** → 你的邮箱地址 |

5. 保存

> 新版 UI 在步骤 A 的 Public Hostname 弹窗里就有 Access 配置入口，
> 如果你在那里直接配了，就不用再单独建应用。两条路效果一样。

---

## 步骤 C：验证

1. 浏览器打开 `https://jvmlab.<你的域名>`
2. **应该先跳到 Cloudflare 的登录页**（让你输邮箱收验证码）—— 看到这一步说明 Access 生效了
3. 登录后才看到仪表盘

验证 Access 确实在拦（未登录时应被重定向，而不是直接 200）：

```bash
curl -sI https://jvmlab.<你的域名>/ | head -5
# 期望看到 302，Location 指向 cloudflareaccess.com
# 如果直接 200，说明 Access 没生效，回去检查策略
```

---

## 已知限制

### ① 空 body 的 4xx 会被显示成 502

**实测确认。** 应用返回「**没有 body 的 400**」时，Cloudflare 把它显示成
`502 Bad Gateway` + `Host Error`，用户完全看不出真实原因。

所以本项目所有错误响应都带 **JSON body**：

| 情况 | 返回 | 用户看到 |
|---|---|---|
| 文件不存在 | `404` + `{"error":"文件不存在或不可访问","hint":"..."}` | 能看懂的提示 |
| 文件不存在（旧写法） | `400` 空 body | ❌ `502 Bad Gateway / Host Error` |

**规则：经 Cloudflare 暴露的服务，不要返回空 body 的错误响应。**
状态码再正确也没用，Cloudflare 会把空 body 的 4xx 变成 502。

### ② 大文件下载（**未严格验证**）

实测数据：

| 文件 | 大小 | 经 Cloudflare 下载 |
|---|---|---|
| `heap-<ts>.hprof`（live 快照） | ~31MB | 未测，预期正常 |
| `java_pid1.hprof`（OOM 自动落盘） | ~235MB | **未测** |

Cloudflare 免费版的**上传**限制是 100MB；**响应体**没有公开的明确上限，
但大响应容易超时或被重置。本项目按 100MB 作为经验阈值：

- 文件列表里超过 100MB 会标黄 `超 100MB`
- 点下载会先弹确认框，提示替代方案
- 响应带 `X-Lab-Warning` 头（值必须纯 ASCII，见下）

**要下载大文件，更稳的路子是 SSH 隧道**：

```bash
ssh -L 8081:127.0.0.1:8081 root@<IP>
# 然后从 http://localhost:8081/ 下载，完全绕过 Cloudflare
```

> 如果你实测了 235MB 能不能过，把结果记到这里。

### ③ 响应头不能含非 ASCII 字符

Tomcat **静默丢弃**含中文等非 ASCII 字符的响应头 —— 不报错、不打日志、响应里就是没有。
`X-Lab-Warning` 第一版写了中文，头凭空消失，改成英文立刻出现。

**规则：响应头的值只能是 ASCII。** 中文放 body 里。

---

## 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| **502 Bad Gateway** | ① 源站返回了空 body 的 4xx ② 文件太大 ③ jvm-lab 容器没跑 | 先看 `./lab.sh log`；再看接口本地返回什么 |
| 502 且 URL 填错 | 填了 `localhost:8081` | 改回 `jvm-lab:8080` |
| 域名解析不了 | DNS 记录没自动创建 | 检查 Cloudflare DNS 里是否有该子域的 CNAME 指向 `<隧道ID>.cfargotunnel.com` |
| 能打开但**曲线不动** | 浏览器侧拿不到 `/status` | F12 看 Network；确认 `/status` 返回 200 |
| 直接 200 没跳登录 | Access 策略没绑到这个 hostname | 检查 Access 应用的 Public hostname 是否一致 |
| 页面样式全丢 | 静态资源没加载 | 硬刷新（Ctrl+Shift+R） |

**排查 502 的通用方法**：先绕过 Cloudflare 直接打源站。

```bash
curl -sI localhost:8081/<路径>          # 宿主机上直接看真实状态码
docker logs jvm-lab --tail 50            # 看应用有没有报错
```

源站返回 200 但公网 502 → 问题在 Cloudflare 那层（大小/超时/空 body）；
源站本身就报错 → 问题在应用里。这一步能把范围砍一半。

---

## 安全边界（暴露后仍然成立）

暴露的是**访问入口**，不是**资源限制**。容器那四道防线一个都没变：

- 内存 2GiB / swap 禁用 —— 就算有人狂点 OOM，炸的是容器内的进程
- 进程数 320 / CPU 2 核 —— 打不出宿主机
- 端口仍只绑 `127.0.0.1` —— 隧道是唯一的入口，绕过 Cloudflare 直连不了

但请记住两点：

1. **Access 是唯一的大门**。策略配错 = 大门敞开。
2. **别把这个域名分享出去**，哪怕是给同事演示。要给人看就用 SSH 隧道，或者临时加一条 Access 策略再删掉。

---

## 如果不想用 Cloudflare Access

退路是在应用里加 HTTP Basic Auth：引入 `spring-boot-starter-security`，
配一个用户名口令。缺点是每次访问都要弹框，而且口令管理得自己来。
既然你已经有 Zero Trust，Access 是更省事也更安全的做法。
