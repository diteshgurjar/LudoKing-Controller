package app.ludo.diwas.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Random;

/**
 * Hardware-accelerated 60fps 3D tumbling dice view.
 * Pops upward above the dice tray during a roll, rotates across all 3 axes with
 * perspective-foreshortened faces & pips, and lands smoothly on the exact rolled
 * value (1-6) with a subtle bounce settle.
 */
public class Dice3DView extends View {

    public interface RollEndListener {
        void onRollFinished(int value);
    }

    private static final float[][] BASE_VERTS = {
            {-1f, -1f,  1f}, // 0
            { 1f, -1f,  1f}, // 1
            { 1f,  1f,  1f}, // 2
            {-1f,  1f,  1f}, // 3
            {-1f, -1f, -1f}, // 4
            { 1f, -1f, -1f}, // 5
            { 1f,  1f, -1f}, // 6
            {-1f,  1f, -1f}  // 7
    };

    // Each face: {value, v0, v1, v2, v3} ordered counter-clockwise when viewed from outside
    private static final int[][] FACES = {
            {1, 0, 1, 2, 3}, // +Z Front
            {6, 5, 4, 7, 6}, // -Z Back
            {2, 3, 2, 6, 7}, // +Y Bottom
            {5, 4, 5, 1, 0}, // -Y Top
            {3, 1, 5, 6, 2}, // +X Right
            {4, 4, 0, 3, 7}  // -X Left
    };

    private static final float[][][] PIP_COORDS = {
            {}, // unused 0
            {{0f, 0f}}, // 1
            {{-0.48f, -0.48f}, {0.48f, 0.48f}}, // 2
            {{-0.50f, -0.50f}, {0f, 0f}, {0.50f, 0.50f}}, // 3
            {{-0.48f, -0.48f}, {0.48f, -0.48f}, {-0.48f, 0.48f}, {0.48f, 0.48f}}, // 4
            {{-0.50f, -0.50f}, {0.50f, -0.50f}, {0f, 0f}, {-0.50f, 0.50f}, {0.50f, 0.50f}}, // 5
            {{-0.48f, -0.52f}, {0.48f, -0.52f}, {-0.48f, 0f}, {0.48f, 0f}, {-0.48f, 0.52f}, {0.48f, 0.52f}} // 6
    };

    private final Paint faceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faceBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pipHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path facePath = new Path();
    private final Path pipPath = new Path();
    private final RectF idleRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final Random random = new Random();

    private final float[][] transformed = new float[8][3];
    private final float[][] projected = new float[8][2];
    private final FaceDrawItem[] drawItems = new FaceDrawItem[6];

    private int currentValue = 1;
    private boolean rolling = false;
    private float rotX = 0f;
    private float rotY = 0f;
    private float rotZ = 0f;
    private float jumpProgress = 0f;   // 0..1 lift above tray
    private float scaleBoost = 1f;     // dynamic scale during jump & settle bounce
    private ValueAnimator rollAnimator;
    private ValueAnimator settleAnimator;

    private static class FaceDrawItem {
        int faceIdx;
        float avgZ;
        float light;
        boolean visible;
    }

    public Dice3DView(Context context) {
        super(context);
        init();
    }

    public Dice3DView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Dice3DView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        faceFillPaint.setStyle(Paint.Style.FILL);
        faceBorderPaint.setStyle(Paint.Style.STROKE);
        faceBorderPaint.setStrokeJoin(Paint.Join.ROUND);
        faceBorderPaint.setStrokeCap(Paint.Cap.ROUND);
        faceBorderPaint.setColor(Color.rgb(92, 98, 112));

        pipPaint.setStyle(Paint.Style.FILL);
        pipPaint.setColor(Color.rgb(24, 26, 32));

        pipHighlightPaint.setStyle(Paint.Style.FILL);
        pipHighlightPaint.setColor(Color.argb(65, 255, 255, 255));

