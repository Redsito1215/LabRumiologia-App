package com.uteq.software.labrumiologia.detection;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import com.uteq.software.labrumiologia.data.EquipmentRepository;
import com.uteq.software.labrumiologia.model.Detection;
import com.uteq.software.labrumiologia.model.EquipmentInfo;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.flex.FlexDelegate;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Detector YOLO TFLite (Ultralytics).
 * Soporta export end-to-end [1, N, 6] (xyxy, conf, clase) y salida cruda [1, 4+nc, anchors].
 */
public class YoloDetector implements AutoCloseable {
    public static final String MODEL_FILE = "model.tflite";
    public static final String LABELS_FILE = "labels.txt";
    /** Umbral alto para evitar fantasmas cuando no hay equipo en cámara. */
    public static final float CONF_THRESHOLD = 0.70f;
    /** IoU para NMS; también suprime clases distintas muy solapadas (mismo objeto). */
    public static final float IOU_THRESHOLD = 0.45f;
    public static final float CROSS_CLASS_IOU = 0.55f;
    public static final int MAX_DETECTIONS = 4;
    /** Ajuste visual mínimo: conserva el encuadre aprendido por YOLO. */
    public static final float BOX_INSET_RATIO = 0.02f;

    private enum OutputMode { END2END_ROWS, END2END_COLS, RAW_YOLO }

    private final Interpreter interpreter;
    private final List<String> labels;
    private final EquipmentRepository catalog;
    private final int numClasses;
    private final int inputSize;
    private final boolean nchw;
    private final OutputMode outputMode;
    private final int rawAnchors;
    private final int rawChannels;
    private final ByteBuffer inputBuffer;
    private final int[] intValues;
    private final Paint letterboxPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private float scale = 1f;
    private float padX = 0f;
    private float padY = 0f;
    private int srcWidth;
    private int srcHeight;

    public YoloDetector(Context context) throws IOException {
        labels = loadLabels(context, LABELS_FILE);
        if (labels.isEmpty()) {
            throw new IOException("labels.txt vacío o no encontrado en assets");
        }
        catalog = new EquipmentRepository(context);
        numClasses = labels.size();
        interpreter = createInterpreter(context);

        int[] inShape = interpreter.getInputTensor(0).shape();
        if (inShape.length == 4 && inShape[1] == 3) {
            nchw = true;
            inputSize = inShape[2];
        } else if (inShape.length == 4) {
            nchw = false;
            inputSize = inShape[1];
        } else {
            nchw = false;
            inputSize = 512;
        }

        int[] outShape = interpreter.getOutputTensor(0).shape();
        if (outShape.length == 3) {
            int dim1 = outShape[1];
            int dim2 = outShape[2];
            if (dim2 == 6) {
                outputMode = OutputMode.END2END_ROWS;
                rawAnchors = dim1;
                rawChannels = 6;
            } else if (dim1 == 6) {
                outputMode = OutputMode.END2END_COLS;
                rawAnchors = dim2;
                rawChannels = 6;
            } else {
                outputMode = OutputMode.RAW_YOLO;
                boolean transposed = dim1 < dim2;
                rawAnchors = transposed ? dim2 : dim1;
                rawChannels = transposed ? dim1 : dim2;
            }
        } else {
            outputMode = OutputMode.RAW_YOLO;
            rawAnchors = 8400;
            rawChannels = 4 + numClasses;
        }

        intValues = new int[inputSize * inputSize];
        inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
    }

    public boolean isReady() {
        return interpreter != null && !labels.isEmpty();
    }

    public synchronized List<Detection> detect(Bitmap bitmap) {
        srcWidth = bitmap.getWidth();
        srcHeight = bitmap.getHeight();
        Bitmap letterboxed = letterbox(bitmap);
        fillInput(letterboxed);
        if (letterboxed != bitmap) letterboxed.recycle();

        int[] shape = interpreter.getOutputTensor(0).shape();
        float[][][] output = new float[1][shape[1]][shape[2]];
        interpreter.run(inputBuffer, output);

        switch (outputMode) {
            case END2END_ROWS:
                return postprocessEnd2EndRows(output[0]);
            case END2END_COLS:
                return postprocessEnd2EndCols(output[0]);
            default:
                boolean transposed = shape[1] < shape[2];
                int nc = Math.min(numClasses, Math.max(1, (transposed ? shape[1] : shape[2]) - 4));
                return postprocessRaw(output[0], transposed ? shape[2] : shape[1], nc, transposed);
        }
    }

    public int getSourceWidth() {
        return srcWidth;
    }

