package com.bjtu.railtransit.vehicle.protocol704;

public class PortConnectionStatus {

    private int port;
    private boolean connected;
    private boolean connecting;
    private long lastConnectSuccessTime;
    private long lastDisconnectTime;
    private long lastReceiveTime;
    private long lastFrameIntervalMs;
    private long lastFrameLength;
    private long bytesReceived;
    private long bytesSent;
    private long frameCount;
    private long lastSendTime;
    private String lastError;
    private String lastOutputHex;
    private long lastOutputTime;
    private long outputFrameCount;
    private long outputErrorCount;
    private String lastOutputError;

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public boolean isConnecting() { return connecting; }
    public void setConnecting(boolean connecting) { this.connecting = connecting; }

    public long getLastConnectSuccessTime() { return lastConnectSuccessTime; }
    public void setLastConnectSuccessTime(long lastConnectSuccessTime) { this.lastConnectSuccessTime = lastConnectSuccessTime; }

    public long getLastDisconnectTime() { return lastDisconnectTime; }
    public void setLastDisconnectTime(long lastDisconnectTime) { this.lastDisconnectTime = lastDisconnectTime; }

    public long getLastReceiveTime() { return lastReceiveTime; }
    public void setLastReceiveTime(long lastReceiveTime) { this.lastReceiveTime = lastReceiveTime; }

    public long getLastFrameIntervalMs() { return lastFrameIntervalMs; }
    public void setLastFrameIntervalMs(long lastFrameIntervalMs) { this.lastFrameIntervalMs = lastFrameIntervalMs; }

    public long getLastFrameLength() { return lastFrameLength; }
    public void setLastFrameLength(long lastFrameLength) { this.lastFrameLength = lastFrameLength; }

    public long getBytesReceived() { return bytesReceived; }
    public void setBytesReceived(long bytesReceived) { this.bytesReceived = bytesReceived; }
    public long getBytesSent() { return bytesSent; }
    public void setBytesSent(long bytesSent) { this.bytesSent = bytesSent; }
    public long getLastSendTime() { return lastSendTime; }
    public void setLastSendTime(long lastSendTime) { this.lastSendTime = lastSendTime; }

    public long getFrameCount() { return frameCount; }
    public void setFrameCount(long frameCount) { this.frameCount = frameCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getLastOutputHex() { return lastOutputHex; }
    public void setLastOutputHex(String lastOutputHex) { this.lastOutputHex = lastOutputHex; }

    public long getLastOutputTime() { return lastOutputTime; }
    public void setLastOutputTime(long lastOutputTime) { this.lastOutputTime = lastOutputTime; }

    public long getOutputFrameCount() { return outputFrameCount; }
    public void setOutputFrameCount(long outputFrameCount) { this.outputFrameCount = outputFrameCount; }

    public long getOutputErrorCount() { return outputErrorCount; }
    public void setOutputErrorCount(long outputErrorCount) { this.outputErrorCount = outputErrorCount; }

    public String getLastOutputError() { return lastOutputError; }
    public void setLastOutputError(String lastOutputError) { this.lastOutputError = lastOutputError; }
}
