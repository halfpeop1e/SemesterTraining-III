package com.bjtu.railtransit.vehicle.protocol704;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Protocol704Status {

    private String trainId;
    private String host;
    private List<Integer> ports = new ArrayList<>();
    private Map<Integer, PortConnectionStatus> portStatuses;
    private boolean connected;
    private long startTime;
    private String lastRawHex;
    private int lastFrameLength;
    private Parsed704Frame lastParsedFrame;
    private MappedControlCommand lastMappedCommand;
    private RealtimeVehicleState realtimeVehicleState;
    private List<Protocol704LogEntry> recentLogs = new ArrayList<>();
    private String connectionNote;

    public String getTrainId() { return trainId; }
    public void setTrainId(String trainId) { this.trainId = trainId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public List<Integer> getPorts() { return ports; }
    public void setPorts(List<Integer> ports) { this.ports = ports; }

    public Map<Integer, PortConnectionStatus> getPortStatuses() { return portStatuses; }
    public void setPortStatuses(Map<Integer, PortConnectionStatus> portStatuses) { this.portStatuses = portStatuses; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public String getLastRawHex() { return lastRawHex; }
    public void setLastRawHex(String lastRawHex) { this.lastRawHex = lastRawHex; }

    public int getLastFrameLength() { return lastFrameLength; }
    public void setLastFrameLength(int lastFrameLength) { this.lastFrameLength = lastFrameLength; }

    public Parsed704Frame getLastParsedFrame() { return lastParsedFrame; }
    public void setLastParsedFrame(Parsed704Frame lastParsedFrame) { this.lastParsedFrame = lastParsedFrame; }

    public MappedControlCommand getLastMappedCommand() { return lastMappedCommand; }
    public void setLastMappedCommand(MappedControlCommand lastMappedCommand) { this.lastMappedCommand = lastMappedCommand; }

    public RealtimeVehicleState getRealtimeVehicleState() { return realtimeVehicleState; }
    public void setRealtimeVehicleState(RealtimeVehicleState realtimeVehicleState) { this.realtimeVehicleState = realtimeVehicleState; }

    public List<Protocol704LogEntry> getRecentLogs() { return recentLogs; }
    public void setRecentLogs(List<Protocol704LogEntry> recentLogs) { this.recentLogs = recentLogs; }

    public String getConnectionNote() { return connectionNote; }
    public void setConnectionNote(String connectionNote) { this.connectionNote = connectionNote; }
}