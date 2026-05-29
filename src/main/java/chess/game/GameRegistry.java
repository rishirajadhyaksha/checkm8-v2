package chess.game;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class GameRegistry {

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> socketToGame  = new ConcurrentHashMap<>();

    public GameSession createGame(GameSession.Mode mode, GameSession.AiDifficulty difficulty,
                                  int timeControlSeconds, String creatorName) {
        String id = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        GameSession game = new GameSession(id, mode, difficulty, timeControlSeconds, creatorName);
        sessions.put(id, game);
        return game;
    }

    public Optional<GameSession> findById(String gameId) {
        return Optional.ofNullable(sessions.get(gameId));
    }

    /** All open PvP games waiting for a second player */
    public List<GameSession.LobbyEntryDTO> listOpenPvP() {
        return sessions.values().stream()
                .filter(g -> g.getMode() == GameSession.Mode.PVP && !g.isFull())
                .sorted(Comparator.comparingLong(GameSession::getCreatedAt).reversed())
                .map(GameSession::toLobbyEntry)
                .collect(Collectors.toList());
    }

    public void registerSocket(String socketId, String gameId) {
        socketToGame.put(socketId, gameId);
    }

    public Optional<GameSession> getGameForSocket(String socketId) {
        String gameId = socketToGame.get(socketId);
        if (gameId == null) return Optional.empty();
        return findById(gameId);
    }

    public void removeSocket(String socketId) {
        String gameId = socketToGame.remove(socketId);
        if (gameId != null) {
            GameSession g = sessions.get(gameId);
            if (g != null) {
                boolean whiteGone = g.getWhiteSession() == null || !g.getWhiteSession().isOpen();
                boolean blackGone = g.getBlackSession() == null || !g.getBlackSession().isOpen();
                if (whiteGone && blackGone) {
                    g.shutdown();
                    sessions.remove(gameId);
                }
            }
        }
    }

    /** Broadcast updated lobby to all sockets NOT in an active game */
    public List<GameSession.LobbyEntryDTO> getLobbySnapshot() {
        return listOpenPvP();
    }
}
