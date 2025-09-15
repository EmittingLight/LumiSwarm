package dev.yaga.cuckoo;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;

import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.Glow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;

import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class App extends Application {

    // ==== настройки ====
    private static final int START_COUNT = 180;
    private final Random rnd = new Random();

    // слои
    private Pane   field;
    private Canvas trailsCanvas;   // шлейф/послесвечение
    private Canvas linksCanvas;    // «созвездия»
    private Canvas fxCanvas;       // эффекты (молния, круги волн, вспышка)
    private Group  glowLayer;      // сами точки (add-mode)
    private final List<Firefly> swarm = new ArrayList<>();

    // интерактив
    private Point2D attractor = null;
    private boolean repel = false;
    private double gravityStrength = 8000; // сила курсора

    private boolean trailsOn = true;
    private boolean linksOn  = true;
    private double  linkDist = 90;

    private boolean galaxyOn = true;
    private double  swirlStrength = 220;

    private boolean paletteDrift = true;
    private double  driftDegPerSec = 8;

    private double glowLevel = 0.6;

    // новые эффекты
    private final List<Shockwave> waves = new ArrayList<>();
    private final List<Bolt> bolts = new ArrayList<>();
    private double flashAlpha = 0.0; // «вспышка экрана» при молнии

    private long lastNs = 0;

    @Override
    public void start(Stage stage) {
        field = new Pane();
        field.setPrefSize(1100, 700);
        field.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#0d1114")), new Stop(1, Color.web("#1a2126"))),
                CornerRadii.EMPTY, Insets.EMPTY)));

        trailsCanvas = new Canvas(field.getPrefWidth(), field.getPrefHeight());
        linksCanvas  = new Canvas(field.getPrefWidth(), field.getPrefHeight());
        fxCanvas     = new Canvas(field.getPrefWidth(), field.getPrefHeight());

        glowLayer = new Group();
        glowLayer.setBlendMode(BlendMode.ADD);
        glowLayer.setMouseTransparent(true);
        glowLayer.setEffect(new Glow(glowLevel));

        field.getChildren().addAll(trailsCanvas, linksCanvas, fxCanvas, glowLayer);

        field.widthProperty().addListener((o, a, w) -> {
            double W = w.doubleValue();
            trailsCanvas.setWidth(W); linksCanvas.setWidth(W); fxCanvas.setWidth(W);
        });
        field.heightProperty().addListener((o, a, h) -> {
            double H = h.doubleValue();
            trailsCanvas.setHeight(H); linksCanvas.setHeight(H); fxCanvas.setHeight(H);
        });

        for (int i = 0; i < START_COUNT; i++) addFirefly();

        Scene scene = new Scene(field);
        stage.setTitle("Светлячки — эксклюзив Яги");
        stage.setScene(scene);
        stage.show();

        // мышь
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            attractor = new Point2D(e.getX(), e.getY());
            repel = e.isSecondaryButtonDown();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            attractor = new Point2D(e.getX(), e.getY());
            repel = e.isSecondaryButtonDown();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> attractor = null);

        // колесо — кол-во частиц
        scene.setOnScroll(e -> tweakCount(e.getDeltaY() > 0 ? 16 : -16));

        // клавиши
        scene.setOnKeyPressed(e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.S) snapshotPNG();
            else if (k == KeyCode.R) randomizePalette();
            else if (k == KeyCode.T) trailsOn = !trailsOn;
            else if (k == KeyCode.C) linksOn  = !linksOn;
            else if (k == KeyCode.G) galaxyOn = !galaxyOn;
            else if (k == KeyCode.P) paletteDrift = !paletteDrift;
            else if (k == KeyCode.B) {
                glowLevel = (glowLevel < 0.05) ? 0.35 : (glowLevel < 0.6 ? 0.85 : 0.0);
                glowLayer.setEffect(new Glow(glowLevel));
            } else if (k == KeyCode.OPEN_BRACKET) {     // [
                gravityStrength *= 0.85;
            } else if (k == KeyCode.CLOSE_BRACKET) {    // ]
                gravityStrength *= 1.15;
            } else if (k == KeyCode.MINUS) {            // -
                linkDist = Math.max(20, linkDist - 10);
            } else if (k == KeyCode.EQUALS) {           // =
                linkDist = Math.min(240, linkDist + 10);
            } else if (k == KeyCode.SPACE) {            // волна: push / pull
                Point2D p = cursorOrCenter();
                boolean pull = e.isShiftDown();
                spawnWave(p.getX(), p.getY(), pull);
            } else if (k == KeyCode.L) {                // молния
                Point2D p = cursorOrCenter();
                spawnBolt(p.getX(), p.getY());
            }
        });

        // анимация
        new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastNs == 0) { lastNs = now; return; }
                double dt = (now - lastNs) / 1_000_000_000.0;
                lastNs = now;

                update(dt);
                renderLinks();
                renderTrails();
                renderFX(dt);
            }
        }.start();
    }

    // ===== частица =====
    private class Firefly {
        double x, y, vx, vy, phase, size;
        double baseHue, baseSat, baseBri;
        Color base;
        Circle node;

        Firefly() {
            double w = field.getWidth()  > 0 ? field.getWidth()  : field.getPrefWidth();
            double h = field.getHeight() > 0 ? field.getHeight() : field.getPrefHeight();
            x = rnd.nextDouble() * w;
            y = rnd.nextDouble() * h;

            vx = (rnd.nextDouble() - 0.5) * 40;
            vy = (rnd.nextDouble() - 0.5) * 40;

            size = 1.5 + rnd.nextDouble() * 2.7;

            Color c = pickColor();
            baseHue = c.getHue(); baseSat = c.getSaturation(); baseBri = c.getBrightness();
            base = c;

            node = new Circle(size, base);
            node.setTranslateX(x);
            node.setTranslateY(y);
            node.setOpacity(0.0);

            phase = rnd.nextDouble() * Math.PI * 2;
        }

        void step(double dt) {
            // блуждание
            vx += (rnd.nextDouble() - 0.5) * 18 * dt;
            vy += (rnd.nextDouble() - 0.5) * 18 * dt;

            // курсор
            if (attractor != null) {
                double ax = attractor.getX() - x;
                double ay = attractor.getY() - y;
                double dist = Math.hypot(ax, ay) + 1e-6;
                double force = gravityStrength / (dist + 40);
                if (repel) force = -force;
                vx += (ax / dist) * force * dt;
                vy += (ay / dist) * force * dt;
            }

            // галактика
            if (galaxyOn) {
                double cx = (attractor != null) ? attractor.getX() : field.getWidth() / 2.0;
                double cy = (attractor != null) ? attractor.getY() : field.getHeight() / 2.0;
                double rx = x - cx, ry = y - cy;
                double r = Math.hypot(rx, ry) + 1e-6;
                double tang = swirlStrength / (1 + r / 120.0);
                vx += (-ry / r) * tang * dt;
                vy += ( rx / r) * tang * dt;
            }

            // влияние ударных волн
            for (Shockwave w : waves) {
                double dx = x - w.x, dy = y - w.y;
                double d = Math.hypot(dx, dy) + 1e-6;
                double band = 24; // толщина фронта
                double k = 1.0 - Math.min(1.0, Math.abs(d - w.r) / band);
                if (k > 0) {
                    double dir = w.pull ? -1.0 : 1.0;
                    double imp = dir * w.power * k;
                    vx += (dx / d) * imp;
                    vy += (dy / d) * imp;
                }
            }

            // демпфирование и ограничение
            vx *= 0.99; vy *= 0.99;
            double v = Math.hypot(vx, vy), vmax = 170;
            if (v > vmax) { vx = vx / v * vmax; vy = vy / v * vmax; }

            // движение
            x += vx * dt; y += vy * dt;

            // тороидальные границы
            double W = field.getWidth()  > 0 ? field.getWidth()  : field.getPrefWidth();
            double H = field.getHeight() > 0 ? field.getHeight() : field.getPrefHeight();
            if (x < -20) x = W + 20; if (x > W + 20) x = -20;
            if (y < -20) y = H + 20; if (y > H + 20) y = -20;

            // дрейф палитры
            if (paletteDrift) {
                baseHue = (baseHue + driftDegPerSec * dt) % 360.0;
                base = Color.hsb(baseHue, baseSat, baseBri, 1.0);
            }

            // сияние
            phase += dt * (1.5 + rnd.nextDouble() * 0.5);
            double glow = 0.20 + 0.80 * 0.5 * (1 + Math.sin(phase));
            node.setFill(base.interpolate(Color.WHITE, 0.6));
            node.setOpacity(glow);

            node.setTranslateX(x);
            node.setTranslateY(y);
        }
    }

    // ===== эффекты =====

    // Ударная волна
    private static class Shockwave {
        double x, y, r, vr, power;
        boolean pull;  // true = тянет внутрь
        double life = 0.8; // сек (для отрисовки круга)

        Shockwave(double x, double y, boolean pull) {
            this.x = x; this.y = y; this.pull = pull;
            this.r = 0; this.vr = 420; // скорость фронта
            this.power = (pull ? -1 : 1) * 28; // импульс частицам (знак дубль, но ладно)
        }

        boolean update(double dt) { r += vr * dt; life -= dt; return life > 0; }
    }

    private void spawnWave(double x, double y, boolean pull) {
        waves.add(new Shockwave(x, y, pull));
    }

    // Молния
    private static class Bolt {
        final List<Point2D> pts = new ArrayList<>();
        double alpha = 1.0;
        double thickness = 2.6;

        Bolt(double x1, double y1, double x2, double y2, Random rnd) {
            genSegment(x1, y1, x2, y2, 12, 28, rnd);
        }

        // рекурсивная генерация ломаной (midpoint displacement)
        private void genSegment(double x1, double y1, double x2, double y2, int depth, double jitter, Random rnd) {
            if (depth == 0) {
                if (pts.isEmpty()) pts.add(new Point2D(x1, y1));
                pts.add(new Point2D(x2, y2));
                return;
            }
            double mx = (x1 + x2) * 0.5;
            double my = (y1 + y2) * 0.5;
            // смещение перпендикулярно
            double dx = x2 - x1, dy = y2 - y1;
            double len = Math.hypot(dx, dy) + 1e-6;
            double nx = -dy / len, ny = dx / len;
            double offset = (rnd.nextDouble() - 0.5) * jitter;
            mx += nx * offset; my += ny * offset;

            genSegment(x1, y1, mx, my, depth - 1, jitter * 0.6, rnd);
            genSegment(mx, my, x2, y2, depth - 1, jitter * 0.6, rnd);

            // редкие боковые ответвления
            if (rnd.nextDouble() < 0.12 && depth > 6) {
                double bx = mx + nx * jitter * 0.4;
                double by = my + ny * jitter * 0.4;
                genSegment(mx, my, bx, by, depth - 2, jitter * 0.5, rnd);
            }
        }

        boolean update(double dt) {
            alpha -= 2.2 * dt;    // быстро гаснет
            thickness *= 0.96;
            return alpha > 0;
        }
    }

    private void spawnBolt(double tx, double ty) {
        double x1 = rnd.nextDouble() * field.getWidth();
        double y1 = -20; // из «неба»
        Bolt b = new Bolt(x1, y1, tx, ty, rnd);
        bolts.add(b);
        flashAlpha = 0.55; // вспышка
        // пнуть частицы поблизости от места удара
        for (Firefly f : swarm) {
            double dx = f.x - tx, dy = f.y - ty, d = Math.hypot(dx, dy) + 1e-6;
            if (d < 220) {
                double kick = 420 / (d + 20);
                f.vx += (dx / d) * kick;
                f.vy += (dy / d) * kick;
            }
        }
    }

    // ===== апдейт и рендер =====
    private void update(double dt) {
        // частицы
        for (Firefly f : swarm) f.step(dt);

        // волны
        waves.removeIf(w -> !w.update(dt));
        // молнии
        bolts.removeIf(b -> !b.update(dt));
    }

    private void renderLinks() {
        GraphicsContext g = linksCanvas.getGraphicsContext2D();
        g.clearRect(0, 0, linksCanvas.getWidth(), linksCanvas.getHeight());
        if (!linksOn) return;

        g.setLineWidth(1.0);
        int n = swarm.size();
        int drawn = 0, limit = 5000;
        for (int i = 0; i < n; i++) {
            var a = swarm.get(i);
            for (int j = i + 1; j < n; j++) {
                var b = swarm.get(j);
                double dx = a.x - b.x, dy = a.y - b.y;
                double d2 = dx * dx + dy * dy;
                if (d2 < linkDist * linkDist) {
                    double d = Math.sqrt(d2);
                    double alpha = Math.max(0, 1.0 - d / linkDist);
                    g.setStroke(Color.rgb(200, 240, 230, 0.28 * alpha));
                    g.strokeLine(a.x, a.y, b.x, b.y);
                    if (++drawn > limit) return;
                }
            }
        }
    }

    private void renderTrails() {
        GraphicsContext g = trailsCanvas.getGraphicsContext2D();
        double w = trailsCanvas.getWidth(), h = trailsCanvas.getHeight();
        if (!trailsOn) { g.clearRect(0, 0, w, h); return; }

        // затухание
        g.setGlobalAlpha(0.10);
        g.setFill(Color.rgb(13, 17, 20));
        g.fillRect(0, 0, w, h);

        // световые пятна
        g.setGlobalAlpha(0.35);
        for (Firefly f : swarm) {
            double r = f.size * 3.8;
            g.setFill(f.base);
            g.fillOval(f.x - r, f.y - r, 2 * r, 2 * r);
        }
        g.setGlobalAlpha(1.0);
    }

    private void renderFX(double dt) {
        GraphicsContext g = fxCanvas.getGraphicsContext2D();
        double w = fxCanvas.getWidth(), h = fxCanvas.getHeight();
        g.clearRect(0, 0, w, h);

        // круги ударных волн
        for (Shockwave wv : waves) {
            double alpha = Math.max(0, Math.min(1, wv.life));
            Color c = wv.pull ? Color.web("#88ddff") : Color.web("#ffee88");
            g.setStroke(c.deriveColor(0, 1, 1, 0.5 * alpha));
            g.setLineWidth(2);
            g.strokeOval(wv.x - wv.r, wv.y - wv.r, wv.r * 2, wv.r * 2);
        }

        // молнии
        for (Bolt b : bolts) {
            g.setLineWidth(b.thickness);
            g.setStroke(Color.rgb(230, 255, 255, 0.8 * b.alpha));
            for (int i = 0; i < b.pts.size() - 1; i++) {
                Point2D p = b.pts.get(i), q = b.pts.get(i + 1);
                g.strokeLine(p.getX(), p.getY(), q.getX(), q.getY());
            }
        }

        // вспышка экрана
        if (flashAlpha > 0) {
            g.setGlobalAlpha(flashAlpha);
            g.setFill(Color.rgb(255, 255, 255));
            g.fillRect(0, 0, w, h);
            g.setGlobalAlpha(1);
            flashAlpha -= 2.5 * dt;
        }
    }

    // ===== управление количеством =====
    private void addFirefly() {
        Firefly f = new Firefly();
        swarm.add(f);
        glowLayer.getChildren().add(f.node);
    }
    private void removeFirefly() {
        if (swarm.isEmpty()) return;
        Firefly f = swarm.remove(swarm.size() - 1);
        glowLayer.getChildren().remove(f.node);
    }
    private void tweakCount(int delta) {
        if (delta > 0) for (int i = 0; i < delta; i++) addFirefly();
        else for (int i = 0; i < -delta && !swarm.isEmpty(); i++) removeFirefly();
    }

    // ===== палитра/фон =====
    private Color pickColor() {
        double hue = 70 + rnd.nextDouble() * 70; // лесная гамма 70..140
        double sat = 0.55 + rnd.nextDouble() * 0.25;
        double bri = 0.65 + rnd.nextDouble() * 0.30;
        return Color.hsb(hue, sat, bri, 1.0);
    }
    private void randomizePalette() {
        Color top = Color.hsb(200 + rnd.nextDouble() * 60, 0.25, 0.10);
        Color bottom = Color.hsb(200 + rnd.nextDouble() * 60, 0.30, 0.18);
        field.setBackground(new Background(new BackgroundFill(
                new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, top), new Stop(1, bottom)),
                CornerRadii.EMPTY, Insets.EMPTY)));
        for (Firefly f : swarm) {
            Color c = pickColor();
            f.baseHue = c.getHue(); f.baseSat = c.getSaturation(); f.baseBri = c.getBrightness();
            f.base = c;
        }
    }

    // ===== утилиты =====
    private Point2D cursorOrCenter() {
        // если курсора нет (нажатие с клавиатуры), берём центр
        double x = (attractor != null ? attractor.getX() : field.getWidth() / 2.0);
        double y = (attractor != null ? attractor.getY() : field.getHeight() / 2.0);
        return new Point2D(x, y);
    }

    private void snapshotPNG() {
        try {
            var img = field.snapshot(new SnapshotParameters(), null);
            String name = "fireflies-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png";
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(name));
        } catch (Exception ex) { ex.printStackTrace(); }
    }

    public static void main(String[] args) { launch(args); }
}
