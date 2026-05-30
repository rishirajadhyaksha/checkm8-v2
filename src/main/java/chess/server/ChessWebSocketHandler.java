package chess.server;

import chess.game.*;
import chess.model.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class ChessWebSocketHandler extends TextWebSocketHandler {

    private final GameRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    // Track all connected sessions so we can push lobby updates
    private final Set<WebSocketSession> allSessions = new CopyOnWriteArraySet<>();

    public ChessWebSocketHandler(GameRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        allSessions.add(session);
        sendJson(session, Map.of("event", "connected", "sessionId", session.getId()));
        // Send lobby immediately
        pushLobbyTo(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode msg = mapper.readTree(message.getPayload());
        String action = msg.get("action").asText();
        switch (action) {
            case "join_pva"     -> handleJoinPvA(session, msg);
            case "create_pvp"   -> handleCreatePvP(session, msg);
            case "join_pvp"     -> handleJoinPvP(session, msg);
            case "list_games"   -> pushLobbyTo(session);
            case "move"         -> handleMove(session, msg);
            case "legal"        -> handleLegal(session, msg);
            case "new_game"     -> handleNewGame(session);
            case "claim_timeout"-> handleClaimTimeout(session);
            default             -> sendError(session, "Unknown action: " + action);
        }
    }

    // ── PvA ───────────────────────────────────────────────────────────────────
    private void handleJoinPvA(WebSocketSession session, JsonNode msg) {
        String diff = msg.has("difficulty") ? msg.get("difficulty").asText() : "MEDIUM";
        int tc = msg.has("timeControl") ? msg.get("timeControl").asInt() : 0;
        String name = msg.has("name") ? msg.get("name").asText() : "Player";

        GameSession.AiDifficulty difficulty;
        try { difficulty = GameSession.AiDifficulty.valueOf(diff.toUpperCase()); }
        catch (Exception e) { difficulty = GameSession.AiDifficulty.MEDIUM; }

        GameSession game = registry.createGame(GameSession.Mode.PVA, difficulty, tc, name);
        game.setWhiteSession(session);
        game.setOnTimeout(() -> broadcast(game, game.toBoardState("board_update")));
        game.startTurnClock();
        registry.registerSocket(session.getId(), game.getGameId());

        sendJson(session, Map.of("event","joined","gameId",game.getGameId(),
                "color","WHITE","mode","PVA","difficulty",difficulty.name()));
        broadcast(game, game.toBoardState("board_update"));
    }

    // ── Create PvP ───────────────────────────────────────────────────────────
    private void handleCreatePvP(WebSocketSession session, JsonNode msg) {
        int tc = msg.has("timeControl") ? msg.get("timeControl").asInt() : 0;
        String name = msg.has("name") ? msg.get("name").asText() : "Player";

        GameSession game = registry.createGame(GameSession.Mode.PVP, null, tc, name);
        game.setWhiteSession(session);
        registry.registerSocket(session.getId(), game.getGameId());

        sendJson(session, Map.of("event","joined","gameId",game.getGameId(),
                "color","WHITE","mode","PVP","waiting",true));

        // Tell everyone in the lobby there's a new game
        broadcastLobby();
    }

    // ── Join existing PvP ────────────────────────────────────────────────────
    private void handleJoinPvP(WebSocketSession session, JsonNode msg) {
        if (!msg.has("gameId")) { sendError(session, "gameId required"); return; }
        String gameId = msg.get("gameId").asText();

        Optional<GameSession> opt = registry.findById(gameId);
        if (opt.isEmpty()) { sendError(session, "Game not found"); return; }
        GameSession game = opt.get();
        if (game.isFull()) { sendError(session, "Game is full"); return; }

        game.setBlackSession(session);
        game.setOnTimeout(() -> broadcast(game, game.toBoardState("board_update")));
        game.startTurnClock();
        registry.registerSocket(session.getId(), game.getGameId());

        sendJson(session, Map.of("event","joined","gameId",game.getGameId(),
                "color","BLACK","mode","PVP"));
        sendJson(game.getWhiteSession(), Map.of("event","opponent_joined","gameId",game.getGameId()));
        broadcast(game, game.toBoardState("board_update"));

        // Lobby changed — game no longer open
        broadcastLobby();
    }

    // ── Move ─────────────────────────────────────────────────────────────────
    private void handleMove(WebSocketSession session, JsonNode msg) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) { sendError(session, "Not in a game"); return; }
        GameSession game = gameOpt.get();

        Optional<Color> colorOpt = game.colorOf(session);
        if (colorOpt.isEmpty()) { sendError(session, "Not a player"); return; }
        if (game.getBoard().getCurrentTurn() != colorOpt.get()) { sendError(session, "Not your turn"); return; }
        if (game.isAiThinking()) { sendError(session, "AI is thinking"); return; }

        int fromRow = msg.get("fromRow").asInt();
        int fromCol = msg.get("fromCol").asInt();
        int toRow   = msg.get("toRow").asInt();
        int toCol   = msg.get("toCol").asInt();
        String promo = msg.has("promotion") ? msg.get("promotion").asText() : null;

        boolean applied = game.applyMove(fromRow, fromCol, toRow, toCol, promo);
        if (!applied) { sendError(session, "Illegal move"); return; }

        broadcast(game, game.toBoardState("board_update"));

        String gs = game.getBoard().getGameState().name();
        if (game.getMode() == GameSession.Mode.PVA &&
                (gs.equals("PLAYING") || gs.equals("CHECK"))) {
            broadcast(game, game.toBoardState("ai_thinking"));
            game.scheduleAiMove(() -> broadcast(game, game.toBoardState("board_update")));
        }
    }

    // ── Legal moves ───────────────────────────────────────────────────────────
    private void handleLegal(WebSocketSession session, JsonNode msg) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) return;
        GameSession game = gameOpt.get();
        int row = msg.get("row").asInt(), col = msg.get("col").asInt();
        List<Map<String,Integer>> result = game.getLegalMoves(row, col).stream()
                .map(m -> Map.of("row", m[0], "col", m[1])).toList();
        sendJson(session, Map.of("event","legal_moves","row",row,"col",col,"moves",result));
    }

    // ── Timeout claim (from client clock hitting zero) ────────────────────────
    private void handleClaimTimeout(WebSocketSession session) {
        Optional<GameSession> gameOpt = registry.getGameForSocket(session.getId());
        if (gameOpt.isEmpty()) return;
        GameSession game = gameOpt.get();
        // Server-side clock is authoritative — just re-check and broadcast result
        broadcast(game, game.toBoardState("board_update"));
    }

    // ── New game ──────────────────────────────────────────────────────────────
    private void handleNewGame(WebSocketSession session) {
        registry.getGameForSocket(session.getId()).ifPresent(g -> {
            g.getBoard().reset();
            g.startTurnClock();
            broadcast(g, g.toBoardState("board_update"));
        });
    }

    // ── Disconnect ────────────────────────────────────────────────────────────
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        allSessions.remove(session);
        registry.getGameForSocket(session.getId()).ifPresent(game -> {
            game.colorOf(session).ifPresent(c -> {
                WebSocketSession opp = c == Color.WHITE ? game.getBlackSession() : game.getWhiteSession();
                if (opp != null && opp.isOpen())
                    sendJson(opp, Map.of("event", "opponent_disconnected",
                                        "message", "Your opponent has left the game."));
            });
        });
        registry.removeSocket(session.getId());
        broadcastLobby();
    }

    // ── Lobby push ───────────────────────────────────────────────────────────
    private void pushLobbyTo(WebSocketSession session) {
        try {
            var entries = registry.getLobbySnapshot();
            sendJson(session, Map.of("event","lobby_update","games",entries));
        } catch (Exception e) { /* ignore */ }
    }

    private void broadcastLobby() {
        try {
            var entries = registry.getLobbySnapshot();
            String json = mapper.writeValueAsString(Map.of("event","lobby_update","games",entries));
            for (WebSocketSession s : allSessions) {
                // Only push to sessions not in an active game
                if (registry.getGameForSocket(s.getId()).isEmpty()) {
                    sendRaw(s, json);
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void broadcast(GameSession game, Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            sendRaw(game.getWhiteSession(), json);
            if (game.getMode() == GameSession.Mode.PVP) sendRaw(game.getBlackSession(), json);
        } catch (Exception e) { /* ignore */ }
    }

    private void sendJson(WebSocketSession session, Object payload) {
        try { sendRaw(session, mapper.writeValueAsString(payload)); }
        catch (Exception e) { /* ignore */ }
    }

    private synchronized void sendRaw(WebSocketSession session, String json) {
        if (session != null && session.isOpen()) {
            try { session.sendMessage(new TextMessage(json)); }
            catch (IOException e) { /* ignore */ }
        }
    }

    private void sendError(WebSocketSession s, String msg) {
        sendJson(s, Map.of("event","error","message",msg));
    }
}
