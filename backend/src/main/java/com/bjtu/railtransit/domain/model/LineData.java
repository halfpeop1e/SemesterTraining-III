package com.bjtu.railtransit.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 线路完整数据汇聚模型，包含所有30个Sheet的数据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LineData {

    // ---- 轨道基础设施 ----
    private List<LongShortChain> longShortChains;
    private List<TrackPoint> trackPoints;
    private List<AxleCounterSection> axleCounterSections;
    private List<PhysicalSection> physicalSections;
    private List<LogicalSection> logicalSections;
    private List<AxleCounter> axleCounters;

    // ---- 信号系统 ----
    private List<Signal> signals;
    private List<Balise> balises;
    private List<Route> routes;
    private List<ProtectionSection> protectionSections;
    private List<ApproachSection> pointApproachSections;
    private List<ApproachSection> cbtcApproachSections;
    private List<TriggerSection> pointTriggerSections;
    private List<TriggerSection> cbtcTriggerSections;

    // ---- 轨道设备 ----
    private List<Switch> switches;
    private List<Bumper> bumpers;
    private List<SpksSwitch> spksSwitches;

    // ---- 车站设备 ----
    private List<PlatformDoor> platformDoors;
    private List<EmergencyButton> emergencyButtons;

    // ---- 土建/线路属性 ----
    private List<Gradient> gradients;
    private List<StaticSpeedLimit> staticSpeedLimits;
    private List<Tunnel> tunnels;
    private List<FloodGate> floodGates;
    private List<DepotDoor> depotDoors;
    private List<CollisionZone> collisionZones;
    private List<UniformSpeedLimit> uniformSpeedLimits;
    private List<UniformGradient> uniformGradients;
    private List<ZoneProperty> zoneProperties;
    private List<DeviceIdMapping> deviceIdMappings;
    private List<VirtualPoint> virtualPoints;

    // Getters and Setters

    public List<LongShortChain> getLongShortChains() { return longShortChains; }
    public void setLongShortChains(List<LongShortChain> v) { this.longShortChains = v; }

    public List<TrackPoint> getTrackPoints() { return trackPoints; }
    public void setTrackPoints(List<TrackPoint> v) { this.trackPoints = v; }

    public List<AxleCounterSection> getAxleCounterSections() { return axleCounterSections; }
    public void setAxleCounterSections(List<AxleCounterSection> v) { this.axleCounterSections = v; }

    public List<PhysicalSection> getPhysicalSections() { return physicalSections; }
    public void setPhysicalSections(List<PhysicalSection> v) { this.physicalSections = v; }

    public List<LogicalSection> getLogicalSections() { return logicalSections; }
    public void setLogicalSections(List<LogicalSection> v) { this.logicalSections = v; }

    public List<AxleCounter> getAxleCounters() { return axleCounters; }
    public void setAxleCounters(List<AxleCounter> v) { this.axleCounters = v; }

    public List<Signal> getSignals() { return signals; }
    public void setSignals(List<Signal> v) { this.signals = v; }

    public List<Balise> getBalises() { return balises; }
    public void setBalises(List<Balise> v) { this.balises = v; }

    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> v) { this.routes = v; }

    public List<ProtectionSection> getProtectionSections() { return protectionSections; }
    public void setProtectionSections(List<ProtectionSection> v) { this.protectionSections = v; }

    public List<ApproachSection> getPointApproachSections() { return pointApproachSections; }
    public void setPointApproachSections(List<ApproachSection> v) { this.pointApproachSections = v; }

    public List<ApproachSection> getCbtcApproachSections() { return cbtcApproachSections; }
    public void setCbtcApproachSections(List<ApproachSection> v) { this.cbtcApproachSections = v; }

    public List<TriggerSection> getPointTriggerSections() { return pointTriggerSections; }
    public void setPointTriggerSections(List<TriggerSection> v) { this.pointTriggerSections = v; }

    public List<TriggerSection> getCbtcTriggerSections() { return cbtcTriggerSections; }
    public void setCbtcTriggerSections(List<TriggerSection> v) { this.cbtcTriggerSections = v; }

    public List<Switch> getSwitches() { return switches; }
    public void setSwitches(List<Switch> v) { this.switches = v; }

    public List<Bumper> getBumpers() { return bumpers; }
    public void setBumpers(List<Bumper> v) { this.bumpers = v; }

    public List<SpksSwitch> getSpksSwitches() { return spksSwitches; }
    public void setSpksSwitches(List<SpksSwitch> v) { this.spksSwitches = v; }

    public List<PlatformDoor> getPlatformDoors() { return platformDoors; }
    public void setPlatformDoors(List<PlatformDoor> v) { this.platformDoors = v; }

    public List<EmergencyButton> getEmergencyButtons() { return emergencyButtons; }
    public void setEmergencyButtons(List<EmergencyButton> v) { this.emergencyButtons = v; }

    public List<Gradient> getGradients() { return gradients; }
    public void setGradients(List<Gradient> v) { this.gradients = v; }

    public List<StaticSpeedLimit> getStaticSpeedLimits() { return staticSpeedLimits; }
    public void setStaticSpeedLimits(List<StaticSpeedLimit> v) { this.staticSpeedLimits = v; }

    public List<Tunnel> getTunnels() { return tunnels; }
    public void setTunnels(List<Tunnel> v) { this.tunnels = v; }

    public List<FloodGate> getFloodGates() { return floodGates; }
    public void setFloodGates(List<FloodGate> v) { this.floodGates = v; }

    public List<DepotDoor> getDepotDoors() { return depotDoors; }
    public void setDepotDoors(List<DepotDoor> v) { this.depotDoors = v; }

    public List<CollisionZone> getCollisionZones() { return collisionZones; }
    public void setCollisionZones(List<CollisionZone> v) { this.collisionZones = v; }

    public List<UniformSpeedLimit> getUniformSpeedLimits() { return uniformSpeedLimits; }
    public void setUniformSpeedLimits(List<UniformSpeedLimit> v) { this.uniformSpeedLimits = v; }

    public List<UniformGradient> getUniformGradients() { return uniformGradients; }
    public void setUniformGradients(List<UniformGradient> v) { this.uniformGradients = v; }

    public List<ZoneProperty> getZoneProperties() { return zoneProperties; }
    public void setZoneProperties(List<ZoneProperty> v) { this.zoneProperties = v; }

    public List<DeviceIdMapping> getDeviceIdMappings() { return deviceIdMappings; }
    public void setDeviceIdMappings(List<DeviceIdMapping> v) { this.deviceIdMappings = v; }

    public List<VirtualPoint> getVirtualPoints() { return virtualPoints; }
    public void setVirtualPoints(List<VirtualPoint> v) { this.virtualPoints = v; }

    // ---- Inner Model Classes ----

    // === 长短链 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LongShortChain {
        private int 编号;
        private double 左值;
        private double 右值;
        private int 所处线路上下行;

        public int get编号() { return 编号; }
        public void set编号(int v) { this.编号 = v; }
        public double get左值() { return 左值; }
        public void set左值(double v) { this.左值 = v; }
        public double get右值() { return 右值; }
        public void set右值(double v) { this.右值 = v; }
        public int get所处线路上下行() { return 所处线路上下行; }
        public void set所处线路上下行(int v) { this.所处线路上下行 = v; }
    }

    // === 点表 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TrackPoint {
        private int 索引编号;
        private String 名称;
        @JsonProperty("所属线路编号")
        private int 所属线路编号;
        @JsonProperty("所处公里标(cm)")
        private double 所处公里标;
        private double 经度;
        private double 纬度;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get所属线路编号() { return 所属线路编号; }
        public void set所属线路编号(int v) { this.所属线路编号 = v; }
        public double get所处公里标() { return 所处公里标; }
        public void set所处公里标(double v) { this.所处公里标 = v; }
        public double get经度() { return 经度; }
        public void set经度(double v) { this.经度 = v; }
        public double get纬度() { return 纬度; }
        public void set纬度(double v) { this.纬度 = v; }
    }

    // === 计轴区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AxleCounterSection {
        private int 索引编号;
        @JsonProperty("名称")
        private String name;
        @JsonProperty("计轴区段名称")
        private String 计轴区段名称;
        @JsonProperty("起点Seg编号")
        private int 起点Seg编号;
        @JsonProperty("起点所处Seg偏移量")
        private double 起点所处Seg偏移量;
        @JsonProperty("终点Seg编号")
        private int 终点Seg编号;
        @JsonProperty("终点所处Seg偏移量")
        private double 终点所处Seg偏移量;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String get计轴区段名称() { return 计轴区段名称; }
        public void set计轴区段名称(String v) { this.计轴区段名称 = v; }
        public int get起点Seg编号() { return 起点Seg编号; }
        public void set起点Seg编号(int v) { this.起点Seg编号 = v; }
        public double get起点所处Seg偏移量() { return 起点所处Seg偏移量; }
        public void set起点所处Seg偏移量(double v) { this.起点所处Seg偏移量 = v; }
        public int get终点Seg编号() { return 终点Seg编号; }
        public void set终点Seg编号(int v) { this.终点Seg编号 = v; }
        public double get终点所处Seg偏移量() { return 终点所处Seg偏移量; }
        public void set终点所处Seg偏移量(double v) { this.终点所处Seg偏移量 = v; }
    }

    // === 物理区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PhysicalSection {
        private int 索引编号;
        private String 名称;
        private int 物理区段所对应计轴区段索引;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get物理区段所对应计轴区段索引() { return 物理区段所对应计轴区段索引; }
        public void set物理区段所对应计轴区段索引(int v) { this.物理区段所对应计轴区段索引 = v; }
    }

    // === 逻辑区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LogicalSection {
        private int 索引编号;
        private String 名称;
        private int 起点所处Seg编号;
        private double 起点所处Seg偏移量;
        private int 终点所处Seg编号;
        private double 终点所处Seg偏移量;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get起点所处Seg编号() { return 起点所处Seg编号; }
        public void set起点所处Seg编号(int v) { this.起点所处Seg编号 = v; }
        public double get起点所处Seg偏移量() { return 起点所处Seg偏移量; }
        public void set起点所处Seg偏移量(double v) { this.起点所处Seg偏移量 = v; }
        public int get终点所处Seg编号() { return 终点所处Seg编号; }
        public void set终点所处Seg编号(int v) { this.终点所处Seg编号 = v; }
        public double get终点所处Seg偏移量() { return 终点所处Seg偏移量; }
        public void set终点所处Seg偏移量(double v) { this.终点所处Seg偏移量 = v; }
    }

    // === 计轴器 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AxleCounter {
        private int 索引编号;
        @JsonProperty("计轴器名称")
        private String 计轴器名称;
        @JsonProperty("所处公里标（cm）")
        private double 所处公里标;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get计轴器名称() { return 计轴器名称; }
        public void set计轴器名称(String v) { this.计轴器名称 = v; }
        public double get所处公里标() { return 所处公里标; }
        public void set所处公里标(double v) { this.所处公里标 = v; }
    }

    // === 信号机 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Signal {
        private int 索引编号;
        private String 名称;
        private int 类型;
        private String 属性;
        private int 所处Seg编号;
        @JsonProperty("所处Seg偏移量（cm）")
        private double 所处Seg偏移量;
        @JsonProperty("防护方向")
        private String 防护方向;
        private String 灯列信息;
        private long 互联互通编号;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get类型() { return 类型; }
        public void set类型(int v) { this.类型 = v; }
        public String get属性() { return 属性; }
        public void set属性(String v) { this.属性 = v; }
        public int get所处Seg编号() { return 所处Seg编号; }
        public void set所处Seg编号(int v) { this.所处Seg编号 = v; }
        public double get所处Seg偏移量() { return 所处Seg偏移量; }
        public void set所处Seg偏移量(double v) { this.所处Seg偏移量 = v; }
        public String get防护方向() { return 防护方向; }
        public void set防护方向(String v) { this.防护方向 = v; }
        public String get灯列信息() { return 灯列信息; }
        public void set灯列信息(String v) { this.灯列信息 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
    }

    // === 应答器 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Balise {
        private int 索引编号;
        private String ID;
        private String 名称;
        @JsonProperty("所处seg编号")
        private int 所处seg编号;
        @JsonProperty("所处Seg偏移量（cm）")
        private double 所处Seg偏移量;
        private long 互联互通编号;
        private String 应答器属性;
        private int 关联信号机编号;
        private int 应答器作用方向;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String getID() { return ID; }
        public void setID(String v) { this.ID = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get所处seg编号() { return 所处seg编号; }
        public void set所处seg编号(int v) { this.所处seg编号 = v; }
        public double get所处Seg偏移量() { return 所处Seg偏移量; }
        public void set所处Seg偏移量(double v) { this.所处Seg偏移量 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public String get应答器属性() { return 应答器属性; }
        public void set应答器属性(String v) { this.应答器属性 = v; }
        public int get关联信号机编号() { return 关联信号机编号; }
        public void set关联信号机编号(int v) { this.关联信号机编号 = v; }
        public int get应答器作用方向() { return 应答器作用方向; }
        public void set应答器作用方向(int v) { this.应答器作用方向 = v; }
    }

    // === 进路 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Route {
        private int 索引编号;
        private String 进路名称;
        private String 进路性质;
        private int 始端信号机编号;
        private int 终端信号机编号;
        private int 包含计轴区段数目;
        private List<Integer> 计轴区段IDs;
        private int 保护区段数目;
        private List<Integer> 保护区段IDs;
        private int 点式接近区段数目;
        private List<Integer> 点式接近区段IDs;
        private int CBTC接近区段数目;
        private List<Integer> CBTC接近区段IDs;
        private int 点式触发区段数量;
        private List<Integer> 点式触发区段IDs;
        private int CBTC触发区段数量;
        private List<Integer> CBTC触发区段IDs;
        private int 所属CI区域编号;
        private int 联锁表号码;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get进路名称() { return 进路名称; }
        public void set进路名称(String v) { this.进路名称 = v; }
        public String get进路性质() { return 进路性质; }
        public void set进路性质(String v) { this.进路性质 = v; }
        public int get始端信号机编号() { return 始端信号机编号; }
        public void set始端信号机编号(int v) { this.始端信号机编号 = v; }
        public int get终端信号机编号() { return 终端信号机编号; }
        public void set终端信号机编号(int v) { this.终端信号机编号 = v; }
        public int get包含计轴区段数目() { return 包含计轴区段数目; }
        public void set包含计轴区段数目(int v) { this.包含计轴区段数目 = v; }
        public List<Integer> get计轴区段IDs() { return 计轴区段IDs; }
        public void set计轴区段IDs(List<Integer> v) { this.计轴区段IDs = v; }
        public int get保护区段数目() { return 保护区段数目; }
        public void set保护区段数目(int v) { this.保护区段数目 = v; }
        public List<Integer> get保护区段IDs() { return 保护区段IDs; }
        public void set保护区段IDs(List<Integer> v) { this.保护区段IDs = v; }
        public int get点式接近区段数目() { return 点式接近区段数目; }
        public void set点式接近区段数目(int v) { this.点式接近区段数目 = v; }
        public List<Integer> get点式接近区段IDs() { return 点式接近区段IDs; }
        public void set点式接近区段IDs(List<Integer> v) { this.点式接近区段IDs = v; }
        public int getCBTC接近区段数目() { return CBTC接近区段数目; }
        public void setCBTC接近区段数目(int v) { this.CBTC接近区段数目 = v; }
        public List<Integer> getCBTC接近区段IDs() { return CBTC接近区段IDs; }
        public void setCBTC接近区段IDs(List<Integer> v) { this.CBTC接近区段IDs = v; }
        public int get点式触发区段数量() { return 点式触发区段数量; }
        public void set点式触发区段数量(int v) { this.点式触发区段数量 = v; }
        public List<Integer> get点式触发区段IDs() { return 点式触发区段IDs; }
        public void set点式触发区段IDs(List<Integer> v) { this.点式触发区段IDs = v; }
        public int getCBTC触发区段数量() { return CBTC触发区段数量; }
        public void setCBTC触发区段数量(int v) { this.CBTC触发区段数量 = v; }
        public List<Integer> getCBTC触发区段IDs() { return CBTC触发区段IDs; }
        public void setCBTC触发区段IDs(List<Integer> v) { this.CBTC触发区段IDs = v; }
        public int get所属CI区域编号() { return 所属CI区域编号; }
        public void set所属CI区域编号(int v) { this.所属CI区域编号 = v; }
        public int get联锁表号码() { return 联锁表号码; }
        public void set联锁表号码(int v) { this.联锁表号码 = v; }
    }

    // === 保护区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProtectionSection {
        private int 索引编号;
        private int 包含的计轴区段数目;
        private List<Integer> 计轴区段IDs;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get包含的计轴区段数目() { return 包含的计轴区段数目; }
        public void set包含的计轴区段数目(int v) { this.包含的计轴区段数目 = v; }
        public List<Integer> get计轴区段IDs() { return 计轴区段IDs; }
        public void set计轴区段IDs(List<Integer> v) { this.计轴区段IDs = v; }
    }

    // === 接近区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApproachSection {
        private int 索引编号;
        private int 包含区段数目;
        private List<Integer> 区段IDs;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get包含区段数目() { return 包含区段数目; }
        public void set包含区段数目(int v) { this.包含区段数目 = v; }
        public List<Integer> get区段IDs() { return 区段IDs; }
        public void set区段IDs(List<Integer> v) { this.区段IDs = v; }
    }

    // === 触发区段 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TriggerSection {
        private int ID;
        private int 区段数量;
        private List<Integer> 区段IDs;

        public int getID() { return ID; }
        public void setID(int v) { this.ID = v; }
        public int get区段数量() { return 区段数量; }
        public void set区段数量(int v) { this.区段数量 = v; }
        public List<Integer> get区段IDs() { return 区段IDs; }
        public void set区段IDs(List<Integer> v) { this.区段IDs = v; }
    }

    // === 道岔 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Switch {
        private int 索引编号;
        private String 名称;
        private int 联动道岔编号;
        private int 方向;
        private int 定位SegID;
        private int 反位SegID;
        private int 汇合SegID;
        private int 侧向静态限速;
        private long 互联互通编号;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get联动道岔编号() { return 联动道岔编号; }
        public void set联动道岔编号(int v) { this.联动道岔编号 = v; }
        public int get方向() { return 方向; }
        public void set方向(int v) { this.方向 = v; }
        public int get定位SegID() { return 定位SegID; }
        public void set定位SegID(int v) { this.定位SegID = v; }
        public int get反位SegID() { return 反位SegID; }
        public void set反位SegID(int v) { this.反位SegID = v; }
        public int get汇合SegID() { return 汇合SegID; }
        public void set汇合SegID(int v) { this.汇合SegID = v; }
        public int get侧向静态限速() { return 侧向静态限速; }
        public void set侧向静态限速(int v) { this.侧向静态限速 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
    }

    // === 车档 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bumper {
        private int 索引编号;
        private int 所属Seg编号;
        private double 所属Seg偏移量;
        private int 车档类型;
        private long 互联互通编号;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get所属Seg编号() { return 所属Seg编号; }
        public void set所属Seg编号(int v) { this.所属Seg编号 = v; }
        public double get所属Seg偏移量() { return 所属Seg偏移量; }
        public void set所属Seg偏移量(double v) { this.所属Seg偏移量 = v; }
        public int get车档类型() { return 车档类型; }
        public void set车档类型(int v) { this.车档类型 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
    }

    // === SPKS开关 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpksSwitch {
        private int 索引编号;
        private String 区域描述;
        private int 所处Seg编号;
        private double 所处Seg偏移量;
        private long 互联互通编号;
        private int CI;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get区域描述() { return 区域描述; }
        public void set区域描述(String v) { this.区域描述 = v; }
        public int get所处Seg编号() { return 所处Seg编号; }
        public void set所处Seg编号(int v) { this.所处Seg编号 = v; }
        public double get所处Seg偏移量() { return 所处Seg偏移量; }
        public void set所处Seg偏移量(double v) { this.所处Seg偏移量 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public int getCI() { return CI; }
        public void setCI(int v) { this.CI = v; }
    }

    // === 屏蔽门 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlatformDoor {
        private int 索引编号;
        private String 名称;
        private int 所属站台编号;
        private long 互联互通编号;
        private int CI;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get所属站台编号() { return 所属站台编号; }
        public void set所属站台编号(int v) { this.所属站台编号 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public int getCI() { return CI; }
        public void setCI(int v) { this.CI = v; }
    }

    // === 紧急按钮 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmergencyButton {
        private int 索引编号;
        private String 名称;
        private int 所属站台编号;
        private long 互联互通编号;
        private int CI;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get名称() { return 名称; }
        public void set名称(String v) { this.名称 = v; }
        public int get所属站台编号() { return 所属站台编号; }
        public void set所属站台编号(int v) { this.所属站台编号 = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public int getCI() { return CI; }
        public void setCI(int v) { this.CI = v; }
    }

    // === 坡度 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Gradient {
        private int 索引编号;
        private int 坡度起点所处seg编号;
        private double 坡度起点所处seg偏移量;
        private int 坡度终点所处seg编号;
        private double 坡度终点所处seg偏移量;
        private int 坡度值;
        @JsonProperty("坡段相对于线路逻辑方向的倾斜方向")
        private String 倾斜方向;
        private int 竖曲线半径;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get坡度起点所处seg编号() { return 坡度起点所处seg编号; }
        public void set坡度起点所处seg编号(int v) { this.坡度起点所处seg编号 = v; }
        public double get坡度起点所处seg偏移量() { return 坡度起点所处seg偏移量; }
        public void set坡度起点所处seg偏移量(double v) { this.坡度起点所处seg偏移量 = v; }
        public int get坡度终点所处seg编号() { return 坡度终点所处seg编号; }
        public void set坡度终点所处seg编号(int v) { this.坡度终点所处seg编号 = v; }
        public double get坡度终点所处seg偏移量() { return 坡度终点所处seg偏移量; }
        public void set坡度终点所处seg偏移量(double v) { this.坡度终点所处seg偏移量 = v; }
        public int get坡度值() { return 坡度值; }
        public void set坡度值(int v) { this.坡度值 = v; }
        public String get倾斜方向() { return 倾斜方向; }
        public void set倾斜方向(String v) { this.倾斜方向 = v; }
        public int get竖曲线半径() { return 竖曲线半径; }
        public void set竖曲线半径(int v) { this.竖曲线半径 = v; }
    }

    // === 静态限速 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StaticSpeedLimit {
        private int 索引编号;
        @JsonProperty("限速区段所处seg编号")
        private int 限速区段所处seg编号;
        @JsonProperty("起点所处seg偏移量")
        private double 起点所处seg偏移量;
        @JsonProperty("终点所处seg偏移量")
        private double 终点所处seg偏移量;
        @JsonProperty("关联道岔编号")
        private int 关联道岔编号;
        @JsonProperty("限速值")
        private int 限速值;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get限速区段所处seg编号() { return 限速区段所处seg编号; }
        public void set限速区段所处seg编号(int v) { this.限速区段所处seg编号 = v; }
        public double get起点所处seg偏移量() { return 起点所处seg偏移量; }
        public void set起点所处seg偏移量(double v) { this.起点所处seg偏移量 = v; }
        public double get终点所处seg偏移量() { return 终点所处seg偏移量; }
        public void set终点所处seg偏移量(double v) { this.终点所处seg偏移量 = v; }
        public int get关联道岔编号() { return 关联道岔编号; }
        public void set关联道岔编号(int v) { this.关联道岔编号 = v; }
        public int get限速值() { return 限速值; }
        public void set限速值(int v) { this.限速值 = v; }
    }

    // === 隧道 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tunnel {
        private int 索引编号;
        @JsonProperty("隧道所处Seg编号")
        private int 隧道所处Seg编号;
        @JsonProperty("起点所处Seg偏移量")
        private double 起点所处Seg偏移量;
        @JsonProperty("终点所处Seg偏移量")
        private double 终点所处Seg偏移量;
        @JsonProperty("隧道长度")
        private double 隧道长度;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get隧道所处Seg编号() { return 隧道所处Seg编号; }
        public void set隧道所处Seg编号(int v) { this.隧道所处Seg编号 = v; }
        public double get起点所处Seg偏移量() { return 起点所处Seg偏移量; }
        public void set起点所处Seg偏移量(double v) { this.起点所处Seg偏移量 = v; }
        public double get终点所处Seg偏移量() { return 终点所处Seg偏移量; }
        public void set终点所处Seg偏移量(double v) { this.终点所处Seg偏移量 = v; }
        public double get隧道长度() { return 隧道长度; }
        public void set隧道长度(double v) { this.隧道长度 = v; }
    }

    // === 防淹门 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FloodGate {
        private int 索引编号;
        private int 所处Seg编号;
        private double 所处Seg偏移量;
        private int 相邻防护门编号;
        private int 防护信号机数量;
        private List<Integer> 防护信号机IDs;
        private int 防护区域计轴区段数量;
        private List<Integer> 防护区域计轴区段IDs;
        private long 互联互通编号;
        private double 防淹门区域长度;
        private int 所属CI;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get所处Seg编号() { return 所处Seg编号; }
        public void set所处Seg编号(int v) { this.所处Seg编号 = v; }
        public double get所处Seg偏移量() { return 所处Seg偏移量; }
        public void set所处Seg偏移量(double v) { this.所处Seg偏移量 = v; }
        public int get相邻防护门编号() { return 相邻防护门编号; }
        public void set相邻防护门编号(int v) { this.相邻防护门编号 = v; }
        public int get防护信号机数量() { return 防护信号机数量; }
        public void set防护信号机数量(int v) { this.防护信号机数量 = v; }
        public List<Integer> get防护信号机IDs() { return 防护信号机IDs; }
        public void set防护信号机IDs(List<Integer> v) { this.防护信号机IDs = v; }
        public int get防护区域计轴区段数量() { return 防护区域计轴区段数量; }
        public void set防护区域计轴区段数量(int v) { this.防护区域计轴区段数量 = v; }
        public List<Integer> get防护区域计轴区段IDs() { return 防护区域计轴区段IDs; }
        public void set防护区域计轴区段IDs(List<Integer> v) { this.防护区域计轴区段IDs = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public double get防淹门区域长度() { return 防淹门区域长度; }
        public void set防淹门区域长度(double v) { this.防淹门区域长度 = v; }
        public int get所属CI() { return 所属CI; }
        public void set所属CI(int v) { this.所属CI = v; }
    }

    // === 车库门 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DepotDoor {
        private int 索引编号;
        private int 所处Seg编号;
        private double 所处Seg偏移量;
        private double 车库门防护区段长度;
        private int 车库门属性;
        private int 入库进路数量;
        private List<Integer> 入库进路IDs;
        private int 出库进路数量;
        private List<Integer> 出库进路IDs;
        private int 车库门对应SPKS开关数量;
        private List<Integer> SPKS开关IDs;
        private int 对应防护信号机数量;
        private List<Integer> 防护信号机IDs;
        private int 对应相邻计轴区段数量;
        private List<Integer> 对应相邻计轴区段IDs;
        private long 互联互通编号;
        private int CI;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get所处Seg编号() { return 所处Seg编号; }
        public void set所处Seg编号(int v) { this.所处Seg编号 = v; }
        public double get所处Seg偏移量() { return 所处Seg偏移量; }
        public void set所处Seg偏移量(double v) { this.所处Seg偏移量 = v; }
        public double get车库门防护区段长度() { return 车库门防护区段长度; }
        public void set车库门防护区段长度(double v) { this.车库门防护区段长度 = v; }
        public int get车库门属性() { return 车库门属性; }
        public void set车库门属性(int v) { this.车库门属性 = v; }
        public int get入库进路数量() { return 入库进路数量; }
        public void set入库进路数量(int v) { this.入库进路数量 = v; }
        public List<Integer> get入库进路IDs() { return 入库进路IDs; }
        public void set入库进路IDs(List<Integer> v) { this.入库进路IDs = v; }
        public int get出库进路数量() { return 出库进路数量; }
        public void set出库进路数量(int v) { this.出库进路数量 = v; }
        public List<Integer> get出库进路IDs() { return 出库进路IDs; }
        public void set出库进路IDs(List<Integer> v) { this.出库进路IDs = v; }
        public int getSPKS开关数量() { return 车库门对应SPKS开关数量; }
        public void setSPKS开关数量(int v) { this.车库门对应SPKS开关数量 = v; }
        public List<Integer> getSPKS开关IDs() { return SPKS开关IDs; }
        public void setSPKS开关IDs(List<Integer> v) { this.SPKS开关IDs = v; }
        public int get对应防护信号机数量() { return 对应防护信号机数量; }
        public void set对应防护信号机数量(int v) { this.对应防护信号机数量 = v; }
        public List<Integer> get防护信号机IDs() { return 防护信号机IDs; }
        public void set防护信号机IDs(List<Integer> v) { this.防护信号机IDs = v; }
        public int get对应相邻计轴区段数量() { return 对应相邻计轴区段数量; }
        public void set对应相邻计轴区段数量(int v) { this.对应相邻计轴区段数量 = v; }
        public List<Integer> get对应相邻计轴区段IDs() { return 对应相邻计轴区段IDs; }
        public void set对应相邻计轴区段IDs(List<Integer> v) { this.对应相邻计轴区段IDs = v; }
        public long get互联互通编号() { return 互联互通编号; }
        public void set互联互通编号(long v) { this.互联互通编号 = v; }
        public int getCI() { return CI; }
        public void setCI(int v) { this.CI = v; }
    }

    // === 碰撞区域 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CollisionZone {
        private int 索引编号;
        private int 点类型;
        private int 车档编号;
        private int 碰撞点限速;
        private int 逻辑区段数量;
        private List<Integer> 逻辑区段IDs;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get点类型() { return 点类型; }
        public void set点类型(int v) { this.点类型 = v; }
        public int get车档编号() { return 车档编号; }
        public void set车档编号(int v) { this.车档编号 = v; }
        public int get碰撞点限速() { return 碰撞点限速; }
        public void set碰撞点限速(int v) { this.碰撞点限速 = v; }
        public int get逻辑区段数量() { return 逻辑区段数量; }
        public void set逻辑区段数量(int v) { this.逻辑区段数量 = v; }
        public List<Integer> get逻辑区段IDs() { return 逻辑区段IDs; }
        public void set逻辑区段IDs(List<Integer> v) { this.逻辑区段IDs = v; }
    }

    // === 线路统一限速 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UniformSpeedLimit {
        private int 索引编号;
        private int 限速值;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get限速值() { return 限速值; }
        public void set限速值(int v) { this.限速值 = v; }
    }

    // === 线路统一坡度 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UniformGradient {
        private int 索引编号;
        private int 坡度值;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public int get坡度值() { return 坡度值; }
        public void set坡度值(int v) { this.坡度值 = v; }
    }

    // === 区域属性 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZoneProperty {
        private int 索引编号;
        private String 区域类型;
        private int 区域ID;
        private String 区域属性;
        private long 管辖区电子地图数据校验信息;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get区域类型() { return 区域类型; }
        public void set区域类型(String v) { this.区域类型 = v; }
        public int get区域ID() { return 区域ID; }
        public void set区域ID(int v) { this.区域ID = v; }
        public String get区域属性() { return 区域属性; }
        public void set区域属性(String v) { this.区域属性 = v; }
        public long get管辖区电子地图数据校验信息() { return 管辖区电子地图数据校验信息; }
        public void set管辖区电子地图数据校验信息(long v) { this.管辖区电子地图数据校验信息 = v; }
    }

    // === 设备编号映射 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceIdMapping {
        private int 索引编号;
        private String 区域类型;
        private int 区域ID;
        private long 对应互联互通ID;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get区域类型() { return 区域类型; }
        public void set区域类型(String v) { this.区域类型 = v; }
        public int get区域ID() { return 区域ID; }
        public void set区域ID(int v) { this.区域ID = v; }
        public long get对应互联互通ID() { return 对应互联互通ID; }
        public void set对应互联互通ID(long v) { this.对应互联互通ID = v; }
    }

    // === 虚拟点 ===
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VirtualPoint {
        private int 索引编号;
        private String 虚拟点名称;

        public int get索引编号() { return 索引编号; }
        public void set索引编号(int v) { this.索引编号 = v; }
        public String get虚拟点名称() { return 虚拟点名称; }
        public void set虚拟点名称(String v) { this.虚拟点名称 = v; }
    }
}
