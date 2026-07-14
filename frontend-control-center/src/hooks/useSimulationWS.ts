/**
 * WebSocket 实时仿真快照 Hook
 * 优先 WebSocket 推送，断开时自动降级到 HTTP 轮询
 */
import { useState, useEffect, useRef, useCallback } from 'react';
import type { SimulationSnapshot } from '../types/dispatch';

function buildWsUrl(): string {
  const { protocol, hostname, port } = window.location;
  const wsProtocol = protocol === 'https:' ? 'wss' : 'ws';
  const wsPort = port === '5173' ? '8080' : port;
  return `${wsProtocol}://${hostname}:${wsPort}/ws/simulation`;
}

interface UseSimulationWSReturn {
  snapshot: SimulationSnapshot | null;
  connected: boolean;
  error: string;
  refresh: () => Promise<void>;
  clear: () => void;
}

export function useSimulationWS(): UseSimulationWSReturn {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [connected, setConnected] = useState(false);
  const [error, setError] = useState('');
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);
  const mountedRef = useRef(true);
  const reconnectDelay = useRef(2000); // 递增重连间隔

  // HTTP 轮询（WebSocket 不可用时的降级方案）
  const pollOnce = useCallback(async () => {
    try {
      const { getSnapshot } = await import('../api/dispatch');
      const snap = await getSnapshot();
      if (snap && snap.trains) {
        setSnapshot(snap);
      }
    } catch {
      // 后端未启动或网络错误，静默
    }
  }, []);

  // 启动 HTTP 轮询
  const startPolling = useCallback(() => {
    if (pollTimer.current) return;
    pollOnce(); // 立即拉取一次
    pollTimer.current = setInterval(pollOnce, 1000);
  }, [pollOnce]);

  // 停止 HTTP 轮询
  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const connect = useCallback(() => {
    if (!mountedRef.current) return;
    if (wsRef.current?.readyState === WebSocket.OPEN) return;

    try {
      const url = buildWsUrl();
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        if (!mountedRef.current) { ws.close(); return; }
        setConnected(true);
        setError('');
        reconnectDelay.current = 2000;
        // A paused or newly restored simulation may not emit a WebSocket tick.
        // Always hydrate once from the authoritative snapshot before relying on pushes.
        void pollOnce();
        stopPolling(); // WebSocket 连通后停止降级轮询
      };

      ws.onmessage = (event) => {
        if (!mountedRef.current) return;
        try {
          const data = JSON.parse(event.data) as SimulationSnapshot;
          if (data && data.trains) {
            setSnapshot(data);
          }
        } catch {
          // 忽略解析失败的消息
        }
      };

      ws.onerror = () => {
        if (!mountedRef.current) return;
      };

      ws.onclose = () => {
        if (!mountedRef.current) return;
        setConnected(false);
        wsRef.current = null;
        // 启动 HTTP 降级轮询
        startPolling();
        // 递增重连间隔，最大 30 秒
        reconnectTimer.current = setTimeout(() => {
          reconnectDelay.current = Math.min(reconnectDelay.current * 1.5, 30000);
          connect();
        }, reconnectDelay.current);
      };
    } catch {
      if (mountedRef.current) {
        startPolling();
      }
    }
  }, [stopPolling, startPolling, pollOnce]);

  // 标签页切回前台时强制重连，解决浏览器后台节流导致 WebSocket 断开问题
  const handleVisibilityChange = useCallback(() => {
    if (document.visibilityState === 'visible') {
      reconnectDelay.current = 2000; // 重置退避
      if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
        connect();
      }
      // 即使 WebSocket 未断也要刷新一次数据
      pollOnce();
    }
  }, [connect, pollOnce]);

  useEffect(() => {
    mountedRef.current = true;
    connect();
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      mountedRef.current = false;
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (reconnectTimer.current) {
        clearTimeout(reconnectTimer.current);
        reconnectTimer.current = null;
      }
      stopPolling();
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [connect, stopPolling, handleVisibilityChange]);

  const refresh = useCallback(async () => {
    await pollOnce();
  }, [pollOnce]);

  const clear = useCallback(() => setSnapshot(null), []);

  return { snapshot, connected, error, refresh, clear };
}
