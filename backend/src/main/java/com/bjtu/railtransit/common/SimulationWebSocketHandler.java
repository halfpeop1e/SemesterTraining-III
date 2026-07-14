package com.bjtu.railtransit.common;

import com.bjtu.railtransit.domain.model.SimulationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SimulationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulationWebSocketHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    /** sessionId -> subscribed trainId */
    private final Map<String, String> sessionTrainMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessions.add(session);
        log.info("WS client connected: {} (total: {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        sessions.remove(session);
        sessionTrainMap.remove(session.getId());
        log.info("WS client disconnected: {} (total: {})", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        try {
            var node = mapper.readTree(message.getPayload());
            // { "type": "subscribe", "trainId": "LAB1" }
            if ("subscribe".equals(node.path("type").asText())) {
                String trainId = node.path("trainId").asText();
                sessionTrainMap.put(session.getId(), trainId);
                log.info("WS session {} subscribed to train {}", session.getId(), trainId);
            }
        } catch (Exception e) {
            log.warn("WS message parse error", e);
        }
    }

    /** 发给所有控制中心前端（保持兼容） */
    public void broadcast(SimulationSnapshot snapshot) {
        if (sessions.isEmpty())
            return;
        try {
            String json = mapper.writeValueAsString(snapshot);
            TextMessage msg = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(msg);
                    } catch (IOException e) {
                        log.warn("WS send fail");
                    }
                }
            }
        } catch (Exception e) {
            log.error("WS broadcast error", e);
        }
    }

    /** 发给指定列车的车载前端 */
    public void sendToTrain(String trainId, Object frame) {
        try {
            String json = mapper.writeValueAsString(frame);
            TextMessage msg = new TextMessage(json);
            for (var entry : sessionTrainMap.entrySet()) {
                if (trainId.equals(entry.getValue())) {
                    for (WebSocketSession session : sessions) {
                        if (session.getId().equals(entry.getKey()) && session.isOpen()) {
                            try {
                                session.sendMessage(msg);
                            } catch (IOException e) {
                                /* ignore */ }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("WS sendToTrain error", e);
        }
    }

    public Set<WebSocketSession> getSessions() {
        return sessions;
    }
}