    public int getSourceHeight() {
        return srcHeight;
    }

    /** Formato Ultralytics export: [N, 6] → x1,y1,x2,y2,conf,class */
    private List<Detection> postprocessEnd2EndRows(float[][] rows) {
        List<Detection> raw = new ArrayList<>();
        for (float[] row : rows) {
            if (row.length < 6) continue;
            float conf = row[4];
            if (conf < CONF_THRESHOLD) continue;
            int cls = Math.round(row[5]);
            if (cls < 0 || cls >= labels.size()) continue;
            RectF box = tighten(toSource(boxFromXYXY(row[0], row[1], row[2], row[3])));
            if (!isValidBox(box)) continue;
            String id = labels.get(cls);
            raw.add(new Detection(id, nameOf(id), conf, box));
        }
        return nms(raw);
    }

    private List<Detection> postprocessEnd2EndCols(float[][] cols) {
        List<Detection> raw = new ArrayList<>();
        int n = cols[0].length;
        for (int i = 0; i < n; i++) {
            float conf = cols[4][i];
            if (conf < CONF_THRESHOLD) continue;
            int cls = Math.round(cols[5][i]);
            if (cls < 0 || cls >= labels.size()) continue;
            RectF box = tighten(toSource(boxFromXYXY(cols[0][i], cols[1][i], cols[2][i], cols[3][i])));
            if (!isValidBox(box)) continue;
            String id = labels.get(cls);
            raw.add(new Detection(id, nameOf(id), conf, box));
        }
        return nms(raw);
    }

