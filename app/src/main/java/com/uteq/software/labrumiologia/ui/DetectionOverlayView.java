package com.uteq.software.labrumiologia.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.uteq.software.labrumiologia.R;
import com.uteq.software.labrumiologia.model.Detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DetectionOverlayView extends View {
    public interface OnDetectionTapListener {
        void onDetectionTapped(Detection detection, int index);
    }

    private static final int[] TRACK_COLORS = {
            0xFFFF9800,
            0xFF00BCD4,
            0xFF8BC34A,
            0xFFE91E63,
            0xFF3F51B5,
            0xFFFFEB3B,
            0xFF9C27B0,
            0xFF795548
    };

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF labelRect = new RectF();

    private final List<Detection> detections = new ArrayList<>();
    private final List<RectF> viewBoxes = new ArrayList<>();
    private int selectedIndex = -1;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private OnDetectionTapListener tapListener;

    public DetectionOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3.5f);
        boxPaint.setStrokeJoin(Paint.Join.MITER);
        boxPaint.setColor(ContextCompat.getColor(context, R.color.box_stroke));
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(5f);
        selectedPaint.setStrokeJoin(Paint.Join.MITER);
        selectedPaint.setColor(ContextCompat.getColor(context, R.color.box_selected));
        textPaint.setColor(ContextCompat.getColor(context, R.color.white));
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);
        bgPaint.setColor(ContextCompat.getColor(context, R.color.label_bg));
    }

    public void setOnDetectionTapListener(OnDetectionTapListener listener) {
        this.tapListener = listener;
    }

    public void setImageSize(int width, int height) {
        imageWidth = Math.max(1, width);
        imageHeight = Math.max(1, height);
    }

    public void setDetections(List<Detection> list, int selected) {
        detections.clear();
        if (list != null) detections.addAll(list);
        selectedIndex = selected;
        rebuildViewBoxes();
        invalidate();
    }

    public void setSelectedIndex(int index) {
        selectedIndex = index;
        invalidate();
    }

    private void rebuildViewBoxes() {
        viewBoxes.clear();
        float viewW = getWidth();
        float viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float scale = Math.max(viewW / imageWidth, viewH / imageHeight);
        float dx = (viewW - imageWidth * scale) / 2f;
        float dy = (viewH - imageHeight * scale) / 2f;
        for (Detection d : detections) {
            RectF src = d.box;
            viewBoxes.add(new RectF(
                    src.left * scale + dx,
                    src.top * scale + dy,
                    src.right * scale + dx,
                    src.bottom * scale + dy
            ));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildViewBoxes();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (viewBoxes.size() != detections.size()) rebuildViewBoxes();
        for (int i = 0; i < detections.size(); i++) {
            RectF box = viewBoxes.get(i);
            int color = TRACK_COLORS[Math.floorMod(detections.get(i).classId.hashCode(), TRACK_COLORS.length)];
            Paint stroke = i == selectedIndex ? selectedPaint : boxPaint;
            int prev = stroke.getColor();
            stroke.setColor(i == selectedIndex ? ColorUtils.blendARGB(color, Color.WHITE, 0.25f) : color);
            canvas.drawRect(box, stroke);
            stroke.setColor(prev);

            String badge = String.format(
                    Locale.getDefault(),
                    "%s · %.0f%%",
                    detections.get(i).label,
                    detections.get(i).confidence * 100f
            );
            float tw = textPaint.measureText(badge);
            float top = Math.max(box.top - 36f, 8f);
            labelRect.set(box.left, top, box.left + tw + 20f, top + 32f);
            bgPaint.setColor(ColorUtils.setAlphaComponent(color, 200));
            canvas.drawRoundRect(labelRect, 10f, 10f, bgPaint);
            canvas.drawText(badge, box.left + 10f, top + 23f, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && tapListener != null) {
            for (int i = 0; i < viewBoxes.size(); i++) {
                if (viewBoxes.get(i).contains(event.getX(), event.getY())) {
                    tapListener.onDetectionTapped(detections.get(i), i);
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
