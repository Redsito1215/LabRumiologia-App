package com.uteq.software.labrumiologia.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.uteq.software.labrumiologia.R;
import com.uteq.software.labrumiologia.model.Detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DetectionOverlayView extends View {
    public interface OnDetectionTapListener {
        void onDetectionTapped(Detection detection, int index);
    }

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
        boxPaint.setStrokeWidth(6f);
        boxPaint.setStrokeJoin(Paint.Join.ROUND);
        boxPaint.setColor(ContextCompat.getColor(context, R.color.box_stroke));
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(10f);
        selectedPaint.setStrokeJoin(Paint.Join.ROUND);
        selectedPaint.setColor(ContextCompat.getColor(context, R.color.box_selected));
        textPaint.setColor(ContextCompat.getColor(context, R.color.white));
        textPaint.setTextSize(32f);
        textPaint.setFakeBoldText(true);
        bgPaint.setColor(ContextCompat.getColor(context, R.color.label_bg));
    }

    public void setOnDetectionTapListener(OnDetectionTapListener listener) {
        this.tapListener = listener;
    }

    public void setImageSize(int width, int height) {
        imageWidth = Math.max(1, width);
        imageHeight = Math.max(1, height);
        rebuildViewBoxes();
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
        if (viewW <= 0 || viewH <= 0 || imageWidth <= 0 || imageHeight <= 0) return;

        float scale = Math.max(viewW / imageWidth, viewH / imageHeight);
        float dx = (viewW - imageWidth * scale) * 0.5f;
        float dy = (viewH - imageHeight * scale) * 0.5f;

        for (Detection d : detections) {
            RectF src = d.box;
            if (src == null) {
                viewBoxes.add(new RectF());
                continue;
            }
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
        for (int i = 0; i < viewBoxes.size(); i++) {
            RectF box = viewBoxes.get(i);
            if (box.width() < 24f || box.height() < 24f) continue;

            Detection det = detections.get(i);
            boolean isSelected = (i == selectedIndex);

            int color;
            if (isSelected) {
                color = 0xFF2EE6A6;
            } else {
                color = 0xFF5BC3E9;
            }

            Paint stroke = isSelected ? selectedPaint : boxPaint;
            stroke.setColor(color);
            canvas.drawRoundRect(box, 16f, 16f, stroke);

            String badge = String.format(
                    Locale.getDefault(),
                    "%s %.2f",
                    det.label,
                    det.confidence
            );
            float tw = textPaint.measureText(badge);
            float pad = 16f;
            float labelH = 44f;
            float top = box.top - labelH;

            if (top < 0) top = box.top + 8f;
            float left = box.left;

            labelRect.set(left, top, left + tw + pad * 2f, top + labelH);
            bgPaint.setColor(color);
            canvas.drawRoundRect(labelRect, 8f, 8f, bgPaint);
            canvas.drawText(badge, left + pad, top + labelH * 0.7f, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && tapListener != null) {
            for (int i = 0; i < viewBoxes.size(); i++) {
                RectF box = viewBoxes.get(i);
                if (box.width() >= 24f && box.height() >= 24f && box.contains(event.getX(), event.getY())) {
                    tapListener.onDetectionTapped(detections.get(i), i);
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
