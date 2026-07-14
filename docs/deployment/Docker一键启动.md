# Docker 一键启动

这套 Compose 会启动三个服务：后端、控制中心前端、车载前端。前端的 `/api` 请求由各自容器代理至同一个后端，因此浏览器只需要打开两个地址。

## 首次构建或代码更新后

在仓库根目录运行：

```powershell
.\scripts\start-platform.ps1 -Build
```

首次构建会下载 Java、Node 和 Nginx 镜像，耗时较长。后续正常启动使用：

```powershell
.\scripts\start-platform.ps1
```

打开：

- 控制中心：<http://localhost:5173>
- 车载系统：<http://localhost:5174>
- 后端健康检查：<http://localhost:8080/api/health>

停止全部服务：

```powershell
.\scripts\stop-platform.ps1
```

## 实验室模式

先确认电脑已经接入 `192.168.100.x` 网段，且 `Test-NetConnection` 能连通 `.121:8888`、`.122:9999`、`.123:8001`。再运行：

```powershell
.\scripts\start-platform.ps1 -Lab -TrainId LB -Build
```

`LB` 必须和车载页面中“上线并等待发车”创建的列车 ID 完全一致。实验室模式会启用：

- 信号屏 `.122:9999`
- 网络屏 `.121:8888`
- Vision 1.3 UDP `.124:8303`

脚本不会自动连接 PLC，也不会向 PLC 写回帧。创建并上线 `LB` 后，在车载页面的 `704司机台 · LB` 面板点击“连接”，这是为了确保实体司机台始终绑定到你明确选择的仿真列车。

查看实体设备发送状态：

```powershell
Invoke-RestMethod http://localhost:8080/api/hil/status | ConvertTo-Json -Depth 8
```

Vision 是 UDP，`framesSent` 持续增加仅说明本机已发出数据报，仍必须观察实体视景画面确认显示。TCP 信号屏和网络屏应同时检查 `connected=true` 与实体画面。

## 可选配置

复制 `.env.example` 为 `.env` 后可修改端口或实验室 IP。`.env` 已被 Git 忽略，不会误提交本机配置。普通模式保持 `PLC_OUTPUT_ENABLED=false`；使用 `scripts/start-platform.ps1 -Lab` 时会启用已选定的 28 B PLC 回写。

Docker Desktop 的 Linux 容器需要具备访问实验室网段的权限。先用以下命令检查后端容器实际网络连通性：

```powershell
Invoke-RestMethod http://localhost:8080/api/hil/status | ConvertTo-Json -Depth 8
```

实验室模式下，创建并上线同名列车后点击 704 连接；若状态仍持续重连，而宿主机 `Test-NetConnection 192.168.100.123 -Port 8001` 已成功，则说明 Docker Desktop 未能访问实验室网段。此时先用宿主机直接运行后端进行现场演示，并记录 Docker Desktop 的网络限制；不要绕过老师网络策略。
