package com.uteq.software.labrumiologia.model;

import android.graphics.RectF;

public class Detection {
    public final String label;
    public final String classId;
    public final float confidence;
    public final RectF box;

    public Detection(String classId, String label, float confidence, RectF box) {
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.box = box;
    }
}