    private List<Detection> postprocessRaw(float[][] pred, int count, int nc, boolean transposed) {
        List<Detection> raw = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!transposed && pred[i].length < 4 + nc) continue;
            float cx = transposed ? pred[0][i] : pred[i][0];
            float cy = transposed ? pred[1][i] : pred[i][1];
            float w = transposed ? pred[2][i] : pred[i][2];
            float h = transposed ? pred[3][i] : pred[i][3];
            float bestScore = 0f;
            int bestClass = -1;
            for (int c = 0; c < nc; c++) {
                float score = transposed ? pred[4 + c][i] : pred[i][4 + c];
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || bestScore < CONF_THRESHOLD) continue;
            RectF box = tighten(toSource(boxFromCenter(cx, cy, w, h)));
            if (!isValidBox(box)) continue;
            String id = labels.get(bestClass);
            raw.add(new Detection(id, nameOf(id), bestScore, box));
        }
        return nms(raw);
    }

    private boolean isValidBox(RectF box) {
        if (box.width() <= 8f || box.height() <= 8f) return false;
        float imgArea = Math.max(1, srcWidth) * (float) Math.max(1, srcHeight);
        float boxArea = box.width() * box.height();
        // Descarta cajas aplastadas (barras arriba), ruido y cajas que llenan toda la escena.
        if (boxArea < imgArea * 0.02f) return false;
        if (boxArea > imgArea * 0.92f) return false;
        float relH = box.height() / Math.max(1, srcHeight);
        float relW = box.width() / Math.max(1, srcWidth);
        if (relH < 0.10f || relW < 0.10f) return false;
        float aspect = box.width() / Math.max(1f, box.height());
        return aspect >= 0.25f && aspect <= 4.0f;
    }

    private String nameOf(String id) {
        EquipmentInfo info = catalog.get(id);
        return info != null && info.name != null ? info.name : displayName(id);
    }

    private RectF toSource(RectF box) {
        return new RectF(
                clamp((box.left - padX) / scale, 0, srcWidth),
                clamp((box.top - padY) / scale, 0, srcHeight),
                clamp((box.right - padX) / scale, 0, srcWidth),
                clamp((box.bottom - padY) / scale, 0, srcHeight)
        );
    }

    /** Recorta el exceso típico de YOLO; en cajas pequeñas (lejos) aplica menos inset. */
    private RectF tighten(RectF box) {
        float imgArea = Math.max(1, srcWidth) * (float) Math.max(1, srcHeight);
        float rel = (box.width() * box.height()) / imgArea;
        float ratio = BOX_INSET_RATIO;
        if (rel < 0.04f) ratio *= 0.45f;      // lejos: casi no encoger
        else if (rel < 0.12f) ratio *= 0.70f; // medio
        float ix = box.width() * ratio;
        float iyTop = box.height() * ratio;
        float iyBottom = box.height() * ratio;
        float left = clamp(box.left + ix, 0, srcWidth);
        float top = clamp(box.top + iyTop, 0, srcHeight);
        float right = clamp(box.right - ix, 0, srcWidth);
        float bottom = clamp(box.bottom - iyBottom, 0, srcHeight);
        if (right - left < 8f || bottom - top < 8f) return box;
        return new RectF(left, top, right, bottom);
    }

    private RectF boxFromXYXY(float x1, float y1, float x2, float y2) {
        if (x1 <= 1.5f && y1 <= 1.5f && x2 <= 1.5f && y2 <= 1.5f) {
            x1 *= inputSize;
            y1 *= inputSize;
            x2 *= inputSize;
            y2 *= inputSize;
        }
        return new RectF(
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.max(x1, x2),
                Math.max(y1, y2)
        );
    }

    private RectF boxFromCenter(float cx, float cy, float w, float h) {
        if (cx <= 1.5f && cy <= 1.5f && w <= 2f && h <= 2f) {
            cx *= inputSize;
            cy *= inputSize;
            w *= inputSize;
            h *= inputSize;
        }
        return new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    /**
     * NMS: suprime la misma clase por IoU y también clases distintas muy solapadas
     * (evita Daisy+GC+Estufa sobre el mismo equipo).
     */
    private List<Detection> nms(List<Detection> detections) {
        Collections.sort(detections, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> result = new ArrayList<>();
        boolean[] removed = new boolean[detections.size()];
        for (int i = 0; i < detections.size(); i++) {
            if (removed[i]) continue;
            Detection a = detections.get(i);
            result.add(a);
            if (result.size() >= MAX_DETECTIONS) break;
            for (int j = i + 1; j < detections.size(); j++) {
                if (removed[j]) continue;
                Detection b = detections.get(j);
                float overlap = iou(a.box, b.box);
                boolean sameClass = a.classId.equals(b.classId);
                if ((sameClass && overlap > IOU_THRESHOLD) || (!sameClass && overlap > CROSS_CLASS_IOU)) {
                    removed[j] = true;
                }
            }
        }
        return result;
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

    private Bitmap letterbox(Bitmap src) {
        scale = Math.min((float) inputSize / src.getWidth(), (float) inputSize / src.getHeight());
        int newW = Math.round(src.getWidth() * scale);
        int newH = Math.round(src.getHeight() * scale);
        padX = (inputSize - newW) / 2f;
        padY = (inputSize - newH) / 2f;
        Bitmap out = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.rgb(114, 114, 114));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        m.postTranslate(padX, padY);
        canvas.drawBitmap(src, m, letterboxPaint);
        return out;
    }

    private void fillInput(Bitmap bitmap) {
        inputBuffer.rewind();
        bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);
        if (nchw) {
            for (int c = 0; c < 3; c++) {
                int shift = 16 - 8 * c;
                for (int pixel : intValues) {
                    inputBuffer.putFloat(((pixel >> shift) & 0xFF) / 255f);
                }
            }
            return;
        }
        for (int pixel : intValues) {
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
            inputBuffer.putFloat((pixel & 0xFF) / 255f);
        }
    }

    private static String displayName(String classId) {
        String[] parts = classId.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p.substring(0, 1).toUpperCase(Locale.ROOT));
            if (p.length() > 1) sb.append(p.substring(1));
        }
        return sb.toString();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Interpreter createInterpreter(Context context) throws IOException {
        ByteBuffer model = loadModelBuffer(context, MODEL_FILE);
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        try {
            return new Interpreter(model, options);
        } catch (Exception first) {
            try {
                Interpreter.Options flex = new Interpreter.Options();
                flex.setNumThreads(4);
                flex.addDelegate(new FlexDelegate());
                return new Interpreter(model, flex);
            } catch (Exception second) {
                throw new IOException(first.getMessage(), first);
            }
        }
    }

    private static boolean assetExists(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static ByteBuffer loadModelBuffer(Context context, String file) throws IOException {
        if (!assetExists(context, file)) {
            throw new IOException("Falta " + file + " en assets. Reinstale la app desde Android Studio.");
        }
        try {
            return loadModelMapped(context, file);
        } catch (IOException e) {
            return loadModelBytes(context, file);
        }
    }

    private static MappedByteBuffer loadModelMapped(Context context, String file) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(file);
        try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = is.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private static ByteBuffer loadModelBytes(Context context, String file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        byte[] bytes = out.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.order(ByteOrder.nativeOrder());
        buffer.put(bytes);
        buffer.rewind();
        return buffer;
    }

    private static List<String> loadLabels(Context context, String file) throws IOException {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) list.add(line);
            }
        }
        return list;
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
