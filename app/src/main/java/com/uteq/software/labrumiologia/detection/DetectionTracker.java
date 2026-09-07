package com.uteq.software.labrumiologia.detection;

import android.graphics.RectF;

import com.uteq.software.labrumiologia.model.Detection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tracking corto: suaviza la caja (EMA) y mantiene el ID mientras el equipo sigue en escena.
 * No conserva detecciones "fantasma" cuando el modelo ya no ve nada.
 */
public class DetectionTracker {
    private static final float MATCH_IOU = 0.20f;
    private static final float EMA_ALPHA = 0.65f;
    /** Frames sin match antes de borrar el track. */
    private static final int MAX_MISSED = 3;
    private static final int MAX_TRACKS = 4;
    private static final float MIN_TRACK_CONF = 0.55f;

    private static final class Track {
        final int id;
        String classId;
        String label;
        float confidence;
        final RectF box = new RectF();
        int missed;
        boolean updated;

        Track(int id, Detection d) {
            this.id = id;
            this.classId = d.classId;
            this.label = d.label;
            this.confidence = d.confidence;
            this.box.set(d.box);
            this.missed = 0;
            this.updated = true;
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;

    public synchronized void reset() {
        tracks.clear();
        nextId = 1;
    }

    public synchronized List<Detection> update(List<Detection> detections) {
        for (Track t : tracks) {
            t.updated = false;
        }

        List<Detection> incoming = detections != null ? detections : new ArrayList<>();
        boolean[] used = new boolean[incoming.size()];

        for (Track track : tracks) {
            int bestIdx = -1;
            float bestIou = MATCH_IOU;
            for (int i = 0; i < incoming.size(); i++) {
                if (used[i]) continue;
                Detection d = incoming.get(i);
                if (!track.classId.equals(d.classId)) continue;
                float iou = iou(track.box, d.box);
                if (iou > bestIou) {
                    bestIou = iou;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                Detection d = incoming.get(bestIdx);
                used[bestIdx] = true;
                smooth(track.box, d.box, EMA_ALPHA);
                track.confidence = track.confidence * (1f - EMA_ALPHA) + d.confidence * EMA_ALPHA;
                track.label = d.label;
                track.missed = 0;
                track.updated = true;
            }
        }

        for (int i = 0; i < incoming.size(); i++) {
            if (used[i]) continue;
            if (tracks.size() >= MAX_TRACKS) break;
            Detection d = incoming.get(i);
            if (d.confidence < MIN_TRACK_CONF) continue;
            tracks.add(new Track(nextId++, d));
        }

        Iterator<Track> it = tracks.iterator();
        while (it.hasNext()) {
            Track t = it.next();
            if (!t.updated) {
                t.missed++;
                if (t.missed > MAX_MISSED) {
                    it.remove();
                }
            }
        }

        // Solo devolver tracks vistos en este frame (sin fantasmas).
        List<Detection> out = new ArrayList<>();
        for (Track t : tracks) {
            if (!t.updated) continue;
            if (t.confidence < MIN_TRACK_CONF) continue;
            out.add(new Detection(t.classId, t.label, t.confidence, new RectF(t.box)));
        }
        return out;
    }

    public static int indexOfClass(List<Detection> list, String classId) {
        if (classId == null || list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (classId.equals(list.get(i).classId)) return i;
        }
        return -1;
    }

    private static void smooth(RectF dst, RectF src, float alpha) {
        dst.left = dst.left * (1f - alpha) + src.left * alpha;
        dst.top = dst.top * (1f - alpha) + src.top * alpha;
        dst.right = dst.right * (1f - alpha) + src.right * alpha;
        dst.bottom = dst.bottom * (1f - alpha) + src.bottom * alpha;
    }

    private static float iou(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);
        float inter = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        float union = a.width() * a.height() + b.width() * b.height() - inter;
        return union <= 0 ? 0 : inter / union;
    }
}
