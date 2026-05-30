package chess.game;

import chess.model.*;
import chess.ai.*;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.*;

public class GameSession {

    public enum Mode { PVP, PVA }
    public enum AiDifficulty { EASY, MEDIUM, HARD, MASTER }

    private final String gameId;
    private final Board board;
    private final Mode mode;
    private final AiDifficulty difficulty;
    private final int timeControlSeconds; // per player, 0 = unlimited
    private final String creatorName;
    private final long createdAt;

    // Clocks (milliseconds remaining per player)
    private long whiteTimeMs;
    private long blackTimeMs;
    private long turnStartMs = -1;

    private WebSocketSession whiteSession;
    private WebSocketSession blackSession;

    private ChessAI basicAI;
    private StrongChessAI strongAI;

    private volatile boolean aiThinking = false;
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean gameOverOnTime = false;
    private Color winner = null;

    // Server-side proactive clock — fires every 500ms regardless of moves
    private ScheduledExecutorService clockTicker;
    private Runnable onTimeout;

    public void setOnTimeout(Runnable cb) { this.onTimeout = cb; }

    public GameSession(String gameId, Mode mode, AiDifficulty difficulty,
                       int timeControlSeconds, String creatorName) {
        this.gameId = gameId;
        this.board = new Board();
        this.mode = mode;
        this.difficulty = difficulty;
        this.timeControlSeconds = timeControlSeconds;
        this.creatorName = creatorName != null ? creatorName : "Anonymous";
        this.createdAt = System.currentTimeMillis();
        this.whiteTimeMs = timeControlSeconds > 0 ? timeControlSeconds * 1000L : Long.MAX_VALUE;
        this.blackTimeMs = timeControlSeconds > 0 ? timeControlSeconds * 1000L : Long.MAX_VALUE;

        if (mode == Mode.PVA) {
            switch (difficulty) {
                case EASY   -> basicAI = new ChessAI(Color.BLACK, 1);
                case MEDIUM -> basicAI = new ChessAI(Color.BLACK, 3);
                case HARD   -> basicAI = new ChessAI(Color.BLACK, 5);
                case MASTER -> strongAI = new StrongChessAI(Color.BLACK);
            }
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getGameId()         { return gameId; }
    public Board getBoard()           { return board; }
    public Mode getMode()             { return mode; }
    public AiDifficulty getDifficulty(){ return difficulty; }
    public int getTimeControlSeconds(){ return timeControlSeconds; }
    public String getCreatorName()    { return creatorName; }
    public long getCreatedAt()        { return createdAt; }
    public boolean isAiThinking()     { return aiThinking; }

    public void setWhiteSession(WebSocketSession s) { this.whiteSession = s; }
    public void setBlackSession(WebSocketSession s) { this.blackSession = s; }
    public WebSocketSession getWhiteSession()       { return whiteSession; }
    public WebSocketSession getBlackSession()       { return blackSession; }

    public boolean isFull() {
        if (mode == Mode.PVA) return whiteSession != null;
        return whiteSession != null && blackSession != null;
    }

    public Optional<Color> colorOf(WebSocketSession session) {
        if (session.equals(whiteSession)) return Optional.of(Color.WHITE);
        if (session.equals(blackSession)) return Optional.of(Color.BLACK);
        return Optional.empty();
    }

    // ── Clock ─────────────────────────────────────────────────────────────────
    public synchronized void startTurnClock() {
        if (timeControlSeconds <= 0) return;
        turnStartMs = System.currentTimeMillis();
        // Start the proactive server-side ticker if not already running
        if (clockTicker == null || clockTicker.isShutdown()) {
            clockTicker = Executors.newSingleThreadScheduledExecutor();
            clockTicker.scheduleAtFixedRate(() -> {
                if (gameOverOnTime) { clockTicker.shutdownNow(); return; }
                checkTimeout();
            }, 500, 500, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stopTurnClock() {
        if (timeControlSeconds > 0 && turnStartMs > 0) {
            long elapsed = System.currentTimeMillis() - turnStartMs;
            // The turn that just ended was for the color that just moved,
            // which is now the OPPOSITE of getCurrentTurn() (move already applied)
            if (board.getCurrentTurn() == Color.BLACK) {
                whiteTimeMs = Math.max(0, whiteTimeMs - elapsed);
            } else {
                blackTimeMs = Math.max(0, blackTimeMs - elapsed);
            }
            turnStartMs = -1;
            checkTimeout();
        }
    }

    public long getWhiteTimeMs() {
        if (timeControlSeconds <= 0) return -1;
        if (turnStartMs > 0 && board.getCurrentTurn() == Color.WHITE) {
            return Math.max(0, whiteTimeMs - (System.currentTimeMillis() - turnStartMs));
        }
        return whiteTimeMs;
    }

    public long getBlackTimeMs() {
        if (timeControlSeconds <= 0) return -1;
        if (turnStartMs > 0 && board.getCurrentTurn() == Color.BLACK) {
            return Math.max(0, blackTimeMs - (System.currentTimeMillis() - turnStartMs));
        }
        return blackTimeMs;
    }

    public synchronized void checkTimeout() {
        if (timeControlSeconds <= 0 || gameOverOnTime) return;

        long white = getWhiteTimeMs();
        long black = getBlackTimeMs();

        if (white <= 0) {
            whiteTimeMs = 0;
            gameOverOnTime = true;
            winner = Color.BLACK;
        } else if (black <= 0) {
            blackTimeMs = 0;
            gameOverOnTime = true;
            winner = Color.WHITE;
        }

        if (gameOverOnTime) {
            if (clockTicker != null) clockTicker.shutdownNow();
            if (onTimeout != null) onTimeout.run();
        }
    }

    // ── Move ──────────────────────────────────────────────────────────────────
    public synchronized boolean applyMove(int fromRow, int fromCol, int toRow, int toCol, String promotionPiece) {
        checkTimeout();
        if (gameOverOnTime) return false;
        List<Move> legal = board.getLegalMovesForPiece(fromRow, fromCol);
        for (Move m : legal) {
            if (m.getToRow() == toRow && m.getToCol() == toCol) {
                if (m.isPromotion() && promotionPiece != null) {
                    Piece promo = makePromoPiece(promotionPiece, board.getCurrentTurn());
                    Move pm = new Move(fromRow, fromCol, toRow, toCol,
                            m.getPiece(), m.getCapturedPiece(),
                            false, false, false, true, promo);
                    board.makeMove(pm);
                } else {
                    board.makeMove(m);
                }
                stopTurnClock();
                startTurnClock();
                return true;
            }
        }
        return false;
    }

    public void scheduleAiMove(Runnable afterMove) {
        if (aiThinking) return;
        aiThinking = true;
        aiExecutor.submit(() -> {
            try {
                Move best;
                if (strongAI != null)       best = strongAI.findBestMove(board);
                else if (basicAI != null)   best = basicAI.findBestMove(board);
                else return;
                if (best != null) {
                    synchronized (this) { board.makeMove(best); }
                    stopTurnClock();
                    startTurnClock();
                }
            } finally {
                aiThinking = false;
                afterMove.run();
            }
        });
    }

    // ── Serialization ─────────────────────────────────────────────────────────
    public BoardStateDTO toBoardState(String event) {

        BoardStateDTO dto = new BoardStateDTO();
        dto.event       = event;
        dto.gameId      = gameId;
        dto.turn        = board.getCurrentTurn().name();
        dto.gameState   = board.getGameState().name();
        dto.aiThinking  = aiThinking;
        dto.mode        = mode.name();
        dto.whiteTimeMs = getWhiteTimeMs();
        dto.blackTimeMs = getBlackTimeMs();
        dto.timeControl = timeControlSeconds;
        dto.timeout = gameOverOnTime;
        dto.winner = winner != null ? winner.name() : null;

        dto.board = new String[8][8];
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                Piece p = board.getPiece(r, c);
                dto.board[r][c] = p == null ? null : p.getColor().name() + "_" + p.getSymbol();
            }

        dto.moveHistory = board.getMoveHistory().stream().map(Move::toString).toList();
        return dto;
    }

    /** Lightweight summary for the lobby listing */
    public LobbyEntryDTO toLobbyEntry() {
        LobbyEntryDTO e = new LobbyEntryDTO();
        e.gameId       = gameId;
        e.creatorName  = creatorName;
        e.timeControl  = timeControlSeconds;
        e.createdAt    = createdAt;
        return e;
    }

    public List<int[]> getLegalMoves(int row, int col) {
        return board.getLegalMovesForPiece(row, col).stream()
                .map(m -> new int[]{m.getToRow(), m.getToCol()})
                .toList();
    }

    private Piece makePromoPiece(String symbol, Color color) {
        return switch (symbol.toUpperCase()) {
            case "Q" -> new chess.model.Queen(color);
            case "R" -> new chess.model.Rook(color);
            case "B" -> new chess.model.Bishop(color);
            case "N" -> new chess.model.Knight(color);
            default  -> new chess.model.Queen(color);
        };
    }

    public void shutdown() {
        aiExecutor.shutdownNow();
        if (clockTicker != null) clockTicker.shutdownNow();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    public static class BoardStateDTO {
        public String event, gameId, turn, gameState, mode;
        public boolean aiThinking;
        public long whiteTimeMs, blackTimeMs;
        public int timeControl;
        public String[][] board;
        public List<String> moveHistory;
        public boolean timeout;
        public String winner;
    }

    public static class LobbyEntryDTO {
        public String gameId, creatorName;
        public int timeControl;
        public long createdAt;
    }
}