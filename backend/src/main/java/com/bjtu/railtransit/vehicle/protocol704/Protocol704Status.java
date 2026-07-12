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
    /** Socket-level state is separate from whether a valid local-v1 frame has arrived. */
    private boolean receivedValidFrame;
    private long lastValidFrameTime;
    private Protocol704CommandLifecycle lastCommandLifecycle;
    private String activeBinding;
    private boolean simulationReady;
    private String simulationReadiness;
    private long simulationContextUpdatedAt;
    private boolean staleInputFailSafeTriggered;
    private long staleInputFailSafeTime;

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
    public boolean isReceivedValidFrame() { return receivedValidFrame; }
    public void setReceivedValidFrame(boolean receivedValidFrame) { this.receivedValidFrame = receivedValidFrame; }
    public long getLastValidFrameTime() { return lastValidFrameTime; }
    public void setLastValidFrameTime(long lastValidFrameTime) { this.lastValidFrameTime = lastValidFrameTime; }
    public Protocol704CommandLifecycle getLastCommandLifecycle() { return lastCommandLifecycle; }
    public void setLastCommandLifecycle(Protocol704CommandLifecycle lastCommandLifecycle) { this.lastCommandLifecycle = lastCommandLifecycle; }
    public String getActiveBinding() { return activeBinding; }
    public void setActiveBinding(String activeBinding) { this.activeBinding = activeBinding; }
    public boolean isSimulationReady() { return simulationReady; }
    public void setSimulationReady(boolean simulationReady) { this.simulationReady = simulationReady; }
    public String getSimulationReadiness() { return simulationReadiness; }
    public void setSimulationReadiness(String simulationReadiness) { this.simulationReadiness = simulationReadiness; }
    public long getSimulationContextUpdatedAt() { return simulationContextUpdatedAt; }
    public void setSimulationContextUpdatedAt(long simulationContextUpdatedAt) {
        this.simulationContextUpdatedAt = simulationContextUpdatedAt;
    }
    public boolean isStaleInputFailSafeTriggered() { return staleInputFailSafeTriggered; }
    public void setStaleInputFailSafeTriggered(boolean value) { this.staleInputFailSafeTriggered = value; }
    public long getStaleInputFailSafeTime() { return staleInputFailSafeTime; }
    public void setStaleInputFailSafeTime(long value) { this.staleInputFailSafeTime = value; }
}
