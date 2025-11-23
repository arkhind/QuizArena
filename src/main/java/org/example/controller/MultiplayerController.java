package org.example.controller;

import org.example.dto.request.multiplayer.*;
import org.example.dto.response.multiplayer.MultiplayerResultsDTO;
import org.example.dto.response.multiplayer.MultiplayerSessionDTO;
import org.example.dto.response.multiplayer.ParticipantsDTO;
import org.example.service.MultiplayerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/multiplayer")
public class MultiplayerController {

    private final MultiplayerService multiplayerService;

    @Autowired
    public MultiplayerController(MultiplayerService multiplayerService) {
        this.multiplayerService = multiplayerService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<MultiplayerSessionDTO> createSession(@RequestBody CreateMultiplayerRequest request) {
        try {
            MultiplayerSessionDTO session = multiplayerService.createMultiplayerSession(request);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<MultiplayerSessionDTO> getSession(@PathVariable String sessionId) {
        try {
            MultiplayerSessionDTO session = multiplayerService.getMultiplayerSession(sessionId);
            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/sessions/join")
    public ResponseEntity<Boolean> joinSession(@RequestBody JoinMultiplayerRequest request) {
        try {
            boolean joined = multiplayerService.joinMultiplayerSession(request);
            return ResponseEntity.ok(joined);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/sessions/{sessionId}/participants")
    public ResponseEntity<ParticipantsDTO> getParticipants(@PathVariable String sessionId) {
        try {
            ParticipantsDTO participants = multiplayerService.getSessionParticipants(sessionId);
            return ResponseEntity.ok(participants);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/sessions/start")
    public ResponseEntity<Boolean> startSession(@RequestBody StartMultiplayerRequest request) {
        try {
            boolean started = multiplayerService.startMultiplayerSession(request);
            return ResponseEntity.ok(started);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @GetMapping("/sessions/{sessionId}/results")
    public ResponseEntity<MultiplayerResultsDTO> getResults(@PathVariable String sessionId) {
        try {
            MultiplayerResultsDTO results = multiplayerService.getMultiplayerResults(sessionId);
            return ResponseEntity.ok(results);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/sessions/cancel")
    public ResponseEntity<Boolean> cancelSession(@RequestBody CancelMultiplayerRequest request) {
        try {
            boolean cancelled = multiplayerService.cancelMultiplayerSession(request);
            return ResponseEntity.ok(cancelled);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }
}

