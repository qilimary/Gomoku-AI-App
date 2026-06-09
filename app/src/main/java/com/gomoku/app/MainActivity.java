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
import java.util.HashMap;
import java.util.Random;

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

        // ================= 五子棋 (Gomoku) 变量 =================
        private final int G_BOARD_SIZE = 15;
        private float g_cellSize, g_boardSize;
        private int[][] g_board = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private int[][] g_displayBoard = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private ArrayList<int[]> g_moveHistory = new ArrayList<>();
        private int[] g_lastMoveHighlight = null;
        private int g_gameMode = 0, g_humanColor = 1, g_currentPlayer = 1; 
        private int g_aiDepth = 7;
        private volatile boolean g_aiThinking = false, g_gameOver = false;
        private String g_statusMsg = "";
        private volatile int g_thinkingSec = 0;
        private long[][][] g_zobristTable = new long[G_BOARD_SIZE][G_BOARD_SIZE][3];
        private long g_zobristHash = 0;
        private HashMap<Long, GTTEntry> g_ttTable = new HashMap<>();
        private int[][] g_posWeights = new int[G_BOARD_SIZE][G_BOARD_SIZE];
        private ThreadLocal<int[]> g_tlLine = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[9]; } };

        final int[][] G_LIVE_FOUR = {{0,1,1,1,1,0}};
        final int[][] G_DEAD_FOUR = {{1,1,1,1,0}, {0,1,1,1,1}, {1,0,1,1,1}, {1,1,0,1,1}, {1,1,1,0,1}};
        final int[][] G_LIVE_THREE = {{0,1,1,1,0,0}, {0,0,1,1,1,0}, {0,1,0,1,1,0}, {0,1,1,0,1,0}};

        class GTTEntry {
            int depth, score, flag, bestR, bestC;
            GTTEntry(int d, int s, int f, int r, int c) { depth = d; score = s; flag = f; bestR = r; bestC = c; }
        }

        // ================= 中国象棋 (Xiangqi) 变量 =================
        private float x_cellSize, x_boardWidth, x_boardHeight;
        private char[][] x_board = new char[10][9];
        private char[][] x_displayBoard = new char[10][9];
        private ArrayList<XMoveRecord> x_moveHistory = new ArrayList<>();
        private int[] x_selectedPos = null;
        private int[] x_aiLastMove = null;
        private ArrayList<Integer> x_validMovesDisplay = new ArrayList<>();
        private int x_gameMode = 0; 
        private int x_aiDepth = 4;
        private boolean x_isRedTurn = true;
        private volatile boolean x_aiThinking = false, x_gameOver = false;
        private String x_statusMsg = "红方走";
        private volatile int x_thinkingSec = 0;
        private long[][][] x_zobristTable = new long[10][9][14];
        private long[] x_zobristTurn = new long[2];
        private long x_zobristHash = 0;
        private HashMap<Long, XTTEntry> x_ttTable = new HashMap<>();
        
        // 【关键修复】两个互相隔离的内存池，彻底解决胜负判断覆盖和错乱的 Bug
        private ThreadLocal<int[]> x_tlMoves = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[150]; } };
        private ThreadLocal<int[]> x_tlCheckMoves = new ThreadLocal<int[]>() { @Override protected int[] initialValue() { return new int[150]; } };

        class XMoveRecord {
            int sr, sc, er, ec; char captured;
            XMoveRecord(int sr, int sc, int er, int ec, char captured) {
                this.sr=sr; this.sc=sc; this.er=er; this.ec=ec; this.captured=captured;
            }
        }
        class XTTEntry {
            int depth, score, flag;
            XTTEntry(int d, int s, int f) { depth=d; score=s; flag=f; }
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

        private void saveGameState() {
            SharedPreferences.Editor editor = getContext().getSharedPreferences("BoardGamesSave", Context.MODE_PRIVATE).edit();
            editor.putInt("gameType", currentGameType);
            editor.apply();
        }

        private void loadGameState() {
            SharedPreferences prefs = getContext().getSharedPreferences("BoardGamesSave", Context.MODE_PRIVATE);
            currentGameType = prefs.getInt("gameType", 0);
            restartGomoku(); 
            restartXiangqi();
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

        private void restartGomoku() {
            g_board = new int[G_BOARD_SIZE][G_BOARD_SIZE];
            g_moveHistory.clear();
            g_lastMoveHighlight = null; g_zobristHash = 0; g_ttTable.clear();
            g_aiThinking = false; keepThinking = false; g_gameOver = false;
            g_currentPlayer = 1;
            if (g_gameMode == 0) g_humanColor = rand.nextBoolean() ? 1 : 2;
            syncGomokuBoard(); updateStatusMsg(); invalidate();
            if (g_gameMode == 0 && g_humanColor == 2 && currentGameType == 0) triggerGomokuAiMove();
        }

        private boolean g_isForbiddenMove(int[][] b, int r, int c, int p) {
            int original = b[r][c]; b[r][c] = p;
            if (g_checkWin(b, r, c, p)) { b[r][c] = original; return false; }
            int fours = 0, liveThrees = 0;
            for (int[] d : new int[][]{{1,0}, {0,1}, {1,1}, {1,-1}}) {
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
            for (int[] d : new int[][]{{1,0}, {0,1}, {1,1}, {1,-1}}) {
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

        // 【新增】五子棋开局库：前两步0毫秒瞬间响应
        private int[] g_getOpeningMove() {
            if (g_moveHistory.isEmpty()) return new int[]{7, 7}; // 第一步下天元
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
                int[] bestMove = g_getOpeningMove();
                if (bestMove == null) bestMove = g_iterativeDeepening(g_aiDepth);

                final int[] finalBestMove = bestMove;
                post(() -> {
                    removeCallbacks(timerRunnable); g_aiThinking = false;
                    if (!keepThinking) {
                        g_statusMsg = "思考已中断，请重新落子";
                        g_undoMove(); return;
                    }
                    if (finalBestMove != null && finalBestMove[0] != -1 && !g_gameOver) {
                        g_makeMove(finalBestMove[0], finalBestMove[1], g_currentPlayer);
                        if (g_checkWin(g_board, finalBestMove[0], finalBestMove[1], g_currentPlayer)) { g_statusMsg = "AI赢了！"; g_gameOver = true; } 
                        else { g_currentPlayer = g_humanColor; updateStatusMsg(); }
                    }
                    invalidate();
                });
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

            ArrayList<int[]> moves = g_genMoves(ttR, ttC, aiColor);
            if (moves.isEmpty()) return new int[]{-1, -1, 0};

            int bestR = -1, bestC = -1, max = Integer.MIN_VALUE+1, min = Integer.MAX_VALUE-1, origAlpha = alpha;
            for (int[] m : moves) {
                if (!keepThinking) break;
                int r = m[0], c = m[1];
                if (g_isForbiddenMove(g_board, r, c, player)) continue;

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
                if (g_ttTable.size() > 200000) g_ttTable.clear();
                g_ttTable.put(hashKey, new GTTEntry(depth, fScore, flag, bestR, bestC));
            }
            return new int[]{bestR, bestC, fScore};
        }

        private boolean g_hasNeighbor(int[][] b, int r, int c) {
            for (int i=Math.max(0,r-2); i<=Math.min(G_BOARD_SIZE-1,r+2); i++)
                for (int j=Math.max(0,c-2); j<=Math.min(G_BOARD_SIZE-1,c+2); j++)
                    if (b[i][j] != 0) return true;
            return false;
        }

        private ArrayList<int[]> g_genMoves(int pvR, int pvC, int aiColor) {
            ArrayList<int[]> list = new ArrayList<>(20); boolean hasP = false; int hC = 3-aiColor;
            if (pvR>=0 && pvR<G_BOARD_SIZE && pvC>=0 && pvC<G_BOARD_SIZE && g_board[pvR][pvC]==0)
                list.add(new int[]{pvR, pvC, g_localScore(g_board,pvR,pvC,aiColor)+g_localScore(g_board,pvR,pvC,hC)+100000});
            for (int r=0; r<G_BOARD_SIZE; r++) {
                for (int c=0; c<G_BOARD_SIZE; c++) {
                    if (g_board[r][c]!=0) hasP = true;
                    else if (g_hasNeighbor(g_board,r,c) && (r!=pvR||c!=pvC))
                        list.add(new int[]{r, c, g_localScore(g_board,r,c,aiColor)+g_localScore(g_board,r,c,hC)});
                }
            }
            if (!hasP) { list.add(new int[]{7, 7, 0}); return list; }
            list.sort((a,b)->Integer.compare(b[2], a[2]));
            return new ArrayList<>(list.subList(0, Math.min(12, list.size())));
        }

        private int g_evaluateBoard() {
            int score = 0, aiC = 3-g_humanColor;
            for (int r=0; r<G_BOARD_SIZE; r++) {
                for (int c=0; c<G_BOARD_SIZE; c++) {
                    if (g_board[r][c] == aiC) score += g_localScore(g_board, r, c, aiC) + g_posWeights[r][c];
                    else if (g_board[r][c] == g_humanColor) score -= (g_localScore(g_board, r, c, g_humanColor)*1.3) + g_posWeights[r][c];
                }
            }
            return score;
        }

        private boolean g_checkWin(int[][] b, int r, int c, int p) {
            for (int[] d : new int[][]{{1,0},{0,1},{1,1},{1,-1}}) {
                int count=1;
                for (int step : new int[]{1,-1}) {
                    for (int i=1; i<9; i++) {
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

        // ================= 中国象棋 (Xiangqi) 核心逻辑 =================

        private void initXiangqiEngine() {
            for (int i=0; i<10; i++)
                for (int j=0; j<9; j++)
                    for (int k=0; k<14; k++) x_zobristTable[i][j][k] = rand.nextLong();
            x_zobristTurn[0] = rand.nextLong(); x_zobristTurn[1] = rand.nextLong();
        }

        private void restartXiangqi() {
            String[] init = {
                "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
                ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR"
            };
            for (int i=0; i<10; i++) x_board[i] = init[i].toCharArray();
            x_moveHistory.clear(); x_selectedPos = null; x_aiLastMove = null; x_validMovesDisplay.clear();
            x_isRedTurn = true; x_aiThinking = false; keepThinking = false; x_gameOver = false;
            x_zobristHash = 0; x_ttTable.clear();
            syncXiangqiBoard(); updateStatusMsg(); invalidate();
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
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    int nr = r+d[0], nc = c+d[1];
                    while (nr>=0 && nr<10 && nc>=0 && nc<9) {
                        if (b[nr][nc]=='.') movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                        else { if(x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc; break; }
                        nr+=d[0]; nc+=d[1];
                    }
                }
            } else if (lp == 'n') {
                for (int[] d : new int[][]{{-2,-1},{-2,1},{2,-1},{2,1},{-1,-2},{1,-2},{-1,2},{1,2}}) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nr>=0&&nr<10&&nc>=0&&nc<9) {
                        if (Math.abs(d[0])==2 && b[r+d[0]/2][c]!='.') continue;
                        if (Math.abs(d[1])==2 && b[r][c+d[1]/2]!='.') continue;
                        if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'b') {
                for (int[] d : new int[][]{{-2,-2},{-2,2},{2,-2},{2,2}}) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nr>=0&&nr<10&&nc>=0&&nc<9) {
                        if (red && nr<5) continue; if (!red && nr>4) continue;
                        if (b[r+d[0]/2][c+d[1]/2]=='.') {
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                        }
                    }
                }
            } else if (lp == 'a') {
                for (int[] d : new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}}) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nc>=3&&nc<=5) {
                        if ((red && nr>=7 && nr<=9) || (!red && nr>=0 && nr<=2))
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'k') {
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
                    int nr=r+d[0], nc=c+d[1];
                    if (nc>=3&&nc<=5) {
                        if ((red && nr>=7 && nr<=9) || (!red && nr>=0 && nr<=2))
                            if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
                }
            } else if (lp == 'c') {
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
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
                int[] drs = red ? new int[]{-1} : new int[]{1};
                if ((red && r<5) || (!red && r>4)) drs = red ? new int[]{-1, 0, 0} : new int[]{1, 0, 0};
                int[] dcs = drs.length > 1 ? new int[]{0, -1, 1} : new int[]{0};
                for (int i=0; i<drs.length; i++) {
                    int nr=r+drs[i], nc=c+dcs[i];
                    if (nr>=0&&nr<10&&nc>=0&&nc<9) {
                        if (b[nr][nc]=='.' || x_isRed(b[nr][nc])!=red) movesList[size++] = (r<<12)|(c<<8)|(nr<<4)|nc;
                    }
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

        // 【关键修复】使用独立的 tlCheckMoves，不污染 tlMoves，彻底解决误报绝杀 Bug
        private boolean x_isInCheck(char[][] b, boolean isRed) {
            int tr=-1, tc=-1;
            for (int r=0; r<10; r++) { for (int c=3; c<=5; c++) {
                if (isRed && b[r][c]=='K') { tr=r; tc=c; } else if (!isRed && b[r][c]=='k') { tr=r; tc=c; }
            }}
            if (tr==-1) return true;
            int[] moves = x_tlCheckMoves.get();
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    if (b[r][c]!='.' && x_isRed(b[r][c])!=isRed) {
                        int count = x_genValidMoves(b, r, c, moves);
                        for (int i=0; i<count; i++) {
                            if (((moves[i]>>4)&0xF) == tr && (moves[i]&0xF) == tc) return true;
                        }
                    }
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

        private int x_evalBoard(char[][] b) {
            int score = 0;
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    char p = b[r][c]; if (p=='.') continue;
                    int val = 0; boolean isR = x_isRed(p); char lp = Character.toLowerCase(p);
                    if (lp=='k') val=10000; else if (lp=='r') val=900; else if (lp=='c') val=450;
                    else if (lp=='n') val=400; else if (lp=='b') val=200; else if (lp=='a') val=200; else if (lp=='p') val=100;
                    
                    if (lp=='p') {
                        if (isR && r<5) val += 30 + (4-r)*10 + (3<=c&&c<=5?10:0);
                        else if (!isR && r>4) val += 30 + (r-5)*10 + (3<=c&&c<=5?10:0);
                    } else if (lp=='n') {
                        val += (4 - Math.abs(c-4))*5; if (r==0||r==9||c==0||c==8) val-=15;
                    } else if (lp=='c') {
                        if (c==4) val+=10; if ((isR && r<3)||(!isR && r>6)) val+=15;
                    } else if (lp=='r') {
                        if ((r==9&&c==0)||(r==9&&c==8)||(r==0&&c==0)||(r==0&&c==8)) val-=25;
                        else if (c==3||c==4||c==5) val+=10;
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
                int[] bestMove = x_iterativeDeepening(x_aiDepth);
                post(() -> {
                    removeCallbacks(timerRunnable); x_aiThinking = false;
                    if (!keepThinking) { x_statusMsg = "思考已中断"; x_undoMove(); return; }
                    
                    if (bestMove[0] != -1) {
                        int sr=bestMove[0]>>12, sc=(bestMove[0]>>8)&0xF, er=(bestMove[0]>>4)&0xF, ec=bestMove[0]&0xF;
                        x_makeMove(sr, sc, er, ec);
                        if (x_checkGameOver(x_board, x_isRedTurn)) { x_statusMsg = "绝杀！"+(x_isRedTurn?"黑":"红")+"方胜利！"; x_gameOver = true; }
                        else updateStatusMsg();
                    } else { x_statusMsg = "绝杀！你胜利了！"; x_gameOver = true; }
                    invalidate();
                });
            }).start();
        }

        private int[] x_iterativeDeepening(int maxDepth) {
            int bestMove = -1;
            for (int d=1; d<=maxDepth; d++) {
                if (!keepThinking) break;
                long hashKey = x_zobristHash ^ x_zobristTurn[x_isRedTurn?0:1];
                int res = x_alphaBeta(d, Integer.MIN_VALUE+1, Integer.MAX_VALUE-1, x_isRedTurn, hashKey);
                if (!keepThinking) break;
                XTTEntry en = x_ttTable.get(hashKey);
                if (en != null && en.depth == d) {
                    bestMove = x_ttTable.get(hashKey ^ 0xABCDEFL).score; 
                    if (res > 90000) break;
                }
            }
            return new int[]{bestMove};
        }

        private int x_alphaBeta(int depth, int alpha, int beta, boolean isMax, long hash) {
            if (x_isKingsFacing(x_board)) return isMax ? 100000 : -100000;
            if (depth == 0) {
                if (isMax && x_isInCheck(x_board, false)) return 100000;
                if (!isMax && x_isInCheck(x_board, true)) return -100000;
                return x_evalBoard(x_board);
            }

            XTTEntry entry = x_ttTable.get(hash);
            if (entry != null && entry.depth >= depth) {
                if (entry.flag == 0) return entry.score;
                if (entry.flag == 1 && entry.score > alpha) alpha = entry.score;
                if (entry.flag == 2 && entry.score < beta) beta = entry.score;
                if (alpha >= beta) return entry.score;
            }

            int[] allMoves = new int[150]; int moveCount = 0;
            int[] genMoves = x_tlMoves.get(); // alpha-beta 内复制是安全的
            for (int r=0; r<10; r++) {
                for (int c=0; c<9; c++) {
                    if (x_board[r][c]!='.' && x_isRed(x_board[r][c])==isMax) {
                        int n = x_genValidMoves(x_board, r, c, genMoves);
                        for (int i=0; i<n; i++) allMoves[moveCount++] = genMoves[i];
                    }
                }
            }
            if (moveCount == 0) return isMax ? -100000 : 100000;

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

                int val = x_alphaBeta(depth-1, alpha, beta, !isMax, nextHash);
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
                if (x_ttTable.size()>200000) x_ttTable.clear();
                x_ttTable.put(hash, new XTTEntry(depth, bestVal, flag));
                x_ttTable.put(hash ^ 0xABCDEFL, new XTTEntry(0, bestM, 0)); 
            }
            return bestVal;
        }

        private void x_makeMove(int sr, int sc, int er, int ec) {
            char cap = x_board[er][ec];
            x_board[er][ec] = x_board[sr][sc]; x_board[sr][sc] = '.';
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
                    x_board[m.sr][m.sc] = x_board[m.er][m.ec];
                    x_board[m.er][m.ec] = m.captured;
                    x_isRedTurn = !x_isRedTurn;
                }
                x_aiLastMove = null; x_selectedPos = null; x_validMovesDisplay.clear();
                syncXiangqiBoard(); updateStatusMsg(); invalidate();
            }
        }


        // ================= 绘图与 UI 引擎 (完全还原原貌) =================

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            w = getWidth(); margin = w * 0.06f; 
            
            // 完美还原背景色：五子棋为纯白，象棋为木色
            canvas.drawColor(currentGameType == 0 ? Color.WHITE : Color.parseColor("#F5DEB3"));

            // 完美还原顶部经典按钮
            String modeStr = currentGameType == 0 ? (g_gameMode == 0 ? "模式: 人机对战" : "模式: 双人对战") : (x_gameMode == 0 ? "模式: 人机对战" : "模式: 双人对战");
            float modeBtnW = w * 0.45f, modeBtnH = w * 0.12f, modeBtnX = w / 2 - modeBtnW / 2, modeBtnY = margin;
            drawBtn(canvas, modeStr, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH, Color.parseColor("#E0E0E0"), Color.BLACK, w * 0.04f);

            // 完美还原右上角规则说明 🐶
            float ruleBtnW = w * 0.10f; float ruleBtnX = w - margin - ruleBtnW;
            drawBtn(canvas, "🐶", ruleBtnX, margin, ruleBtnX + ruleBtnW, margin + ruleBtnW, Color.parseColor("#EEEEEE"), Color.BLACK, w * 0.05f);

            startY = margin + w * 0.25f;

            if (currentGameType == 0) drawGomokuUI(canvas);
            else drawXiangqiUI(canvas);
        }

        private void drawGomokuUI(Canvas canvas) {
            g_boardSize = w - 2 * margin; g_cellSize = g_boardSize / 14f; startX = margin;
            
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
                        // 还原最初的小红点提示
                        if (g_lastMoveHighlight != null && g_lastMoveHighlight[0]==r && g_lastMoveHighlight[1]==c) {
                            paint.setColor(Color.RED); canvas.drawCircle(cx, cy, g_cellSize*0.1f, paint);
                        }
                    }
                }
            }

            drawBottomMenu(canvas, startY + g_boardSize, g_statusMsg, g_aiThinking, g_aiDepth);
        }

        private void drawXiangqiUI(Canvas canvas) {
            x_boardWidth = w - 2 * margin; x_cellSize = x_boardWidth / 8f; x_boardHeight = x_cellSize * 9f; startX = margin;

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
            float bottomY = baseTop + w * 0.08f;
            paint.setColor(Color.RED); paint.setStyle(Paint.Style.FILL); paint.setTextSize(w * 0.055f); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(msg, w/2f, bottomY, paint);

            float btnW = w * 0.20f, btnH = w * 0.096f, space = (w - 3*btnW) / 4f; float row1Y = bottomY + w * 0.06f;
            
            // 完美还原这三个经典按钮
            String btn1Text = isThinking ? "中断" : "悔棋";
            int btn1Bg = isThinking ? Color.parseColor("#FF9800") : Color.parseColor("#E0E0E0");
            drawBtn(canvas, btn1Text, space, row1Y, space+btnW, row1Y+btnH, btn1Bg, isThinking ? Color.WHITE : Color.BLACK, w*0.04f);
            drawBtn(canvas, "重玩", space*2+btnW, row1Y, space*2+btnW*2, row1Y+btnH, Color.parseColor("#4CAF50"), Color.WHITE, w*0.04f);
            drawBtn(canvas, "退出", space*3+btnW*2, row1Y, space*3+btnW*3, row1Y+btnH, Color.parseColor("#F44336"), Color.WHITE, w*0.04f);

            int currentMode = (currentGameType == 0) ? g_gameMode : x_gameMode;
            float row3Y = row1Y + btnH + w * 0.04f;
            
            if (currentMode == 0) {
                float row2Y = row1Y + btnH + w * 0.04f;
                int d1 = currentGameType==0?4:2, d2 = currentGameType==0?7:3, d3 = currentGameType==0?11:4;
                int c1 = depth==d1 ? Color.parseColor("#81C784") : Color.parseColor("#EEEEEE");
                int c2 = depth==d2 ? Color.parseColor("#64B5F6") : Color.parseColor("#EEEEEE");
                int c3 = depth==d3 ? Color.parseColor("#FF8A65") : Color.parseColor("#EEEEEE");
                
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

            // 【新增底端专属游戏切换按钮】不影响原有布局
            float switchBtnW = w * 0.5f, switchBtnH = w * 0.11f;
            float switchBtnX = w / 2f - switchBtnW / 2f;
            float switchBtnY = row3Y + btnH + w * 0.06f; // 保证有足够间距
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
            new AlertDialog.Builder(getContext()).setTitle("🐶 游戏规则与说明").setMessage(rule).setPositiveButton("我知道了", null).show();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
            float ex = event.getX(), ey = event.getY();

            float modeBtnW = w * 0.45f, modeBtnH = w * 0.12f, modeBtnX = w / 2 - modeBtnW / 2, modeBtnY = margin;
            float ruleBtnW = w * 0.10f; float ruleBtnX = w - margin - ruleBtnW;

            if (checkClick(ex, ey, ruleBtnX, margin, ruleBtnX + ruleBtnW, margin + ruleBtnW)) { showRulesDialog(); return true; }

            // 原汁原味的顶部模式切换
            if (checkClick(ex, ey, modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + modeBtnH)) {
                if (g_aiThinking || x_aiThinking) return true;
                if (currentGameType == 0) { g_gameMode = 1 - g_gameMode; restartGomoku(); } 
                else { x_gameMode = 1 - x_gameMode; restartXiangqi(); }
                return true;
            }

            float baseTop = startY + (currentGameType == 0 ? g_boardSize : x_boardHeight);
            float bottomY = baseTop + w * 0.08f;
            float btnW = w * 0.20f, btnH = w * 0.096f, space = (w - 3*btnW) / 4f; float row1Y = bottomY + w * 0.06f;

            if (checkClick(ex, ey, space, row1Y, space+btnW, row1Y+btnH)) {
                if (currentGameType == 0) { if(g_aiThinking) keepThinking=false; else g_undoMove(); }
                else { if(x_aiThinking) keepThinking=false; else x_undoMove(); }
                return true;
            }
            if (checkClick(ex, ey, space*2+btnW, row1Y, space*2+btnW*2, row1Y+btnH)) {
                if (currentGameType == 0) { if(!g_aiThinking) restartGomoku(); } else { if(!x_aiThinking) restartXiangqi(); }
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
                int d1 = currentGameType==0?4:2, d2 = currentGameType==0?7:3, d3 = currentGameType==0?11:4;
                if (checkClick(ex, ey, space, row2Y, space+btnW, row2Y+btnH)) { setDepth(d1); return true; }
                if (checkClick(ex, ey, space*2+btnW, row2Y, space*2+btnW*2, row2Y+btnH)) { setDepth(d2); return true; }
                if (checkClick(ex, ey, space*3+btnW*2, row2Y, space*3+btnW*3, row2Y+btnH)) { setDepth(d3); return true; }

                if (checkClick(ex, ey, space, row3Y, space+btnW, row3Y+btnH)) { int d=getDepth(); if(d>1) setDepth(d-1); return true; }
                if (checkClick(ex, ey, w-space-btnW, row3Y, w-space, row3Y+btnH)) { int d=getDepth(); if(d<20) setDepth(d+1); return true; }
            }

            // 【新增专属切换大按钮检测】
            float switchBtnW = w * 0.5f, switchBtnH = w * 0.11f;
            float switchBtnX = w / 2f - switchBtnW / 2f;
            float switchBtnY = (mode == 0 ? row3Y : row1Y) + btnH + w * 0.06f; 
            if (checkClick(ex, ey, switchBtnX, switchBtnY, switchBtnX+switchBtnW, switchBtnY+switchBtnH)) {
                if (!isThinking) { currentGameType = 1 - currentGameType; invalidate(); }
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
