import React from 'react';

export default function Environment3D() {
  return (
    <>
      <ambientLight intensity={0.45} color="#1a3a5c" />
      <directionalLight position={[200, 300, 100]} intensity={1.5} color="#fff8e7" castShadow
        shadow-mapSize-width={2048} shadow-mapSize-height={2048}
        shadow-camera-far={1200} shadow-camera-left={-400} shadow-camera-right={400}
        shadow-camera-top={400} shadow-camera-bottom={-400} />
      <directionalLight position={[-100, 80, -150]} intensity={0.3} color="#4a90d9" />
      <hemisphereLight args={['#87ceeb', '#1a3a5c', 0.25]} />
      <gridHelper args={[2000, 50, '#1c3050', '#0f1e30']} position={[0, -0.5, 0]} />
      <fog attach="fog" args={['#060b11', 500, 2000]} />
    </>
  );
}
