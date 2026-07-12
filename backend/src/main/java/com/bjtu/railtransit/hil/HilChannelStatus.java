package com.bjtu.railtransit.hil;

public class HilChannelStatus {
    private final String name;
    private final String endpoint;
    private boolean enabled;
    private boolean connected;
    private long framesSent;
    private long bytesSent;
    private long lastSendTime;
    private String lastError;

    public HilChannelStatus(String name, String endpoint) {
        this.name = name;
        this.endpoint = endpoint;
    }

    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public long getFramesSent() { return framesSent; }
    public void incrementFramesSent() { this.framesSent++; }
    public long getBytesSent() { return bytesSent; }
    public void addBytesSent(long bytes) { this.bytesSent += bytes; }
    public long getLastSendTime() { return lastSendTime; }
    public void setLastSendTime(long lastSendTime) { this.lastSendTime = lastSendTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}

