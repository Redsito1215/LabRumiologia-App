# Entrenamiento YOLO — Laboratorio de Rumiología
#
# Preferir Colab: ml/notebooks/train_yolo_colab.ipynb
# Local:
#   python ml/scripts/build_yolo_dataset.py
#   python ml/scripts/train_yolo.py --data ml/dataset/data.yaml
#   python ml/scripts/export_tflite.py

from pathlib import Path

from ultralytics import YOLO

DATA = Path("../dataset/data.yaml")
PROJECT = Path("../runs")
MODELS = Path("../models")
MODELS.mkdir(parents=True, exist_ok=True)

model = None
for name in ("yolo26m.pt", "yolo26s.pt", "yolo26n.pt", "yolov8n.pt"):
    try:
        model = YOLO(name)
        print("Base:", name)
        break
    except Exception as exc:
        print(name, "->", exc)

results = model.train(
    data=str(DATA.resolve()),
    epochs=120,
    imgsz=640,
    batch=8,
    project=str(PROJECT),
    name="rumiologia",
    exist_ok=True,
    patience=40,
    cos_lr=True,
    optimizer="AdamW",
    lr0=0.001,
)

best = Path(results.save_dir) / "weights" / "best.pt"
(MODELS / "best.pt").write_bytes(best.read_bytes())

metrics = model.val(data=str(DATA.resolve()), split="test")
print(metrics)

exported = model.export(format="tflite", imgsz=640)
print("Exportado:", exported)