        shadowPaint.setStyle(Paint.Style.FILL);

        idleFillPaint.setStyle(Paint.Style.FILL);
        idleRimPaint.setStyle(Paint.Style.STROKE);
        idleRimPaint.setColor(Color.rgb(120, 125, 138));

        for (int i = 0; i < 6; i++) {
            drawItems[i] = new FaceDrawItem();
        }
    }

    public int getValue() {
        return currentValue;
    }

    public boolean isRolling() {
        return rolling;
    }

    public void setValueImmediate(int value) {
        cancelAnimations();
        currentValue = Math.max(1, Math.min(6, value));
        rolling = false;
        jumpProgress = 0f;
        scaleBoost = 1f;
        float[] target = getTargetAnglesForValue(currentValue);
        rotX = target[0];
        rotY = target[1];
        rotZ = 0f;
        invalidate();
    }

    public void rollTo(final int targetValue, final RollEndListener listener) {
        cancelAnimations();
        final int clamped = Math.max(1, Math.min(6, targetValue));
        final float[] endAngles = getTargetAnglesForValue(clamped);

        // Multi-axis 3D spins (2 to 3 full revolutions) ending cleanly on target face
        final int spinsX = (random.nextBoolean() ? 1 : -1) * (2 + random.nextInt(2));
        final int spinsY = (random.nextBoolean() ? 1 : -1) * (2 + random.nextInt(2));
        final int spinsZ = (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(2));

        final float startX = endAngles[0] + (float) (spinsX * Math.PI * 2.0) + 0.55f;
        final float startY = endAngles[1] + (float) (spinsY * Math.PI * 2.0) - 0.45f;
        final float startZ = (float) (spinsZ * Math.PI * 2.0);

        rolling = true;
        currentValue = clamped;

        rollAnimator = ValueAnimator.ofFloat(0f, 1f);
        rollAnimator.setDuration(420L);
        rollAnimator.setInterpolator(new DecelerateInterpolator(1.35f));
        rollAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            rotX = startX + (endAngles[0] - startX) * t;
            rotY = startY + (endAngles[1] - startY) * t;
            rotZ = startZ * (1f - t);

            // Parabolic lift up above the dice tray during the tumble
            float arc = (float) Math.sin(Math.PI * t);
            jumpProgress = arc;
            scaleBoost = 1f + 0.30f * arc;
            invalidate();
        });
        rollAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                rolling = false;
                jumpProgress = 0f;
                rotX = endAngles[0];
                rotY = endAngles[1];
                rotZ = 0f;
                playSettleBounce();
                if (listener != null) {
                    listener.onRollFinished(clamped);
                }
            }
        });
        rollAnimator.start();
    }

    private void playSettleBounce() {
        settleAnimator = ValueAnimator.ofFloat(0f, 1f);
        settleAnimator.setDuration(140L);
        settleAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Quick crisp landing bounce: 1.10 -> 0.96 -> 1.00
            if (t < 0.45f) {
                float p = t / 0.45f;
                scaleBoost = 1.10f - 0.14f * p;
            } else {
                float p = (t - 0.45f) / 0.55f;
                scaleBoost = 0.96f + 0.04f * p;
            }
            invalidate();
        });
        settleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                scaleBoost = 1f;
                invalidate();
            }
        });
        settleAnimator.start();
    }

    public void cancelAnimations() {
        if (rollAnimator != null) {
            rollAnimator.cancel();
            rollAnimator = null;
        }
        if (settleAnimator != null) {
            settleAnimator.cancel();
            settleAnimator = null;
        }
        rolling = false;
        jumpProgress = 0f;
        scaleBoost = 1f;
    }

    private float[] getTargetAnglesForValue(int value) {
        float halfPi = (float) (Math.PI / 2.0);
        float pi = (float) Math.PI;
        switch (value) {
            case 1: return new float[]{0f, 0f};
            case 2: return new float[]{halfPi, 0f};
            case 3: return new float[]{0f, -halfPi};
            case 4: return new float[]{0f, halfPi};
            case 5: return new float[]{-halfPi, 0f};
            case 6: return new float[]{0f, pi};
            default: return new float[]{0f, 0f};
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Tray anchor sits centered vertically in the lower 62dp dice pad; upper space allows 3D jump
        float density = getResources().getDisplayMetrics().density;
        float traySize = Math.min(w * 0.64f, 48f * density);
        float cx = w * 0.5f;
        float baseCy = h - 31f * density;

        if (!rolling) {
            drawSettledDie(canvas, cx, baseCy, traySize * scaleBoost);
        } else {
            drawRolling3DCube(canvas, cx, baseCy, traySize);
        }
    }

    private void drawSettledDie(Canvas canvas, float cx, float cy, float size) {
        float half = size * 0.5f;
        float radius = size * 0.22f;

        // Subtle tray drop shadow
        shadowRect.set(cx - half * 0.88f, cy + half * 0.78f, cx + half * 0.88f, cy + half * 1.06f);
        shadowPaint.setColor(Color.argb(60, 0, 0, 0));
        canvas.drawOval(shadowRect, shadowPaint);

        idleRect.set(cx - half, cy - half, cx + half, cy + half);

        // Soft glossy ivory-to-silver gradient matching classic Ludo King flat die face
        idleFillPaint.setShader(new LinearGradient(
                idleRect.left, idleRect.top,
                idleRect.right, idleRect.bottom,
                new int[]{Color.WHITE, Color.rgb(245, 246, 250), Color.rgb(212, 217, 226)},
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(idleRect, radius, radius, idleFillPaint);
        idleFillPaint.setShader(null);

        // Crisp bevel border
        idleRimPaint.setStrokeWidth(Math.max(1.5f, size * 0.045f));
        canvas.drawRoundRect(idleRect, radius, radius, idleRimPaint);

        // Inner top highlight sheen
        float inset = size * 0.06f;
        RectF sheen = new RectF(idleRect.left + inset, idleRect.top + inset,
                idleRect.right - inset, idleRect.top + size * 0.34f);
        pipHighlightPaint.setColor(Color.argb(120, 255, 255, 255));
        canvas.drawRoundRect(sheen, radius * 0.7f, radius * 0.7f, pipHighlightPaint);

        // Crisp pips for currentValue
        float pipRadius = size * 0.098f;
        float spread = half * 0.94f;
        float[][] pips = PIP_COORDS[currentValue];
        pipPaint.setColor(currentValue == 1 ? Color.rgb(28, 30, 36) : Color.rgb(24, 26, 32));
        for (float[] p : pips) {
            float px = cx + p[0] * spread;
            float py = cy + p[1] * spread;
            canvas.drawCircle(px, py, pipRadius, pipPaint);
            // Tiny gloss dot inside pip
            pipHighlightPaint.setColor(Color.argb(55, 255, 255, 255));
            canvas.drawCircle(px - pipRadius * 0.25f, py - pipRadius * 0.25f, pipRadius * 0.28f, pipHighlightPaint);
        }
    }

    private void drawRolling3DCube(Canvas canvas, float cx, float baseCy, float traySize) {
        // Max vertical jump upward above the tray
        float maxLift = Math.max(traySize * 0.72f, baseCy - traySize * 0.62f);
        float cy = baseCy - jumpProgress * maxLift;

        // Ground shadow shrinks and softens as the die leaps upward
        float shadowScale = 1f - 0.35f * jumpProgress;
        float shadowHalfW = traySize * 0.44f * shadowScale;
        float shadowHalfH = traySize * 0.14f * shadowScale;
        int shadowAlpha = (int) (75 * (1f - 0.55f * jumpProgress));
        shadowRect.set(cx - shadowHalfW, baseCy + traySize * 0.36f - shadowHalfH,
                cx + shadowHalfW, baseCy + traySize * 0.36f + shadowHalfH);
        shadowPaint.setColor(Color.argb(shadowAlpha, 0, 0, 0));
        canvas.drawOval(shadowRect, shadowPaint);

        // Precompute trig
        float cosX = (float) Math.cos(rotX), sinX = (float) Math.sin(rotX);
        float cosY = (float) Math.cos(rotY), sinY = (float) Math.sin(rotY);
        float cosZ = (float) Math.cos(rotZ), sinZ = (float) Math.sin(rotZ);

        float camDist = 4.6f;
        float cubeHalfPx = traySize * 0.44f * scaleBoost;
        float fovScale = cubeHalfPx * (camDist - 1f);

        for (int i = 0; i < 8; i++) {
            float x0 = BASE_VERTS[i][0];
            float y0 = BASE_VERTS[i][1];
            float z0 = BASE_VERTS[i][2];

            // Rz
            float x1 = x0 * cosZ - y0 * sinZ;
            float y1 = x0 * sinZ + y0 * cosZ;
            float z1 = z0;

            // Ry
            float x2 = x1 * cosY + z1 * sinY;
            float y2 = y1;
            float z2 = -x1 * sinY + z1 * cosY;

            // Rx
            float x3 = x2;
            float y3 = y2 * cosX - z2 * sinX;
            float z3 = y2 * sinX + z2 * cosX;

            transformed[i][0] = x3;
            transformed[i][1] = y3;
            transformed[i][2] = z3;

            float invZ = 1f / (camDist - z3);
            projected[i][0] = cx + x3 * fovScale * invZ;
            projected[i][1] = cy + y3 * fovScale * invZ;
        }

        // Light vector (slightly top-left-front)
        float lx = -0.32f, ly = -0.52f, lz = 0.79f;

        for (int f = 0; f < 6; f++) {
            int i0 = FACES[f][1], i1 = FACES[f][2], i2 = FACES[f][3], i3 = FACES[f][4];
            float[] p0 = transformed[i0];
            float[] p1 = transformed[i1];
            float[] p2 = transformed[i2];
            float[] p3 = transformed[i3];

            // Face normal = (p1 - p0) x (p3 - p0)
            float ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
            float vx = p3[0] - p0[0], vy = p3[1] - p0[1], vz = p3[2] - p0[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;

            // Normalize
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }

            // Note: because Android canvas Y points down, front (+Z) face has (p1-p0)=(+2,0,0), (p3-p0)=(0,+2,0) -> normal (0,0,+1)
            float avgX = (p0[0] + p1[0] + p2[0] + p3[0]) * 0.25f;
            float avgY = (p0[1] + p1[1] + p2[1] + p3[1]) * 0.25f;
            float avgZ = (p0[2] + p1[2] + p2[2] + p3[2]) * 0.25f;

            // Perspective backface test against camera at (0, 0, camDist)
            float viewDot = (-avgX) * nx + (-avgY) * ny + (camDist - avgZ) * nz;

            FaceDrawItem item = drawItems[f];
            item.faceIdx = f;
            item.avgZ = avgZ;
            item.visible = viewDot > 0.02f;
            float ndotl = Math.max(0f, nx * lx + ny * ly + nz * lz);
            item.light = 0.64f + 0.36f * ndotl;
        }

        // Painter's algorithm (back to front)
        Arrays.sort(drawItems, (a, b) -> Float.compare(a.avgZ, b.avgZ));

        faceBorderPaint.setStrokeWidth(Math.max(1.5f, traySize * 0.045f));

        for (FaceDrawItem item : drawItems) {
            if (!item.visible) continue;
            int f = item.faceIdx;
            int val = FACES[f][0];
            int i0 = FACES[f][1], i1 = FACES[f][2], i2 = FACES[f][3], i3 = FACES[f][4];

            float[] q0 = projected[i0];
            float[] q1 = projected[i1];
            float[] q2 = projected[i2];
            float[] q3 = projected[i3];

            // Slightly rounded quad path via corner chamfers for realistic molded dice edges
            buildRoundedQuadPath(facePath, q0, q1, q2, q3, 0.14f);

            int baseR = (int) Math.min(255, 250 * item.light);
            int baseG = (int) Math.min(255, 251 * item.light);
            int baseB = (int) Math.min(255, 255 * item.light);
            faceFillPaint.setColor(Color.rgb(baseR, baseG, baseB));
            canvas.drawPath(facePath, faceFillPaint);
            canvas.drawPath(facePath, faceBorderPaint);

            // Perspective-mapped pips on this 3D face
            draw3DFacePips(canvas, val, transformed[i0], transformed[i1], transformed[i2], transformed[i3],
                    cx, cy, camDist, fovScale, item.light);
        }
    }

    private void buildRoundedQuadPath(Path path, float[] p0, float[] p1, float[] p2, float[] p3, float cornerFrac) {
        path.reset();
        float[][] pts = {p0, p1, p2, p3};
        for (int i = 0; i < 4; i++) {
            float[] prev = pts[(i + 3) % 4];
            float[] curr = pts[i];
            float[] next = pts[(i + 1) % 4];

            float sx = curr[0] + (prev[0] - curr[0]) * cornerFrac;
            float sy = curr[1] + (prev[1] - curr[1]) * cornerFrac;
            float ex = curr[0] + (next[0] - curr[0]) * cornerFrac;
            float ey = curr[1] + (next[1] - curr[1]) * cornerFrac;

            if (i == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
            path.quadTo(curr[0], curr[1], ex, ey);
        }
        path.close();
    }

    private void draw3DFacePips(Canvas canvas, int val,
                                float[] p0, float[] p1, float[] p2, float[] p3,
                                float cx, float cy, float camDist, float fovScale, float light) {
        // Face center and local U/V basis in 3D
        float centerX = (p0[0] + p1[0] + p2[0] + p3[0]) * 0.25f;
        float centerY = (p0[1] + p1[1] + p2[1] + p3[1]) * 0.25f;
        float centerZ = (p0[2] + p1[2] + p2[2] + p3[2]) * 0.25f;

        float ux = (p1[0] - p0[0]) * 0.5f;
        float uy = (p1[1] - p0[1]) * 0.5f;
        float uz = (p1[2] - p0[2]) * 0.5f;

        float vx = (p3[0] - p0[0]) * 0.5f;
        float vy = (p3[1] - p0[1]) * 0.5f;
        float vz = (p3[2] - p0[2]) * 0.5f;

        int pipShade = (int) (18 + 26 * (1f - light));
        pipPaint.setColor(Color.rgb(pipShade, pipShade + 2, pipShade + 6));

        float pipR = 0.175f;
        float[][] pips = PIP_COORDS[val];
        final int segs = 10;

        for (float[] pip : pips) {
            float pu = pip[0];
            float pv = pip[1];
            pipPath.reset();
            for (int s = 0; s < segs; s++) {
                double ang = (2.0 * Math.PI * s) / segs;
                float du = pu + pipR * (float) Math.cos(ang);
                float dv = pv + pipR * (float) Math.sin(ang);

                float wx = centerX + ux * du + vx * dv;
                float wy = centerY + uy * du + vy * dv;
                float wz = centerZ + uz * du + vz * dv;

                float invZ = 1f / (camDist - wz);
                float sx = cx + wx * fovScale * invZ;
                float sy = cy + wy * fovScale * invZ;
                if (s == 0) {
                    pipPath.moveTo(sx, sy);
                } else {
                    pipPath.lineTo(sx, sy);
                }
            }
            pipPath.close();
            canvas.drawPath(pipPath, pipPaint);
        }
    }
}
