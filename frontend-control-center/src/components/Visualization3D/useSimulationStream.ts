/**
 * 仿真数据轮询 Hook
 * 每 500ms 轮询一次 /api/simulations/snapshot
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { getSnapshot } from '../../api/dispatch';
import type { SimulationSnapshot, StationGeo } from '../../types/dispatch';

interface UseSimulationStreamReturn {
  snapshot: SimulationSnapshot | null;
  stations: StationGeo[];
  error: string;
  refresh: () => Promise<void>;
}

export function useSimulationStream(): UseSimulationStreamReturn {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [stations, setStations] = useState<StationGeo[]>([]);
  const [error, setError] = useState('');
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = useCallback(async () => {
    try {
      const snap = await getSnapshot();
      setSnapshot(snap);
      setError('');
    } catch (e: any) {
      setError(e.message || '获取快照失败');
    }
  }, []);

  // 首次加载线路数据（从快照中可能没有，需要单独获取）
  useEffect(() => {
    import('../../api/dispatch')
      .then(({ getLineMap }) => getLineMap())
      .then((data) => {
        if (Array.isArray(data) && data.length > 0) setStations(data);
      })
      .catch(() => {});
  }, []);

  // 轮询快照
  useEffect(() => {
    refresh(); // 立即获取一次
    intervalRef.current = setInterval(refresh, 500);
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [refresh]);

  return { snapshot, stations, error, refresh };
}
