package com.uteq.software.labrumiologia.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class RecordingWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase;

    public RecordingWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFF37D8A2);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(900);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> { phase = (float) value.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    public void stop() {
        if (animator != null) animator.cancel();
        animator = null;
        phase = 0f;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float center = getHeight() / 2f;
        float gap = width / 8f;
        paint.setStrokeWidth(Math.max(5f, width / 18f));
        for (int i = 0; i < 5; i++) {
            float pulse = (float) ((Math.sin(phase + i * 0.9f) + 1f) / 2f);
            float height = getHeight() * (0.22f + 0.58f * pulse);
            float x = gap * (i + 2);
            canvas.drawLine(x, center - height / 2f, x, center + height / 2f, paint);
        }
    }

    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
