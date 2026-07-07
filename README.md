# SemesterTraining-III
bjtu大三小学期实训

## 项目简介

本项目是一个城市轨道交通智能运行与供能调度的仿真系统，支持多车协同调度、轨道线路与信号控制、车辆运行仿真以及供电能源评估与分析等功能。

## 技术栈

- **后端**: Java 17 + Spring Boot 3 + Maven
- **前端**: React 18 + TypeScript + Vite（中控系统 + 车载系统，双前端项目）
- **数据配置**: JSON

## 目录结构

```

├── README.md
├── .gitignore
├── backend/                     # 后端 Spring Boot 项目
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/bjtu/railtransit/
│       │   ├── RailTransitApplication.java
│       │   ├── common/          # 公共类（ApiResponse 等）
│       │   ├── config/          # 配置类（CORS 等）
│       │   ├── dispatch/        # 总控调度与多车协同模块
│       │   ├── signal/          # 轨道线路与信号控制模块
│       │   ├── vehicle/         # 车载系统与车辆运行仿真模块
│       │   ├── energy/          # 供电能源模块
│       │   ├── evaluation/      # 结果评估分析模块
│       │   ├── simulation/      # 仿真引擎模块
│       │   └── domain/          # 领域对象
│       │       ├── dto/
│       │       ├── model/
│       │       └── enums/
│       └── resources/
│           └── application.yml
├── frontend-control-center/     # 中控系统前端 (React + Vite) 端口 5173
│   ├── package.json
│   ├── index.html
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/                 # API 请求层
│       ├── components/          # 公共组件
│       ├── pages/               # 页面模块
│       │   ├── Dashboard/       # 仪表盘
│       │   ├── Dispatch/        # 调度管理
│       │   ├── LineSignal/      # 线路信号
│       │   └── EnergyEvaluation/# 能源评估
│       ├── types/               # TypeScript 类型定义
│       └── styles/              # 全局样式
├── frontend-onboard-system/     # 车载系统前端 (React + Vite) 端口 5174
│   ├── package.json
│   ├── index.html
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/                 # API 请求层
│       ├── components/          # 公共组件
│       ├── pages/               # 页面模块
│       │   └── Vehicle/         # 车辆仿真
│       ├── types/               # TypeScript 类型定义
│       └── styles/              # 全局样式
├── configs/                     # 示例配置文件
│   ├── line-profile.sample.json
│   ├── train-model.sample.json
│   └── scenario-config.sample.json
└── docs/                        # 项目文档
    ├── team-division.md
    ├── api.md
    ├── data-model.md
    ├── development-guide.md
    └── test-plan.md
```

## 后端启动

```bash
cd backend
mvn spring-boot:run
```

后端运行在 `http://localhost:8080`，健康检查接口：`GET /api/health`

## 前端启动

### 中控系统 (frontend-control-center)

```bash
cd frontend-control-center
npm install
npm run dev
```

中控系统运行在 `http://localhost:5173`，已配置代理转发 `/api` 到后端 `http://localhost:8080`。

### 车载系统 (frontend-onboard-system)

```bash
cd frontend-onboard-system
npm install
npm run dev
```

车载系统运行在 `http://localhost:5174`，已配置代理转发 `/api` 到后端 `http://localhost:8080`。

## 模块分工说明

| 模块 | 后端包 | 前端页面 | 前端项目 | 说明 |
|------|--------|----------|----------|------|
| 总控调度与多车协同 | `dispatch/` | `Dispatch/` | frontend-control-center | 列车调度、多车协同控制 |
| 轨道线路与信号控制 | `signal/` | `LineSignal/` | frontend-control-center | 线路管理、信号灯控制 |
| 车载系统与车辆运行仿真 | `vehicle/` | `Vehicle/` | frontend-onboard-system | 车辆模型、运行仿真 |
| 供电能源评估与分析 | `energy/` `evaluation/` | `EnergyEvaluation/` | frontend-control-center | 能耗计算、结果评估 |
| 仿真引擎 | `simulation/` | - | - | 仿真核心调度引擎 |
| 仪表盘 | - | `Dashboard/` | frontend-control-center | 系统概览与实时监控 |
