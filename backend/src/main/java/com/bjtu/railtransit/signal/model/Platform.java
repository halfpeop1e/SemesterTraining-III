package com.bjtu.railtransit.signal.model;

import java.util.List;

public class Platform {
    private int id;
    private String chainage;                 // 原始 "K0+313.000"
    private double centerM;                  // 解析后绝对里程 m
    private int segId;
    private int dir;                         // 逻辑方向 0x55/0xaa
    private List<Integer> triggerAxleSectionIds;
    private boolean clearPass;               // 清客标志

    public Platform() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getChainage() { return chainage; }
    public void setChainage(String chainage) { this.chainage = chainage; }
    public double getCenterM() { return centerM; }
    public void setCenterM(double centerM) { this.centerM = centerM; }
    public int getSegId() { return segId; }
    public void setSegId(int segId) { this.segId = segId; }
    public int getDir() { return dir; }
    public void setDir(int dir) { this.dir = dir; }
    public List<Integer> getTriggerAxleSectionIds() { return triggerAxleSectionIds; }
    public void setTriggerAxleSectionIds(List<Integer> triggerAxleSectionIds) { this.triggerAxleSectionIds = triggerAxleSectionIds; }
    public boolean isClearPass() { return clearPass; }
    public void setClearPass(boolean clearPass) { this.clearPass = clearPass; }
}
