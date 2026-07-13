# 视景 Vision 1.3 联调说明

## 结论

本项目向实验室视景主机发送的是北京地铁 9 号线 Vision 1.3 的主线基包：

```text
4  数据报计数
1  信号机数量 N = 77
77 信号机状态
1  道岔数量 M = 29
29 道岔状态
4  本车速度 mm/s
2  发车显示预留
1  本车工况
1  本车加速度百分比
4  本车位置 mm
2  本车边号（Seg）
1  本车方向
1  他车数量 L = 0
-------------------
128 B
```

每增加一辆同样已上线的 HIL 仿真列车，包尾追加 9 B：位置 mm（4）、边号（2）、方向（1）、速度 cm/s（2）。

## 154B 抓包的解释

`8303-port.pcapng` 中的 UDP 包为 154 B。解码后其头部为 `N=92`、`M=40`：

```text
4 + 1 + 92 + 1 + 40 + 16 = 154 B
```

这与 Vision 1.3 文档定义的 `N=77`、`M=29` 不同，是老师系统的另一套扩展数据表，不能作为本项目 Vision 1.3 主线包的长度依据。

## 已实现

- 每 100 ms UDP 发送 Vision 1.3 包。
- 按文档固定顺序发送 77 个正线信号机和 29 个正线道岔。
- 信号机状态使用 Vision 编码：红 `0x01`、绿 `0x02`、白 `0x04`、黄 `0x10`、蓝 `0x40`、红黄 `0x11`。
- 道岔状态使用：定位 `0x01`、反位 `0x02`。
- 本车速度、位置、边号、方向来自后端的同一辆 HIL 仿真列车。
- 边号按 Vision 文档表 3 的 `1~48` 边号表转换，不发送系统内部的 `Seg 1~319` 编号。
- 其他已上线 HIL 列车自动写入他车尾部列表；单车时不会产生额外字节。
- `GET /api/hil/status` 可查看 vision 的启用状态、发送帧数、字节数和错误信息。

默认配置为关闭，不会在普通软件仿真或启动后端时向 `.124` 发送任何 UDP 数据。

## 实验室启用

先确认本机已接入实验室网段，并且 `.124` 是视景接收机。将 `LB` 改成当前已上线、已连接 704 的同一列车 ID：

```powershell
cd E:\大三下小学期\SemesterTraining-III\backend
mvn spring-boot:run "-Dspring-boot.run.arguments=--hil.enabled=true --hil.train-id=LB --hil.vision.enabled=true --hil.vision.host=192.168.100.124 --hil.vision.port=8303"
```

启动后访问：

```text
http://localhost:8080/api/hil/status
```

验收条件：

- `vision.enabled=true`
- `vision.connected=true`
- `vision.framesSent` 持续增加
- `vision.bytesSent / vision.framesSent = 128`
- 移动列车时，视景中的速度、位置、前方信号机与道岔显示同步变化

若视景画面不刷新，先向老师确认该设备当前接收的是否正是 Vision 1.3 的 `77/29` 数据表；若现场明确要求 `92/40` 扩展格式，不能仅改长度，必须拿到该版本的完整信号机、道岔排列顺序后单独实现。
