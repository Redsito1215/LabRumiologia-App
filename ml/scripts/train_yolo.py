"""
Entrena un detector YOLO (Ultralytics) con el dataset etiquetado en Label Studio.

El enunciado pide YOLOv15. Ultralytics no publica yolov15.pt; se usa YOLO26 (familia
YOLO actual, enero 2026) con la misma API y formato de anotaciones YOLO.

  python ml/scripts/train_yolo.py --data ml/dataset/data.yaml
  python ml/scripts/train_yolo.py --model yolo26m.pt --epochs 200 --imgsz 640
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path


FALLBACK_MODELS = ("yolo26m.pt", "yolo26s.pt", "yolo26n.pt")


def validate_dataset(cfg: dict, root: Path, allow_missing: bool) -> None:
    names = cfg.get("names") or {}
    class_ids = list(range(len(names))) if isinstance(names, list) else [int(k) for k in names]
    counts: Counter[int] = Counter()
    for split in ("train", "val", "test"):
        image_rel = str(cfg.get(split) or "")
        if not image_rel:
            continue
        label_dir = root / image_rel.replace("/images/", "/labels/").replace("\\images\\", "\\labels\\")
        for label in label_dir.glob("*.txt"):
            for raw in label.read_text(encoding="utf-8").splitlines():
                fields = raw.split()
                if fields:
                    counts[int(fields[0])] += 1
    missing = [cid for cid in class_ids if counts[cid] == 0]
    print("Cajas por clase:", {cid: counts[cid] for cid in class_ids})
    if missing and not allow_missing:
        raise SystemExit(
            f"Dataset incompleto: las clases {missing} no tienen cajas. "
            "Etiquételas antes de gastar horas entrenando o use "
            "--allow-missing-classes conscientemente."
        )


def load_model(requested: str):
    from ultralytics import YOLO

    candidates = [requested, *[m for m in FALLBACK_MODELS if m != requested]]
    last_error: Exception | None = None
    for name in candidates:
        try:
            print(f"Cargando modelo base: {name}")
            return YOLO(name)
        except Exception as exc:  # noqa: BLE001 — probar siguiente checkpoint
            last_error = exc
            print(f"No se pudo cargar {name}: {exc}")
    raise SystemExit(f"No se pudo cargar ningún checkpoint YOLO. Último error: {last_error}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=Path("ml/dataset/data.yaml"))
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument(
        "--batch",
        type=float,
        default=-1,
        help="-1 ajusta automáticamente el lote a la memoria disponible",
    )
    parser.add_argument(
        "--model",
        type=str,
        default="yolo26m.pt",
        help="Checkpoint Ultralytics; este proyecto usa YOLO26 medium",
    )
    parser.add_argument("--project", type=Path, default=Path("ml/runs"))
    parser.add_argument("--name", type=str, default="rumiologia")
    parser.add_argument("--allow-missing-classes", action="store_true")
    args = parser.parse_args()

    import yaml

    data_yaml = args.data.resolve()
    cfg = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
    validate_dataset(cfg, data_yaml.parent, args.allow_missing_classes)
    cfg["path"] = str(data_yaml.parent)
    runtime_yaml = data_yaml.parent / "_runtime_data.yaml"
    runtime_yaml.write_text(yaml.safe_dump(cfg, sort_keys=False), encoding="utf-8")

    model = load_model(args.model)
    results = model.train(
        data=str(runtime_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        project=str(args.project.resolve()),
        name=args.name,
        exist_ok=True,
        patience=45,
        close_mosaic=20,
        cos_lr=True,
        optimizer="AdamW",
        lr0=0.001,
        weight_decay=0.0005,
        hsv_h=0.015,
        hsv_s=0.5,
        hsv_v=0.3,
        degrees=3.0,
        translate=0.10,
        scale=0.30,
        fliplr=0.5,
        mosaic=0.5,
        workers=2,
        plots=True,
    )

    best = Path(results.save_dir) / "weights" / "best.pt"
    models_dir = Path("ml/models")
    models_dir.mkdir(parents=True, exist_ok=True)
    target = models_dir / "best.pt"
    target.write_bytes(best.read_bytes())
    print(f"Modelo guardado en {target}")

    metrics = model.val(data=str(runtime_yaml), split="test")
    print(metrics)


if __name__ == "__main__":
    main()
