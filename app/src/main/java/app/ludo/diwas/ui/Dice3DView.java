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
 * Glossy molded 3D tumbling dice view modeled on classic Ludo King physics:
 * - Soft rounded molded 3D cube with gradient face shading (no harsh wireframe borders)
 * - Large bold concave-style black pips foreshortened accurately in 3D
 * - Chunky upward pop-and-tumble right above the dice tray, settling smoothly
 *   into a glossy rounded die face matching the exact rolled value (1-6).
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

    private static final int[][] FACES = {
            {1, 0, 1, 2, 3}, // +Z Front
            {6, 5, 4, 7, 6}, // -Z Back
            {2, 3, 2, 6, 7}, // +Y Bottom
            {5, 4, 5, 1, 0}, // -Y Top
            {3, 1, 5, 6, 2}, // +X Right
            {4, 4, 0, 3, 7}  // -X Left
    };

    private static final float[][][] PIP_COORDS = {
            {}, // 0
            {{0f, 0f}}, // 1
            {{-0.47f, -0.47f}, {0.47f, 0.47f}}, // 2
            {{-0.48f, -0.48f}, {0f, 0f}, {0.48f, 0.48f}}, // 3
            {{-0.46f, -0.46f}, {0.46f, -0.46f}, {-0.46f, 0.46f}, {0.46f, 0.46f}}, // 4
            {{-0.48f, -0.48f}, {0.48f, -0.48f}, {0f, 0f}, {-0.48f, 0.48f}, {0.48f, 0.48f}}, // 5
            {{-0.46f, -0.52f}, {0.46f, -0.52f}, {-0.46f, 0f}, {0.46f, 0f}, {-0.46f, 0.52f}, {0.46f, 0.52f}} // 6
    };

    private final Paint silhouettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faceSeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idleRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path facePath = new Path();
    private final Path pipPath = new Path();
    private final RectF idleRect = new RectF();
    private final RectF sheenRect = new RectF();
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
    private float jumpProgress = 0f;
    private float scaleBoost = 1f;
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

        // Soft molded outer cushion gives chunky rounded 3D plastic edges
        silhouettePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        silhouettePaint.setStrokeJoin(Paint.Join.ROUND);
        silhouettePaint.setStrokeCap(Paint.Cap.ROUND);
        silhouettePaint.setColor(Color.rgb(156, 162, 174));

        faceFillPaint.setStyle(Paint.Style.FILL);

        // Very subtle soft bevel edge instead of harsh dark outline
        faceSeamPaint.setStyle(Paint.Style.STROKE);
        faceSeamPaint.setStrokeJoin(Paint.Join.ROUND);
        faceSeamPaint.setStrokeCap(Paint.Cap.ROUND);
        faceSeamPaint.setColor(Color.argb(65, 110, 118, 132));

        pipPaint.setStyle(Paint.Style.FILL);
        pipPaint.setColor(Color.rgb(20, 22, 26));

        sheenPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setStyle(Paint.Style.FILL);

        idleFillPaint.setStyle(Paint.Style.FILL);
        idleRimPaint.setStyle(Paint.Style.STROKE);
        idleRimPaint.setColor(Color.rgb(138, 144, 156));

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

        final int spinsX = (random.nextBoolean() ? 1 : -1) * (2 + random.nextInt(2));
        final int spinsY = (random.nextBoolean() ? 1 : -1) * (2 + random.nextInt(2));
        final int spinsZ = (random.nextBoolean() ? 1 : -1) * 1;

        final float startX = endAngles[0] + (float) (spinsX * Math.PI * 2.0) + 0.60f;
        final float startY = endAngles[1] + (float) (spinsY * Math.PI * 2.0) - 0.50f;
        final float startZ = (float) (spinsZ * Math.PI * 2.0);

        rolling = true;
        currentValue = clamped;

        rollAnimator = ValueAnimator.ofFloat(0f, 1f);
        rollAnimator.setDuration(380L);
        rollAnimator.setInterpolator(new DecelerateInterpolator(1.30f));
        rollAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            rotX = startX + (endAngles[0] - startX) * t;
            rotY = startY + (endAngles[1] - startY) * t;
            rotZ = startZ * (1f - t);

            float arc = (float) Math.sin(Math.PI * t);
            jumpProgress = arc;
            // Big chunky pop matching Ludo King 3D dice jump
            scaleBoost = 1.06f + 0.38f * arc;
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
        settleAnimator.setDuration(130L);
        settleAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            if (t < 0.45f) {
                float p = t / 0.45f;
                scaleBoost = 1.12f - 0.16f * p;
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

        float density = getResources().getDisplayMetrics().density;
        float traySize = Math.min(w * 0.66f, 50f * density);
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
        float radius = size * 0.27f;

        // Soft drop shadow inside the dice tray
        shadowRect.set(cx - half * 0.90f, cy + half * 0.72f, cx + half * 0.90f, cy + half * 1.06f);
        shadowPaint.setColor(Color.argb(65, 25, 25, 35));
        canvas.drawOval(shadowRect, shadowPaint);

        idleRect.set(cx - half, cy - half, cx + half, cy + half);

        // Pillowy glossy 3D shaded white die body matching classic Ludo King settled die
        idleFillPaint.setShader(new LinearGradient(
                idleRect.left, idleRect.top,
                idleRect.right * 0.25f + idleRect.left * 0.75f, idleRect.bottom,
                new int[]{
                        Color.rgb(255, 255, 255),
                        Color.rgb(246, 247, 250),
                        Color.rgb(220, 224, 232),
                        Color.rgb(186, 192, 204)
                },
                new float[]{0f, 0.42f, 0.78f, 1f},
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(idleRect, radius, radius, idleFillPaint);
        idleFillPaint.setShader(null);

        // Inner bright top gloss cap
        float inset = size * 0.08f;
        sheenRect.set(idleRect.left + inset, idleRect.top + inset * 0.8f,
                idleRect.right - inset, idleRect.top + size * 0.40f);
        sheenPaint.setShader(new LinearGradient(
                sheenRect.left, sheenRect.top,
                sheenRect.left, sheenRect.bottom,
                Color.argb(175, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(sheenRect, radius * 0.75f, radius * 0.75f, sheenPaint);
        sheenPaint.setShader(null);

        // Soft bevel outer border
        idleRimPaint.setStrokeWidth(Math.max(1.2f, size * 0.032f));
        canvas.drawRoundRect(idleRect, radius, radius, idleRimPaint);

        // Bold large black pips matching the reference video
        float pipRadius = size * 0.122f;
        float spread = half * 0.90f;
        float[][] pips = PIP_COORDS[currentValue];
        pipPaint.setColor(Color.rgb(16, 18, 22));
        for (float[] p : pips) {
            float px = cx + p[0] * spread;
            float py = cy + p[1] * spread;
            // Subtle dimple ring beneath pip
            sheenPaint.setColor(Color.argb(45, 255, 255, 255));
            canvas.drawCircle(px, py + pipRadius * 0.16f, pipRadius * 1.04f, sheenPaint);
            // Main bold pip
            canvas.drawCircle(px, py, pipRadius, pipPaint);
        }
    }

    private void drawRolling3DCube(Canvas canvas, float cx, float baseCy, float traySize) {
        // Chunky jump right over the dice tray (stays prominent like reference video)
        float maxLift = traySize * 0.56f;
        float cy = baseCy - jumpProgress * maxLift;

        // Soft ground shadow in tray
        float shadowScale = 1.0f - 0.30f * jumpProgress;
        float shadowHalfW = traySize * 0.46f * shadowScale;
        float shadowHalfH = traySize * 0.14f * shadowScale;
        int shadowAlpha = (int) (80 * (1f - 0.48f * jumpProgress));
        shadowRect.set(cx - shadowHalfW, baseCy + traySize * 0.36f - shadowHalfH,
                cx + shadowHalfW, baseCy + traySize * 0.36f + shadowHalfH);
        shadowPaint.setColor(Color.argb(shadowAlpha, 15, 15, 25));
        canvas.drawOval(shadowRect, shadowPaint);

        float cosX = (float) Math.cos(rotX), sinX = (float) Math.sin(rotX);
        float cosY = (float) Math.cos(rotY), sinY = (float) Math.sin(rotY);
        float cosZ = (float) Math.cos(rotZ), sinZ = (float) Math.sin(rotZ);

        float camDist = 4.4f;
        float cubeHalfPx = traySize * 0.45f * scaleBoost;
        float fovScale = cubeHalfPx * (camDist - 1f);

        for (int i = 0; i < 8; i++) {
            float x0 = BASE_VERTS[i][0];
            float y0 = BASE_VERTS[i][1];
            float z0 = BASE_VERTS[i][2];

            float x1 = x0 * cosZ - y0 * sinZ;
            float y1 = x0 * sinZ + y0 * cosZ;
            float z1 = z0;

            float x2 = x1 * cosY + z1 * sinY;
            float y2 = y1;
            float z2 = -x1 * sinY + z1 * cosY;

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

        float lx = -0.28f, ly = -0.58f, lz = 0.77f;

        for (int f = 0; f < 6; f++) {
            int i0 = FACES[f][1], i1 = FACES[f][2], i2 = FACES[f][3], i3 = FACES[f][4];
            float[] p0 = transformed[i0];
            float[] p1 = transformed[i1];
            float[] p2 = transformed[i2];
            float[] p3 = transformed[i3];

            float ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
            float vx = p3[0] - p0[0], vy = p3[1] - p0[1], vz = p3[2] - p0[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;

            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }

            float avgX = (p0[0] + p1[0] + p2[0] + p3[0]) * 0.25f;
            float avgY = (p0[1] + p1[1] + p2[1] + p3[1]) * 0.25f;
            float avgZ = (p0[2] + p1[2] + p2[2] + p3[2]) * 0.25f;

            float viewDot = (-avgX) * nx + (-avgY) * ny + (camDist - avgZ) * nz;

            FaceDrawItem item = drawItems[f];
            item.faceIdx = f;
            item.avgZ = avgZ;
            item.visible = viewDot > 0.01f;
            float ndotl = Math.max(0f, nx * lx + ny * ly + nz * lz);
            item.light = 0.66f + 0.34f * ndotl;
        }

        Arrays.sort(drawItems, (a, b) -> Float.compare(a.avgZ, b.avgZ));

        // Pass 1: Soft thick rounded cushion silhouette so cube corners look puffy & molded
        silhouettePaint.setStrokeWidth(Math.max(4f, traySize * 0.14f * scaleBoost));
        for (FaceDrawItem item : drawItems) {
            if (!item.visible) continue;
            int f = item.faceIdx;
            buildRoundedQuadPath(facePath,
                    projected[FACES[f][1]], projected[FACES[f][2]],
                    projected[FACES[f][3]], projected[FACES[f][4]], 0.24f);
            int rimVal = (int) Math.min(235, 198 * item.light);
            silhouettePaint.setColor(Color.rgb(rimVal, rimVal + 2, rimVal + 6));
            canvas.drawPath(facePath, silhouettePaint);
        }

        // Pass 2: Smooth glossy gradient faces + bold perspective pips
        faceSeamPaint.setStrokeWidth(Math.max(1f, traySize * 0.025f));
        for (FaceDrawItem item : drawItems) {
            if (!item.visible) continue;
            int f = item.faceIdx;
            int val = FACES[f][0];
            int i0 = FACES[f][1], i1 = FACES[f][2], i2 = FACES[f][3], i3 = FACES[f][4];
            float[] q0 = projected[i0], q1 = projected[i1], q2 = projected[i2], q3 = projected[i3];

            buildRoundedQuadPath(facePath, q0, q1, q2, q3, 0.26f);

            int hi = (int) Math.min(255, 255 * Math.min(1f, item.light + 0.08f));
            int lo = (int) Math.min(245, 224 * item.light);
            faceFillPaint.setShader(new LinearGradient(
                    q0[0], q0[1], q2[0], q2[1],
                    Color.rgb(hi, hi, Math.min(255, hi + 2)),
                    Color.rgb(lo, lo + 2, Math.min(255, lo + 8)),
                    Shader.TileMode.CLAMP
            ));
            canvas.drawPath(facePath, faceFillPaint);
            faceFillPaint.setShader(null);
            canvas.drawPath(facePath, faceSeamPaint);

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
        float centerX = (p0[0] + p1[0] + p2[0] + p3[0]) * 0.25f;
        float centerY = (p0[1] + p1[1] + p2[1] + p3[1]) * 0.25f;
        float centerZ = (p0[2] + p1[2] + p2[2] + p3[2]) * 0.25f;

        float ux = (p1[0] - p0[0]) * 0.5f;
        float uy = (p1[1] - p0[1]) * 0.5f;
        float uz = (p1[2] - p0[2]) * 0.5f;

        float vx = (p3[0] - p0[0]) * 0.5f;
        float vy = (p3[1] - p0[1]) * 0.5f;
        float vz = (p3[2] - p0[2]) * 0.5f;

        int pipShade = (int) (14 + 24 * (1f - light));
        pipPaint.setColor(Color.rgb(pipShade, pipShade + 2, pipShade + 4));

        // Larger chunky pips matching Ludo King 3D die
        float pipR = 0.23f;
        float[][] pips = PIP_COORDS[val];
        final int segs = 12;

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
