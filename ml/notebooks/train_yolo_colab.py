# Entrenamiento YOLO26 — Laboratorio de Rumiología (Google Colab)
#
# 1. Runtime → Change runtime type → GPU
# 2. Suba ml/exports/rumiologia_yolo_dataset.zip (o móntelo desde Drive)
# 3. Ejecute las celdas en orden
# 4. Descargue best.pt + model.tflite + labels.txt
# 5. En el PC: python ml/scripts/integrate_colab_artifacts.py --dir <carpeta>

from pathlib import Path
import shutil
import zipfile

from ultralytics import YOLO
import yaml

DATA_ZIP = Path("/content/rumiologia_yolo_dataset.zip")  # ajuste si usa Drive
WORK = Path("/content/rumiologia")
MODELS = WORK / "models"
RUNS = WORK / "runs"
MODELS.mkdir(parents=True, exist_ok=True)
RUNS.mkdir(parents=True, exist_ok=True)

if DATA_ZIP.exists():
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(DATA_ZIP, "r") as zf:
        zf.extractall(WORK)
else:
    print("Suba rumiologia_yolo_dataset.zip a", DATA_ZIP)

data_yaml = WORK / "data.yaml"
cfg = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
cfg["path"] = str(WORK)
runtime = WORK / "_runtime_data.yaml"
runtime.write_text(yaml.safe_dump(cfg, sort_keys=False), encoding="utf-8")

# Misma familia que ml/scripts/train_yolo.py (fallback a nano si medium no carga)
for name in ("yolo26m.pt", "yolo26s.pt", "yolo26n.pt", "yolov8n.pt"):
    try:
        model = YOLO(name)
        print("Base:", name)
        break
    except Exception as exc:
        print(name, "->", exc)
else:
    raise SystemExit("No se pudo cargar checkpoint YOLO")

results = model.train(
    data=str(runtime),
    epochs=120,
    imgsz=640,
    batch=-1,
    project=str(RUNS),
    name="rumiologia",
    exist_ok=True,
    patience=40,
    close_mosaic=15,
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
(MODELS / "best.pt").write_bytes(best.read_bytes())
print("best.pt ->", MODELS / "best.pt")

metrics = model.val(data=str(runtime), split="test")
print(metrics)

# Export TFLite (preferido en Colab/Linux)
try:
    exported = Path(model.export(format="tflite", imgsz=640, half=True, nms=False, end2end=False))
except Exception as e:
    print("half TFLite falló, intentando float32:", e)
    exported = Path(model.export(format="tflite", imgsz=640, nms=False, end2end=False))

tflite_out = MODELS / "model.tflite"
shutil.copy2(exported, tflite_out)
print("model.tflite ->", tflite_out)

names = cfg.get("names", {})
if isinstance(names, dict):
    ordered = [names[i] for i in sorted(names, key=lambda k: int(k))]
else:
    ordered = list(names)
(MODELS / "labels.txt").write_text("\n".join(ordered) + "\n", encoding="utf-8")
print("labels.txt listo. Descargue la carpeta:", MODELS)
