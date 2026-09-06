"""
Exporta best.pt a TFLite (vía ONNX en Windows si LiteRT no está disponible)
y copia model.tflite + labels.txt a assets de la app.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path


def export_via_ultralytics(weights: Path, imgsz: int) -> Path:
    from ultralytics import YOLO

    model = YOLO(str(weights))
    try:
        # Configuración oficial de YOLO26 para Android: postprocesado nativo y
        # cuantización dinámica (pesos INT8, activaciones FP32).
        export_path = Path(model.export(
            format="litert",
            imgsz=imgsz,
            quantize="w8a32",
            nms=False,
            end2end=False,
        ))
        return export_path
    except Exception as e:
        print(f"Export LiteRT moderno falló ({e}). Intentando TFLite FP16…", file=sys.stderr)
        try:
            return Path(model.export(
                format="tflite", imgsz=imgsz, half=True, nms=False, end2end=False
            ))
        except Exception as legacy_error:
            print(f"Export TFLite falló ({legacy_error}). Intentando ONNX…", file=sys.stderr)
            onnx_path = Path(model.export(format="onnx", imgsz=imgsz, simplify=True, end2end=False))
            return convert_onnx_to_tflite(onnx_path, imgsz)


def convert_onnx_to_tflite(onnx_path: Path, imgsz: int) -> Path:
    """Conversión ONNX → SavedModel → TFLite (Windows-friendly)."""
    try:
        import onnx
        from onnx_tf.backend import prepare
        import tensorflow as tf
    except ImportError:
        # Alternativa: onnx2tf
        try:
            import subprocess

            out_dir = onnx_path.parent / "onnx2tf_out"
            subprocess.check_call(
                [
                    sys.executable,
                    "-m",
                    "onnx2tf",
                    "-i",
                    str(onnx_path),
                    "-o",
                    str(out_dir),
                    "-ett",
                    "float32",
                    "-coion",
                ]
            )
            float32 = out_dir / "best_float32.tflite"
            if float32.exists():
                return float32
            candidates = [p for p in out_dir.rglob("*.tflite") if "float32" in p.name.lower()]
            if not candidates:
                candidates = list(out_dir.rglob("*.tflite"))
            if not candidates:
                raise RuntimeError("onnx2tf no generó .tflite")
            return candidates[0]
        except Exception as e:
            raise SystemExit(
                "No se pudo convertir a TFLite en este entorno.\n"
                "Opciones: exportar en Linux/Colab con Ultralytics, o instale onnx2tf/tensorflow.\n"
                f"Detalle: {e}"
            ) from e

    onnx_model = onnx.load(str(onnx_path))
    tf_rep = prepare(onnx_model)
    saved = onnx_path.parent / "tf_saved"
    tf_rep.export_graph(str(saved))
    converter = tf.lite.TFLiteConverter.from_saved_model(str(saved))
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS,
    ]
    tflite_model = converter.convert()
    out = onnx_path.with_suffix(".tflite")
    out.write_bytes(tflite_model)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, default=Path("ml/models/best.pt"))
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--labels", type=Path, default=Path("ml/dataset/data.yaml"))
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    args = parser.parse_args()

    if not args.weights.exists():
        raise SystemExit(f"No existe {args.weights}. Entrene primero con train_yolo.py")

    export_path = export_via_ultralytics(args.weights, args.imgsz)

    models_dir = Path("ml/models")
    models_dir.mkdir(parents=True, exist_ok=True)
    tflite_out = models_dir / "model.tflite"
    shutil.copy2(export_path, tflite_out)

    args.assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(tflite_out, args.assets / "model.tflite")

    labels_txt = args.assets / "labels.txt"
    try:
        import yaml

        data = yaml.safe_load(args.labels.read_text(encoding="utf-8"))
        names = data.get("names", {})
        if isinstance(names, dict):
            ordered = [names[i] for i in sorted(names, key=lambda k: int(k))]
        else:
            ordered = list(names)
        labels_txt.write_text("\n".join(ordered) + "\n", encoding="utf-8")
        shutil.copy2(labels_txt, models_dir / "labels.txt")
    except Exception as e:
        print(f"Aviso: no se pudo regenerar labels.txt ({e})")

    print(f"Exportado: {tflite_out}")
    print(f"Copiado a: {args.assets / 'model.tflite'}")


if __name__ == "__main__":
    main()
