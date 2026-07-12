package com.bjtu.railtransit.vehicle.protocol704;

import java.time.LocalDateTime;

// local-v1, not validated with real hardware
public class HmiTractionCutRequest {
    private String trainId;
    private boolean[] carCutMask = new boolean[6];
    private LocalDateTime timestamp;

    public HmiTractionCutRequest() {
        this.timestamp = LocalDateTime.now();
    }

    public HmiTractionCutRequest(String trainId, boolean[] carCutMask, LocalDateTime timestamp) {
        this.trainId = trainId;
        this.carCutMask = carCutMask != null ? carCutMask.clone() : new boolean[6];
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }

    public boolean[] getCarCutMask() { return carCutMask; }
    public void setCarCutMask(boolean[] carCutMask) { this.carCutMask = carCutMask != null ? carCutMask.clone() : new boolean[6]; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
