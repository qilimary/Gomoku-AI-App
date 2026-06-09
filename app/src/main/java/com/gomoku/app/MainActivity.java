package com.gomoku.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gameView = new GameView(this);
        setContentView(gameView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.saveGameState(); 
        }
    }

    class GameView extends View {
        // ================= 全局共享状态 =================
        private int currentGameType = 0; // 0 = 五子棋, 1 = 中国象棋
        private float w, margin, startX, startY;
        private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Random rand = new Random();
        private volatile boolean keepThinking = false; 

        private final int[][] G_DIRS = {{1,0}, {0,1}, {1,1}, {1,-1}};
        private final int[] G_STEPS = {1, -1};
        private final int[][] X_DIRS_CROSS = {{-1,0},{1,0},{0,-1},{0,1}};
        private final int[][] X_DIRS_KNIGHT = {{-2,-1},{-2,1},{2,-1},{2,1},{-1,-2},{1,-2},{-1,2},{1,2}};
        private final int[][] X_DIRS_BISHOP = {{-2,-2},{-2,2},{2,-2},{2,2}};
        private final int[][] X_DIRS_ADVISOR = {{-1,-1},{-1,1},{1,-1},{1,1}};

        // ================= 五子棋 变量 =================
        private final int G_BOARD_SIZE = 15;
        private float g_cellSize, g_boardSize;
        private int[][] g_board = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private int[][] g_displayBoard = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private ArrayList<int[]> g_moveHistory = new ArrayList<>();
        private int[] g_lastMoveHighlight = null;
        private int g_gameMode = 0, g_humanColor = 1, g_currentPlayer = 1; 
        private int g_aiDepth = 4;
        private volatile boolean g_aiThinking = false, g_gameOver = false;
        private String g_statusMsg = "";
        private volatile int g_thinkingSec = 0;
        private long[][][] g_zobristTable = new long[G_BOARD_SIZE][G_BOARD_SIZE][3];
        private long g_zobristHash = 0;
        private ConcurrentHashMap<Long, GTTEntry> g_ttTable = new ConcurrentHashMap<>();
        private int[][] g_posWeights = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private ThreadLocal<int[]> g_tlLine = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[9]; } };

        final int[][] G_LIVE_FOUR = {{0,1,1,1,1,0}};
        final int[][] G_DEAD_FOUR = {{1,1,1,1,0}, {0,1,1,1,1}, {1,0,1,1,1}, {1,1,0,1,1}, {1,1,1,0,1}};
        final int[][] G_LIVE_THREE = {{0,1,1,1,0,0}, {0,0,1,1,1,0}, {0,1,0,1,1,0}, {0,1,1,0,1,0}};

        class GTTEntry {
            int depth, score, flag, bestR, bestC;
            GTTEntry(int d, int s, int f, int r, int c) { depth = d; score = s; flag = f; bestR = r; bestC = c; }
        }

        // ================= 中国象棋 变量 =================
        private float x_cellSize, x_boardWidth, x_boardHeight;
        private char[][] x_board = new char[10][9];
        private char[][] x_displayBoard = new char[10][9];
        private ArrayList<XMoveRecord> x_moveHistory = new ArrayList<>();
        private int[] x_selectedPos = null;
        private int[] x_aiLastMove = null;
        private ArrayList<Integer> x_validMovesDisplay = new ArrayList<>();
        private int x_gameMode = 0; 
        private int x_aiDepth = 6;
        private boolean x_isRedTurn = true;
        private volatile boolean x_aiThinking = false, x_gameOver = false;
        private String x_statusMsg = "红方走";
        private volatile int x_thinkingSec = 0;
        private long[][][] x_zobristTable = new long[10][9][14];
        private long[] x_zobristTurn = new long[2];
        private long x_zobristHash = 0;
        private ConcurrentHashMap<Long, XTTEntry> x_ttTable = new ConcurrentHashMap<>();
        
        private int[][] x_depthMoves = new int[30][150]; 
        private ThreadLocal<int[]> x_tlMoves = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[150]; } };
        private ThreadLocal<int[]> x_tlCheckMoves = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[150]; } };
        
        // 新增：象棋杀手走法表，提升剪枝效率
        private int[] x_killerMoves = new int[30];

        class XMoveRecord {
            int sr, sc, er, ec; char captured;
            XMoveRecord(int sr, int sc, int er, int ec, char captured) {
                this.sr=sr; this.sc=sc; this.er=er; this.ec=ec; this.captured=captured;
            }
        }
        class XTTEntry {
            int depth, score, flag, bestMove; // 修复：将bestMove直接存入Entry，防止哈希冲突导致智障走法
            XTTEntry(int d, int s, int f, int bm) { depth=d; score=s; flag=f; bestMove=bm; }
        }

        private Runnable timerRunnable = new Runnable() {
            @Override
            public void run() {
                boolean isThinking = (currentGameType == 0) ? g_aiThinking : x_aiThinking;
                if (isThinking) {
                    if (currentGameType == 0) g_thinkingSec++; else x_thinkingSec++;
                    updateStatusMsg();
                    invalidate();
                    postDelayed(this, 1000);
                }
            }
        };

        public GameView(Context context) {
            super(context);
            initGomokuEngine();
            initXiangqiEngine();
            loadGameState(); 
        }

        public void saveGameState() {
            SharedPreferences.Editor ed = getContext().getSharedPreferences("BoardGamesSave", Context.MODE_PRIVATE).edit();
            ed.putInt("gameType", currentGameType);
            ed.putInt("g_aiDepth", g_aiDepth); ed.putInt("x_aiDepth", x_aiDepth);
            ed.putInt("g_gameMode", g_gameMode); ed.putInt("x_gameMode", x_gameMode);
            ed.putInt("g_humanColor", g_humanColor);
            
            StringBuilder gHist = new StringBuilder();
            for (int[] m : g_moveHistory) gHist.append(m[0]).append(",").append(m[1]).append(",").append(m[2]).append(";");
            ed.putString("g_history", gHist.toString());

            StringBuilder xHist = new StringBuilder();
            for (XMoveRecord m : x_moveHistory) xHist.append(m.sr).append(",").append(m.sc).append(",").append(m.er).append(",").append(m.ec).append(";");
            ed.putString("x_history", xHist.toString());
            ed.apply();
        }

        private void loadGameState() {
            SharedPreferences prefs = getContext().getSharedPreferences("BoardGamesSave", Context.MODE_PRIVATE);
            currentGameType = prefs.getInt("gameType", 0);
            g_aiDepth = prefs.getInt("g_aiDepth", 4); x_aiDepth = prefs.getInt("x_aiDepth", 6);
            g_gameMode = prefs.getInt("g_gameMode", 0); x_gameMode = prefs.getInt("x_gameMode", 0);
            g_humanColor = prefs.getInt("g_humanColor", 1);
            
            restartGomoku(false); restartXiangqi(false);

            String gHist = prefs.getString("g_history", "");
            if (!gHist.isEmpty()) {
                String[] moves = gHist.split(";");
                for (String ms : moves) {
                    if (ms.isEmpty()) continue;
                    String[] parts = ms.split(",");
                    g_makeMove(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    g_currentPlayer = 3 - Integer.parseInt(parts[2]);
                }
            }

            String xHist = prefs.getString("x_history", "");
            if (!xHist.isEmpty()) {
                String[] moves = xHist.split(";");
                for (String ms : moves) {
                    if (ms.isEmpty()) continue;
                    String[] parts = ms.split(",");
                    x_makeMove(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
            }

            syncGomokuBoard(); syncXiangqiBoard();
            updateStatusMsg(); invalidate();
            
            if (g_moveHistory.isEmpty() && g_gameMode == 0 && g_humanColor == 2 && currentGameType == 0) triggerGomokuAiMove();
        }

        private void updateStatusMsg() {
            if (currentGameType == 0) {
                if (g_gameOver) return;
                if (g_gameMode == 1) g_statusMsg = (g_currentPlayer == 1) ? "轮到黑棋" : "轮到白棋";
                else {
                    if (g_currentPlayer == g_humanColor) g_statusMsg = "轮到玩家下棋";
                    else g_statusMsg = (g_aiDepth > 5) ? "AI思考中... 已思考 " + g_thinkingSec + "s" : "AI下棋中...";
                }
            } else {
                if (x_gameOver) return;
                if (x_gameMode == 1) x_statusMsg = x_isRedTurn ? "红方走" : "黑方走";
                else {
                    if (x_isRedTurn) x_statusMsg = "轮到玩家走 (红方)";
                    else x_statusMsg = "AI思考中... 已思考 " + x_thinkingSec + "s";
                }
            }
        }

        // ================= 五子棋核心逻辑 =================

        private void initGomokuEngine() {
            for (int i=0; i<G_BOARD_SIZE; i++)
                for (int j=0; j<G_BOARD_SIZE; j++)
                    for (int k=0; k<3; k++) g_zobristTable[i][j][k] = rand.nextLong();
            int center = G_BOARD_SIZE / 2;
            for (int r=0; r<G_BOARD_SIZE; r++)
                for (int c=0; c<G_BOARD_SIZE; c++) g_posWeights[r][c] = (center - Math.max(Math.abs(center-r), Math.abs(center-c))) * 10;
        }

        private void syncGomokuBoard() {
            for (int i=0; i<G_BOARD_SIZE; i++) System.arraycopy(g_board[i], 0, g_displayBoard[i], 0, G_BOARD_SIZE);
        }

        private void restartGomoku(boolean triggerAI) {
            g_board = new int[G_BOARD_SIZE][G_BOARD_SIZE];
            g_moveHistory.clear();
            g_lastMoveHighlight = null; g_zobristHash = 0; g_ttTable.clear();
            g_aiThinking = false; keepThinking = false; g_gameOver = false;
            g_currentPlayer = 1;
            if (g_gameMode == 0 && triggerAI) g_humanColor = rand.nextBoolean() ? 1 : 2;
            syncGomokuBoard(); updateStatusMsg(); invalidate();
            if (triggerAI && g_gameMode == 0 && g_humanColor == 2 && currentGameType == 0) triggerGomokuAiMove();
        }

        private boolean g_isForbiddenMove(int[][] b, int r, int c, int p) {
            int original = b[r][c]; b[r][c] = p;
            if (g_checkWin(b, r, c, p)) { b[r][c] = original; return false; }
            int fours = 0, liveThrees = 0;
            for (int[] d : G_DIRS) {
                int[] line = g_extractLine(b, r, c, d);
                if (g_countContinuous(line, 4, p) >= 6) { b[r][c] = original; return true; } 
                if (g_matchAny(line, p, G_LIVE_FOUR) || g_matchAny(line, p, G_DEAD_FOUR)) fours++;
                if (g_matchAny(line, p, G_LIVE_THREE)) liveThrees++;
            }
            b[r][c] = original; return fours >= 2 || liveThrees >= 2;
        }

        private int g_countContinuous(int[] line, int center, int p) {
            int count = 1;
            for (int i=center+1; i<line.length && line[i]==p; i++) count++;
            for (int i=center-1; i>=0 && line[i]==p; i--) count++;
            return count;
        }

        private int[] g_extractLine(int[][] b, int r, int c, int[] d) {
            int[] line = g_tlLine.get();
            for (int i=-4; i<=4; i++) {
                int nr = r + d[0]*i, nc = c + d[1]*i;
                line[i+4] = (nr>=0 && nr<G_BOARD_SIZE && nc>=0 && nc<G_BOARD_SIZE) ? b[nr][nc] : -1;
            }
            return line;
        }

        private boolean g_matchAny(int[] line, int p, int[][] patterns) {
            for (int[] pat : patterns) {
                for (int i=0; i<=9-pat.length; i++) {
                    boolean match = true;
                    for (int j=0; j<pat.length; j++) {
                        if (pat[j] == 1 && line[i+j] != p) { match=false; break; }
                        if (pat[j] == 0 && line[i+j] != 0) { match=false; break; }
                    }
                    if (match) return true;
                }
            }
            return false;
        }

        private int g_localScore(int[][] b, int r, int c, int p) {
            int score = 0, liveFours = 0, deadFours = 0, liveThrees = 0;
            int original = b[r][c]; b[r][c] = p;
            for (int[] d : G_DIRS) {
                int[] line = g_extractLine(b, r, c, d);
                int cons = g_countContinuous(line, 4, p);
                if (cons >= 5) { b[r][c]=original; return 1000000; }
                if (g_matchAny(line, p, G_LIVE_FOUR)) { liveFours++; score += 100000; } 
                else if (g_matchAny(line, p, G_DEAD_FOUR)) { deadFours++; score += 3000; }
                if (g_matchAny(line, p, G_LIVE_THREE)) { liveThrees++; score += 3000; }
            }
            b[r][c] = original; 
            if (liveFours > 0) score += 100000;
            if (deadFours >= 2) score += 90000;
            else if (deadFours >= 1 && liveThrees >= 1) score += 80000;
            else if (liveThrees >= 2) score += 70000;
            return score;
        }

        private int[] g_getOpeningMove() {
            if (g_moveHistory.isEmpty()) return new int[]{7, 7};
            if (g_moveHistory.size() == 1) {
                int hr = g_moveHistory.get(0)[0], hc = g_moveHistory.get(0)[1];
                int[][] responses = {{hr-1, hc}, {hr+1, hc}, {hr, hc-1}, {hr, hc+1}, 
                                     {hr-1, hc-1}, {hr-1, hc+1}, {hr+1, hc-1}, {hr+1, hc+1}};
                ArrayList<int[]> valid = new ArrayList<>();
                for (int[] r : responses) if (r[0]>=0 && r[0]<G_BOARD_SIZE && r[1]>=0 && r[1]<G_BOARD_SIZE) valid.add(r);
                if (!valid.isEmpty()) return valid.get(rand.nextInt(valid.size()));
            }
            return null;
        }

        private void triggerGomokuAiMove() {
            g_aiThinking = true; keepThinking = true; g_thinkingSec = 0;
            updateStatusMsg(); invalidate();
            removeCallbacks(timerRunnable); postDelayed(timerRunnable, 1000); 

            new Thread(() -> {
                int[] finalBestMove = null;
                try {
                    int[] bestMove = g_getOpeningMove();
                    if (bestMove == null) bestMove = g_iterativeDeepening(g_aiDepth);
                    finalBestMove = bestMove;
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    final int[] moveToPlay = finalBestMove;
                    post(() -> {
                        removeCallbacks(timerRunnable); g_aiThinking = false;
                        if (!keepThinking) {
                            g_statusMsg = "思考已中断，请重新落子";
                            g_undoMove(); return;
                        }
                        if (moveToPlay != null && moveToPlay[0] != -1 && !g_gameOver) {
                            if (g_isForbiddenMove(g_board, moveToPlay[0], moveToPlay[1], g_currentPlayer)) {
                                g_statusMsg = "AI算出禁手，重新评估";
                                updateStatusMsg(); invalidate();
                                g_currentPlayer = g_humanColor; // 交还回合重算
                                return;
                            }
                            g_makeMove(moveToPlay[0], moveToPlay[1], g_currentPlayer);
                            if (g_checkWin(g_board, moveToPlay[0], moveToPlay[1], g_currentPlayer)) { g_statusMsg = "AI赢了！"; g_gameOver = true; } 
                            else { g_currentPlayer = g_humanColor; updateStatusMsg(); }
                        } else {
                            g_statusMsg = "出现错误或无子可下";
                        }
                        invalidate();
                    });
                }
            }).start();
        }

        private int[] g_iterativeDeepening(int maxDepth) {
            int[] best = {-1, -1}; int aiColor = 3 - g_humanColor;
            for (int d=1; d<=maxDepth; d++) {
                if (!keepThinking) break;
                int[] res = g_alphaBeta(d, Integer.MIN_VALUE+1, Integer.MAX_VALUE-1, aiColor);
                if (res[0] != -1) { best[0]=res[0]; best[1]=res[1]; if (res[2]>=900000) break; }
            }
            return best;
        }

        private int[] g_alphaBeta(int depth, int alpha, int beta, int player) {
            if (depth == 0) return new int[]{-1, -1, g_evaluateBoard()};
            int aiColor = 3 - g_humanColor;
            long hashKey = g_zobristHash ^ (player==aiColor ? 0xFAFBFCFDL : 0L);
            GTTEntry entry = g_ttTable.get(hashKey);
            int ttR = -1, ttC = -1;
            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return new int[]{entry.bestR, entry.bestC, entry.score};
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return new int[]{entry.bestR, entry.bestC, entry.score};
                ttR = entry.bestR; ttC = entry.bestC;
            }

            // 优化：返回打包的int数组，消除对象创建带来的GC停顿
            int[] moves = g_genMoves(ttR, ttC, aiColor);
            if (moves.length == 0) return new int[]{-1, -1, 0};

            int bestR = -1, bestC = -1, max = Integer.MIN_VALUE+1, min = Integer.MAX_VALUE-1, origAlpha = alpha;
            for (int m : moves) {
                if (!keepThinking) break;
                int r = (m >> 8) & 0xFF;
                int c = m & 0xFF;

                // 优化：搜索中移除耗时禁手判断，AI不会主动下禁手送死
                g_board[r][c] = player; g_zobristHash ^= g_zobristTable[r][c][player];
                int score = g_checkWin(g_board, r, c, player) ? ((player==aiColor) ? 1000000+depth : -1000000-depth) 
                                                              : g_alphaBeta(depth-1, alpha, beta, 3-player)[2];
                g_zobristHash ^= g_zobristTable[r][c][player]; g_board[r][c] = 0;

                if (player == aiColor) {
                    if (score > max) { max = score; bestR = r; bestC = c; }
                    alpha = Math.max(alpha, score); if (alpha >= beta) break;
                } else {
                    if (score < min) { min = score; bestR = r; bestC = c; }
                    beta = Math.min(beta, score); if (alpha >= beta) break;
                }
            }
            int fScore = (player == aiColor) ? max : min;
            int flag = (fScore <= origAlpha) ? 2 : (fScore >= beta ? 1 : 0);
            if (bestR != -1 && keepThinking) {
                // 优化：移除 clear()，让置换表自然覆盖，保持迭代加深优势
                g_ttTable.put(hashKey, new GTTEntry(depth, fScore, flag, bestR, bestC));
            }
            return new int[]{bestR, bestC, fScore};
        }

        private boolean g_hasNeighbor(int[][] b, int r, int c, int dist) {
            for (int i=Math.max(0,r-dist); i<=Math.min(G_BOARD_SIZE-1,r+dist); i++)
                for (int j=Math.max(0,c-dist); j<=Math.min(G_BOARD_SIZE-1,c+dist); j++)
                    if (b[i][j] != 0) return true;
            return false;
        }

        // 优化：不再 new int[]，改用基本类型打包，彻底消除 GC 卡顿
        private int[] g_genMoves(int pvR, int pvC, int aiColor) {
            int[] list = new int[15 * 15]; 
            int size = 0; int hC = 3-aiColor;
            int minR = G_BOARD_SIZE, maxR = 0, minC = G_BOARD_SIZE, maxC = 0;
            
            for(int r=0; r<G_BOARD_SIZE; r++) {
                for(int c=0; c<G_BOARD_SIZE; c++) {
                    if(g_board[r][c] != 0) {
                        if(r < minR) minR = r; if(r > maxR) maxR = r;
                        if(c < minC) minC = c; if(c > maxC) maxC = c;
                    }
                }
            }
            if (maxR == 0) { return new int[]{(100000 << 16) | (7 << 8) | 7}; } // 空盘走天元
            minR = Math.max(0, minR-3); maxR = Math.min(G_BOARD_SIZE-1, maxR+3);
            minC = Math.max(0, minC-3); maxC = Math.min(G_BOARD_SIZE-1, maxC+3);

            if (pvR>=minR && pvR<=maxR && pvC>=minC && pvC<=maxC && g_board[pvR][pvC]==0) {
                int s = g_localScore(g_board,pvR,pvC,aiColor)+g_localScore(g_board,pvR,pvC,hC)+100000;
                list[size++] = (s << 16) | (pvR << 8) | pvC;
            }

            for (int r=minR; r<=maxR; r++) {
                for (int c=minC; c<=maxC; c++) {
                    if (g_board[r][c]==0 && g_hasNeighbor(g_board,r,c,3) && (r!=pvR||c!=pvC)) {
                        int s = g_localScore(g_board,r,c,aiColor)+g_localScore(g_board,r,c,hC);
                        list[size++] = (s << 16) | (r << 8) | c;
                    }
                }
            }
            
            // 冒泡排序，仅排序前12个最高分走法
            for (int i=0; i<Math.min(12, size); i++) {
                for (int j=i+1; j<size; j++) {
                    if ((list[i] >>> 16) < (list[j] >>> 16)) { // 无符号右移比较分数
                        int temp = list[i]; list[i] = list[j]; list[j] = temp;
                    }
                }
            }
            
            int[] result = new int[Math.min(12, size)];
            System.arraycopy(list, 0, result, 0, result.length);
            return result;
        }

        // 优化：边界裁剪，只评估有棋子附近的分数，极大加速叶节点评估
        private int g_evaluateBoard() {
            int score = 0, aiC = 3-g_humanColor;
            int minR = 15, maxR = 0, minC = 15, maxC = 0;
            for (int r=0; r<G_BOARD_SIZE; r++) {
                for (int c=0; c<G_BOARD_SIZE; c++) {
                    if (g_board[r][c] != 0) {
                        minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                        minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                    }
                }
            }
            if (maxR == 0) return 0;
            
            for (int r=Math.max(0, minR-1); r<=Math.min(G_BOARD_SIZE-1, maxR+1); r++) {
                for (int c=Math.max(0, minC-1); c<=Math.min(G_BOARD_SIZE-1, maxC+1); c++) {
                    if (g_board[r][c] == aiC) score += g_localScore(g_board, r, c, aiC) + g_posWeights[r][c];
                    else if (g_board[r][c] == g_humanColor) score -= (int)(g_localScore(g_board, r, c, g_humanColor)*1.3) + g_posWeights[r][c];
                }
            }
            return score;
        }

        private boolean g_checkWin(int[][] b, int r, int c, int p) {
            for (int[] d : G_DIRS) {
                int count=1;
                for (int step : G_STEPS) {
                    for (int i=1; i<5; i++) {
                        int nr = r + d[0]*step*i, nc = c + d[1]*step*i;
                        if (nr>=0 && nr<G_BOARD_SIZE && nc>=0 && nc<G_BOARD_SIZE && b[nr][nc]==p) count++; else break;
                    }
                }
                if (count >= 5) return true;
            }
            return false;
        }

        private void g_makeMove(int r, int c, int p) {
            g_board[r][c] = p; g_moveHistory.add(new int[]{r,c,p});
            g_zobristHash ^= g_zobristTable[r][c][p]; g_lastMoveHighlight = new int[]{r,c};
            syncGomokuBoard();
        }

        private void g_undoMove() {
            if (g_gameOver) return;
            int pops = g_gameMode==0 ? 2 : 1;
            if (g_moveHistory.size() >= pops) {
                for (int i=0; i<pops; i++) {
                    int[] m = g_moveHistory.remove(g_moveHistory.size()-1);
                    g_board[m[0]][m[1]] = 0; g_zobristHash ^= g_zobristTable[m[0]][m[1]][m[2]];
                    g_currentPlayer = m[2];
                }
                g_lastMoveHighlight = g_moveHistory.isEmpty() ? null : new int[]{g_moveHistory.get(g_moveHistory.size()-1)[0], g_moveHistory.get(g_moveHistory.size()-1)[1]};
                syncGomokuBoard(); updateStatusMsg(); invalidate();
            }
        }

        // ================= 中国象棋 核心逻辑 =================

        private void initXiangqiEngine() {
            for (int i=0; i<10; i++)
                for (int j=0; j<9; j++)
                    for (int k=0; k<14; k++) x_zobristTable[i][j][k] = rand.nextLong();
            x_zobristTurn[0] = rand.nextLong(); x_zobristTurn[1] = rand.nextLong();
        }

        private void restartXiangqi(boolean triggerAI) {
            String[] init = {
                "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
                ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
            };
            for (int i=0; i<10; i++) x_board[i] = init[i].toCharArray();
            
            x_zobristHash = 0;
            for(int r=0; r<10; r++) {
                for(int c=0; c<9; c++) {
                    if(x_board[r][c] != '.') {
                        x_zobristHash ^= x_zobristTable[r][c][getPieceIndex(x_board[r][c])];
                    }
                }
            }

            x_moveHistory.clear(); x_selectedPos = null; x_aiLastMove = null; x_validMovesDisplay.clear();
            x_isRedTurn = true; x_aiThinking = false; keepThinking = false; x_gameOver = false;
            x_ttTable.clear(); 
            for(int i=0; i<30; i++) x_killerMoves[i] = -1; // 初始化杀手表
            syncXiangqiBoard(); updateStatusMsg(); invalidate();
            if (triggerAI && x_gameMode == 0 && !x_isRedTurn) triggerXiangqiAiMove();
        }

        private void syncXiangqiBoard() {
            for (int i=0; i<10; i++) System.arraycopy(x_board[i], 0, x_displayBoard[i], 0, 9);
        }

        private int getPieceIndex(char p) {
            String map = "rnbakcpRNBAKCP"; int idx = map.indexOf(p);
            return idx >= 0 ? idx : 0;
        }

        private boolean x_isRed(char p) { return p >= 'A' && p <= 'Z'; }

        private int x_genValidMoves(char[][] b, int r, int c, int[] movesList) {
            char p = b[r][c]; if (p == '.') return 0;
            boolean red = x_isRed(p); int size = 0;
            char lp = Character.toLowerCase(p);

            if (lp == 'r') {
                for (int[] d : X_DIRS_CROSS) {
                    int nr = r+d[0], nc = c+d[1];
                    while (nr>=0 && nr<10 && nc>=0 && nc<9) {
                        if (b[nr][nc]=='.') movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                        else { if(x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc; break; }
                        nr+=d[0]; nc+=d[1];
                    }
                }
            } else if (lp == 'n') {
                for (int[] d : X_DIRS_KNIGHT) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nr>=0&&nr<10&&nc>=0&&nc<9) {
                        if (Math.abs(d[0])==2 && b[r+d[0]/2][c]!='.') continue;
                        if (Math.abs(d[1])==2 && b[r][c+d[1]/2]!='.') continue;
                        if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'b') {
                for (int[] d : X_DIRS_BISHOP) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nr>=0&&nr<10&&nc>=0&&nc<9) {
                        if (red && nr<5) continue; if (!red && nr>4) continue;
                        if (b[r+d[0]/2][c+d[1]/2]=='.') {
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                        }
                    }
                }
            } else if (lp == 'a') {
                for (int[] d : X_DIRS_ADVISOR) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nc>=3&&nc<=5) {
                        if ((red && nr>=7 && nr<=9) || (!red && nr>=0 && nr<=2))
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'k') {
                for (int[] d : X_DIRS_CROSS) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nc>=3&&nc<=5) {
                        if ((red && nr>=7 && nr<=9) || (!red && nr>=0 && nr<=2))
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'c') {
                for (int[] d : X_DIRS_CROSS) {
                    int nr = r+d[0], nc = c+d[1]; boolean jumped = false;
                    while (nr>=0 && nr<10 && nc>=0 && nc<9) {
                        if (b[nr][nc]=='.') { if(!jumped) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc; }
                        else {
                            if (!jumped) jumped = true;
                            else { if(x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc; break; }
                        }
                        nr+=d[0]; nc+=d[1];
                    }
                }
            } else if (lp == 'p') {
                int dir = red ? -1 : 1;
                if (r+dir >= 0 && r+dir < 10) {
                    if (b[r+dir][c]=='.' || x_isRed(b[r+dir][c])!=red) movesList[size++] = (r<<12)|(c<<8)|((r+dir)<<4)|c;
                }
                if ((red && r<5) || (!red && r>4)) {
                    if (c-1 >= 0 && (b[r][c-1]=='.' || x_isRed(b[r][c-1])!=red)) movesList[size++] = (r<<12)|(c<<8)|(r<<4)|(c-1);
                    if (c+1 < 9 && (b[r][c+1]=='.' || x_isRed(b[r][c+1])!=red)) movesList[size++] = (r<<12)|(c<<8)|(r<<4)|(c+1);
                }
            }
            return size;
        }

        private boolean x_isKingsFacing(char[][] b) {
            int ry=-1, rx=-1, by=-1, bx=-1;
            for (int r=0; r<10; r++) {
                for (int c=3; c<=5; c++) {
                    if (b[r][c]=='K') { rx=r; ry=c; } else if (b[r][c]=='k') { bx=r; by=c; }
                }
            }
            if (ry!=by || rx==-1 || bx==-1) return false;
            for (int r=bx+1; r<rx; r++) if (b[r][ry]!='.') return false;
            return true;
        }

        private boolean x_isInCheck(char[][] b, boolean isRed) {
            int tr=-1, tc=-1;
            for (int r=0; r<10; r++) { 
                for (int c=3; c<=5; c++) {
                    if (isRed && b[r][c]=='K') { tr=r; tc=c; break; } 
                    else if (!isRed && b[r][c]=='k') { tr=r; tc=c; break; }
                }
            }
            if (tr==-1) return true; 

            int forward = isRed ? -1 : 1;
            if (tr+forward >= 0 && tr+forward < 10 && b[tr+forward][tc] != '.' && x_isRed(b[tr+forward][tc]) != isRed) {
                if (Character.toLowerCase(b[tr+forward][tc]) == 'p') return true;
            }
            if (tc-1 >= 0 && b[tr][tc-1] != '.' && x_isRed(b[tr][tc-1]) != isRed) {
                if (Character.toLowerCase(b[tr][tc-1]) == 'p') return true;
            }
            if (tc+1 < 9 && b[tr][tc+1] != '.' && x_isRed(b[tr][tc+1]) != isRed) {
                if (Character.toLowerCase(b[tr][tc+1]) == 'p') return true;
            }

            for (int[] d : X_DIRS_KNIGHT) {
                int nr = tr + d[0], nc = tc + d[1];
                if (nr >= 0 && nr < 10 && nc >= 0 && nc < 9) {
                    char p = b[nr][nc];
                    if (p != '.' && x_isRed(p) != isRed && Character.toLowerCase(p) == 'n') {
                        int dr = tr - nr, dc = tc - nc;
                        int pinR = nr, pinC = nc;
                        if (Math.abs(dr) == 2) pinR += dr/2;
                        if (Math.abs(dc) == 2) pinC += dc/2;
                        if (b[pinR][pinC] == '.') return true;
                    }
                }
            }

            for (int[] d : X_DIRS_CROSS) {
                int nr = tr + d[0], nc = tc + d[1];
                int piecesBetween = 0;
                while (nr >= 0 && nr < 10 && nc >= 0 && nc < 9) {
                    char p = b[nr][nc];
                    if (p != '.') {
                        if (x_isRed(p) != isRed) {
                            char lp = Character.toLowerCase(p);
                            if (piecesBetween == 0 && lp == 'r') return true;
                            if (piecesBetween == 1 && lp == 'c') return true;
                        }
                        piecesBetween++;
                        if (piecesBetween > 1) break;
                    }
                    nr += d[0]; nc += d[1];
                }
            }
            return false;
        }

        private boolean x_isLegalMove(char[][] b, int move, boolean isRed) {
            int sr = move>>12, sc = (move>>8)&0xF, er = (move>>4)&0xF, ec = move&0xF;
            char cap = b[er][ec]; b[er][ec] = b[sr][sc]; b[sr][sc] = '.';
            boolean legal = !x_isInCheck(b, isRed) && !x_isKingsFacing(b);
            b[sr][sc] = b[er][ec]; b[er][ec] = cap;
            return legal;
        }

        private boolean x_checkGameOver(char[][] b, boolean isRed) {
            int[] moves = x_tlMoves.get();
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    if (b[r][c]!='.' && x_isRed(b[r][c])==isRed) {
                        int count = x_genValidMoves(b, r, c, moves);
                        for (int i=0; i<count; i++) if (x_isLegalMove(b, moves[i], isRed)) return false;
                    }
                }
            }
            return true;
        }

        // 智能优化：强化位置评估和开路线奖励
        private int x_evalBoard(char[][] b) {
            int score = 0;
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    char p = b[r][c]; if (p=='.') continue;
                    int val = 0; boolean isR = x_isRed(p); char lp = Character.toLowerCase(p);
                    
                    if (lp=='k') val=10000; 
                    else if (lp=='r') {
                        val=900;
                        if (c==3||c==4||c==5) val+=15;
                        boolean isOpen = true;
                        int pawnDir = isR ? -1 : 1;
                        for(int pr = r + pawnDir; pr >= 0 && pr < 10; pr += pawnDir) {
                            if(b[pr][c] == (isR ? 'P' : 'p')) { isOpen = false; break; }
                        }
                        if(isOpen) val += 20; // 车占开路线奖励
                    } else if (lp=='c') {
                        val=450;
                        if (c==4) val+=20;
                    } else if (lp=='n') {
                        val=400;
                        val += (4 - Math.abs(c-4))*5; 
                        if (isR && r<=4) val += 30; 
                        else if (!isR && r>=5) val += 30; 
                        if (isR && (r==2 || r==1) && (c==2 || c==6)) val += 50;
                        else if (!isR && (r==7 || r==8) && (c==2 || c==6)) val += 50;
                        if (r==0||r==9) val -= 30; // 马在底线极度无用
                    } else if (lp=='b') val=200; 
                    else if (lp=='a') val=200; 
                    else if (lp=='p') {
                        val=100;
                        if (isR && r<=4) {
                            val += 150; 
                            if (r>=1 && r<=3) val += 50; 
                            if (c>=3 && c<=5) val += 30; 
                        } else if (!isR && r>=5) {
                            val += 150; 
                            if (r>=6 && r<=8) val += 50; 
                            if (c>=3 && c<=5) val += 30; 
                        }
                    }
                    if (isR) score+=val; else score-=val;
                }
            }
            return score;
        }

        private void triggerXiangqiAiMove() {
            x_aiThinking = true; keepThinking = true; x_thinkingSec = 0;
            updateStatusMsg(); invalidate();
            removeCallbacks(timerRunnable); postDelayed(timerRunnable, 1000);

            new Thread(() -> {
                int[] finalBestMove = new int[]{-1};
                try {
                    finalBestMove = x_iterativeDeepening(x_aiDepth);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    final int best = finalBestMove[0];
                    post(() -> {
                        removeCallbacks(timerRunnable); x_aiThinking = false;
                        if (!keepThinking) { x_statusMsg = "思考已中断"; x_undoMove(); return; }
                        
                        if (best != -1) {
                            int sr=best>>12, sc=(best>>8)&0xF, er=(best>>4)&0xF, ec=best&0xF;
                            x_makeMove(sr, sc, er, ec);
                            if (x_checkGameOver(x_board, x_isRedTurn)) { x_statusMsg = "绝杀！"+(x_isRedTurn?"黑":"红")+"方胜利！"; x_gameOver = true; }
                            else updateStatusMsg();
                        } else { x_statusMsg = "绝杀！你胜利了！"; x_gameOver = true; }
                        invalidate();
                    });
                }
            }).start();
        }

        private int[] x_iterativeDeepening(int maxDepth) {
            int bestMove = -1;
            for (int d=1; d<=maxDepth; d++) {
                if (!keepThinking) break;
                long hashKey = x_zobristHash ^ x_zobristTurn[x_isRedTurn?0:1];
                int res = x_alphaBeta(d, Integer.MIN_VALUE+1, Integer.MAX_VALUE-1, x_isRedTurn, hashKey, false);
                if (!keepThinking) break;
                XTTEntry en = x_ttTable.get(hashKey);
                // 修复：直接读取置换表里的bestMove，彻底杜绝哈希异或带来的走法崩坏
                if (en != null && en.depth == d && en.bestMove != -1) {
                    bestMove = en.bestMove; 
                    if (res > 90000) break;
                }
            }
            return new int[]{bestMove};
        }

        private int x_alphaBeta(int depth, int alpha, int beta, boolean isMax, long hash, boolean isNull) {
            if (x_isKingsFacing(x_board)) return isMax ? 100000 : -100000;
            
            // 智能优化：将军延伸，被将军时加深搜索，防止漏算连杀
            boolean inCheck = x_isInCheck(x_board, isMax);
            if (inCheck) depth++;
            
            if (depth <= 0) {
                if (isMax && x_isInCheck(x_board, false)) return 100000;
                if (!isMax && x_isInCheck(x_board, true)) return -100000;
                return x_evalBoard(x_board);
            }

            // 空步裁剪
            if (!isNull && depth >= 3 && !inCheck) {
                long nextHash = hash ^ x_zobristTurn[0] ^ x_zobristTurn[1];
                int val = x_alphaBeta(depth - 3, alpha, beta, !isMax, nextHash, true); 
                if (isMax && val >= beta) return beta;
                if (!isMax && val <= alpha) return alpha;
            }

            XTTEntry entry = x_ttTable.get(hash);
            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return entry.score;
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return entry.score;
            }

            int[] allMoves = x_depthMoves[depth]; int moveCount = 0;
            int[] genMoves = x_tlMoves.get(); 
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    if (x_board[r][c]!='.' && x_isRed(x_board[r][c])==isMax) {
                        int n = x_genValidMoves(x_board, r, c, genMoves);
                        for (int i=0; i<n; i++) allMoves[moveCount++] = genMoves[i];
                    }
                }
            }
            if (moveCount == 0) return isMax ? -100000 : 100000;

            // 走法排序：1.吃子优先 2.杀手走法优先
            int captureCount = 0;
            for (int i=0; i<moveCount; i++) {
                int m = allMoves[i], er = (m>>4)&0xF, ec = m&0xF;
                if (x_board[er][ec] != '.') {
                    int temp = allMoves[captureCount];
                    allMoves[captureCount] = m;
                    allMoves[i] = temp;
                    captureCount++;
                }
            }
            // 杀手启发排序
            if (x_killerMoves[depth] != -1) {
                for (int i=captureCount; i<moveCount; i++) {
                    if (allMoves[i] == x_killerMoves[depth]) {
                        int temp = allMoves[captureCount];
                        allMoves[captureCount] = allMoves[i];
                        allMoves[i] = temp;
                        break;
                    }
                }
            }

            int bestVal = isMax ? Integer.MIN_VALUE+1 : Integer.MAX_VALUE-1, flag = 0, bestM = -1, origA = alpha;
            for (int i=0; i<moveCount; i++) {
                if (!keepThinking) break;
                int m = allMoves[i];
                if (!x_isLegalMove(x_board, m, isMax)) continue;

                int sr=m>>12, sc=(m>>8)&0xF, er=(m>>4)&0xF, ec=m&0xF;
                char cap = x_board[er][ec];
                x_board[er][ec] = x_board[sr][sc]; x_board[sr][sc] = '.';
                long nextHash = hash ^ x_zobristTable[sr][sc][getPieceIndex(x_board[er][ec])] ^ x_zobristTable[er][ec][getPieceIndex(x_board[er][ec])] ^ x_zobristTurn[0]^x_zobristTurn[1];
                if (cap!='.') nextHash ^= x_zobristTable[er][ec][getPieceIndex(cap)];

                int val = x_alphaBeta(depth-1, alpha, beta, !isMax, nextHash, false);
                x_board[sr][sc] = x_board[er][ec]; x_board[er][ec] = cap;

                if (isMax) {
                    if (val > bestVal) { bestVal = val; bestM = m; }
                    alpha = Math.max(alpha, bestVal); if (alpha >= beta) { flag=1; break; }
                } else {
                    if (val < bestVal) { bestVal = val; bestM = m; }
                    beta = Math.min(beta, bestVal); if (alpha >= beta) { flag=2; break; }
                }
            }

            if (keepThinking && bestM != -1) {
                // 修复：移除造成缓存崩坏的 x_ttTable.put(hash ^ 0xABCDEFL, ...) 逻辑
                // 优化：移除 clear()，让置换表自动替换
                x_ttTable.put(hash, new XTTEntry(depth, bestVal, flag, bestM));
                if (flag != 0 && depth < 30) x_killerMoves[depth] = bestM; // 记录杀手走法
            }
            return bestVal;
        }

        private void x_makeMove(int sr, int sc, int er, int ec) {
            char p = x_board[sr][sc];
            char cap = x_board[er][ec];
            
            x_zobristHash ^= x_zobristTable[sr][sc][getPieceIndex(p)];
            if (cap != '.') x_zobristHash ^= x_zobristTable[er][ec][getPieceIndex(cap)];
            x_zobristHash ^= x_zobristTable[er][ec][getPieceIndex(p)];

            x_board[er][ec] = p; x_board[sr][sc] = '.';
            x_moveHistory.add(new XMoveRecord(sr, sc, er, ec, cap));
            x_aiLastMove = new int[]{sr, sc, er, ec}; x_selectedPos = null; x_validMovesDisplay.clear();
            x_isRedTurn = !x_isRedTurn;
            syncXiangqiBoard();
        }

        private void x_undoMove() {
            if (x_gameOver) return;
            int pops = x_gameMode==0 ? 2 : 1;
            if (x_moveHistory.size() >= pops) {
                for (int i=0; i<pops; i++) {
                    XMoveRecord m = x_moveHistory.remove(x_moveHistory.size()-1);
                    char p = x_board[m.er][m.ec];
                    
                    x_zobristHash ^= x_zobristTable[m.er][m.ec][getPieceIndex(p)];
                    if (m.captured != '.') x_zobristHash ^= x_zobristTable[m.er][m.ec][getPieceIndex(m.captured)];
                    x_zobristHash ^= x_zobristTable[m.sr][m.sc][getPieceIndex(p)];

                    x_board[m.sr][m.sc] = p;
                    x_board[m.er][m.ec] = m.captured;
                    x_isRedTurn = !x_isRedTurn;
                }
                x_aiLastMove = x_moveHistory.isEmpty() ? null : new int[]{
                    x_moveHistory.get(x_moveHistory.size()-1).sr,
                    x_moveHistory.get(x_moveHistory.size()-1).sc,
                    x_moveHistory.get(x_moveHistory.size()-1).er,
                    x_moveHistory.get(x_moveHistory.size()-1).ec
                }; 
                x_selectedPos = null; x_validMovesDisplay.clear();
                syncXiangqiBoard(); updateStatusMsg(); invalidate();
            }
        }

        // ================= 绘图与 UI 引擎 =================

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            w = getWidth(); margin = w * 0.06f; 
            
            canvas.drawColor(currentGameType == 0 ? Color.WHITE : Color.parseColor("#F5DEB3"));

            String modeStr = currentGameType == 0 ? (g_gameMode == 0 ? "模式: 人机对战" : "模式: 双人对战") : (x_gameMode == 0 ? "模式: 人机对战" : "模式: 双人对战");
            float modeBtnW = w * 0.40f, modeBtnH = w * 0.12f, modeBtnX = w / 2 - modeBtnW / 2, modeBtnY = margin;
            drawBtn(canvas, modeStr, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH, Color.parseColor("#E0E0E0"), Color.BLACK, w * 0.04f);

            float ruleBtnW = w * 0.10f; float ruleBtnX = w - margin - ruleBtnW;
            drawBtn(canvas, "❓", ruleBtnX, margin, ruleBtnX + ruleBtnW, margin + ruleBtnW, Color.parseColor("#EEEEEE"), Color.BLACK, w * 0.05f);

            float g_size = w - 2 * margin;
            float x_size = (g_size / 8f) * 9f;
            float heightDiff = x_size - g_size;

            if (currentGameType == 0) {
                startY = margin + w * 0.25f;
                drawGomokuUI(canvas, g_size);
            } else {
                startY = margin + w * 0.25f - heightDiff / 2f; 
                drawXiangqiUI(canvas, g_size);
            }
        }

        private void drawGomokuUI(Canvas canvas, float size) {
            g_boardSize = size; g_cellSize = g_boardSize / 14f; startX = margin;
            
            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5f);
            canvas.drawRect(startX, startY, startX+g_boardSize, startY+g_boardSize, paint);
            paint.setStrokeWidth(2f);
            for (int i=1; i<14; i++) {
                canvas.drawLine(startX, startY+i*g_cellSize, startX+g_boardSize, startY+i*g_cellSize, paint);
                canvas.drawLine(startX+i*g_cellSize, startY, startX+i*g_cellSize, startY+g_boardSize, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            for (int r : new int[]{3,7,11}) for (int c : new int[]{3,7,11}) canvas.drawCircle(startX+c*g_cellSize, startY+r*g_cellSize, g_cellSize*0.15f, paint);

            for (int r=0; r<G_BOARD_SIZE; r++) {
                for (int c=0; c<G_BOARD_SIZE; c++) {
                    if (g_displayBoard[r][c] != 0) {
                        float cx = startX + c*g_cellSize, cy = startY + r*g_cellSize;
                        paint.setColor(g_displayBoard[r][c]==1 ? Color.BLACK : Color.WHITE);
                        canvas.drawCircle(cx, cy, g_cellSize*0.42f, paint);
                        if (g_displayBoard[r][c]==2) {
                            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE);
                            canvas.drawCircle(cx, cy, g_cellSize*0.42f, paint); paint.setStyle(Paint.Style.FILL);
                        }
                        if (g_lastMoveHighlight != null && g_lastMoveHighlight[0]==r && g_lastMoveHighlight[1]==c) {
                            paint.setColor(Color.RED); canvas.drawCircle(cx, cy, g_cellSize*0.1f, paint);
                        }
                    }
                }
            }

            drawBottomMenu(canvas, startY + g_boardSize, g_statusMsg, g_aiThinking, g_aiDepth);
        }

        private void drawXiangqiUI(Canvas canvas, float width) {
            x_boardWidth = width; x_cellSize = x_boardWidth / 8f; x_boardHeight = x_cellSize * 9f; startX = margin;

            paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4f);
            for (int r=0; r<10; r++) canvas.drawLine(startX, startY+r*x_cellSize, startX+x_boardWidth, startY+r*x_cellSize, paint);
            for (int c=0; c<9; c++) {
                if (c==0 || c==8) canvas.drawLine(startX+c*x_cellSize, startY, startX+c*x_cellSize, startY+x_boardHeight, paint);
                else {
                    canvas.drawLine(startX+c*x_cellSize, startY, startX+c*x_cellSize, startY+4*x_cellSize, paint);
                    canvas.drawLine(startX+c*x_cellSize, startY+5*x_cellSize, startX+c*x_cellSize, startY+x_boardHeight, paint);
                }
            }
            canvas.drawLine(startX+3*x_cellSize, startY, startX+5*x_cellSize, startY+2*x_cellSize, paint);
            canvas.drawLine(startX+5*x_cellSize, startY, startX+3*x_cellSize, startY+2*x_cellSize, paint);
            canvas.drawLine(startX+3*x_cellSize, startY+7*x_cellSize, startX+5*x_cellSize, startY+9*x_cellSize, paint);
            canvas.drawLine(startX+5*x_cellSize, startY+7*x_cellSize, startX+3*x_cellSize, startY+9*x_cellSize, paint);

            paint.setStyle(Paint.Style.FILL); paint.setTextSize(x_cellSize * 0.6f); paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics(); float ty = startY + 4.5f*x_cellSize - (fm.descent+fm.ascent)/2f;
            canvas.drawText("楚 河            汉 界", startX + 4*x_cellSize, ty, paint);

            if (x_aiLastMove != null) {
                paint.setColor(Color.parseColor("#00C8FF")); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(6f);
                float radius = x_cellSize * 0.45f;
                canvas.drawRoundRect(startX+x_aiLastMove[1]*x_cellSize-radius, startY+x_aiLastMove[0]*x_cellSize-radius, startX+x_aiLastMove[1]*x_cellSize+radius, startY+x_aiLastMove[0]*x_cellSize+radius, 8, 8, paint);
                canvas.drawRoundRect(startX+x_aiLastMove[3]*x_cellSize-radius, startY+x_aiLastMove[2]*x_cellSize-radius, startX+x_aiLastMove[3]*x_cellSize+radius, startY+x_aiLastMove[2]*x_cellSize+radius, 8, 8, paint);
            }

            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    char p = x_displayBoard[r][c];
                    if (p != '.') {
                        float cx = startX + c*x_cellSize, cy = startY + r*x_cellSize; float rad = x_cellSize * 0.42f;
                        int col = x_isRed(p) ? Color.parseColor("#DC143C") : Color.BLACK;
                        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor("#FFEFD5"));
                        canvas.drawCircle(cx, cy, rad, paint);
                        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4f); paint.setColor(col);
                        canvas.drawCircle(cx, cy, rad, paint); canvas.drawCircle(cx, cy, rad*0.8f, paint);
                        paint.setStyle(Paint.Style.FILL); paint.setTextSize(rad*1.2f);
                        String txt = String.valueOf("车马象士将炮卒车马相仕帅炮兵".charAt("rnbakcpRNBAKCP".indexOf(p)));
                        canvas.drawText(txt, cx, cy - (paint.getFontMetrics().descent+paint.getFontMetrics().ascent)/2f, paint);
                        
                        if (x_selectedPos != null && x_selectedPos[0]==r && x_selectedPos[1]==c) {
                            paint.setColor(Color.parseColor("#00C800")); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(6f);
                            canvas.drawCircle(cx, cy, rad+4f, paint);
                        }
                    }
                }
            }
            
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.parseColor("#00C800"));
            for (int move : x_validMovesDisplay) {
                int mr = (move>>4)&0xF, mc = move&0xF;
                canvas.drawCircle(startX + mc*x_cellSize, startY + mr*x_cellSize, x_cellSize*0.15f, paint);
            }

            drawBottomMenu(canvas, startY + x_boardHeight, x_statusMsg, x_aiThinking, x_aiDepth);
        }

        private void drawBottomMenu(Canvas canvas, float baseTop, String msg, boolean isThinking, int depth) {
            float bottomY = baseTop + w * 0.11f; 
            paint.setColor(Color.RED); paint.setStyle(Paint.Style.FILL); paint.setTextSize(w * 0.055f); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(msg, w/2f, bottomY, paint);

            float btnW = w * 0.20f, btnH = w * 0.096f, space = (w - 3*btnW) / 4f; float row1Y = bottomY + w * 0.06f;
            
            String btn1Text = isThinking ? "中断" : "悔棋";
            int btn1Bg = isThinking ? Color.parseColor("#FF9800") : Color.parseColor("#E0E0E0");
            drawBtn(canvas, btn1Text, space, row1Y, space+btnW, row1Y+btnH, btn1Bg, isThinking ? Color.WHITE : Color.BLACK, w*0.04f);
            drawBtn(canvas, "重玩", space*2+btnW, row1Y, space*2+btnW*2, row1Y+btnH, Color.parseColor("#4CAF50"), Color.WHITE, w*0.04f);
            drawBtn(canvas, "退出", space*3+btnW*2, row1Y, space*3+btnW*3, row1Y+btnH, Color.parseColor("#F44336"), Color.WHITE, w*0.04f);

            int currentMode = (currentGameType == 0) ? g_gameMode : x_gameMode;
            float row3Y = row1Y;
            
            if (currentMode == 0) {
                float row2Y = row1Y + btnH + w * 0.04f;
                int d1 = currentGameType==0?4:3, d2 = currentGameType==0?7:6, d3 = currentGameType==0?11:9;
                int c1 = depth==d1 ? Color.parseColor("#64B5F6") : Color.parseColor("#EEEEEE");
                int c2 = depth==d2 ? Color.parseColor("#64B5F6") : Color.parseColor("#EEEEEE");
                int c3 = depth==d3 ? Color.parseColor("#64B5F6") : Color.parseColor("#EEEEEE");
                
                drawBtn(canvas, "简单("+d1+")", space, row2Y, space+btnW, row2Y+btnH, c1, Color.BLACK, w*0.035f);
                drawBtn(canvas, "普通("+d2+")", space*2+btnW, row2Y, space*2+btnW*2, row2Y+btnH, c2, Color.BLACK, w*0.035f);
                drawBtn(canvas, "困难("+d3+")", space*3+btnW*2, row2Y, space*3+btnW*3, row2Y+btnH, c3, Color.BLACK, w*0.032f);

                row3Y = row2Y + btnH + w * 0.04f;
                drawBtn(canvas, "-", space, row3Y, space+btnW, row3Y+btnH, Color.parseColor("#E0E0E0"), Color.BLACK, w*0.05f);
                paint.setColor(Color.BLACK); paint.setTextSize(w*0.045f);
                Paint.FontMetrics fm = paint.getFontMetrics(); float textY = row3Y + btnH/2f - (fm.descent+fm.ascent)/2f;
                canvas.drawText("自由深度: " + depth, w/2f, textY, paint);
                drawBtn(canvas, "+", w-space-btnW, row3Y, w-space, row3Y+btnH, Color.parseColor("#E0E0E0"), Color.BLACK, w*0.05f);
            }

            float switchBtnW = w * 0.5f, switchBtnH = w * 0.11f;
            float switchBtnX = w / 2f - switchBtnW / 2f;
            float switchBtnY = row3Y + btnH + w * 0.06f; 
            String sText = currentGameType == 0 ? "⇌ 切换至 中国象棋" : "⇌ 切换至 五子棋";
            int sBg = currentGameType == 0 ? Color.parseColor("#8D6E63") : Color.parseColor("#607D8B");
            drawBtn(canvas, sText, switchBtnX, switchBtnY, switchBtnX+switchBtnW, switchBtnY+switchBtnH, sBg, Color.WHITE, w*0.045f);
        }

        private void drawBtn(Canvas canvas, String text, float l, float t, float r, float b, int bg, int fg, float size) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(bg); canvas.drawRoundRect(l, t, r, b, w*0.02f, w*0.02f, paint);
            paint.setColor(fg); paint.setTextSize(size); paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics(); canvas.drawText(text, l+(r-l)/2f, t+(b-t)/2f-(fm.descent+fm.ascent)/2f, paint);
        }

        private void showRulesDialog() {
            String rule = currentGameType == 0 
                ? "【五子棋规则】\n只要某一方连成5子即为胜利！\n黑棋白棋均受禁手限制（防误触），点错有提示不判负。" 
                : "【中国象棋规则】\n经典中国象棋规则，当对方被将死或无路可走即为绝杀胜利！\n将帅不能直接面对面（防对视），AI极其聪明，享受被虐的快感吧！";
            new AlertDialog.Builder(getContext()).setTitle("❓ 游戏规则与说明").setMessage(rule).setPositiveButton("我知道了", null).show();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
            float ex = event.getX(), ey = event.getY();

            float modeBtnW = w * 0.40f, modeBtnH = w * 0.12f, modeBtnX = w / 2 - modeBtnW / 2, modeBtnY = margin;
            float ruleBtnW = w * 0.10f; float ruleBtnX = w - margin - ruleBtnW;

            if (checkClick(ex, ey, ruleBtnX, margin, ruleBtnX + ruleBtnW, margin + ruleBtnW)) { showRulesDialog(); return true; }

            if (checkClick(ex, ey, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH)) {
                if (g_aiThinking || x_aiThinking) return true;
                if (currentGameType == 0) { g_gameMode = 1 - g_gameMode; restartGomoku(true); } 
                else { x_gameMode = 1 - x_gameMode; restartXiangqi(true); }
                return true;
            }

            float g_size = w - 2 * margin;
            float x_size = (g_size / 8f) * 9f;
            float heightDiff = x_size - g_size;
            
            float baseTop = margin + w * 0.25f + g_size; 
            float bottomY = baseTop + w * 0.11f;
            float btnW = w * 0.20f, btnH = w * 0.096f, space = (w - 3*btnW) / 4f; float row1Y = bottomY + w * 0.06f;

            if (checkClick(ex, ey, space, row1Y, space+btnW, row1Y+btnH)) {
                if (currentGameType == 0) { if(g_aiThinking) keepThinking=false; else g_undoMove(); }
                else { if(x_aiThinking) keepThinking=false; else x_undoMove(); }
                return true;
            }
            if (checkClick(ex, ey, space*2+btnW, row1Y, space*2+btnW*2, row1Y+btnH)) {
                if (currentGameType == 0) { if(!g_aiThinking) restartGomoku(true); } else { if(!x_aiThinking) restartXiangqi(true); }
                return true;
            }
            if (checkClick(ex, ey, space*3+btnW*2, row1Y, space*3+btnW*3, row1Y+btnH)) {
                System.exit(0); return true;
            }

            boolean isThinking = (currentGameType == 0) ? g_aiThinking : x_aiThinking;
            int mode = (currentGameType == 0) ? g_gameMode : x_gameMode;
            float row3Y = row1Y;

            if (mode == 0 && !isThinking) {
                float row2Y = row1Y + btnH + w * 0.04f; row3Y = row2Y + btnH + w * 0.04f;
                int d1 = currentGameType==0?4:3, d2 = currentGameType==0?7:6, d3 = currentGameType==0?11:9;
                if (checkClick(ex, ey, space, row2Y, space+btnW, row2Y+btnH)) { setDepth(d1); return true; }
                if (checkClick(ex, ey, space*2+btnW, row2Y, space*2+btnW*2, row2Y+btnH)) { setDepth(d2); return true; }
                if (checkClick(ex, ey, space*3+btnW*2, row2Y, space*3+btnW*3, row2Y+btnH)) { setDepth(d3); return true; }

                if (checkClick(ex, ey, space, row3Y, space+btnW, row3Y+btnH)) { int d=getDepth(); if(d>1) setDepth(d-1); return true; }
                if (checkClick(ex, ey, w-space-btnW, row3Y, w-space, row3Y+btnH)) { int d=getDepth(); if(d<20) setDepth(d+1); return true; }
            }

            float switchBtnW = w * 0.5f, switchBtnH = w * 0.11f;
            float switchBtnX = w / 2f - switchBtnW / 2f;
            float switchBtnY = (mode == 0 ? row3Y : row1Y) + btnH + w * 0.06f; 
            
            // 修复：只要任何游戏AI在思考，就绝对禁止切换，防止状态锁死
            if (checkClick(ex, ey, switchBtnX, switchBtnY, switchBtnX+switchBtnW, switchBtnY+switchBtnH)) {
                if (!g_aiThinking && !x_aiThinking) { 
                    currentGameType = 1 - currentGameType; 
                    invalidate(); 
                }
                return true;
            }

            if (currentGameType == 0 && !g_gameOver && !g_aiThinking) handleGomokuTouch(ex, ey);
            else if (currentGameType == 1 && !x_gameOver && !x_aiThinking) handleXiangqiTouch(ex, ey);

            return true;
        }

        private boolean checkClick(float x, float y, float l, float t, float r, float b) { return x>=l && x<=r && y>=t && y<=b; }
        private int getDepth() { return currentGameType == 0 ? g_aiDepth : x_aiDepth; }
        private void setDepth(int d) { if(currentGameType==0) g_aiDepth=d; else x_aiDepth=d; updateStatusMsg(); invalidate(); }

        private void handleGomokuTouch(float ex, float ey) {
            if (g_gameMode == 1 || g_currentPlayer == g_humanColor) {
                int c = Math.round((ex - startX) / g_cellSize), r = Math.round((ey - startY) / g_cellSize);
                if (r>=0 && r<G_BOARD_SIZE && c>=0 && c<G_BOARD_SIZE && g_board[r][c]==0) {
                    if (g_isForbiddenMove(g_board, r, c, g_currentPlayer)) { g_statusMsg = "禁手违规！"; invalidate(); }
                    else {
                        g_makeMove(r, c, g_currentPlayer);
                        if (g_checkWin(g_board, r, c, g_currentPlayer)) { g_statusMsg=(g_currentPlayer==1?"黑":"白")+"棋胜利！"; g_gameOver=true; }
                        else { g_currentPlayer = 3-g_currentPlayer; updateStatusMsg(); if(g_gameMode==0) triggerGomokuAiMove(); }
                        invalidate();
                    }
                }
            }
        }

        private void handleXiangqiTouch(float ex, float ey) {
            if (x_gameMode == 1 || x_isRedTurn) {
                int c = Math.round((ex - startX) / x_cellSize), r = Math.round((ey - startY) / x_cellSize);
                if (r>=0 && r<10 && c>=0 && c<9) {
                    if (x_selectedPos != null) {
                        int sr=x_selectedPos[0], sc=x_selectedPos[1];
                        boolean valid = false;
                        for (int m : x_validMovesDisplay) if (((m>>4)&0xF)==r && (m&0xF)==c) { valid=true; break; }
                        if (valid) {
                            if (!x_isLegalMove(x_board, (sr<<12)|(sc<<8)|(r<<4)|c, x_isRedTurn)) {
                                x_statusMsg = "被将军或飞将！无法走此步"; x_selectedPos=null; x_validMovesDisplay.clear();
                            } else {
                                x_makeMove(sr, sc, r, c);
                                if (x_checkGameOver(x_board, x_isRedTurn)) { x_statusMsg = "绝杀！"+(x_isRedTurn?"黑":"红")+"方胜利！"; x_gameOver=true; }
                                else { updateStatusMsg(); if(x_gameMode==0) triggerXiangqiAiMove(); }
                            }
                        } else {
                            if (x_board[r][c]!='.' && x_isRed(x_board[r][c])==x_isRedTurn) {
                                x_selectedPos = new int[]{r,c}; x_validMovesDisplay.clear();
                                int[] moves = x_tlMoves.get(); int cnt = x_genValidMoves(x_board, r, c, moves);
                                for (int i=0; i<cnt; i++) x_validMovesDisplay.add(moves[i]);
                            } else { x_selectedPos = null; x_validMovesDisplay.clear(); }
                        }
                    } else {
                        if (x_board[r][c]!='.' && x_isRed(x_board[r][c])==x_isRedTurn) {
                            x_selectedPos = new int[]{r,c}; x_validMovesDisplay.clear();
                            int[] moves = x_tlMoves.get(); int cnt = x_genValidMoves(x_board, r, c, moves);
                            for (int i=0; i<cnt; i++) x_validMovesDisplay.add(moves[i]);
                        }
                    }
                    invalidate();
                }
            }
        }
    }
}
