package com.pulserun.game;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/** Portrait, touch-first Android version of Pulse Run. */
public final class GameActivity extends Activity {
    private GameView gameView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 9, 20));
        getWindow().setNavigationBarColor(Color.rgb(5, 9, 20));
        gameView = new GameView(this);
        setContentView(gameView);
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override protected void onResume() { super.onResume(); hideSystemBars(); if (gameView != null) gameView.resumeLoop(); }
    @Override protected void onPause() { if (gameView != null) gameView.pauseLoop(); super.onPause(); }
    @Override public void onBackPressed() {
        if (gameView != null && gameView.pauseFromBack()) return;
        super.onBackPressed();
    }

    private static final class GameView extends View {
        private static final float WORLD_W = 440f, WORLD_H = 900f;
        private static final float ARENA_LEFT = -210f, ARENA_RIGHT = 650f;
        private static final float ARENA_TOP = -260f, ARENA_BOTTOM = 1500f;
        private static final float HOME_X = WORLD_W / 2, HOME_Y = 448f, HOME_RADIUS = 62f;
        private static final int MENU = 0, RUN = 1, UPGRADE = 2, OVER = 3, PAUSED = 4, SHOP = 5;
        private static final int CYAN = Color.rgb(79, 218, 171), PINK = Color.rgb(255, 112, 91);
        private static final int GOLD = Color.rgb(255, 193, 92), PURPLE = Color.rgb(187, 135, 227);
        private static final RectF[] RUINS = {};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final Typeface boldTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        private final Typeface mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Random random = new Random();
        private Bitmap gardenBitmap;
        private final ArrayList<Enemy> enemies = new ArrayList<>();
        private final ArrayList<Bullet> bullets = new ArrayList<>();
        private final ArrayList<Shard> shards = new ArrayList<>();
        private final ArrayList<Particle> particles = new ArrayList<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable loop = new Runnable() {
            @Override public void run() {
                if (!loopRunning) return;
                animationFrame++;
                if (mode == RUN) updateGame();
                invalidate();
                handler.postDelayed(this, 16L);
            }
        };

        private int mode = MENU;
        private int hp = 100, maxHp = 100, homeHp = 300, maxHomeHp = 300, score, best, kills, level, xp, xpNext, wave;
        private int roundNumber = 1, coins, speedLevel;
        private long roundElapsedMs, lastRoundUpdateMs;
        private int fireTicks, muzzleFlashTicks, spawnTicks, dashCooldown, dashInvulnerable, hitInvulnerable;
        private int rapidLevel, damageLevel, magnetLevel, bannerTicks;
        private long frame;
        private long animationFrame;
        private double px = WORLD_W / 2, py = WORLD_H * .70;
        private double moveX, moveY, scale = 1, offsetX, offsetY, cameraX, cameraY;
        private int movePointer = -1;
        private boolean loopRunning;
        private String banner = "";
        private String shopMessage = "Choose upgrades for the next arena round";
        private float touchX, touchY;

        GameView(Context context) {
            super(context);
            setFocusable(true);
            best = context.getSharedPreferences("pulse-run", Context.MODE_PRIVATE).getInt("best", 0);
        }

        void resumeLoop() {
            if (!loopRunning) { if (mode == RUN) lastRoundUpdateMs = SystemClock.elapsedRealtime(); loopRunning = true; handler.post(loop); }
        }

        void pauseLoop() { loopRunning = false; handler.removeCallbacks(loop); }

        boolean pauseFromBack() {
            if (mode == RUN) { mode = PAUSED; moveX = moveY = 0; movePointer = -1; return true; }
            if (mode == PAUSED) { mode = RUN; lastRoundUpdateMs = SystemClock.elapsedRealtime(); return true; }
            return false;
        }

        @Override protected void onDetachedFromWindow() { pauseLoop(); super.onDetachedFromWindow(); }

        private void startRun() {
            enemies.clear(); bullets.clear(); shards.clear(); particles.clear();
            hp = 100; maxHp = 100; maxHomeHp = 300; homeHp = maxHomeHp; score = 0; kills = 0; coins = 0; level = 1; xp = 0; xpNext = 5;
            roundNumber = 1; roundElapsedMs = 0; lastRoundUpdateMs = SystemClock.elapsedRealtime(); wave = 1; frame = 0; fireTicks = 0; spawnTicks = 24; dashCooldown = 0;
            dashInvulnerable = 0; hitInvulnerable = 0; rapidLevel = 0; damageLevel = 0; magnetLevel = 0; speedLevel = 0;
            px = WORLD_W / 2; py = WORLD_H * .70; moveX = moveY = 0; movePointer = -1;
            mode = RUN;
        }

        private void updateGame() {
            frame++;
            long now = SystemClock.elapsedRealtime();
            if (lastRoundUpdateMs == 0) lastRoundUpdateMs = now;
            roundElapsedMs += Math.max(0, now - lastRoundUpdateMs);
            lastRoundUpdateMs = now;
            wave = roundNumber;
            if (roundElapsedMs >= 60_000L) {
                coins += 25 + roundNumber * 5;
                roundNumber++;
                roundElapsedMs = 0; lastRoundUpdateMs = now;
                mode = SHOP; moveX = moveY = 0; movePointer = -1;
                shopMessage = "Round cleared! Your survival bonus is ready.";
                enemies.clear(); bullets.clear(); shards.clear();
                return;
            }
            if (fireTicks > 0) fireTicks--;
            if (muzzleFlashTicks > 0) muzzleFlashTicks--;
            if (dashCooldown > 0) dashCooldown--;
            if (dashInvulnerable > 0) dashInvulnerable--;
            if (hitInvulnerable > 0) hitInvulnerable--;
            if (bannerTicks > 0) bannerTicks--;

            float length = (float) Math.hypot(moveX, moveY);
            if (length > .05f) {
                float speed = 3.2f + speedLevel * .32f;
                double nx = px + moveX / length * speed;
                double ny = py + moveY / length * speed;
                if (!blockedByMap(nx, py, 17)) px = nx;
                if (!blockedByMap(px, ny, 17)) py = ny;
            }

            Enemy target = nearestEnemy();
            if (target != null && fireTicks == 0) shoot(target);
            int enemyLimit = wave == 1 ? 5 : Math.min(38, 5 + wave * 2);
            if (spawnTicks-- <= 0 && enemies.size() < enemyLimit) {
                spawnEnemy();
                spawnTicks = wave == 1 ? 90 : Math.max(18, 70 - wave * 4);
            }
            updateBullets();
            updateEnemies();
            updateShards();
            updateParticles();
        }

        private void shoot(Enemy target) {
            double dx = target.x - px, dy = target.y - py, d = Math.max(.01, Math.hypot(dx, dy));
            bullets.add(new Bullet(px + dx / d * 20, py + dy / d * 20, dx / d * 9.6, dy / d * 9.6, 1 + damageLevel));
            fireTicks = Math.max(8, 19 - rapidLevel * 3); muzzleFlashTicks = 4;
            burst(px + dx / d * 23, py + dy / d * 23, CYAN, 3);
        }

        private void updateBullets() {
            for (int i = bullets.size() - 1; i >= 0; i--) {
                Bullet b = bullets.get(i); b.x += b.vx; b.y += b.vy; b.life--;
                if (b.life <= 0 || b.x < ARENA_LEFT || b.x > ARENA_RIGHT || b.y < ARENA_TOP || b.y > ARENA_BOTTOM || insideRuin(b.x, b.y, 2)) { bullets.remove(i); continue; }
                for (int j = enemies.size() - 1; j >= 0; j--) {
                    Enemy e = enemies.get(j);
                    if (distance(b.x, b.y, e.x, e.y) < e.radius + 6) {
                        e.hp -= b.damage; bullets.remove(i); burst(b.x, b.y, PINK, 4);
                        if (e.hp <= 0) {
                            burst(e.x, e.y, e.color, 16); shards.add(new Shard(e.x, e.y));
                            enemies.remove(j); kills++; score += 10 * wave; coins += 3 + roundNumber;
                            if (score > best) { best = score; getContext().getSharedPreferences("pulse-run", Context.MODE_PRIVATE).edit().putInt("best", best).apply(); }
                        }
                        break;
                    }
                }
            }
        }

        private void updateEnemies() {
            for (Enemy e : enemies) {
                double homeDx = HOME_X - e.x, homeDy = HOME_Y - e.y;
                double homeDistance = Math.max(.01, Math.hypot(homeDx, homeDy));
                double playerDx = px - e.x, playerDy = py - e.y;
                double playerDistance = Math.max(.01, Math.hypot(playerDx, playerDy));
                double aggroRange = e.type == 1 ? 330 : 230;
                boolean huntingPlayer = playerDistance < aggroRange || playerDistance + 12 < homeDistance;
                double dx = huntingPlayer ? playerDx : homeDx;
                double dy = huntingPlayer ? playerDy : homeDy;
                double d = huntingPlayer ? playerDistance : homeDistance;
                if (e.attackCooldown > 0) e.attackCooldown--;
                if (e.playerAttackCooldown > 0) e.playerAttackCooldown--;
                if (huntingPlayer && d < e.radius + (e.type == 1 ? 23 : 20)) {
                    if (e.playerAttackCooldown == 0 && dashInvulnerable == 0 && hitInvulnerable == 0) {
                        int damage = e.type == 1 ? 17 : (e.type == 2 ? 15 : 11);
                        hp = Math.max(0, hp - damage);
                        hitInvulnerable = 48;
                        e.playerAttackCooldown = e.type == 1 ? 42 : 54;
                        burst(px, py, PINK, 12);
                        if (hp == 0) { mode = OVER; moveX = moveY = 0; movePointer = -1; }
                    }
                    if (mode != RUN) break;
                    continue;
                }
                if (!huntingPlayer && d < HOME_RADIUS + e.radius) {
                    if (e.attackCooldown == 0) {
                        homeHp = Math.max(0, homeHp - e.homeDamage);
                        e.attackCooldown = 54;
                        burst(HOME_X + random.nextInt(45) - 22, HOME_Y + 24, e.color, 8);
                        if (homeHp == 0) { mode = OVER; moveX = moveY = 0; movePointer = -1; }
                    }
                    if (mode != RUN) break;
                    continue;
                }
                double nx = e.x + dx / d * e.speed;
                double ny = e.y + dy / d * e.speed;
                if (!blockedByMap(nx, e.y, e.radius)) e.x = nx;
                if (!blockedByMap(e.x, ny, e.radius)) e.y = ny;
                if (blockedByMap(e.x, e.y, e.radius)) {
                    double side = e.type % 2 == 0 ? 1 : -1;
                    double sx = e.x + (-dy / d) * e.speed * side;
                    double sy = e.y + (dx / d) * e.speed * side;
                    if (!blockedByMap(sx, sy, e.radius)) { e.x = sx; e.y = sy; }
                }
                if (distance(px, py, e.x, e.y) < e.radius + 17 && dashInvulnerable == 0 && hitInvulnerable == 0) {
                    hp -= 14; hitInvulnerable = 48; burst(px, py, PINK, 14);
                    if (hp <= 0) { hp = 0; mode = OVER; moveX = moveY = 0; movePointer = -1; }
                }
                if (mode != RUN) break;
            }
        }

        private void updateShards() {
            for (int i = shards.size() - 1; i >= 0; i--) {
                Shard s = shards.get(i);
                double dx = px - s.x, dy = py - s.y, d = Math.max(.01, Math.hypot(dx, dy));
                double magnetRange = 180 + magnetLevel * 70;
                if (d < magnetRange) {
                    double speed = Math.min(12, 3.2 + (magnetRange - d) * (.022 + magnetLevel * .004));
                    s.x += dx / d * speed; s.y += dy / d * speed;
                }
                if (d < 22) {
                    shards.remove(i); xp++;
                    if (xp >= xpNext) { xp -= xpNext; xpNext += 3; level++; mode = UPGRADE; moveX = moveY = 0; movePointer = -1; break; }
                }
            }
        }

        private void updateParticles() {
            Iterator<Particle> it = particles.iterator();
            while (it.hasNext()) { Particle p = it.next(); p.x += p.vx; p.y += p.vy; p.vx *= .965; p.vy *= .965; if (--p.life <= 0) it.remove(); }
        }

        private Enemy nearestEnemy() {
            Enemy result = null; double bestDistance = Double.MAX_VALUE;
            for (Enemy e : enemies) { double d = distance(px, py, e.x, e.y); if (d < bestDistance) { bestDistance = d; result = e; } }
            return result;
        }

        private void spawnEnemy() {
            double x = px, y = py;
            for (int tries = 0; tries < 14; tries++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double spawnDistance = 335 + random.nextDouble() * 120;
                x = clamp(px + Math.cos(angle) * spawnDistance, ARENA_LEFT + 26, ARENA_RIGHT - 26);
                y = clamp(py + Math.sin(angle) * spawnDistance, ARENA_TOP + 26, ARENA_BOTTOM - 26);
                if (distance(x, y, px, py) >= 260 && !blockedByMap(x, y, 23)) break;
            }
            // Three readable silhouettes: shambling workers, a fast zombie dog, and two heavier humans.
            int type = random.nextInt(5) == 0 ? 1 : new int[]{0, 2, 3}[random.nextInt(3)];
            double speed = .72 + Math.min(1.25, wave * .045) + random.nextDouble() * .18;
            int health = 3 + Math.max(0, wave - 1) * 3, radius = 16, color = Color.rgb(123, 190, 108), homeDamage = 8;
            if (type == 1) { speed *= 1.9; radius = 19; health = Math.max(4, health - 1); color = Color.rgb(194, 91, 64); homeDamage = 7; }
            if (type == 2) { speed *= .58; radius = 23; health += wave == 1 ? 2 : 5; color = Color.rgb(174, 105, 77); homeDamage = wave == 1 ? 12 : 18; }
            if (type == 3) { speed *= .72; radius = 20; health += wave == 1 ? 1 : 2; color = Color.rgb(167, 126, 203); homeDamage = wave == 1 ? 9 : 13; }
            enemies.add(new Enemy(x, y, health, speed, radius, color, type, homeDamage));
        }

        private void dash() {
            if (mode != RUN || dashCooldown > 0) return;
            double dx = moveX, dy = moveY;
            if (Math.hypot(dx, dy) < .1) {
                Enemy e = nearestEnemy();
                if (e != null) { dx = px - e.x; dy = py - e.y; } else dy = -1;
            }
            double d = Math.max(.01, Math.hypot(dx, dy));
            for (int step = 0; step < 12; step++) {
                double nx = clamp(px + dx / d * 9, ARENA_LEFT + 18, ARENA_RIGHT - 18);
                double ny = clamp(py + dy / d * 9, ARENA_TOP + 18, ARENA_BOTTOM - 18);
                if (blockedByMap(nx, ny, 17)) break;
                px = nx; py = ny;
            }
            dashCooldown = 225; dashInvulnerable = 17; burst(px, py, CYAN, 18);
        }

        private void selectUpgrade(int index) {
            if (mode != UPGRADE) return;
            if (index == 0) { rapidLevel = Math.min(4, rapidLevel + 1); banner = "RAPID PULSE"; }
            else if (index == 1) { damageLevel = Math.min(3, damageLevel + 1); banner = "HEAVY CHARGE"; }
            else if (homeHp < maxHomeHp - 35) { homeHp = Math.min(maxHomeHp, homeHp + 40); banner = "HOME REPAIRED +40"; }
            else if (hp < maxHp - 22) { hp = Math.min(maxHp, hp + 22); banner = "FIELD MEDKIT +22"; }
            else { magnetLevel = Math.min(6, magnetLevel + 1); banner = "CRYSTAL MAGNET + RANGE"; }
            bannerTicks = 100; mode = RUN; lastRoundUpdateMs = SystemClock.elapsedRealtime();
        }

        private int shopPrice(int item) {
            if (item == 0) return rapidLevel >= 5 ? -1 : 20 + rapidLevel * 12;
            if (item == 1) return damageLevel >= 4 ? -1 : 24 + damageLevel * 18;
            if (item == 2) return speedLevel >= 4 ? -1 : 18 + speedLevel * 13;
            if (item == 3) return maxHomeHp >= 500 ? -1 : 35 + (maxHomeHp - 300) / 20 * 15;
            return magnetLevel >= 6 ? -1 : 28 + magnetLevel * 16;
        }

        private void buyShopItem(int item) {
            int price = shopPrice(item);
            if (price < 0) { shopMessage = "That upgrade is already at maximum level."; return; }
            if (coins < price) { shopMessage = "Not enough credits. Earn more by defeating monsters."; return; }
            coins -= price;
            if (item == 0) { rapidLevel++; shopMessage = "Arc cannon upgraded: faster fire."; }
            else if (item == 1) { damageLevel++; shopMessage = "Heavy rounds installed: more damage."; }
            else if (item == 2) { speedLevel++; shopMessage = "Reactor boots upgraded: faster movement."; }
            else if (item == 3) { maxHomeHp += 20; homeHp = Math.min(maxHomeHp, homeHp + 45); hp = Math.min(maxHp, hp + 10); shopMessage = "Home reinforced: +20 max health and repairs."; }
            else { magnetLevel++; shopMessage = "Crystal magnet upgraded: wider, faster pickup."; }
        }

        private void startNextRound() {
            mode = RUN; roundElapsedMs = 0; lastRoundUpdateMs = SystemClock.elapsedRealtime(); spawnTicks = 35; fireTicks = 0;
            moveX = moveY = 0; movePointer = -1;
            banner = "ROUND " + roundNumber; bannerTicks = 140;
        }

        private void burst(double x, double y, int color, int count) {
            for (int i = 0; i < count; i++) {
                double a = random.nextDouble() * Math.PI * 2, speed = .8 + random.nextDouble() * 3.3;
                particles.add(new Particle(x, y, Math.cos(a) * speed, Math.sin(a) * speed, color));
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            scale = Math.min(w / WORLD_W, h / WORLD_H);
            offsetX = (w - WORLD_W * scale) * .5;
            offsetY = (h - WORLD_H * scale) * .5;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(175, 216, 239));
            canvas.save(); canvas.translate((float) offsetX, (float) offsetY); canvas.scale((float) scale, (float) scale);
            cameraX = clamp(px - WORLD_W / 2, ARENA_LEFT, ARENA_RIGHT - WORLD_W);
            cameraY = clamp(py - WORLD_H / 2, ARENA_TOP, ARENA_BOTTOM - WORLD_H);
            canvas.save(); canvas.translate((float)-cameraX, (float)-cameraY);
            ensureGardenBitmap();
            canvas.drawBitmap(gardenBitmap, ARENA_LEFT - 32, ARENA_TOP - 32, null);
            drawHomeBase(canvas);
            drawAmbient(canvas);
            for (Particle p : particles) { paint.setColor(withAlpha(p.color, Math.max(25, p.life * 8))); canvas.drawCircle((float)p.x,(float)p.y,2.6f,paint); }
            for (Shard s : shards) drawShard(canvas, s);
            for (Bullet b : bullets) drawBullet(canvas, b);
            for (Enemy e : enemies) drawEnemy(canvas, e);
            if (mode != MENU) drawPlayer(canvas);
            canvas.restore();
            drawHud(canvas);
            if (mode == MENU) drawMenu(canvas);
            else if (mode == UPGRADE) drawUpgrade(canvas);
            else if (mode == OVER) drawGameOver(canvas);
            else if (mode == PAUSED) drawPause(canvas);
            else if (mode == SHOP) drawShop(canvas);
            if (mode == RUN && hitInvulnerable > 0) drawDamageFlash(canvas);
            if (bannerTicks > 0 && mode == RUN) drawLabel(canvas, banner, WORLD_W / 2, 124, 16, GOLD, true);
            canvas.restore();
        }

        private void drawBackground(Canvas c) {
            c.drawColor(Color.rgb(175, 216, 239));
            paint.setColor(Color.rgb(113, 184, 87));
            c.drawRoundRect(new RectF(ARENA_LEFT - 8, ARENA_TOP - 8, ARENA_RIGHT + 8, ARENA_BOTTOM + 8), 36, 36, paint);
            for (int y = (int) ARENA_TOP; y < ARENA_BOTTOM; y += 48) {
                for (int x = (int) ARENA_LEFT; x < ARENA_RIGHT; x += 48) {
                    paint.setColor(0xFF5C9B4D);
                    c.drawLine(x + 9, y + 38, x + 7, y + 32, paint);
                    c.drawLine(x + 11, y + 38, x + 14, y + 31, paint);
                    c.drawLine(x + 34, y + 15, x + 31, y + 9, paint);
                    int mark = (x * 17 + y * 31) % 5;
                    if (mark == 0) {
                        paint.setColor(0xFFFFF3B0); c.drawCircle(x + 34, y + 32, 2.5f, paint);
                        paint.setColor(0xFFFFFAE0); c.drawCircle(x + 31, y + 32, 1.7f, paint); c.drawCircle(x + 37, y + 32, 1.7f, paint);
                        c.drawCircle(x + 34, y + 29, 1.7f, paint); c.drawCircle(x + 34, y + 35, 1.7f, paint);
                    }
                    if (mark == 1) { paint.setColor(0xFF4D8B43); c.drawLine(x + 13, y + 29, x + 20, y + 36, paint); }
                }
            }
            paint.setColor(0xFFB98759); c.drawRoundRect(new RectF(HOME_X - 25, HOME_Y + 52, HOME_X + 25, HOME_Y + 220), 10, 10, paint);
            paint.setColor(0xFFE0BC83); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
            c.drawRoundRect(new RectF(HOME_X - 25, HOME_Y + 52, HOME_X + 25, HOME_Y + 220), 10, 10, paint); paint.setStyle(Paint.Style.FILL);
            // Open backyard perimeter: warm timber rails and evenly spaced fence posts.
            paint.setColor(0xFF65452E); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(16);
            c.drawRoundRect(new RectF(ARENA_LEFT - 5, ARENA_TOP - 5, ARENA_RIGHT + 5, ARENA_BOTTOM + 5), 12, 12, paint);
            paint.setColor(0xFFE7B66E); paint.setStrokeWidth(4);
            c.drawRoundRect(new RectF(ARENA_LEFT - 5, ARENA_TOP - 5, ARENA_RIGHT + 5, ARENA_BOTTOM + 5), 12, 12, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int x = (int)ARENA_LEFT + 4; x < ARENA_RIGHT; x += 31) {
                paint.setColor(0xFF97603A); c.drawRoundRect(new RectF(x, ARENA_TOP - 16, x + 7, ARENA_TOP + 8), 3, 3, paint); c.drawRoundRect(new RectF(x, ARENA_BOTTOM - 8, x + 7, ARENA_BOTTOM + 16), 3, 3, paint);
                paint.setColor(0xFFF4D18C); c.drawLine(x + 1, ARENA_TOP - 14, x + 1, ARENA_TOP + 4, paint); c.drawLine(x + 1, ARENA_BOTTOM - 4, x + 1, ARENA_BOTTOM + 14, paint);
            }
            for (int y = (int)ARENA_TOP + 8; y < ARENA_BOTTOM; y += 34) {
                paint.setColor(0xFF97603A); c.drawRoundRect(new RectF(ARENA_LEFT - 16, y, ARENA_LEFT + 8, y + 7), 3, 3, paint); c.drawRoundRect(new RectF(ARENA_RIGHT - 8, y, ARENA_RIGHT + 16, y + 7), 3, 3, paint);
            }
        }

        private void ensureGardenBitmap() {
            if (gardenBitmap != null) return;
            int padding = 32;
            int width = (int)(ARENA_RIGHT - ARENA_LEFT) + padding * 2;
            int height = (int)(ARENA_BOTTOM - ARENA_TOP) + padding * 2;
            gardenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas background = new Canvas(gardenBitmap);
            background.translate(padding - ARENA_LEFT, padding - ARENA_TOP);
            drawBackground(background);
        }

        private void drawHomeBase(Canvas c) {
            float x = HOME_X, y = HOME_Y;
            c.save(); c.scale(1.04f, 1.04f, x, y);
            paint.setColor(0x40FFC45E); c.drawCircle(x, y + 12, 82, paint);
            paint.setColor(0x98050A08); c.drawOval(new RectF(x - 72, y + 34, x + 72, y + 66), paint);
            // Compact contemporary safehouse: flat canopy, glass frontage, and rooftop solar tiles.
            paint.setColor(0xFF564C3F); c.drawRoundRect(new RectF(x - 60, y - 17, x + 60, y + 53), 8, 8, paint);
            paint.setColor(0xFFE5BD79); c.drawRoundRect(new RectF(x - 54, y - 11, x + 54, y + 47), 5, 5, paint);
            paint.setColor(0xFFB47D52); c.drawRect(x - 60, y - 27, x + 60, y - 15, paint);
            paint.setColor(0xFFFFD792); c.drawRect(x - 56, y - 27, x + 57, y - 23, paint);
            paint.setColor(0xFF263D44); c.drawRoundRect(new RectF(x + 8, y - 9, x + 47, y + 23), 4, 4, paint);
            paint.setColor(0xFF5AB6A2); c.drawRect(x + 13, y - 5, x + 43, y + 1, paint);
            paint.setColor(0xFF28414A); c.drawRect(x + 26, y - 7, x + 29, y + 22, paint);
            paint.setColor(0xFFFFD36D); c.drawRect(x + 14, y + 4, x + 21, y + 10, paint); c.drawRect(x + 34, y + 4, x + 41, y + 10, paint);
            paint.setColor(0xFFB8F0DE); paint.setStrokeWidth(2); c.drawLine(x + 12, y - 5, x + 19, y + 18, paint); c.drawLine(x + 32, y - 5, x + 39, y + 1, paint);
            paint.setColor(0xFFFFE2A1); c.drawRoundRect(new RectF(x + 53, y - 12, x + 56, y + 6), 2, 2, paint);
            paint.setColor(0xFF684332); c.drawRoundRect(new RectF(x - 39, y + 5, x - 10, y + 51), 4, 4, paint);
            paint.setColor(0xFFAA6A47); c.drawRect(x - 35, y + 9, x - 14, y + 13, paint);
            paint.setColor(0xFFFFD792); c.drawCircle(x - 15, y + 29, 2, paint);
            paint.setColor(0xFF344D51); c.drawRoundRect(new RectF(x - 5, y + 30, x + 4, y + 45), 3, 3, paint);
            paint.setColor(0xFF172D38); c.drawRoundRect(new RectF(x + 5, y - 41, x + 48, y - 30), 3, 3, paint);
            paint.setColor(0xFF58B8A3); paint.setStrokeWidth(1);
            c.drawLine(x + 17, y - 40, x + 17, y - 31, paint); c.drawLine(x + 31, y - 40, x + 31, y - 31, paint);
            c.drawLine(x + 7, y - 36, x + 46, y - 36, paint);
            paint.setColor(0xFF293C44); c.drawRoundRect(new RectF(x - 47, y - 40, x - 7, y - 31), 3, 3, paint);
            paint.setColor(0xFF61B7A2); c.drawLine(x - 38, y - 39, x - 38, y - 32, paint); c.drawLine(x - 25, y - 39, x - 25, y - 32, paint); c.drawLine(x - 12, y - 39, x - 12, y - 32, paint);
            paint.setColor(0xFFB7ECDB); c.drawLine(x - 49, y - 8, x - 49, y + 19, paint);
            paint.setColor(0xFF445D4A); c.drawRect(x - 63, y + 49, x + 63, y + 55, paint);
            paint.setColor(0xFFB17B48); c.drawRect(x - 67, y + 55, x + 67, y + 59, paint);
            paint.setColor(0xFF47734C); c.drawOval(new RectF(x - 70, y + 30, x - 58, y + 49), paint); c.drawOval(new RectF(x + 58, y + 30, x + 70, y + 49), paint);
            paint.setColor(0xFFFFD792); c.drawCircle(x - 58, y - 4, 2, paint); c.drawCircle(x + 58, y - 4, 2, paint);
            drawLabel(c, "SAFEHOUSE", x, y - 48, 8, 0xFFFFE3A1, true);
            float barW = 120, barY = y + 66, ratio = Math.max(0, homeHp / (float)maxHomeHp);
            paint.setColor(0xD709100D); c.drawRoundRect(new RectF(x - barW/2, barY, x + barW/2, barY + 10), 5, 5, paint);
            paint.setColor(homeHp > 100 ? 0xFF70E08A : PINK);
            c.drawRoundRect(new RectF(x - barW/2 + 2, barY + 2, x - barW/2 + 2 + (barW - 4) * ratio, barY + 8), 3, 3, paint);
            drawLabel(c, "HOME  " + homeHp + " / " + maxHomeHp, x, barY + 23, 8, 0xFFFFE4B0, true);
            c.restore();
        }

        private void drawRuins(Canvas c) {
            for (int i = 0; i < RUINS.length; i++) {
                RectF r = RUINS[i];
                if (i == 3 || i == 5) {
                    paint.setColor(0x503FE5FF); c.drawCircle(r.centerX(), r.centerY(), r.width() * .48f, paint);
                    paint.setColor(0xFF176078); c.drawCircle(r.centerX(), r.centerY(), r.width() * .29f, paint);
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(CYAN);
                    c.drawCircle(r.centerX(), r.centerY(), r.width() * .36f, paint); paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(r.centerX(), r.centerY(), 5, paint);
                } else {
                    paint.setColor(0xFF020910); c.drawRoundRect(new RectF(r.left + 5, r.top + 8, r.right + 5, r.bottom + 8), 9, 9, paint);
                    paint.setColor(0xFF243A4A); c.drawRoundRect(r, 8, 8, paint);
                    paint.setColor(0xFF354F60); c.drawRoundRect(new RectF(r.left + 4, r.top + 4, r.right - 4, r.centerY()), 5, 5, paint);
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xFF678092);
                    c.drawRoundRect(r, 8, 8, paint); paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFF172A3A); c.drawRoundRect(new RectF(r.left + 10, r.centerY() - 4, r.right - 10, r.centerY() + 4), 4, 4, paint);
                    paint.setColor(0xFF73A9B9); c.drawCircle(r.left + 7, r.top + 7, 2.5f, paint); c.drawCircle(r.right - 7, r.top + 7, 2.5f, paint);
                    paint.setColor(CYAN); c.drawCircle(r.centerX(), r.centerY(), 2.2f, paint);
                }
            }
        }

        private void drawHud(Canvas c) {
            paint.setColor(0xE51D2921); c.drawRoundRect(new RectF(13, 14, WORLD_W - 13, 66), 17, 17, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.2f); paint.setColor(withAlpha(CYAN, 170));
            c.drawRoundRect(new RectF(13, 14, WORLD_W - 13, 66), 17, 17, paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(CYAN, 48)); c.drawCircle(34, 39, 15, paint);
            paint.setColor(CYAN); c.drawCircle(34, 39, 7, paint); paint.setColor(0xFFECFFFF); c.drawCircle(34, 39, 3, paint);
            drawLabel(c, "HOME DEFENSE", 55, 37, 14, 0xFFFFD48C, false);
            drawLabel(c, "KEEP YOUR HOME ALIVE", 55, 51, 8, 0xFFB7C7A6, false);
            int seconds = Math.max(0, 60 - (int)(roundElapsedMs / 1000L));
            String timer = (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
            drawLabel(c, "ROUND " + roundNumber + "  " + timer, WORLD_W * .54f, 37, 13, Color.WHITE, true);
            drawLabel(c, "SCORE  " + score, WORLD_W * .54f, 52, 9, 0xFFB4C8D7, true);
            paint.setColor(0xFFFFD765); c.drawCircle(WORLD_W - 89, 37, 5, paint);
            drawLabel(c, "" + coins, WORLD_W - 78, 42, 12, GOLD, false);
            paint.setColor(0xFF132019); c.drawRoundRect(new RectF(26, 78, WORLD_W - 26, 87), 5, 5, paint);
            paint.setColor(0xFF536747); c.drawRoundRect(new RectF(26, 78, WORLD_W - 26, 87), 5, 5, paint);
            float xpWidth = (WORLD_W - 52) * xp / Math.max(1f, xpNext);
            paint.setColor(0xFF55B89A); c.drawRoundRect(new RectF(26, 78, 26 + xpWidth, 87), 5, 5, paint);
            paint.setColor(CYAN); c.drawRoundRect(new RectF(26, 78, 26 + xpWidth, 82), 3, 3, paint);
            drawLabel(c, "LV " + level, 28, 105, 11, 0xFFFFD48C, false);
            drawLabel(c, "ZOMBIES " + enemies.size(), WORLD_W - 102, 105, 10, 0xFFB7C7A6, false);
            float healthY = WORLD_H - 58;
            int healthColor = hp > 35 ? 0xFF70D987 : PINK;
            paint.setColor(0xE8101714); c.drawRoundRect(new RectF(17, healthY - 5, WORLD_W - 17, healthY + 23), 10, 10, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(0xFFE7F2E8);
            c.drawRoundRect(new RectF(17, healthY - 5, WORLD_W - 17, healthY + 23), 10, 10, paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF351E20); c.drawRoundRect(new RectF(25, healthY, WORLD_W - 25, healthY + 13), 6, 6, paint);
            float hull = Math.max(0, Math.min(1, hp / (float)maxHp));
            paint.setColor(withAlpha(healthColor, 100)); c.drawRoundRect(new RectF(26, healthY + 1, 24 + (WORLD_W - 48) * hull, healthY + 12), 5, 5, paint);
            paint.setColor(healthColor); c.drawRoundRect(new RectF(27, healthY + 2, 23 + (WORLD_W - 46) * hull, healthY + 10), 4, 4, paint);
            paint.setColor(0xCCFFFFFF); c.drawRect(29, healthY + 3, 25 + (WORLD_W - 50) * hull, healthY + 5, paint);
            drawLabel(c, "♥  SURVIVOR  " + hp + " / " + maxHp, WORLD_W / 2, healthY - 8, 12, Color.WHITE, true);
            drawControls(c);
        }

        private void drawControls(Canvas c) {
            float jy = WORLD_H - 155;
            paint.setColor(0x302CE5F7); c.drawCircle(91, jy, 59, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(0x8B3FE5FF); c.drawCircle(91, jy, 55, paint);
            paint.setColor(0x4A3FE5FF); c.drawCircle(91, jy, 31, paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF397C67); c.drawCircle(91 + (float)moveX * 28, jy + (float)moveY * 28, 20, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(CYAN); c.drawCircle(91 + (float)moveX * 28, jy + (float)moveY * 28, 20, paint); paint.setStyle(Paint.Style.FILL);
            float dashX = WORLD_W - 83;
            paint.setColor(dashCooldown == 0 ? 0x5A4DE7FF : 0x40526575); c.drawCircle(dashX, jy, 49, paint);
            paint.setColor(dashCooldown == 0 ? 0xFF397C67 : 0xFF413F36); c.drawCircle(dashX, jy, 40, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(dashCooldown == 0 ? CYAN : 0xFF64798A); c.drawCircle(dashX, jy, 40, paint); paint.setStyle(Paint.Style.FILL);
            drawLabel(c, "DASH", dashX, jy + 5, 13, dashCooldown == 0 ? Color.WHITE : 0xFF9AABB8, true);
            if (dashCooldown > 0) drawLabel(c, (dashCooldown / 60 + 1) + "s", dashX, jy + 66, 10, 0xFFAAC2D4, true);
        }

        private void drawPlayer(Canvas c) {
            if (hitInvulnerable > 0 && frame % 8 < 4) return;
            float x = (float) px, y = (float) py;
            Enemy target = nearestEnemy();
            float angle = target == null ? 0 : (float) Math.toDegrees(Math.atan2(target.y - py, target.x - px)) + 90;
            paint.setColor(0x4DFFC45E); c.drawCircle(x, y, 31, paint);
            paint.setColor(0x99020810); c.drawOval(new RectF(x - 21, y - 9, x + 21, y + 29), paint);
            c.save(); c.translate(x, y); c.rotate(angle); c.scale(1.82f, 1.82f);
            float step = (float)Math.sin(animationFrame * .24) * (Math.abs(moveX) + Math.abs(moveY) > .1 ? 5 : 1);
            // Human proportions: boots, articulated legs, waist, shoulders, arms and head.
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(5); paint.setColor(0xFF34414B);
            c.drawLine(-4, 5, -6 + step, 17, paint); c.drawLine(4, 5, 6 - step, 17, paint);
            paint.setStrokeWidth(4); paint.setColor(0xFF65747C);
            c.drawLine(-4, 7, -6 + step, 16, paint); c.drawLine(4, 7, 6 - step, 16, paint);
            paint.setStrokeWidth(6); paint.setColor(0xFF24313C);
            c.drawLine(-6 + step, 17, -9 + step, 19, paint); c.drawLine(6 - step, 17, 9 - step, 19, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            // Softly curved jacket silhouette and visible vest panels.
            shape.reset(); shape.moveTo(-5, -12); shape.cubicTo(-9, -11, -11, -6, -10, -1);
            shape.cubicTo(-10, 3, -9, 8, -7, 10); shape.quadTo(0, 13, 7, 10);
            shape.cubicTo(9, 6, 10, 1, 10, -2); shape.cubicTo(11, -7, 8, -11, 5, -12); shape.close();
            paint.setColor(0xFF176C60); c.drawPath(shape, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setColor(0xFFA2F1CF); c.drawPath(shape, paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF25927B); c.drawRoundRect(new RectF(-9, -10, 9, -4), 3, 3, paint);
            paint.setColor(0xFFEDB65D); c.drawRoundRect(new RectF(-5, -9, 5, 5), 3, 3, paint);
            paint.setColor(0xFF24433C); c.drawRoundRect(new RectF(-3.5f, -8, 3.5f, 8), 2, 2, paint);
            paint.setColor(0xFFF2D08F); c.drawCircle(0, -4, 1.1f, paint); c.drawCircle(0, 1, 1.1f, paint);
            // Backpack and radio silhouette.
            paint.setColor(0xFF7B4D36); c.drawRoundRect(new RectF(6, -7, 11, 3), 2.5f, 2.5f, paint);
            paint.setColor(0xFFFFD273); c.drawCircle(9, -4, 1.2f, paint);
            // Sleeves lead naturally into gloved hands gripping the rifle.
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(5); paint.setColor(0xFF176C60);
            c.drawLine(-8, -8, -11, -3, paint); c.drawLine(8, -8, 11, -3, paint);
            paint.setStrokeWidth(3.5f); paint.setColor(0xFFE3B68D);
            c.drawLine(-11, -3, -4, -7, paint); c.drawLine(11, -3, 3, -8, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            // Rifle, stock, barrel and a warm muzzle flash.
            paint.setColor(0xFF35424A); c.drawRoundRect(new RectF(-2, -28, 2, -7), 1.5f, 1.5f, paint);
            paint.setColor(0xFFB6C9BF); c.drawRect(-1, -33, 1, -27, paint);
            paint.setColor(0xFF1E2B30); c.drawRoundRect(new RectF(-4, -13, 4, -9), 1, 1, paint);
            paint.setColor(0xFFDB9650); c.drawCircle(0, -7, 2, paint);
            // Face, hair and cap make the survivor read as a person at a glance.
            paint.setColor(0xFFE5B78F); c.drawOval(new RectF(-6.5f, -24, 6.5f, -12), paint);
            paint.setColor(0xFF594032); c.drawArc(new RectF(-7, -27, 7, -14), 180, 180, false, paint);
            paint.setColor(0xFFE8C36E); c.drawRoundRect(new RectF(-9, -26, 9, -22), 2, 2, paint);
            paint.setColor(0xFF4A382F); c.drawCircle(-2.5f, -18, 1.15f, paint); c.drawCircle(2.5f, -18, 1.15f, paint);
            paint.setColor(0xFFFFFFFF); c.drawCircle(-2.2f, -18.2f, .35f, paint); c.drawCircle(2.8f, -18.2f, .35f, paint);
            paint.setColor(0xFF9A6150); c.drawLine(-1.5f, -14, 1.5f, -14, paint);
            if (muzzleFlashTicks > 0) { paint.setColor(0xFFFFE58B); c.drawCircle(0, -36, 4 + muzzleFlashTicks, paint); }
            c.restore();
            if (dashInvulnerable > 0) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xB9B5E875); c.drawCircle(x, y, 26, paint); paint.setStyle(Paint.Style.FILL); }
        }

        private void drawAmbient(Canvas c) {
            for (int i = 0; i < 28; i++) {
                float x = ARENA_LEFT + 12 + (i * 67 % (int)(ARENA_RIGHT - ARENA_LEFT - 24));
                float y = ARENA_TOP + 18 + (i * 109 + (int)(animationFrame * (i % 3 + 1) * .16f)) % (int)(ARENA_BOTTOM - ARENA_TOP - 36);
                int pulse = 18 + (int)((Math.sin((animationFrame + i * 19) * .09) + 1) * 18);
                paint.setColor(withAlpha(i % 4 == 0 ? CYAN : 0xFF7195AA, pulse));
                c.drawCircle(x, y, i % 5 == 0 ? 2.3f : 1.2f, paint);
            }
        }

        private void drawShard(Canvas c, Shard s) {
            float x = (float)s.x, y = (float)s.y;
            float pulse = 1 + (float)Math.sin((animationFrame + s.phase) * .16) * .13f;
            paint.setColor(0x42FFD765); c.drawCircle(x, y, 14 * pulse, paint);
            c.save(); c.rotate((animationFrame * 3 + s.phase) % 360, x, y);
            shape.reset(); shape.moveTo(x, y - 8 * pulse); shape.lineTo(x + 6 * pulse, y); shape.lineTo(x, y + 8 * pulse); shape.lineTo(x - 6 * pulse, y); shape.close();
            paint.setColor(0xFFFFE69A); c.drawPath(shape, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(GOLD); c.drawPath(shape, paint); paint.setStyle(Paint.Style.FILL);
            c.restore();
        }

        private void drawBullet(Canvas c, Bullet b) {
            float x = (float)b.x, y = (float)b.y;
            paint.setColor(0x55FFF09A); c.drawCircle(x, y, 9, paint);
            paint.setColor(0xCC74F4FF); paint.setStrokeWidth(3); c.drawLine(x - (float)b.vx * 1.2f, y - (float)b.vy * 1.2f, x, y, paint);
            paint.setColor(Color.WHITE); c.drawCircle(x, y, 3, paint);
        }

        private void drawEnemy(Canvas c, Enemy e) {
            float x = (float)e.x, y = (float)e.y, r = e.radius;
            float gait = (float)Math.sin((animationFrame + e.x * 2) * .22) * (e.type == 1 ? 4.5f : 2.5f);
            float pulse = (float)Math.sin((animationFrame + e.y) * .11) * 1.5f;
            paint.setColor(withAlpha(e.color, 32)); c.drawCircle(x, y, r + 9 + pulse, paint);
            paint.setColor(0x99070A08); c.drawOval(new RectF(x-r, y+r*.75f, x+r, y+r*1.35f), paint);
            double targetX = HOME_X, targetY = HOME_Y;
            double playerDistance = distance(px, py, e.x, e.y), homeDistance = distance(HOME_X, HOME_Y, e.x, e.y);
            if (playerDistance < (e.type == 1 ? 330 : 230) || playerDistance + 12 < homeDistance) { targetX = px; targetY = py; }
            c.save(); c.translate(x, y); c.rotate((float)Math.toDegrees(Math.atan2(targetY - e.y, targetX - e.x)) + 90); c.scale(r / 16f * 1.38f, r / 16f * 1.38f);
            if (e.type == 1) {
                drawZombieDog(c, gait);
                c.restore();
                drawEnemyHealthBar(c, e, x, y, r);
                return;
            }
            int skin = e.type == 2 ? 0xFF8A9274 : e.type == 3 ? 0xFF9BA678 : 0xFFA5AC86;
            int shadow = e.type == 2 ? 0xFF41372D : 0xFF35372D;
            // Staggered, limping legs and mismatched boots keep the silhouette human.
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(e.type == 2 ? 8 : 5); paint.setColor(shadow);
            c.drawLine(-4, 3, -6 + gait, 14, paint); c.drawLine(4, 3, 7 - gait, 14, paint);
            paint.setStrokeWidth(5); paint.setColor(skin); c.drawLine(-4, 4, -6 + gait, 13, paint); c.drawLine(4, 4, 7 - gait, 13, paint);
            paint.setStrokeWidth(6); paint.setColor(0xFF292820); c.drawLine(-6 + gait, 14, -10 + gait, 16, paint); c.drawLine(7 - gait, 14, 11 - gait, 15, paint);
            // Slumped torn jacket: deliberately uneven hem and shoulder line.
            shape.reset(); shape.moveTo(-8, -7); shape.lineTo(-5, -10); shape.lineTo(4, -10); shape.lineTo(8, -5);
            shape.lineTo(7, 2); shape.lineTo(4, 5); shape.lineTo(1, 3); shape.lineTo(-2, 6); shape.lineTo(-5, 3); shape.lineTo(-8, 4); shape.close();
            paint.setColor(shadow); c.drawPath(shape, paint);
            shape.offset(0, -1); paint.setColor(e.color); c.drawPath(shape, paint); shape.offset(0, 1);
            paint.setColor(withAlpha(0xFFFFD59A, 170)); c.drawLine(-3, -6, -1, -2, paint); c.drawLine(-1, -2, 1, -4, paint);
            paint.setColor(0xFF4B332C); c.drawLine(3, -8, 2, -3, paint);
            paint.setColor(0xFFE0D4AE); c.drawLine(-3, 0, 3, 1, paint); c.drawLine(-3, 2, 2, 3, paint);
            // One reaching arm and one dragging arm, with visible clawed fingers.
            paint.setStrokeWidth(5); paint.setColor(skin);
            c.drawLine(-7, -6, -12, -1, paint); c.drawLine(-12, -1, -13, 5, paint);
            c.drawLine(7, -6, 11, -3, paint); c.drawLine(11, -3, 13, 2, paint);
            paint.setStrokeWidth(1.8f); c.drawLine(-13, 5, -16, 7, paint); c.drawLine(-13, 5, -15, 9, paint);
            c.drawLine(13, 2, 15, 5, paint); c.drawLine(13, 2, 16, 3, paint);
            // Green-grey face, broken hairline, sunken eyes, and a torn-open jaw.
            paint.setColor(skin); c.drawCircle(0, -12, 7.2f, paint);
            paint.setColor(0xFF625347); c.drawArc(new RectF(-7, -20, 7, -8), 180, 170, false, paint);
            paint.setColor(0xFF6C3C32); c.drawOval(new RectF(-7, -13, -3, -9), paint);
            paint.setColor(0xFF25241F); c.drawOval(new RectF(-4.8f, -14, -2.2f, -11), paint);
            c.drawOval(new RectF(2, -14, 4.8f, -11), paint);
            paint.setColor(e.type == 3 ? 0xFFFFC65B : 0xFFE9C765); c.drawCircle(-3.4f, -12.5f, 1.2f, paint); c.drawCircle(3.4f, -12.5f, 1.2f, paint);
            paint.setColor(0xFF29231F); c.drawOval(new RectF(-3.2f, -8.5f, 3.2f, -4.5f), paint);
            paint.setColor(0xFFE8D9B1); c.drawLine(-2, -7.5f, -1, -6.2f, paint); c.drawLine(1, -7.5f, 2, -6.2f, paint);
            paint.setColor(0xFF713E34); c.drawLine(-6, -15, -2, -14, paint); c.drawLine(2, -16, 6, -15, paint);
            paint.setColor(0xFF5C3C31); c.drawOval(new RectF(-6, -13, -4, -10), paint);
            if (e.type == 3) {
                paint.setColor(0xFFC4B56F); c.drawCircle(-5, -3, 2.5f, paint); c.drawCircle(5, 1, 2, paint); c.drawCircle(0, 5, 2.2f, paint);
            }
            c.restore();
            drawEnemyHealthBar(c, e, x, y, r);
        }

        private void drawZombieDog(Canvas c, float gait) {
            // Low, muscular canine silhouette with four articulated legs, pointed ears and a torn muzzle.
            c.save(); c.scale(1.08f, .96f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(5.5f); paint.setColor(0xFF392B29);
            c.drawLine(-6, 0, -8 + gait, 10, paint); c.drawLine(6, 0, 8 - gait, 10, paint);
            c.drawLine(-5, 4, -4 - gait, 13, paint); c.drawLine(5, 4, 4 + gait, 13, paint);
            paint.setStrokeWidth(3.5f); paint.setColor(0xFFC36A4C);
            c.drawLine(-6, 0, -8 + gait, 9, paint); c.drawLine(6, 0, 8 - gait, 9, paint);
            c.drawLine(-5, 4, -4 - gait, 12, paint); c.drawLine(5, 4, 4 + gait, 12, paint);
            paint.setColor(0xFF24201D); paint.setStrokeWidth(5);
            c.drawLine(-8 + gait, 9, -10 + gait, 10, paint); c.drawLine(8 - gait, 9, 10 - gait, 10, paint);
            c.drawLine(-4 - gait, 12, -6 - gait, 13, paint); c.drawLine(4 + gait, 12, 6 + gait, 13, paint);
            shape.reset(); shape.moveTo(-8, -5); shape.quadTo(-4, -10, 3, -7); shape.lineTo(8, -3);
            shape.lineTo(7, 3); shape.quadTo(0, 8, -7, 3); shape.close();
            paint.setColor(0xFF57382F); c.drawPath(shape, paint);
            paint.setColor(0xFFB45E43); c.drawPath(shape, paint);
            paint.setColor(0xFFFFB276); c.drawArc(new RectF(-6, -7, 7, 6), 210, 100, false, paint);
            // Ragged collar and metal tag.
            paint.setColor(0xFF26332D); paint.setStrokeWidth(3); c.drawLine(-6, -3, 6, -3, paint);
            paint.setColor(0xFFFFC75E); c.drawCircle(0, -1, 1.7f, paint);
            // Neck, jaw and skull point toward the target.
            shape.reset(); shape.moveTo(-6, -5); shape.lineTo(-5, -13); shape.lineTo(-2, -17);
            shape.lineTo(5, -15); shape.lineTo(10, -10); shape.lineTo(8, -5); shape.lineTo(2, -2); shape.close();
            paint.setColor(0xFF6B4033); c.drawPath(shape, paint);
            paint.setColor(0xFFC87652); c.drawOval(new RectF(-5, -16, 6, -5), paint);
            shape.reset(); shape.moveTo(-4, -14); shape.lineTo(-8, -23); shape.lineTo(-1, -19); shape.close();
            paint.setColor(0xFF69382F); c.drawPath(shape, paint);
            shape.reset(); shape.moveTo(2, -16); shape.lineTo(5, -24); shape.lineTo(8, -16); shape.close(); c.drawPath(shape, paint);
            paint.setColor(0xFF32241F); c.drawCircle(-1, -13, 1.7f, paint); c.drawCircle(5, -13, 1.7f, paint);
            paint.setColor(0xFFFFD36A); c.drawCircle(-1, -13, .9f, paint); c.drawCircle(5, -13, .9f, paint);
            paint.setColor(0xFF3A2420); c.drawOval(new RectF(3, -9, 12, -5), paint);
            paint.setColor(0xFF27201D); c.drawCircle(11, -8, 2, paint);
            paint.setColor(0xFFE8D9B1); paint.setStrokeWidth(1.2f);
            c.drawLine(6, -8, 6, -6, paint); c.drawLine(9, -8, 9, -6, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            c.restore();
        }

        private void drawEnemyHealthBar(Canvas c, Enemy e, float x, float y, float r) {
            if (e.hp >= e.maxHp) return;
            float width = r * 2.2f, top = y - r - 12;
            paint.setColor(0xEE101513); c.drawRoundRect(new RectF(x-width/2, top, x+width/2, top+6), 3, 3, paint);
            paint.setColor(e.type == 1 ? 0xFFFF805F : 0xFFFF8068);
            c.drawRoundRect(new RectF(x-width/2+1, top+1, x-width/2+1+(width-2)*Math.max(0, e.hp/(float)e.maxHp), top+5), 2, 2, paint);
        }

        private void drawMenu(Canvas c) {
            overlay(c);
            paint.setColor(0x453FE5FF); c.drawCircle(WORLD_W / 2, 278, 73, paint);
            paint.setColor(0xFF0A1A2A); c.drawCircle(WORLD_W / 2, 278, 57, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(CYAN); c.drawCircle(WORLD_W / 2, 278, 49, paint); paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF16384C); c.drawCircle(WORLD_W / 2, 278, 20, paint);
            paint.setColor(Color.WHITE); c.drawCircle(WORLD_W / 2, 278, 7, paint);
            drawLabel(c, "LAST", WORLD_W / 2, 300, 42, 0xFFFFD48C, true);
            drawLabel(c, "LIGHT", WORLD_W / 2, 347, 42, Color.WHITE, true);
            drawLabel(c, "HOME DEFENSE", WORLD_W / 2, 375, 13, 0xFFFFD48C, true);
            drawLabel(c, "The dead are coming. Protect your home.", WORLD_W / 2, 402, 12, 0xFFE3EDF4, true);
            drawButton(c, new RectF(58, 476, WORLD_W - 58, 540), "START RUN", GOLD);
            drawLabel(c, "Drag to move. Your rifle tracks nearby zombies.", WORLD_W / 2, 590, 12, 0xFFB5CAD9, true);
            drawLabel(c, "Survive each minute and spend credits to upgrade.", WORLD_W / 2, 614, 11, 0xFFB5CAD9, true);
            drawLabel(c, "Best score  " + best, WORLD_W / 2, 690, 13, CYAN, true);
        }

        private void drawUpgrade(Canvas c) {
            overlay(c);
            drawLabel(c, "LEVEL UP", WORLD_W / 2, 265, 31, CYAN, true);
            drawLabel(c, "Choose one upgrade", WORLD_W / 2, 295, 14, Color.WHITE, true);
            String[] title = {"RAPID RIFLE", "HARDENED ROUNDS", homeHp < maxHomeHp - 35 ? "REPAIR THE HOUSE" : hp < maxHp - 22 ? "FIELD MEDKIT" : "CRYSTAL MAGNET"};
            String[] subtitle = {"Fire more often", "More damage per shot", homeHp < maxHomeHp - 35 ? "Restore 40 home health" : hp < maxHp - 22 ? "Restore 22 health" : "Auto pickup range +70  Lv " + (magnetLevel + 1)};
            int[] colors = {CYAN, PINK, GOLD};
            for (int i = 0; i < 3; i++) {
                float y = 335 + i * 106;
                paint.setColor(withAlpha(colors[i], 30)); c.drawRoundRect(new RectF(31, y - 2, WORLD_W - 31, y + 81), 18, 18, paint);
                paint.setColor(0xFF202821); c.drawRoundRect(new RectF(35, y + 4, WORLD_W - 35, y + 82), 16, 16, paint);
                paint.setColor(0xFF354337); c.drawRoundRect(new RectF(35, y, WORLD_W - 35, y + 78), 16, 16, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(withAlpha(colors[i], 160)); c.drawRoundRect(new RectF(35, y, WORLD_W - 35, y + 78), 16, 16, paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(colors[i], 60)); c.drawCircle(67, y + 38, 22, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(colors[i]); c.drawCircle(67, y + 38, 15, paint); paint.setStyle(Paint.Style.FILL);
                drawLabel(c, "" + (i + 1), 67, y + 43, 15, Color.WHITE, true);
                drawLabel(c, title[i], 94, y + 31, 15, colors[i], false);
                drawLabel(c, subtitle[i], 94, y + 56, 12, Color.WHITE, false);
            }
        }

        private void drawShop(Canvas c) {
            overlay(c);
            drawLabel(c, "ROUND " + (roundNumber - 1) + " COMPLETE", WORLD_W / 2, 194, 25, GOLD, true);
            drawLabel(c, "PREPARE FOR ROUND " + roundNumber, WORLD_W / 2, 224, 13, Color.WHITE, true);
            drawLabel(c, shopMessage, WORLD_W / 2, 251, 10, 0xFFB6C9D9, true);
            paint.setColor(0xFFFFD765); c.drawCircle(WORLD_W / 2 - 38, 274, 6, paint);
            drawLabel(c, "CREDITS  " + coins, WORLD_W / 2 - 25, 279, 15, GOLD, false);
            String[] names = {"RAPID RIFLE", "PIERCING ROUNDS", "RUNNER'S BOOTS", "HOME REINFORCEMENT", "CRYSTAL MAGNET"};
            String[] detail = {
                    "Weapon: faster automatic fire  Lv " + rapidLevel,
                    "Weapon: stronger shots  Lv " + damageLevel,
                    "Survivor: move faster  Lv " + speedLevel,
                    "Your home: +20 max health and repair",
                    "Auto pickup range +70  Lv " + magnetLevel
            };
            int[] colors = {CYAN, PINK, GOLD, 0xFF55E6A5, PURPLE};
            for (int i = 0; i < 5; i++) {
                float y = 292 + i * 72;
                paint.setColor(0xFF202821); c.drawRoundRect(new RectF(30, y + 4, WORLD_W - 30, y + 64), 14, 14, paint);
                paint.setColor(0xFF354337); c.drawRoundRect(new RectF(30, y, WORLD_W - 30, y + 60), 14, 14, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f); paint.setColor(withAlpha(colors[i], 180));
                c.drawRoundRect(new RectF(30, y, WORLD_W - 30, y + 60), 14, 14, paint); paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(colors[i], 52)); c.drawCircle(61, y + 30, 18, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(colors[i]); c.drawCircle(61, y + 30, 13, paint); paint.setStyle(Paint.Style.FILL);
                drawLabel(c, new String[]{"⚡", "✦", "➤", "✚", "◈"}[i], 61, y + 35, 14, colors[i], true);
                drawLabel(c, names[i], 88, y + 25, 13, colors[i], false);
                drawLabel(c, detail[i], 88, y + 44, 8, 0xFFD7E5EF, false);
                int price = shopPrice(i);
                String priceText = price < 0 ? "MAX" : price + " ¢";
                drawLabel(c, priceText, WORLD_W - 48, y + 35, 11, price < 0 ? 0xFF8A9BA8 : GOLD, true);
            }
            drawButton(c, new RectF(46, 676, WORLD_W - 46, 742), "START ROUND " + roundNumber, CYAN);
            drawLabel(c, "Buy any number of upgrades, then tap to continue", WORLD_W / 2, 776, 10, 0xFFB5CAD9, true);
        }

        private void drawGameOver(Canvas c) {
            overlay(c);
            drawLabel(c, homeHp <= 0 ? "HOME LOST" : "YOU FELL", WORLD_W / 2, 335, 34, PINK, true);
            drawLabel(c, "Score  " + score, WORLD_W / 2, 387, 20, Color.WHITE, true);
            drawLabel(c, kills + " zombies stopped  •  Round " + roundNumber, WORLD_W / 2, 417, 13, 0xFFCADAE5, true);
            drawLabel(c, "Credits collected  " + coins, WORLD_W / 2, 441, 13, GOLD, true);
            drawLabel(c, "Best score  " + best, WORLD_W / 2, 469, 14, CYAN, true);
            drawButton(c, new RectF(58, 505, WORLD_W - 58, 569), "TRY AGAIN", GOLD);
        }

        private void drawPause(Canvas c) {
            overlay(c);
            drawLabel(c, "PAUSED", WORLD_W / 2, 371, 34, CYAN, true);
            drawButton(c, new RectF(58, 435, WORLD_W - 58, 499), "RESUME", GOLD);
            drawLabel(c, "Tap the button or use the Back gesture to resume", WORLD_W / 2, 535, 12, 0xFFCADAE5, true);
        }

        private void drawDamageFlash(Canvas c) {
            int alpha = Math.max(0, Math.min(58, hitInvulnerable));
            paint.setColor(withAlpha(PINK, alpha));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(9);
            c.drawRoundRect(new RectF(4, 4, WORLD_W - 4, WORLD_H - 4), 16, 16, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void overlay(Canvas c) { paint.setColor(0xD9141915); c.drawRect(0, 0, WORLD_W, WORLD_H, paint); }
        private void drawButton(Canvas c, RectF rect, String text, int color) {
            paint.setColor(withAlpha(color, 36)); c.drawRoundRect(new RectF(rect.left - 5, rect.top - 4, rect.right + 5, rect.bottom + 4), 21, 21, paint);
            paint.setColor(0xFF344437); c.drawRoundRect(rect, 17, 17, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(color); c.drawRoundRect(rect, 17, 17, paint); paint.setStyle(Paint.Style.FILL);
            drawLabel(c, text, rect.centerX(), rect.centerY() + 6, 17, color, true);
        }

        private void drawLabel(Canvas c, String text, float x, float y, float size, int color, boolean centered) {
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(size > 14 ? boldTypeface : mediumTypeface);
            paint.setTextSize(size); paint.setColor(color);
            if (centered) x -= paint.measureText(text) * .5f;
            if (size > 20) paint.setShadowLayer(12, 0, 0, withAlpha(color, 190));
            else paint.clearShadowLayer();
            c.drawText(text, x, y, paint);
            paint.clearShadowLayer();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int index = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float[] p = toWorld(event.getX(index), event.getY(index));
                touchX = p[0]; touchY = p[1];
                if (mode == MENU || mode == OVER) { startRun(); invalidate(); return true; }
                if (mode == PAUSED) { if (touchY > 400 && touchY < 535) { mode = RUN; lastRoundUpdateMs = SystemClock.elapsedRealtime(); } invalidate(); return true; }
                if (mode == UPGRADE) {
                    for (int i = 0; i < 3; i++) { float y = 335 + i * 106; if (touchY >= y && touchY <= y + 78) { selectUpgrade(i); break; } }
                    invalidate(); return true;
                }
                if (mode == SHOP) {
                    if (touchY >= 676 && touchY <= 755) startNextRound();
                    else if (touchY >= 292 && touchY < 652) buyShopItem(Math.min(4, (int)((touchY - 292) / 72)));
                    invalidate(); return true;
                }
                if (touchY < 72 && touchX > WORLD_W - 95) { mode = PAUSED; moveX = moveY = 0; movePointer = -1; invalidate(); return true; }
                if (touchX > WORLD_W * .67f && touchY > WORLD_H - 235) { dash(); invalidate(); return true; }
                if (touchX < WORLD_W * .56f && touchY > WORLD_H * .48f) {
                    movePointer = event.getPointerId(index); updateJoystick(p[0], p[1]); invalidate(); return true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && movePointer >= 0) {
                int i = event.findPointerIndex(movePointer);
                if (i >= 0) { float[] p = toWorld(event.getX(i), event.getY(i)); updateJoystick(p[0], p[1]); invalidate(); }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (event.getPointerId(index) == movePointer || action == MotionEvent.ACTION_CANCEL) { movePointer = -1; moveX = moveY = 0; invalidate(); }
                return true;
            }
            return true;
        }

        private float[] toWorld(float x, float y) { return new float[]{(float)((x - offsetX) / scale), (float)((y - offsetY) / scale)}; }

        private void updateJoystick(float x, float y) {
            double dx = x - 91, dy = y - (WORLD_H - 155), d = Math.max(1, Math.hypot(dx, dy));
            double strength = Math.min(1, d / 50); moveX = dx / d * strength; moveY = dy / d * strength;
        }

        private static int withAlpha(int color, int alpha) { return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color)); }
        private static double distance(double x, double y, double a, double b) { return Math.hypot(x - a, y - b); }
        private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
        private static boolean insideRuin(double x, double y, float padding) {
            for (RectF r : RUINS) if (x >= r.left - padding && x <= r.right + padding && y >= r.top - padding && y <= r.bottom + padding) return true;
            return false;
        }
        private static boolean blockedByMap(double x, double y, float radius) {
            return x - radius < ARENA_LEFT || x + radius > ARENA_RIGHT || y - radius < ARENA_TOP || y + radius > ARENA_BOTTOM || insideRuin(x, y, radius);
        }

        private static final class Enemy {
            double x, y, speed; int hp, maxHp, radius, color, type, homeDamage, attackCooldown, playerAttackCooldown;
            Enemy(double x, double y, int hp, double speed, int radius, int color, int type, int homeDamage) { this.x=x; this.y=y; this.hp=hp; this.maxHp=hp; this.speed=speed; this.radius=radius; this.color=color; this.type=type; this.homeDamage=homeDamage; }
        }
        private static final class Bullet {
            double x, y, vx, vy; int life=90, damage;
            Bullet(double x, double y, double vx, double vy, int damage) { this.x=x; this.y=y; this.vx=vx; this.vy=vy; this.damage=damage; }
        }
        private static final class Shard { double x,y; long phase; Shard(double x,double y){this.x=x;this.y=y;phase=(long)(Math.random()*360);} }
        private static final class Particle {
            double x,y,vx,vy; int life=28,color;
            Particle(double x,double y,double vx,double vy,int color){this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.color=color;}
        }
    }
}

