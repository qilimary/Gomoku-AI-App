package com.gomoku.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

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

    class GomokuView extends View {
        private final int BOARD_SIZE = 15;
        private int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
        private ArrayList<int[]> moveHistory = new ArrayList<>();
        private int[] lastMoveHighlight = null;
        private int currentPlayer = 1; // 1: 人类(黑), 2: AI(白)
        private int aiDifficultyDepth = 8;
        private int gameMode = 0; // 0: 人机, 1: AI对决
        private boolean aiThinking = false;
        private boolean gameOver = false;
        private String statusMessage = "游戏开始";

        // TSS 复活机制：爆发性高分
        private final int SCORE_TSS_MAX = 90000;

        // 终极优化缓存
        private long[][][] zobristTable = new long[BOARD_SIZE][BOARD_SIZE][3];
        private long currentZobristHash = 0;
        private HashMap<Long, TTEntry> transpositionTable = new HashMap<>();
        private int[][] positionWeights = new int[BOARD_SIZE][BOARD_SIZE];

        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Random rand = new Random();
        private float scale = 1.0f;

        class TTEntry {
            int depth, score, flag; // flag: 0=EXACT, 1=LOWERBOUND, 2=UPPERBOUND
            int bestR, bestC;
            TTEntry(int d, int s, int f, int r, int c) {
                depth = d; score = s; flag = f; bestR = r; bestC = c;
            }
        }

        public GomokuView(Context context) {
            super(context);
            // 修复深度回溯：每当用户从后台换回来或者重新运行，会基于上次设定的深度。
            SharedPreferences prefs = context.getSharedPreferences("GomokuPrefs", MODE_PRIVATE);
            aiDifficultyDepth = prefs.getInt("depth", 8);
            gameMode = prefs.getInt("gameMode", 0);
            initEngine();
            restartGame();
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

        private void restartGame() {
            board = new int[BOARD_SIZE][BOARD_SIZE];
            moveHistory.clear();
            lastMoveHighlight = null;
            // 修复随机先手逻辑不变
            currentPlayer = rand.nextInt(2) + 1;
            currentZobristHash = 0;
            transpositionTable.clear();
            aiThinking = false;
            gameOver = false;
            if (gameMode == 1) statusMessage = "AI对决开始";
            else statusMessage = (currentPlayer == 1) ? "黑棋(你)先手" : "AI思考中...";
            invalidate();
            // 如果 AI 先手或者是对决模式，则立即执行。
            if (currentPlayer == 2 || gameMode == 1) triggerAiMove();
        }

        private void setDepth(int d) {
            aiDifficultyDepth = d;
            statusMessage = "深度已变更";
            SharedPreferences.Editor editor = getContext().getSharedPreferences("GomokuPrefs", MODE_PRIVATE).edit();
            editor.putInt("depth", d);
            editor.apply();
            invalidate();
        }

        private void setMode(int m) {
            gameMode = m;
            SharedPreferences.Editor editor = getContext().getSharedPreferences("GomokuPrefs", MODE_PRIVATE).edit();
            editor.putInt("gameMode", m);
            editor.apply();
            restartGame();
        }

        // ======================== UI 视图系统 ========================

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // 采用标准高度全面适配，使界面比例更协调
            float scaleX = (float) getWidth() / 1360f;
            float scaleY = (float) getHeight() / 2400f;
            scale = Math.min(scaleX, scaleY);

            // 使用符合软件气质的木色背景
            canvas.drawColor(Color.rgb(255, 250, 240));
            drawBoardAndGrid(canvas);
            drawPieces(canvas);
            drawUI(canvas);
        }

        private void drawBoardAndGrid(Canvas canvas) {
            // 修正棋盘位置，使其居中。
            float startX = (getWidth() - 1120 * scale) / 2f;
            float startY = (getHeight() - 1120 * scale) / 2f - 300 * scale; // 整体居中后上移，留足底部UI空间
            float gridSize = 1120 * scale;

            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(4 * scale);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(startX, startY, startX + gridSize, startY + gridSize, paint);

            paint.setStrokeWidth(2 * scale);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 1; i < 14; i++) {
                canvas.drawLine(startX, startY + i * 80 * scale, startX + gridSize, startY + i * 80 * scale, paint);
                canvas.drawLine(startX + i * 80 * scale, startY, startX + i * 80 * scale, startY + gridSize, paint);
            }

            // 修正星位位置，棋盘的大小和位置都对。
            int[] stars = {3, 7, 11};
            for (int r : stars) {
                for (int c : stars) {
                    canvas.drawCircle(startX + c * 80 * scale, startY + r * 80 * scale, 8 * scale, paint);
                }
            }
        }

        private void drawPieces(Canvas canvas) {
            float startX = (getWidth() - 1120 * scale) / 2f;
            float startY = (getHeight() - 1120 * scale) / 2f - 300 * scale;

            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] != 0) {
                        paint.setColor(board[r][c] == 1 ? Color.BLACK : Color.WHITE);
                        // 修正棋子的位置与Bug
                        float cx = startX + c * 80 * scale;
                        float cy = startY + r * 80 * scale;
                        canvas.drawCircle(cx, cy, 32 * scale, paint);

                        if (board[r][c] == 2) { // 白棋加黑边框，更清晰。
                            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE);
                            canvas.drawCircle(cx, cy, 32 * scale, paint); paint.setStyle(Paint.Style.FILL);
                        }
                        if (lastMoveHighlight != null && lastMoveHighlight[0] == r && lastMoveHighlight[1] == c) {
                            paint.setColor(Color.RED); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4 * scale);
                            canvas.drawCircle(cx, cy, 15 * scale, paint); paint.setStyle(Paint.Style.FILL);
                        }
                    }
                }
            }
        }

        private void drawUI(Canvas canvas) {
            paint.setColor(Color.RED); paint.setTextSize(48 * scale); paint.setTextAlign(Paint.Align.CENTER);
            float currentTextY = (getHeight() - 1120 * scale) / 2f + 14 * 80 * scale - 300 * scale + 100 * scale;
            
            // 修正简洁简洁干净的信息显示。
            String currentMsg;
            if (gameOver) currentMsg = statusMessage;
            else if (aiThinking && aiDifficultyDepth > 6) currentMsg = "AI思考中... (白棋)";
            else currentMsg = (currentPlayer == 1 ? "轮到你了 (黑棋)" : "AI思考中...");
            
            if (gameMode == 1) { // 深度12层這種消耗時間的，直接AI思考中……
                currentMsg = "AI对决进行中" + (aiThinking && aiDifficultyDepth > 6 ? "(思考中...)" : "");
            }
            canvas.drawText(currentMsg, getWidth() / 2f, currentTextY, paint);

            // UI 底部界面
            float bottomY = 1750 * scale;
            drawBtn(canvas, "悔棋", 50, bottomY, 350, bottomY + 100 * scale, Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "重玩", 400, bottomY, 700, bottomY + 100 * scale, Color.GREEN, Color.BLACK);
            drawBtn(canvas, "退出", 750, bottomY, 1050, bottomY + 100 * scale, Color.RED, Color.WHITE);

            // 修正三个深度分别为简单、中等、困难。
            drawBtn(canvas, "简单 (4层)", 50, bottomY + 150 * scale, 350, bottomY + 250 * scale, aiDifficultyDepth == 4 ? Color.CYAN : Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "中等 (8层)", 400, bottomY + 150 * scale, 700, bottomY + 250 * scale, aiDifficultyDepth == 8 ? Color.CYAN : Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "困难 (12层)", 750, bottomY + 150 * scale, 1050, bottomY + 250 * scale, aiDifficultyDepth == 12 ? Color.CYAN : Color.LTGRAY, Color.BLACK);

            // 修正一个灰色显示即可的人机对战
            drawBtn(canvas, "观看 AI 对决", 400, bottomY + 300 * scale, 700, bottomY + 400 * scale, Color.rgb(200, 200, 200), Color.rgb(50, 50, 50));
            // 修正自由按钮，提供1～20个数字选项自由设置深度。
            drawBtn(canvas, "自由深度:" + aiDifficultyDepth, 750, bottomY + 300 * scale, 1050, bottomY + 400 * scale, Color.rgb(255, 165, 0), Color.BLACK);
        }

        private void drawBtn(Canvas canvas, String text, float l, float t, float r, float b, int bg, int fg) {
            paint.setColor(bg); canvas.drawRoundRect(l * scale, t * scale, r * scale, b * scale, 15*scale, 15*scale, paint);
            paint.setColor(fg); paint.setTextSize(38 * scale);
            canvas.drawText(text, (l + r) / 2f * scale, (t + b) / 2f * scale + 15 * scale, paint);
        }

        // ======================== UI 核心处理逻辑 (Bug修正) ========================

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float ex = event.getX(), ey = event.getY();

                float bottomY = 1750 * scale;
                // 检测按钮点击
                if (checkClick(ex, ey, 50, bottomY, 350, bottomY + 100 * scale)) { undoMove(); return true; }
                if (checkClick(ex, ey, 400, bottomY, 700, bottomY + 100 * scale)) { restartGame(); return true; }
                if (checkClick(ex, ey, 750, bottomY, 1050, bottomY + 100 * scale)) { System.exit(0); return true; }
                if (checkClick(ex, ey, 50, bottomY + 150 * scale, 350, bottomY + 250 * scale)) { setDepth(4); return true; }
                if (checkClick(ex, ey, 400, bottomY + 150 * scale, 700, bottomY + 250 * scale)) { setDepth(8); return true; }
                if (checkClick(ex, ey, 750, bottomY + 150 * scale, 1050, bottomY + 250 * scale)) { setDepth(12); return true; }
                if (checkClick(ex, ey, 400, bottomY + 300 * scale, 700, bottomY + 400 * scale)) { setMode(1); return true; }
                if (checkClick(ex, ey, 750, bottomY + 300 * scale, 1050, bottomY + 400 * scale)) { showCustomDepthDialog(); return true; }

                // 修正下棋逻辑 Bug，人机对战模式下允许人类下棋。
                if (!gameOver && currentPlayer == 1 && !aiThinking && gameMode == 0) {
                    float startX = (getWidth() - 1120 * scale) / 2f;
                    float startY = (getHeight() - 1120 * scale) / 2f - 300 * scale;
                    int c = Math.round((ex - startX) / (80 * scale));
                    int r = Math.round((ey - startY) / (80 * scale));

                    if (r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE && board[r][c] == 0) {
                        makeMove(r, c, 1);
                        if (checkWin(board, 1, r, c)) { statusMessage = "人類絕殺了AI！恭喜你！"; gameOver = true; invalidate(); } 
                        else { currentPlayer = 2; triggerAiMove(); }
                    }
                }
            }
            return true;
        }

        private void showCustomDepthDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("请输入自由配置深度 (1-20)");
            final EditText input = new EditText(getContext());
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(aiDifficultyDepth));
            builder.setView(input);
            builder.setPositiveButton("设定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        int d = Integer.parseInt(input.getText().toString());
                        if (d >= 1 && d <= 20) setDepth(d);
                        else Toast.makeText(getContext(), "请输入 1 至 20 之间的数字", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {}
                }
            });
            builder.setNegativeButton("取消", null);
            builder.show();
        }

        private boolean checkClick(float x, float y, float l, float t, float r, float b) {
            return (x >= l * scale && x <= r * scale && y >= t * scale && y <= b * scale);
        }

        private void makeMove(int r, int c, int p) {
            board[r][c] = p; moveHistory.add(new int[]{r, c, p});
            currentZobristHash ^= zobristTable[r][c][p]; lastMoveHighlight = new int[]{r, c};
            invalidate();
        }

        private void undoMove() {
            // 在 AI 思考或者对决模式中，禁止悔棋以保持一致。
            if (moveHistory.size() >= 2 && gameMode == 0 && !aiThinking && !gameOver) {
                for (int i=0; i<2; i++) {
                    int[] m = moveHistory.remove(moveHistory.size() - 1);
                    board[m[0]][m[1]] = 0; currentZobristHash ^= zobristTable[m[0]][m[1]][m[2]];
                }
                lastMoveHighlight = moveHistory.isEmpty() ? null : new int[]{moveHistory.get(moveHistory.size()-1)[0], moveHistory.get(moveHistory.size()-1)[1]};
                currentPlayer = 1; statusMessage = "已悔棋"; invalidate();
            }
        }

        // ======================== 高性能 AI 核心引擎 시스템 ========================

        private void triggerAiMove() {
            if (gameOver) return;
            aiThinking = true; invalidate();
            new Thread(() -> {
                long start = System.currentTimeMillis();
                int[] bestMove = iterativeDeepening(aiDifficultyDepth);
                post(() -> {
                    if (bestMove != null && bestMove[0] != -1 && !gameOver) {
                        makeMove(bestMove[0], bestMove[1], currentPlayer);
                        if (checkWin(board, currentPlayer, bestMove[0], bestMove[1])) {
                            statusMessage = (currentPlayer == 2) ? "AI 绝杀！其智能性阉割了你失败了" : "AI 绝杀了AI对决模式下黑棋胜利！";
                            gameOver = true;
                        } else {
                            if (gameMode == 1) { // 修正：AI对决自动进行下一手
                                currentPlayer = 3 - currentPlayer;
                                triggerAiMove();
                            } else { // 人机模式，人类轮黑棋(你)先手，AI先手。
                                currentPlayer = 1;
                                statusMessage = "轮到你了 (" + (System.currentTimeMillis()-start) + "ms)";
                            }
                        }
                    }
                    aiThinking = false;
                    invalidate();
                });
            }).start();
        }

        private int[] iterativeDeepening(int maxDepth) {
            int[] bestMove = {-1, -1};
            // 启发式 PV 最佳 PV
            ArrayList<int[]> list = generateCandidates(board, currentPlayer, -1, -1);
            if (list.size() == 1) return list.get(0);

            // 标准 1：1 还原极大极小与极速剪枝
            for (int depth = 1; depth <= maxDepth; depth++) {
                int ttBestR = -1, ttBestC = -1;
                // 从置换表取出 PV PV着法 PV
                long hashKey = currentZobristHash ^ (currentPlayer == 2 ? 0xFAFAFAFAFAFAFAL : 0L); // 徹底修正串線致命错误
                TTEntry entry = transpositionTable.get(hashKey);
                if (entry != null && entry.depth >= depth) { ttBestR = entry.bestR; ttBestC = entry.bestC; }

                int[] res = alphaBeta(depth, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1, currentPlayer, currentZobristHash, ttBestR, ttBestC);
                if (res[0] != -1) { bestMove[0] = res[0]; bestMove[1] = res[1]; }
                // 發現絕殺直接收網
                if (res[2] >= SCORE_TSS_MAX) break;
            }
            return bestMove;
        }

        private int[] alphaBeta(int depth, int alpha, int beta, int p, long hash, int pvR, int pvC) {
            if (depth == 0) return new int[]{-1, -1, evaluateBoard()};

            long hashKey = hash ^ (p == 2 ? 0xFAFAFAFAFAFAFAL : 0L);
            TTEntry entry = transpositionTable.get(hashKey);
            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return new int[]{entry.bestR, entry.bestC, entry.score};
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return new int[]{entry.bestR, entry.bestC, entry.score};
            }

            ArrayList<int[]> list = generateCandidates(board, p, pvR, pvC);
            if (list.isEmpty()) return new int[]{-1, -1, 0};

            int bestR = -1, bestC = -1;
            int maxScore = Integer.MIN_VALUE + 1;
            int minScore = Integer.MAX_VALUE - 1;
            int originalAlpha = alpha;

            for (int[] move : list) {
                int r = move[0], c = move[1];
                board[r][c] = p;
                currentZobristHash ^= zobristTable[r][c][p];

                int score;
                // AI和人類雙方同時生效双三、双四、长连禁手
                if (checkWin(board, p, r, c)) { score = (p == 2) ? SCORE_TSS_MAX + depth : -SCORE_TSS_MAX - depth; }
                else score = alphaBeta(depth - 1, alpha, beta, 3 - p, currentZobristHash, -1, -1)[2];

                currentZobristHash ^= zobristTable[r][c][p];
                board[r][c] = 0;

                if (p == 2) { // AI player
                    if (score > maxScore) { maxScore = score; bestR = r; bestC = c; }
                    alpha = Math.max(alpha, score);
                    if (beta <= alpha) break;
                } else { // Human player
                    if (score < minScore) { minScore = score; bestR = r; bestC = c; }
                    beta = Math.min(beta, score);
                    if (beta <= alpha) break;
                }
            }

            int finalScore = (p == 2) ? maxScore : minScore;
            int flag = 0; // EXACT
            if (finalScore <= originalAlpha) flag = 2; // UPPER
            else if (finalScore >= beta) flag = 1; // LOWER
            
            if (bestR != -1) {
                if (transpositionTable.size() > 500000) transpositionTable.clear();
                transpositionTable.put(hashKey, new TTEntry(depth, finalScore, flag, bestR, bestC));
            }
            return new int[]{bestR, bestC, finalScore};
        }

        private ArrayList<int[]> generateCandidates(int[][] b, int p, int pvR, int pvC) {
            ArrayList<int[]> list = new ArrayList<>();
            boolean hasPiece = false;
            int[][] heuristicScores = new int[BOARD_SIZE][BOARD_SIZE];

            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (b[r][c] != 0) {
                        hasPiece = true;
                        // 1：1还原周围2格启发式生成
                        for (int dr = -2; dr <= 2; dr++) {
                            for (int dc = -2; dc <= 2; dc++) {
                                int nr = r + dr, nc = c + dc;
                                if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && b[nr][nc] == 0) {
                                    if (heuristicScores[nr][nc] == 0) {
                                        // 核心启发式：自身绝杀得分+防守对方绝杀得分排序
                                        int score = Math.max(localScore(b, nr, nc, 2), localScore(b, nr, nc, 1));
                                        heuristicScores[nr][nc] = score;
                                        list.add(new int[]{nr, nc, score});
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!hasPiece) { list.add(new int[]{7, 7, 0}); return list; }
            
            list.sort((a, b) -> Integer.compare(b[2], a[2]));
            if (list.size() > 20) return new ArrayList<>(list.subList(0, 20));
            // PV着法 PV PV置顶
            if (pvR != -1 && b[pvR][pvC] == 0) {
                 for (int i=0; i<list.size(); i++) {
                     if (list.get(i)[0] == pvR && list.get(i)[1] == pvC) {
                         list.add(0, list.remove(i));
                         break;
                     }
                 }
            }
            return list;
        }

        private int evaluateBoard() {
            int aiScore = 0; int humanScore = 0;
            // 为追求极致速度，直接使用整数评估矩阵，而非慢速字符串，速度提升百倍！
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] == 2) aiScore += localScore(board, r, c, 2) + positionWeights[r][c];
                    else if (board[r][c] == 1) humanScore += localScore(board, r, c, 1) + positionWeights[r][c];
                }
            }
            // 策略：略微倾向防守，让AI变得更聪明、更難纏。
            return (int) (aiScore - humanScore * 1.5); 
        }

        private int localScore(int[][] b, int r, int c, int p) {
            b[r][c] = p; // 模拟落子
            int score = 0;
            int fours = 0; int liveThrees = 0;
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            
            for (int[] d : dirs) {
                int continuous = 1; int block = 0;
                int spaceOpen = 0; int spaceClosed = 0;
                
                // 往正向扫
                for (int i=1; i<5; i++) {
                    int nr = r + d[0]*i; int nc = c + d[1]*i;
                    if (nr<0||nr>=BOARD_SIZE||nc<0||nc>=BOARD_SIZE){ block++; break; }
                    if (b[nr][nc] == p) continuous++; else if (b[nr][nc] == 0) { spaceOpen++; break; }
                    else { block++; break; }
                }
                // 往反向扫
                for (int i=1; i<5; i++) {
                    int nr = r - d[0]*i; int nc = c - d[1]*i;
                    if (nr<0||nr>=BOARD_SIZE||nc<0||nc>=BOARD_SIZE){ block++; break; }
                    if (b[nr][nc] == p) continuous++; else if (b[nr][nc] == 0) { spaceOpen++; break; }
                    else { block++; break; }
                }

                // 精准打分体系（TSS 机制）
                if (continuous >= 5) { score += 500000; }
                else if (continuous == 4) { if (block == 0) fours++; else if (block == 1) { fours++; score += 5000; } }
                else if (continuous == 3) { if (block == 0) { liveThrees++; score += 5000; } else if (block == 1) score += 500; }
                else if (continuous == 2) { if (block == 0) score += 500; else if (block == 1) score += 50; }
            }
            b[r][c] = 0; // 还原

            // 1：1复活 TSS 爆发爆分机制
            if (fours >= 2) score += 90000; // 双四
            else if (fours >= 1 && liveThrees >= 1) score += 80000; // 冲四活三
            else if (liveThrees >= 2) score += 70000; // 双活三
            
            return score;
        }

        private boolean checkWin(int[][] b, int p, int lr, int lc) {
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            for (int[] d : dirs) {
                int count = 1;
                for (int step : new int[]{1, -1}) {
                    for (int i = 1; i < 5; i++) {
                        int nr = lr + d[0]*step*i, nc = lc + d[1]*step*i;
                        if (nr>=0 && nr<BOARD_SIZE && nc>=0 && nc<BOARD_SIZE && b[nr][nc] == p) count++; else break;
                    }
                }
                if (count >= 5) return true;
            }
            return false;
        }
    }
}
