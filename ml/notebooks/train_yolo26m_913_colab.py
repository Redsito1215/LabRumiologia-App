"""Entrenamiento final YOLO26m con las 913 imágenes válidas de LabRumiología.

Uso en Colab (GPU):
1. Subir /content/rumiologia_yolo_913.zip.
2. Instalar ultralytics.
3. Subir y ejecutar este archivo: %run /content/train_yolo26m_913_colab.py
4. Descargar /content/LabRumiologia_YOLO26m_913_resultados.zip.
"""

from collections import defaultdict
from pathlib import Path
import json
import random
import shutil
import zipfile

import yaml
from ultralytics import YOLO

SEED = 260913
ZIP_CANDIDATES = (
    Path("/content/rumiologia_yolo_913.zip"),
    Path("/content/drive/MyDrive/rumiologia_yolo_913.zip"),
)
SOURCE_ZIP = next((path for path in ZIP_CANDIDATES if path.exists()), ZIP_CANDIDATES[0])
ROOT = Path("/content/labrumiologia_913")
FLAT = ROOT / "source"
DATASET = ROOT / "dataset"
RUNS = ROOT / "runs"
MODELS = ROOT / "models"
OUTPUT_ZIP = Path("/content/LabRumiologia_YOLO26m_913_resultados.zip")

if not SOURCE_ZIP.exists():
    raise FileNotFoundError(f"Falta {SOURCE_ZIP}. Súbalo primero a Colab.")

if ROOT.exists():
    shutil.rmtree(ROOT)
FLAT.mkdir(parents=True)
DATASET.mkdir(parents=True)
MODELS.mkdir(parents=True)

with zipfile.ZipFile(SOURCE_ZIP) as archive:
    archive.extractall(FLAT)

source_images = FLAT / "images"
source_labels = FLAT / "labels"
class_file = FLAT / "classes.txt"
names = [line.strip() for line in class_file.read_text(encoding="utf-8").splitlines() if line.strip()]
if len(names) != 11:
    raise RuntimeError(f"Se esperaban 11 clases y se encontraron {len(names)}: {names}")

image_by_stem = {p.stem: p for p in source_images.iterdir() if p.is_file()}
samples_by_class = defaultdict(list)
background_samples = []
for label in sorted(source_labels.glob("*.txt")):
    image = image_by_stem.get(label.stem)
    if image is None:
        continue
    class_ids = {
        int(line.split()[0])
        for line in label.read_text(encoding="utf-8").splitlines()
        if line.strip()
    }
    if not class_ids:
        background_samples.append((image, label))
        continue
    # Las fotos anotadas contienen una clase principal. Si alguna contiene varias,
    # se asigna a la menos frecuente para conservarla durante el reparto.
    primary = min(class_ids, key=lambda value: len(samples_by_class[value]))
    samples_by_class[primary].append((image, label))

rng = random.Random(SEED)
manifest = {}
split_totals = defaultdict(int)
for class_id in range(len(names)):
    samples = samples_by_class[class_id]
    rng.shuffle(samples)
    count = len(samples)
    if count < 3:
        raise RuntimeError(f"La clase {names[class_id]} solo tiene {count} imágenes")
    val_count = max(1, round(count * 0.10))
    test_count = max(1, round(count * 0.10))
    train_count = count - val_count - test_count
    partitions = {
        "train": samples[:train_count],
        "val": samples[train_count:train_count + val_count],
        "test": samples[train_count + val_count:],
    }
    manifest[names[class_id]] = {key: len(value) for key, value in partitions.items()}
    for split, pairs in partitions.items():
        image_dir = DATASET / "images" / split
        label_dir = DATASET / "labels" / split
        image_dir.mkdir(parents=True, exist_ok=True)
        label_dir.mkdir(parents=True, exist_ok=True)
        for image, label in pairs:
            shutil.copy2(image, image_dir / image.name)
            shutil.copy2(label, label_dir / label.name)
            split_totals[split] += 1

# Las imágenes sin equipos se entrenan como fondos: imagen + etiqueta vacía.
# Se colocan en train para enseñar al modelo a no encerrar paredes ni mesas.
for image, label in background_samples:
    image_dir = DATASET / "images" / "train"
    label_dir = DATASET / "labels" / "train"
    shutil.copy2(image, image_dir / image.name)
    shutil.copy2(label, label_dir / label.name)
    split_totals["train"] += 1

if sum(split_totals.values()) != 913:
    raise RuntimeError(f"El reparto produjo {dict(split_totals)}, no 913 imágenes")

data_yaml = DATASET / "data.yaml"
data_yaml.write_text(
    yaml.safe_dump(
        {
            "path": str(DATASET),
            "train": "images/train",
            "val": "images/val",
            "test": "images/test",
            "names": {index: name for index, name in enumerate(names)},
        },
        sort_keys=False,
        allow_unicode=True,
    ),
    encoding="utf-8",
)
(ROOT / "split_manifest.json").write_text(
    json.dumps({"totals": dict(split_totals), "classes": manifest}, indent=2, ensure_ascii=False),
    encoding="utf-8",
)
print("Reparto:", dict(split_totals))
print(json.dumps(manifest, indent=2, ensure_ascii=False))

model = YOLO("yolo26m.pt")
results = model.train(
    data=str(data_yaml),
    epochs=140,
    imgsz=640,
    batch=-1,
    project=str(RUNS),
    name="yolo26m_913",
    exist_ok=True,
    patience=35,
    close_mosaic=15,
    cache=False,
    cos_lr=True,
    optimizer="AdamW",
    lr0=0.001,
    weight_decay=0.0005,
    hsv_h=0.015,
    hsv_s=0.45,
    hsv_v=0.30,
    degrees=4.0,
    translate=0.10,
    scale=0.35,
    perspective=0.0002,
    fliplr=0.5,
    mosaic=0.65,
    mixup=0.05,
    workers=2,
    plots=True,
    seed=SEED,
    deterministic=True,
)

best = Path(results.save_dir) / "weights" / "best.pt"
shutil.copy2(best, MODELS / "best.pt")
best_model = YOLO(str(best))
metrics = best_model.val(data=str(data_yaml), split="test", plots=True)

exported = Path(
    best_model.export(format="litert", imgsz=512, quantize="w8a32", nms=None)
)
shutil.copy2(exported, MODELS / "model.tflite")
(MODELS / "labels.txt").write_text("\n".join(names) + "\n", encoding="utf-8")

summary = {
    "images": dict(split_totals),
    "classes": names,
    "metrics": {
        "map50": float(metrics.box.map50),
        "map50_95": float(metrics.box.map),
        "precision": float(metrics.box.mp),
        "recall": float(metrics.box.mr),
    },
}
(MODELS / "training_summary.json").write_text(
    json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
)

if OUTPUT_ZIP.exists():
    OUTPUT_ZIP.unlink()
with zipfile.ZipFile(OUTPUT_ZIP, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in MODELS.rglob("*"):
        if path.is_file():
            archive.write(path, path.relative_to(ROOT))
    archive.write(ROOT / "split_manifest.json", "split_manifest.json")
    for name in ("results.png", "confusion_matrix.png", "confusion_matrix_normalized.png"):
        candidate = Path(results.save_dir) / name
        if candidate.exists():
            archive.write(candidate, f"reports/{name}")

print("ENTRENAMIENTO TERMINADO")
print("Resultados:", OUTPUT_ZIP)
print(json.dumps(summary, indent=2, ensure_ascii=False))
