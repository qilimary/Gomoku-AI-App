package com.gomoku.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class MainActivity extends Activity {
    private GomokuView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new GomokuView(this);
        setContentView(gameView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.saveGameState(); // 保存进度
        }
    }

    class GomokuView extends View {
        private final int BOARD_SIZE = 15;
        private int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
        
        // 双缓冲技术：引入专门用于显示的 UI 棋盘，彻底避免 AI 思考时画面疯狂撕裂和闪烁
        private int[][] displayBoard = new int[BOARD_SIZE][BOARD_SIZE];
        
        private ArrayList<int[]> moveHistory = new ArrayList<>();
        private int[] lastMoveHighlight = null;
        
        private int gameMode = 0; 
        private int humanColor = 1; 
        private int currentPlayer = 1; 
        private int aiDifficultyDepth = 6;
        private volatile boolean aiThinking = false;
        private volatile boolean gameOver = false;
        private String statusMessage = "";

        private float w, margin, boardSize, cellSize, startX, startY;

        private long[][][] zobristTable = new long[BOARD_SIZE][BOARD_SIZE][3];
        private long currentZobristHash = 0;
        private HashMap<Long, TTEntry> transpositionTable = new HashMap<>();
        private int[][] positionWeights = new int[BOARD_SIZE][BOARD_SIZE];

        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Random rand = new Random();

        class TTEntry {
            int depth, score, flag, bestR, bestC;
            TTEntry(int d, int s, int f, int r, int c) {
                depth = d; score = s; flag = f; bestR = r; bestC = c;
            }
        }

        public GomokuView(Context context) {
            super(context);
            initEngine();
            loadGameState(); 
        }

        private void initEngine() {
            for (int i = 0; i < BOARD_SIZE; i++) {
                for (int j = 0; j < BOARD_SIZE; j++) {
                    for (int k = 0; k < 3; k++) zobristTable[i][j][k] = rand.nextLong();
                }
            }
            int center = BOARD_SIZE / 2;
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    int dist = Math.max(Math.abs(center - r), Math.abs(center - c));
                    positionWeights[r][c] = (center - dist) * 10;
                }
            }
        }

        // 同步后台棋盘到前台 UI 棋盘
        private void syncBoard() {
            for (int i = 0; i < BOARD_SIZE; i++) {
                System.arraycopy(board[i], 0, displayBoard[i], 0, BOARD_SIZE);
            }
        }

        public void saveGameState() {
            SharedPreferences prefs = getContext().getSharedPreferences("GomokuSave", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("aiDifficultyDepth", aiDifficultyDepth);
            editor.putInt("gameMode", gameMode);
            editor.putInt("humanColor", humanColor);
            editor.putInt("currentPlayer", currentPlayer);
            editor.putBoolean("gameOver", gameOver);
            
            StringBuilder sb = new StringBuilder();
            for (int[] move : moveHistory) {
                sb.append(move[0]).append(",").append(move[1]).append(",").append(move[2]).append(";");
            }
            editor.putString("moveHistory", sb.toString());
            editor.apply();
        }

        private void loadGameState() {
            SharedPreferences prefs = getContext().getSharedPreferences("GomokuSave", Context.MODE_PRIVATE);
            aiDifficultyDepth = prefs.getInt("aiDifficultyDepth", 6); 
            String history = prefs.getString("moveHistory", "");

            if (history.isEmpty()) {
                restartGame(); 
            } else {
                gameMode = prefs.getInt("gameMode", 0);
                humanColor = prefs.getInt("humanColor", 1);
                currentPlayer = prefs.getInt("currentPlayer", 1);
                gameOver = prefs.getBoolean("gameOver", false);
                
                board = new int[BOARD_SIZE][BOARD_SIZE];
                moveHistory.clear();
                currentZobristHash = 0;

                String[] moves = history.split(";");
                for (String mStr : moves) {
                    if (mStr.isEmpty()) continue;
                    String[] parts = mStr.split(",");
                    int r = Integer.parseInt(parts[0]);
                    int c = Integer.parseInt(parts[1]);
                    int p = Integer.parseInt(parts[2]);
                    board[r][c] = p;
                    moveHistory.add(new int[]{r, c, p});
                    currentZobristHash ^= zobristTable[r][c][p];
                    lastMoveHighlight = new int[]{r, c};
                }
                syncBoard();
                updateStatusMsg();
                
                aiThinking = false;
                if (!gameOver && gameMode == 0 && currentPlayer != humanColor) {
                    triggerAiMove();
                }
            }
        }

        private void restartGame() {
            board = new int[BOARD_SIZE][BOARD_SIZE];
            moveHistory.clear();
            lastMoveHighlight = null;
            currentZobristHash = 0;
            transpositionTable.clear();
            aiThinking = false;
            gameOver = false;
            
            currentPlayer = 1; 
            if (gameMode == 0) {
                humanColor = rand.nextBoolean() ? 1 : 2; 
            }

            syncBoard();
            updateStatusMsg();
            invalidate();
            
            if (gameMode == 0 && humanColor == 2) triggerAiMove();
        }

        private void updateStatusMsg() {
            if (gameOver) return;
            if (gameMode == 1) {
                statusMessage = (currentPlayer == 1) ? "轮到黑棋" : "轮到白棋";
            } else {
                if (currentPlayer == humanColor) {
                    statusMessage = "轮到玩家下棋";
                } else {
                    statusMessage = (aiDifficultyDepth >= 6) ? "AI思考中..." : "AI下棋中...";
                }
            }
        }

        // ======================== 修复后完美的核心算法 ========================

        private boolean isForbiddenMove(int[][] b, int r, int c, int p) {
            if (p == 2) return false; // 白棋没有禁手规则，直接放行
            int original = b[r][c];
            b[r][c] = p;
            if (checkWin(b, r, c, p)) { b[r][c] = original; return false; }
            int fours = 0, liveThrees = 0;
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            for (int[] d : dirs) {
                int[] line = extractLine(b, r, c, d);
                if (countContinuous(line, 4, p) >= 6) { b[r][c] = original; return true; } // 黑棋长连判定禁手
                if (checkFourPattern(line, p)) fours++;
                if (checkLiveThreePattern(line, p)) liveThrees++;
            }
            b[r][c] = original;
            return fours >= 2 || liveThrees >= 2;
        }

        // 彻底修复引发死循环和漏判的神级 Bug
        private int countContinuous(int[] line, int center, int p) {
            int count = 1;
            // 向右延伸
            for (int i = center + 1; i < line.length && line[i] == p; i++) count++;
            // 向左延伸 (注意：此处必须是 i--)
            for (int i = center - 1; i >= 0 && line[i] == p; i--) count++;
            return count;
        }

        private boolean checkFourPattern(int[] line, int p) {
            int[][] patterns = {{0,p,p,p,p}, {p,p,p,p,0}, {p,0,p,p,p}, {p,p,0,p,p}, {p,p,p,0,p}};
            return matchAnyPattern(line, patterns);
        }

        private boolean checkLiveThreePattern(int[] line, int p) {
            int[][] patterns = {{0,p,p,p,0,0}, {0,0,p,p,p,0}, {0,p,0,p,p,0}, {0,p,p,0,p,0}};
            return matchAnyPattern(line, patterns);
        }

        private boolean matchAnyPattern(int[] line, int[][] patterns) {
            for (int[] p : patterns) {
                for (int i = 0; i <= line.length - p.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < p.length; j++) if (line[i+j] != p[j]) { match = false; break; }
                    if (match) return true;
                }
            }
            return false;
        }

        private int[] extractLine(int[][] b, int r, int c, int[] d) {
            int[] line = new int[9];
            for (int i = -4; i <= 4; i++) {
                int nr = r + d[0] * i, nc = c + d[1] * i;
                if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE) line[i + 4] = b[nr][nc];
                else line[i + 4] = -1;
            }
            return line;
        }

        // 修复了抹除棋盘盘面导致棋子丢失的核心 Bug
        private int localScore(int[][] b, int r, int c, int p) {
            int score = 0, fours = 0, liveThrees = 0;
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            int original = b[r][c]; // 记住原本是什么，以防止把真实棋局清空
            b[r][c] = p;
            
            for (int[] d : dirs) {
                int[] line = extractLine(b, r, c, d);
                int consecutive = countContinuous(line, 4, p);
                // 黑棋五连满分，白棋五连及以上满分
                if (p == 1 && consecutive == 5) { b[r][c] = original; return 1000000; }
                if (p == 2 && consecutive >= 5) { b[r][c] = original; return 1000000; }
                
                if (checkFourPattern(line, p)) { fours++; score += 2000; } 
                else if (checkLiveThreePattern(line, p)) { liveThrees++; score += 2000; }
            }
            b[r][c] = original; // 绝对归还！
            
            if (fours >= 2) score += 90000;
            else if (fours >= 1 && liveThrees >= 1) score += 80000;
            else if (liveThrees >= 2) score += 70000;
            return score;
        }

        private void triggerAiMove() {
            aiThinking = true;
            updateStatusMsg();
            invalidate();
            new Thread(() -> {
                int[] bestMove = iterativeDeepening(aiDifficultyDepth);
                post(() -> {
                    if (bestMove != null && bestMove[0] != -1 && !gameOver) {
                        makeMove(bestMove[0], bestMove[1], currentPlayer);
                        if (checkWin(board, bestMove[0], bestMove[1], currentPlayer)) {
                            statusMessage = "AI 胜利！";
                            gameOver = true;
                        } else {
                            currentPlayer = humanColor; 
                            updateStatusMsg();
                        }
                    }
                    aiThinking = false;
                    invalidate();
                });
            }).start();
        }

        private int[] iterativeDeepening(int maxDepth) {
            int[] bestMove = {-1, -1};
            int aiColor = 3 - humanColor;
            for (int d = 1; d <= maxDepth; d++) {
                int[] res = alphaBeta(d, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1, aiColor);
                if (res[0] != -1) {
                    bestMove[0] = res[0]; bestMove[1] = res[1];
                    if (res[2] >= 900000) break;
                }
            }
            return bestMove;
        }

        private int[] alphaBeta(int depth, int alpha, int beta, int player) {
            if (depth == 0) return new int[]{-1, -1, evaluateBoard()};
            
            int aiColor = 3 - humanColor;
            long hashKey = currentZobristHash ^ (player == aiColor ? 0xFAFBFCFDL : 0L);
            TTEntry entry = transpositionTable.get(hashKey);
            int ttBestR = -1, ttBestC = -1;

            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return new int[]{entry.bestR, entry.bestC, entry.score};
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return new int[]{entry.bestR, entry.bestC, entry.score};
                ttBestR = entry.bestR; ttBestC = entry.bestC;
            }

            ArrayList<int[]> moves = generateOrderedMoves(ttBestR, ttBestC, aiColor);
            if (moves.isEmpty()) return new int[]{-1, -1, 0};

            int bestR = -1, bestC = -1, maxScore = Integer.MIN_VALUE + 1, minScore = Integer.MAX_VALUE - 1;
            int originalAlpha = alpha;

            for (int[] move : moves) {
                int r = move[0], c = move[1];
                if (player == 1 && isForbiddenMove(board, r, c, player)) continue;

                board[r][c] = player;
                currentZobristHash ^= zobristTable[r][c][player];

                int score;
                if (checkWin(board, r, c, player)) score = (player == aiColor) ? 1000000 + depth : -1000000 - depth;
                else score = alphaBeta(depth - 1, alpha, beta, 3 - player)[2];

                currentZobristHash ^= zobristTable[r][c][player];
                board[r][c] = 0;

                if (player == aiColor) {
                    if (score > maxScore) { maxScore = score; bestR = r; bestC = c; }
                    alpha = Math.max(alpha, score);
                    if (alpha >= beta) break;
                } else {
                    if (score < minScore) { minScore = score; bestR = r; bestC = c; }
                    beta = Math.min(beta, score);
                    if (alpha >= beta) break;
                }
            }

            int finalScore = (player == aiColor) ? maxScore : minScore;
            int flag = (finalScore <= originalAlpha) ? 2 : (finalScore >= beta ? 1 : 0);

            if (bestR != -1) {
                if (transpositionTable.size() > 200000) transpositionTable.clear();
                transpositionTable.put(hashKey, new TTEntry(depth, finalScore, flag, bestR, bestC));
            }
            return new int[]{bestR, bestC, finalScore};
        }

        private ArrayList<int[]> generateOrderedMoves(int pvR, int pvC, int aiColor) {
            ArrayList<int[]> list = new ArrayList<>();
            boolean hasPiece = false;
            boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
            int hColor = 3 - aiColor;

            if (pvR >= 0 && pvR < BOARD_SIZE && pvC >= 0 && pvC < BOARD_SIZE && board[pvR][pvC] == 0) {
                list.add(new int[]{pvR, pvC, localScore(board, pvR, pvC, aiColor) + localScore(board, pvR, pvC, hColor) + 100000});
                visited[pvR][pvC] = true;
            }

            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] != 0) {
                        hasPiece = true;
                        for (int dr = -2; dr <= 2; dr++) {
                            for (int dc = -2; dc <= 2; dc++) {
                                int nr = r + dr, nc = c + dc;
                                if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] == 0 && !visited[nr][nc]) {
                                    list.add(new int[]{nr, nc, localScore(board, nr, nc, aiColor) + localScore(board, nr, nc, hColor)});
                                    visited[nr][nc] = true;
                                }
                            }
                        }
                    }
                }
            }
            if (!hasPiece) { list.add(new int[]{7, 7, 0}); return list; }
            list.sort((a, b) -> Integer.compare(b[2], a[2]));
            return new ArrayList<>(list.subList(0, Math.min(15, list.size())));
        }

        private int evaluateBoard() {
            int totalScore = 0;
            int aiColor = 3 - humanColor;
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] == aiColor) {
                        totalScore += localScore(board, r, c, aiColor) + positionWeights[r][c];
                    } else if (board[r][c] == humanColor) {
                        totalScore -= (localScore(board, r, c, humanColor) * 1.3) + positionWeights[r][c];
                    }
                }
            }
            return totalScore;
        }

        // 完美五子棋判定规则：黑棋赢必须恰好 5 子，白棋赢 >= 5 子
        private boolean checkWin(int[][] b, int r, int c, int p) {
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            for (int[] d : dirs) {
                int count = 1;
                for (int step : new int[]{1, -1}) {
                    for (int i = 1; i < 9; i++) {
                        int nr = r + d[0]*step*i, nc = c + d[1]*step*i;
                        if (nr>=0 && nr<BOARD_SIZE && nc>=0 && nc<BOARD_SIZE && b[nr][nc] == p) count++; else break;
                    }
                }
                if (p == 1 && count == 5) return true;
                if (p == 2 && count >= 5) return true;
            }
            return false;
        }

        // ======================== UI ========================

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            w = getWidth();
            margin = w * 0.06f; 
            boardSize = w - 2 * margin;
            cellSize = boardSize / 14f;
            startX = margin;
            startY = margin + w * 0.25f; 

            canvas.drawColor(Color.WHITE); 
            drawUI(canvas);
            drawGrid(canvas);
        }

        private void drawGrid(Canvas canvas) {
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE); 
            paint.setStrokeWidth(5f); 
            canvas.drawRect(startX, startY, startX + boardSize, startY + boardSize, paint);
            
            paint.setStrokeWidth(2f);
            for (int i = 1; i < 14; i++) {
                canvas.drawLine(startX, startY + i * cellSize, startX + boardSize, startY + i * cellSize, paint);
                canvas.drawLine(startX + i * cellSize, startY, startX + i * cellSize, startY + boardSize, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            int[] stars = {3, 7, 11};
            for (int r : stars) {
                for (int c : stars) canvas.drawCircle(startX + c * cellSize, startY + r * cellSize, cellSize * 0.15f, paint);
            }

            // 彻底解决撕裂：前端绘画只认准专门的 UI 防闪烁双缓冲棋盘
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (displayBoard[r][c] != 0) {
                        float cx = startX + c * cellSize;
                        float cy = startY + r * cellSize;
                        
                        paint.setColor(displayBoard[r][c] == 1 ? Color.BLACK : Color.WHITE);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy, cellSize * 0.42f, paint);
                        
                        if (displayBoard[r][c] == 2) {
                            paint.setColor(Color.BLACK);
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setStrokeWidth(2f);
                            canvas.drawCircle(cx, cy, cellSize * 0.42f, paint);
                            paint.setStyle(Paint.Style.FILL);
                        }

                        if (lastMoveHighlight != null && lastMoveHighlight[0] == r && lastMoveHighlight[1] == c) {
                            paint.setColor(Color.RED);
                            canvas.drawCircle(cx, cy, cellSize * 0.1f, paint);
                        }
                    }
                }
            }
        }

        private void drawUI(Canvas canvas) {
            String modeStr = gameMode == 0 ? "模式: 人机对战" : "模式: 双人对战";
            float modeBtnW = w * 0.45f;
            float modeBtnH = w * 0.12f;
            float modeBtnX = w / 2 - modeBtnW / 2;
            float modeBtnY = margin;
            drawBtn(canvas, modeStr, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH, Color.parseColor("#E0E0E0"), Color.BLACK, w * 0.04f);

            float bottomStartY = startY + boardSize + w * 0.1f;

            paint.setColor(Color.RED); 
            paint.setStyle(Paint.Style.FILL); 
            paint.setTextSize(w * 0.055f); 
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(statusMessage, w / 2f, bottomStartY, paint);

            float btnW = w * 0.26f, btnH = w * 0.12f, space = (w - 3 * btnW) / 4f;
            float row1Y = bottomStartY + w * 0.08f;
            drawBtn(canvas, "悔棋", space, row1Y, space + btnW, row1Y + btnH, Color.parseColor("#E0E0E0"), Color.BLACK, w * 0.045f);
            drawBtn(canvas, "重玩", space*2 + btnW, row1Y, space*2 + btnW*2, row1Y + btnH, Color.parseColor("#4CAF50"), Color.WHITE, w * 0.045f);
            drawBtn(canvas, "退出", space*3 + btnW*2, row1Y, space*3 + btnW*3, row1Y + btnH, Color.parseColor("#F44336"), Color.WHITE, w * 0.045f);

            if (gameMode == 0) {
                float row2Y = row1Y + btnH + w * 0.04f;
                int c1 = aiDifficultyDepth == 3 ? Color.parseColor("#81C784") : Color.parseColor("#EEEEEE");
                int c2 = aiDifficultyDepth == 6 ? Color.parseColor("#64B5F6") : Color.parseColor("#EEEEEE");
                int c3 = aiDifficultyDepth == 9 ? Color.parseColor("#FF8A65") : Color.parseColor("#EEEEEE");
                
                drawBtn(canvas, "简单(3层)", space, row2Y, space + btnW, row2Y + btnH, c1, Color.BLACK, w * 0.038f);
                drawBtn(canvas, "普通(6层)", space*2 + btnW, row2Y, space*2 + btnW*2, row2Y + btnH, c2, Color.BLACK, w * 0.038f);
                drawBtn(canvas, "困难(9层)", space*3 + btnW*2, row2Y, space*3 + btnW*3, row2Y + btnH, c3, Color.BLACK, w * 0.038f);
            }
        }

        private void drawBtn(Canvas canvas, String text, float l, float t, float r, float b, int bg, int fg, float textSize) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bg); 
            canvas.drawRoundRect(l, t, r, b, w*0.02f, w*0.02f, paint);
            
            paint.setColor(fg); 
            paint.setTextSize(textSize);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float textY = t + (b - t) / 2f - (fm.descent + fm.ascent) / 2f;
            canvas.drawText(text, l + (r - l) / 2f, textY, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float ex = event.getX(), ey = event.getY();

                float modeBtnW = w * 0.45f, modeBtnH = w * 0.12f, modeBtnX = w / 2 - modeBtnW / 2, modeBtnY = margin;
                float btnW = w * 0.26f, btnH = w * 0.12f, space = (w - 3 * btnW) / 4f;
                float row1Y = startY + boardSize + w * 0.18f, row2Y = row1Y + btnH + w * 0.04f;

                if (checkClick(ex, ey, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH)) {
                    if (aiThinking) return true;
                    gameMode = 1 - gameMode; 
                    restartGame(); 
                    return true;
                }
                if (checkClick(ex, ey, space, row1Y, space + btnW, row1Y + btnH)) { undoMove(); return true; }
                if (checkClick(ex, ey, space*2 + btnW, row1Y, space*2 + btnW*2, row1Y + btnH)) { if (!aiThinking) restartGame(); return true; }
                if (checkClick(ex, ey, space*3 + btnW*2, row1Y, space*3 + btnW*3, row1Y + btnH)) { System.exit(0); return true; }

                if (gameMode == 0 && !aiThinking) {
                    if (checkClick(ex, ey, space, row2Y, space + btnW, row2Y + btnH)) { setDepth(3); return true; }
                    if (checkClick(ex, ey, space*2 + btnW, row2Y, space*2 + btnW*2, row2Y + btnH)) { setDepth(6); return true; }
                    if (checkClick(ex, ey, space*3 + btnW*2, row2Y, space*3 + btnW*3, row2Y + btnH)) { setDepth(9); return true; }
                }

                if (!gameOver && !aiThinking) {
                    if (gameMode == 1 || (gameMode == 0 && currentPlayer == humanColor)) {
                        int c = Math.round((ex - startX) / cellSize);
                        int r = Math.round((ey - startY) / cellSize);
                        
                        if (r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE && board[r][c] == 0) {
                            if (currentPlayer == 1 && isForbiddenMove(board, r, c, 1)) {
                                statusMessage = "禁手提示：黑棋不可下此位置！";
                                invalidate();
                            } else {
                                makeMove(r, c, currentPlayer);
                                if (checkWin(board, r, c, currentPlayer)) { 
                                    statusMessage = (currentPlayer == 1 ? "黑棋" : "白棋") + "绝杀了！";
                                    gameOver = true; 
                                } else {
                                    currentPlayer = 3 - currentPlayer; 
                                    updateStatusMsg();
                                    if (gameMode == 0) triggerAiMove();
                                }
                                invalidate();
                            }
                        }
                    }
                }
            }
            return true;
        }

        private void setDepth(int d) { 
            aiDifficultyDepth = d; 
            updateStatusMsg(); 
            invalidate(); 
        }

        private boolean checkClick(float x, float y, float l, float t, float r, float b) {
            return (x >= l && x <= r && y >= t && y <= b);
        }

        private void makeMove(int r, int c, int p) {
            board[r][c] = p; 
            moveHistory.add(new int[]{r, c, p});
            currentZobristHash ^= zobristTable[r][c][p]; 
            lastMoveHighlight = new int[]{r, c};
            syncBoard(); // 更新前台 UI
        }

        private void undoMove() {
            if (aiThinking || gameOver) return;
            int pops = gameMode == 0 ? 2 : 1; 
            if (moveHistory.size() >= pops) {
                for (int i = 0; i < pops; i++) {
                    int[] m = moveHistory.remove(moveHistory.size() - 1);
                    board[m[0]][m[1]] = 0; 
                    currentZobristHash ^= zobristTable[m[0]][m[1]][m[2]];
                    currentPlayer = m[2]; 
                }
                lastMoveHighlight = moveHistory.isEmpty() ? null : new int[]{moveHistory.get(moveHistory.size()-1)[0], moveHistory.get(moveHistory.size()-1)[1]};
                syncBoard(); // 刷新退回后的前台 UI
                updateStatusMsg();
                invalidate();
            }
        }
    }
}
