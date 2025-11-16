package org.example.service;

import org.example.dto.request.multiplayer.*;
import org.example.dto.response.multiplayer.MultiplayerResultsDTO;
import org.example.dto.response.multiplayer.MultiplayerSessionDTO;
import org.example.dto.response.multiplayer.ParticipantsDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис для совместного прохождения квизов.
 */
@Service
@Transactional
public class MultiplayerService {

    public MultiplayerSessionDTO createMultiplayerSession(CreateMultiplayerRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public MultiplayerSessionDTO getMultiplayerSession(String sessionId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public boolean joinMultiplayerSession(JoinMultiplayerRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public ParticipantsDTO getSessionParticipants(String sessionId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public boolean startMultiplayerSession(StartMultiplayerRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public MultiplayerResultsDTO getMultiplayerResults(String sessionId) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }

    public boolean cancelMultiplayerSession(CancelMultiplayerRequest request) {
        throw new UnsupportedOperationException("Метод еще не реализован");
    }
}

