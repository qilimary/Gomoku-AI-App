package com.gomoku.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
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
        private int currentPlayer = 1; // 1: 人类, 2: AI
        private int aiDifficultyDepth = 8;
        private boolean aiThinking = false;
        private boolean gameOver = false;
        private String statusMessage = "玩家先手";

        // 终极优化数据结构
        private long[][][] zobristTable = new long[BOARD_SIZE][BOARD_SIZE][3];
        private long currentZobristHash = 0;
        private HashMap<Long, TTEntry> transpositionTable = new HashMap<>();
        private int[][] positionWeights = new int[BOARD_SIZE][BOARD_SIZE];

        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Random rand = new Random();
        private float scale = 1.0f;

        class TTEntry {
            int depth, score, flag; // 0:EXACT, 1:LOWERBOUND, 2:UPPERBOUND
            int bestR, bestC;
            TTEntry(int d, int s, int f, int r, int c) {
                depth = d; score = s; flag = f; bestR = r; bestC = c;
            }
        }

        public GomokuView(Context context) {
            super(context);
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
            currentPlayer = rand.nextInt(2) + 1;
            currentZobristHash = 0;
            transpositionTable.clear();
            aiThinking = false;
            gameOver = false;
            statusMessage = (currentPlayer == 1) ? "游戏开始：玩家先手" : "游戏开始：AI先手";
            invalidate();
            if (currentPlayer == 2) triggerAiMove();
        }

        // ======================== 核心算法：完美移植并超越 Python 智能 ========================

        // 完美复活禁手规则判断
        private boolean isForbiddenMove(int[][] b, int r, int c, int p) {
            b[r][c] = p;
            
            // 连五具有最高优先度，如果直接连五则不算禁手
            if (checkWin(r, c, p)) {
                b[r][c] = 0;
                return false;
            }

            int fours = 0;
            int liveThrees = 0;
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};

            for (int[] d : dirs) {
                int[] line = extractLine(b, r, c, d);
                int centerIdx = 4; // 抽取的连线中心位置

                // 1. 长连禁手判定
                if (countContinuous(line, centerIdx, p) >= 6) {
                    b[r][c] = 0;
                    return true;
                }

                // 2. 统计冲四/活四数
                if (checkFourPattern(line, p)) fours++;
                // 3. 统计活三数
                if (checkLiveThreePattern(line, p)) liveThrees++;
            }

            b[r][c] = 0;
            // 双三或双四禁手触发
            return fours >= 2 || liveThrees >= 2;
        }

        // 修复后
        private int countContinuous(int[] line, int center, int p) {
            int count = 1;
            for (int i = center + 1; i < line.length && line[i] == p; i++) count++;
            for (int i = center - 1; i >= 0 && line[i] == p; i--) count++; // 改为 count++
            return count;
        }

        // 高速滑窗匹配替代低效字符串
        private boolean checkFourPattern(int[] line, int p) {
            // 包含 [0,p,p,p,p], [p,p,p,p,0], [p,0,p,p,p], [p,p,0,p,p] 等
            int[][] patterns = {
                {0,p,p,p,p}, {p,p,p,p,0}, {p,0,p,p,p}, {p,p,0,p,p}, {p,p,p,0,p}
            };
            return matchAnyPattern(line, patterns);
        }

        private boolean checkLiveThreePattern(int[] line, int p) {
            // 完美还原活三的严格定义：[0,p,p,p,0,0], [0,0,p,p,p,0], [0,p,0,p,p,0], [0,p,p,0,p,0]
            int[][] patterns = {
                {0,p,p,p,0,0}, {0,0,p,p,p,0}, {0,p,0,p,p,0}, {0,p,p,0,p,0}
            };
            return matchAnyPattern(line, patterns);
        }

        private boolean matchAnyPattern(int[] line, int[][] patterns) {
            for (int[] p : patterns) {
                for (int i = 0; i <= line.length - p.length; i++) {
                    boolean match = true;
                    for (int j = 0; j < p.length; j++) {
                        if (line[i+j] != p[j]) { match = false; break; }
                    }
                    if (match) return true;
                }
            }
            return false;
        }

        // 提取以(r,c)为中心，前后各伸展4格的连续线段（处理跳跃棋型的核心）
        private int[] extractLine(int[][] b, int r, int c, int[] d) {
            int[] line = new int[9];
            for (int i = -4; i <= 4; i++) {
                int nr = r + d[0] * i;
                int nc = c + d[1] * i;
                if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE) {
                    line[i + 4] = b[nr][nc];
                } else {
                    line[i + 4] = -1; // 边界障碍物
                }
            }
            return line;
        }

        // 完美复活包含 TSS 机制的局部评估
        private int localScore(int[][] b, int r, int c, int p) {
            int score = 0;
            int fours = 0;
            int liveThrees = 0;
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};

            b[r][c] = p;
            for (int[] d : dirs) {
                int[] line = extractLine(b, r, c, d);
                int consecutive = countContinuous(line, 4, p);

                if (consecutive >= 5) { b[r][c] = 0; return 1000000; }
                
                if (checkFourPattern(line, p)) {
                    fours++;
                    score += 2000;
                } else if (checkLiveThreePattern(line, p)) {
                    liveThrees++;
                    score += 2000;
                }
            }
            b[r][c] = 0;

            // TSS 绝杀检测级爆分机制复活
            if (fours >= 2) score += 90000;
            else if (fours >= 1 && liveThrees >= 1) score += 80000;
            else if (liveThrees >= 2) score += 70000;

            return score;
        }

        private void triggerAiMove() {
            aiThinking = true;
            statusMessage = "AI 正在使用 TSS 引擎深度思考 (深度:" + aiDifficultyDepth + ")...";
            invalidate();
            new Thread(() -> {
                long start = System.currentTimeMillis();
                int[] bestMove = iterativeDeepening(aiDifficultyDepth);
                post(() -> {
                    if (bestMove != null && bestMove[0] != -1 && !gameOver) {
                        makeMove(bestMove[0], bestMove[1], 2);
                        if (checkWin(bestMove[0], bestMove[1], 2)) {
                            statusMessage = "AI 绝杀！你失败了";
                            gameOver = true;
                        } else {
                            currentPlayer = 1;
                            statusMessage = "轮到你了 (" + (System.currentTimeMillis() - start) + "ms)";
                        }
                    }
                    aiThinking = false;
                    invalidate();
                });
            }).start();
        }

        private int[] iterativeDeepening(int maxDepth) {
            int[] bestMove = {-1, -1};
            for (int d = 1; d <= maxDepth; d++) {
                int[] res = alphaBeta(d, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1, 2);
                if (res[0] != -1) {
                    bestMove[0] = res[res.length - 3]; 
                    bestMove[1] = res[res.length - 2];
                    if (res[res.length - 1] >= 900000) break; // 发现必胜绝杀步，直接提前收网
                }
            }
            return bestMove;
        }

        private int[] alphaBeta(int depth, int alpha, int beta, int player) {
            if (depth == 0) return new int[]{-1, -1, evaluateBoard()};

            // 彻底修复置换表串线 Bug：将当前玩家的标识融入哈希 Key 之中
            long hashKey = currentZobristHash ^ (player == 2 ? 0xFAFBFCFDL : 0L);
            TTEntry entry = transpositionTable.get(hashKey);
            int ttBestR = -1, ttBestC = -1;

            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return new int[]{entry.bestR, entry.bestC, entry.score};
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return new int[]{entry.bestR, entry.bestC, entry.score};
                ttBestR = entry.bestR; ttBestC = entry.bestC;
            }

            // 生成候选步骤，并将置换表缓存的最佳着法（PV Move）提到最前面优化剪枝
            ArrayList<int[]> moves = generateOrderedMoves(ttBestR, ttBestC);
            if (moves.isEmpty()) return new int[]{-1, -1, 0};

            int bestR = -1, bestC = -1;
            int maxScore = Integer.MIN_VALUE + 1;
            int minScore = Integer.MAX_VALUE - 1;
            int originalAlpha = alpha;

            for (int[] move : moves) {
                int r = move[0], c = move[1];
                
                // 自动规避禁手步
                if (isForbiddenMove(board, r, c, player)) continue;

                board[r][c] = player;
                currentZobristHash ^= zobristTable[r][c][player];

                int score;
                if (checkWin(r, c, player)) {
                    score = (player == 2) ? 1000000 + depth : -1000000 - depth;
                } else {
                    score = alphaBeta(depth - 1, alpha, beta, 3 - player)[2];
                }

                currentZobristHash ^= zobristTable[r][c][player];
                board[r][c] = 0;

                if (player == 2) {
                    if (score > maxScore) { maxScore = score; bestR = r; bestC = c; }
                    alpha = Math.max(alpha, score);
                    if (alpha >= beta) break;
                } else {
                    if (score < minScore) { minScore = score; bestR = r; bestC = c; }
                    beta = Math.min(beta, score);
                    if (alpha >= beta) break;
                }
            }

            int finalScore = (player == 2) ? maxScore : minScore;
            int flag = 0;
            if (finalScore <= originalAlpha) flag = 2;
            else if (finalScore >= beta) flag = 1;

            if (bestR != -1) {
                if (transpositionTable.size() > 400000) transpositionTable.clear();
                transpositionTable.put(hashKey, new TTEntry(depth, finalScore, flag, bestR, bestC));
            }

            return new int[]{bestR, bestC, finalScore};
        }

        private ArrayList<int[]> generateOrderedMoves(int pvR, int pvC) {
            ArrayList<int[]> list = new ArrayList<>();
            boolean hasPiece = false;
            boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];

            // 优先把置换表传回的最佳位置加进去
            if (pvR >= 0 && pvR < BOARD_SIZE && pvC >= 0 && pvC < BOARD_SIZE && board[pvR][pvC] == 0) {
                int score = localScore(board, pvR, pvC, 2) + localScore(board, pvR, pvC, 1);
                list.add(new int[]{pvR, pvC, score + 100000}); // 给予 PV 着法极高权重置顶
                visited[pvR][pvC] = true;
            }

            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] != 0) {
                        hasPiece = true;
                        for (int dr = -2; dr <= 2; dr++) {
                            for (int dc = -2; dc <= 2; dc++) {
                                int nr = r + dr, nc = c + dc;
                                if (nr >= 0 && nr < BOARD_SIZE && nc >= 0 && nc < BOARD_SIZE && board[nr][nc] == 0) {
                                    if (!visited[nr][nc]) {
                                        int score = localScore(board, nr, nc, 2) + localScore(board, nr, nc, 1);
                                        list.add(new int[]{nr, nc, score});
                                        visited[nr][nc] = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!hasPiece) { list.add(new int[]{7, 7, 0}); return list; }

            // 依据攻击+防守的总评贡献进行启发式降序排列
            list.sort((a, b) -> Integer.compare(b[2], a[2]));
            return new ArrayList<>(list.subList(0, Math.min(15, list.size())));
        }

        private int evaluateBoard() {
            int totalScore = 0;
            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] == 2) {
                        totalScore += localScore(board, r, c, 2) + positionWeights[r][c];
                    } else if (board[r][c] == 1) {
                        totalScore -= (localScore(board, r, c, 1) * 1.3) + positionWeights[r][c];
                    }
                }
            }
            return totalScore;
        }

        private boolean checkWin(int r, int c, int p) {
            int[][] dirs = {{1,0}, {0,1}, {1,1}, {1,-1}};
            for (int[] d : dirs) {
                int count = 1;
                for (int step : new int[]{1, -1}) {
                    for (int i = 1; i < 5; i++) {
                        int nr = r + d[0]*step*i, nc = c + d[1]*step*i;
                        if (nr>=0 && nr<BOARD_SIZE && nc>=0 && nc<BOARD_SIZE && board[nr][nc] == p) count++; else break;
                    }
                }
                if (count >= 5) return true;
            }
            return false;
        }

        // ======================== UI 视图与按键处理系统 ========================

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scaleX = (float) getWidth() / 1360f;
            float scaleY = (float) getHeight() / 2400f;
            scale = Math.min(scaleX, scaleY);

            canvas.drawColor(Color.WHITE);
            drawGrid(canvas);
            drawUI(canvas);
        }

        private void drawGrid(Canvas canvas) {
            float startX = 80 * scale, startY = 360 * scale, size = 14 * 80 * scale;
            paint.setColor(Color.BLACK); paint.setStrokeWidth(4 * scale);
            canvas.drawRect(startX, startY, startX + size, startY + size, paint);

            paint.setStrokeWidth(2 * scale);
            for (int i = 1; i < 14; i++) {
                canvas.drawLine(startX, startY + i * 80 * scale, startX + size, startY + i * 80 * scale, paint);
                canvas.drawLine(startX + i * 80 * scale, startY, startX + i * 80 * scale, startY + size, paint);
            }

            int[] stars = {3, 7, 11};
            paint.setStyle(Paint.Style.FILL);
            for (int r : stars) {
                for (int c : stars) canvas.drawCircle(startX + c * 80 * scale, startY + r * 80 * scale, 8 * scale, paint);
            }

            for (int r = 0; r < BOARD_SIZE; r++) {
                for (int c = 0; c < BOARD_SIZE; c++) {
                    if (board[r][c] != 0) {
                        paint.setColor(board[r][c] == 1 ? Color.BLACK : Color.WHITE);
                        float cx = startX + c * 80 * scale, cy = startY + r * 80 * scale;
                        canvas.drawCircle(cx, cy, 32 * scale, paint);
                        if (board[r][c] == 2) {
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
            canvas.drawText(statusMessage, getWidth() / 2f, 360 * scale + 14 * 80 * scale + 100 * scale, paint);

            drawBtn(canvas, "悔棋", 50, 1750, 350, 1850, Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "重玩", 400, 1750, 700, 1850, Color.GREEN, Color.BLACK);
            drawBtn(canvas, "退出", 750, 1750, 1050, 1850, Color.RED, Color.WHITE);

            drawBtn(canvas, "深度 4", 50, 1900, 350, 2000, aiDifficultyDepth == 4 ? Color.CYAN : Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "深度 8", 400, 1900, 700, 2000, aiDifficultyDepth == 8 ? Color.CYAN : Color.LTGRAY, Color.BLACK);
            drawBtn(canvas, "深度 12", 750, 1900, 1050, 2000, aiDifficultyDepth == 12 ? Color.CYAN : Color.LTGRAY, Color.BLACK);

            drawBtn(canvas, "自由配置深度: " + aiDifficultyDepth, 250, 2060, 850, 2160, Color.rgb(255, 140, 0), Color.WHITE);
        }

        private void drawBtn(Canvas canvas, String text, float l, float t, float r, float b, int bg, int fg) {
            paint.setColor(bg); canvas.drawRoundRect(l * scale, t * scale, r * scale, b * scale, 15*scale, 15*scale, paint);
            paint.setColor(fg); paint.setTextSize(38 * scale);
            canvas.drawText(text, (l + r) / 2f * scale, (t + b) / 2f * scale + 14 * scale, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float ex = event.getX(), ey = event.getY();

                if (checkClick(ex, ey, 50, 1750, 350, 1850)) { undoMove(); return true; }
                if (checkClick(ex, ey, 400, 1750, 700, 1850)) { restartGame(); return true; }
                if (checkClick(ex, ey, 750, 1750, 1050, 1850)) { System.exit(0); return true; }
                if (checkClick(ex, ey, 50, 1900, 350, 2000)) { setDepth(4); return true; }
                if (checkClick(ex, ey, 400, 1900, 700, 2000)) { setDepth(8); return true; }
                if (checkClick(ex, ey, 750, 1900, 1050, 2000)) { setDepth(12); return true; }
                if (checkClick(ex, ey, 250, 2060, 850, 2160)) { showCustomDepthDialog(); return true; }

                if (!gameOver && currentPlayer == 1 && !aiThinking) {
                    int c = Math.round((ex - 80 * scale) / (80 * scale));
                    int r = Math.round((ey - 360 * scale) / (80 * scale));
                    if (r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE && board[r][c] == 0) {
                        if (isForbiddenMove(board, r, c, 1)) {
                            statusMessage = "下棋失败：此位置为黑棋禁手点！";
                        } else {
                            makeMove(r, c, 1);
                            if (checkWin(r, c, 1)) { statusMessage = "人类绝杀了 AI！游戏结束"; gameOver = true; } 
                            else { currentPlayer = 2; triggerAiMove(); }
                        }
                        invalidate();
                    }
                }
            }
            return true;
        }

        private void setDepth(int d) { aiDifficultyDepth = d; statusMessage = "深度已变更为: " + d; invalidate(); }

        private void showCustomDepthDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("请输入自由配置深度 (1-20)");
            final EditText input = new EditText(getContext());
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(aiDifficultyDepth));
            builder.setView(input);
            builder.setPositiveButton("注入算力", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        int d = Integer.parseInt(input.getText().toString());
                        if (d >= 1 && d <= 20) setDepth(d);
                        else Toast.makeText(getContext(), "只允许配置在 1 至 20 层之间", Toast.LENGTH_SHORT).show();
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
        }

        private void undoMove() {
            if (moveHistory.size() >= 2 && !aiThinking) {
                for (int i=0; i<2; i++) {
                    int[] m = moveHistory.remove(moveHistory.size() - 1);
                    board[m[0]][m[1]] = 0; currentZobristHash ^= zobristTable[m[0]][m[1]][m[2]];
                }
                lastMoveHighlight = moveHistory.isEmpty() ? null : new int[]{moveHistory.get(moveHistory.size()-1)[0], moveHistory.get(moveHistory.size()-1)[1]};
                currentPlayer = 1; gameOver = false; statusMessage = "已成功悔棋"; invalidate();
            }
        }
    }
}
