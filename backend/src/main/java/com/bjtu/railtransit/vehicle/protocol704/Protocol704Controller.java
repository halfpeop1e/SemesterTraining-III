package com.bjtu.railtransit.vehicle.protocol704;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vehicle/protocol704")
public class Protocol704Controller {

    @Autowired
    private Protocol704Service protocol704Service;

    @GetMapping("/status")
    public ResponseEntity<Protocol704Status> getStatus(
            @RequestParam(defaultValue = "T1") String trainId) {
        Protocol704Status status = protocol704Service.getStatus(trainId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/connect")
    public ResponseEntity<String> connect(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.start(trainId);
        return ResponseEntity.ok("Connecting to 704 for train " + trainId);
    }

    @PostMapping("/disconnect")
    public ResponseEntity<String> disconnect(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.stop(trainId);
        return ResponseEntity.ok("Disconnected from 704 for train " + trainId);
    }

    @PostMapping("/reset")
    public ResponseEntity<String> reset(
            @RequestParam(defaultValue = "T1") String trainId) {
        protocol704Service.reset(trainId);
        return ResponseEntity.ok("Reset completed for train " + trainId);
    }

    @PostMapping("/test-frame")
    public ResponseEntity<Protocol704Status> injectTestFrame(
            @RequestParam(defaultValue = "T1") String trainId,
            @RequestParam String type) {
        Protocol704Status status = protocol704Service.injectTestFrame(trainId, type);
        return ResponseEntity.ok(status);
    }
}