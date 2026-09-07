"""
Construye dataset YOLO usable a partir de fotos Label Studio + inventario.

Problema: tasks.json solo tiene predicciones bootstrap (caja centrada ~86x82).
Este script:
  1) Calcula cajas más ajustadas (recorte por contenido / bordes).
  2) Asigna clase por prefijo MAQUINA_* según inventario_equipos.json.
  3) Genera pares imagen/etiqueta en ml/dataset/all.
  4) Crea composiciones multi-equipo sintéticas (2 máquinas lado a lado).
  5) Actualiza tasks.json con predicciones mejoradas para revisión en Label Studio.
  6) Parte en yolo_ls train/val/test (todas las clases).

Uso:
  python ml/scripts/build_yolo_dataset.py
  python ml/scripts/build_yolo_dataset.py --no-multi
"""
from __future__ import annotations

import argparse
import json
import random
import re
import shutil
from collections import Counter, defaultdict
from pathlib import Path

import yaml

try:
    from PIL import Image, ImageEnhance, ImageFilter, ImageOps, ImageStat
except ImportError as exc:  # pragma: no cover
    raise SystemExit("Instale Pillow: pip install pillow") from exc

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset"
LS_DIR = ROOT / "labelstudio"
LS_IMAGES = LS_DIR / "images"
TASKS_JSON = LS_DIR / "tasks.json"
INVENTARIO = DATASET / "inventario_equipos.json"
DATA_YAML = DATASET / "data.yaml"
ALL_IMAGES = DATASET / "all" / "images"
ALL_LABELS = DATASET / "all" / "labels"
YOLO_LS = DATASET / "yolo_ls"
IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def load_names(path: Path) -> list[str]:
    cfg = yaml.safe_load(path.read_text(encoding="utf-8"))
    names = cfg.get("names", [])
    if isinstance(names, dict):
        return [names[i] for i in sorted(names, key=lambda k: int(k))]
    return list(names)


def load_maquina_map(path: Path) -> dict[int, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[int, str] = {}
    for eq in data.get("equipos", []):
        folder = str(eq.get("carpeta_origen") or "")
        m = re.search(r"(\d+)", folder)
        if not m:
            continue
        out[int(m.group(1))] = str(eq["clase_yolo"])
    return out


def maquina_from_name(name: str) -> int | None:
    m = re.match(r"MAQUINA_(\d+)", name, flags=re.IGNORECASE)
    return int(m.group(1)) if m else None


def content_box_xywh(img: Image.Image) -> tuple[float, float, float, float]:
    """
    Propone una caja YOLO (cx, cy, w, h) más ajustada que el bootstrap centrado.
    Usa máscara por diferencia con el fondo estimado (bordes).
    """
    w, h = img.size
    # Trabajar a resolución moderada
    max_side = 512
    scale = min(1.0, max_side / max(w, h))
    small = img.convert("RGB")
    if scale < 1.0:
        small = small.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.Resampling.BILINEAR)
    sw, sh = small.size

    # Estimar color de fondo desde un marco de 4% del borde
    border = max(2, int(min(sw, sh) * 0.04))
    pixels = small.load()
    bg_samples: list[tuple[int, int, int]] = []
    for x in range(sw):
        for y in range(border):
            bg_samples.append(pixels[x, y])
            bg_samples.append(pixels[x, sh - 1 - y])
    for y in range(sh):
        for x in range(border):
            bg_samples.append(pixels[x, y])
            bg_samples.append(pixels[sw - 1 - x, y])
    if not bg_samples:
        return 0.5, 0.5, 0.72, 0.78
    br = sum(p[0] for p in bg_samples) / len(bg_samples)
    bg = sum(p[1] for p in bg_samples) / len(bg_samples)
    bb = sum(p[2] for p in bg_samples) / len(bg_samples)

    gray = ImageOps.grayscale(small)
    edges = gray.filter(ImageFilter.FIND_EDGES)
    edge_stat = ImageStat.Stat(edges)
    edge_thr = max(18, edge_stat.mean[0] * 1.35)

    mask = Image.new("L", (sw, sh), 0)
    mp = mask.load()
    ep = edges.load()
    for y in range(sh):
        for x in range(sw):
            r, g, b = pixels[x, y]
            dist = abs(r - br) + abs(g - bg) + abs(b - bb)
            if dist > 55 or ep[x, y] > edge_thr:
                mp[x, y] = 255

    # Suavizar y encontrar bbox
    mask = mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(3))
    bbox = mask.getbbox()
    if bbox is None:
        return 0.5, 0.5, 0.72, 0.78

    x0, y0, x1, y1 = bbox
    # Margen pequeño
    pad = int(0.03 * max(sw, sh))
    x0 = max(0, x0 - pad)
    y0 = max(0, y0 - pad)
    x1 = min(sw, x1 + pad)
    y1 = min(sh, y1 + pad)

    bw = (x1 - x0) / sw
    bh = (y1 - y0) / sh
    cx = (x0 + x1) / 2 / sw
    cy = (y0 + y1) / 2 / sh

    # Evitar cajas casi full-frame o minúsculas
    bw = min(0.92, max(0.28, bw))
    bh = min(0.94, max(0.28, bh))
    # Recentrar si el clamp cambió tamaño
    cx = min(1 - bw / 2, max(bw / 2, cx))
    cy = min(1 - bh / 2, max(bh / 2, cy))
    return cx, cy, bw, bh


def yolo_to_ls(cx: float, cy: float, w: float, h: float) -> dict:
    return {
        "x": (cx - w / 2) * 100,
        "y": (cy - h / 2) * 100,
        "width": w * 100,
        "height": h * 100,
        "rotation": 0,
    }


def write_label(path: Path, class_id: int, box: tuple[float, float, float, float]) -> None:
    cx, cy, w, h = box
    path.write_text(f"{class_id} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n", encoding="utf-8")


def clear_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def split_pairs(pairs: list[tuple[Path, Path]], seed: int = 42) -> dict[str, list[tuple[Path, Path]]]:
    by_class: dict[int, list[tuple[Path, Path]]] = defaultdict(list)
    for img, lbl in pairs:
        cid = int(lbl.read_text(encoding="utf-8").split()[0])
        by_class[cid].append((img, lbl))

    rng = random.Random(seed)
    out: dict[str, list[tuple[Path, Path]]] = {"train": [], "val": [], "test": []}
    for items in by_class.values():
        rng.shuffle(items)
        n = len(items)
        if n == 1:
            out["train"].extend(items)
            continue
        if n == 2:
            out["train"].append(items[0])
            out["val"].append(items[1])
            continue
        n_train = max(1, int(n * 0.70))
        n_val = max(1, int(n * 0.15))
        if n_train + n_val >= n:
            n_train = max(1, n - 2)
            n_val = 1
        out["train"].extend(items[:n_train])
        out["val"].extend(items[n_train : n_train + n_val])
        out["test"].extend(items[n_train + n_val :])
    return out


def make_multi_composites(
    by_class_imgs: dict[str, list[Path]],
    names: list[str],
    out_images: Path,
    out_labels: Path,
    count: int,
    seed: int,
) -> int:
    """Genera imágenes sintéticas con 2 equipos lado a lado y 2 cajas YOLO."""
    rng = random.Random(seed)
    usable = {k: v for k, v in by_class_imgs.items() if len(v) >= 1}
    if len(usable) < 2:
        return 0
    classes = list(usable.keys())
    made = 0
    for i in range(count):
        c1, c2 = rng.sample(classes, 2)
        p1 = rng.choice(usable[c1])
        p2 = rng.choice(usable[c2])
        im1 = Image.open(p1).convert("RGB")
        im2 = Image.open(p2).convert("RGB")
        target_h = 640
        def resize_h(im: Image.Image) -> Image.Image:
            ratio = target_h / im.height
            return im.resize((max(1, int(im.width * ratio)), target_h), Image.Resampling.BILINEAR)
        a = resize_h(im1)
        b = resize_h(im2)
        canvas = Image.new("RGB", (a.width + b.width, target_h), (114, 114, 114))
        canvas.paste(a, (0, 0))
        canvas.paste(b, (a.width, 0))
        # Cajas: cada mitad, con inset 6%
        tw = canvas.width
        th = canvas.height
        def half_box(x0: int, x1: int) -> tuple[float, float, float, float]:
            inset_x = 0.06 * (x1 - x0)
            inset_y = 0.06 * th
            left = x0 + inset_x
            right = x1 - inset_x
            top = inset_y
            bottom = th - inset_y
            cx = ((left + right) / 2) / tw
            cy = ((top + bottom) / 2) / th
            bw = (right - left) / tw
            bh = (bottom - top) / th
            return cx, cy, bw, bh
        box1 = half_box(0, a.width)
        box2 = half_box(a.width, tw)
        stem = f"multi_{c1}_{c2}_{i:03d}"
        out_img = out_images / f"{stem}.jpg"
        canvas.save(out_img, quality=92)
        lines = [
            f"{names.index(c1)} {box1[0]:.6f} {box1[1]:.6f} {box1[2]:.6f} {box1[3]:.6f}",
            f"{names.index(c2)} {box2[0]:.6f} {box2[1]:.6f} {box2[2]:.6f} {box2[3]:.6f}",
        ]
        (out_labels / f"{stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        made += 1
    return made


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", type=Path, default=LS_IMAGES)
    parser.add_argument("--multi", type=int, default=40, help="Composiciones multi-equipo a generar")
    parser.add_argument("--no-multi", action="store_true")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    names = load_names(DATA_YAML)
    maquina_map = load_maquina_map(INVENTARIO)
    if not args.images.is_dir():
        raise SystemExit(f"No hay imágenes en {args.images}")

    clear_dir(ALL_IMAGES)
    clear_dir(ALL_LABELS)

    tasks = []
    class_counts: Counter[str] = Counter()
    by_class_imgs: dict[str, list[Path]] = defaultdict(list)
    skipped = 0

    imgs = sorted(p for p in args.images.iterdir() if p.suffix.lower() in IMG_EXTS)
    print(f"Procesando {len(imgs)} fotos…")
    for idx, img_path in enumerate(imgs, 1):
        mid = maquina_from_name(img_path.name)
        if mid is None or mid not in maquina_map:
            print(f"  omitida (sin mapeo): {img_path.name}")
            skipped += 1
            continue
        class_name = maquina_map[mid]
        if class_name not in names:
            print(f"  omitida (clase no en data.yaml): {class_name}")
            skipped += 1
            continue
        class_id = names.index(class_name)
        with Image.open(img_path) as im:
            im = ImageOps.exif_transpose(im)
            box = content_box_xywh(im)

        dest_img = ALL_IMAGES / img_path.name
        shutil.copy2(img_path, dest_img)
        write_label(ALL_LABELS / f"{img_path.stem}.txt", class_id, box)
        class_counts[class_name] += 1
        by_class_imgs[class_name].append(dest_img)

        value = yolo_to_ls(*box)
        value["rectanglelabels"] = [class_name]
        tasks.append(
            {
                "data": {"image": f"/data/local-files/?d=images/{img_path.name}"},
                "predictions": [
                    {
                        "model_version": "content_box_v1",
                        "score": 0.5,
                        "result": [
                            {
                                "from_name": "label",
                                "to_name": "image",
                                "type": "rectanglelabels",
                                "value": value,
                            }
                        ],
                    }
                ],
            }
        )
        if idx % 40 == 0:
            print(f"  {idx}/{len(imgs)}")

    multi_n = 0
    if not args.no_multi and args.multi > 0:
        multi_n = make_multi_composites(
            by_class_imgs, names, ALL_IMAGES, ALL_LABELS, args.multi, args.seed
        )
        print(f"Composiciones multi-equipo: {multi_n}")

    TASKS_JSON.write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8")
    (LS_DIR / "classes.txt").write_text("\n".join(names) + "\n", encoding="utf-8")

    pairs = []
    for img in sorted(ALL_IMAGES.iterdir()):
        if img.suffix.lower() not in IMG_EXTS:
            continue
        lbl = ALL_LABELS / f"{img.stem}.txt"
        if lbl.exists():
            pairs.append((img, lbl))

    splits = split_pairs(pairs, args.seed)
    # Limpiar y escribir yolo_ls
    if YOLO_LS.exists():
        shutil.rmtree(YOLO_LS)
    for split_name, items in splits.items():
        img_dir = YOLO_LS / "images" / split_name
        lbl_dir = YOLO_LS / "labels" / split_name
        img_dir.mkdir(parents=True, exist_ok=True)
        lbl_dir.mkdir(parents=True, exist_ok=True)
        for img, lbl in items:
            shutil.copy2(img, img_dir / img.name)
            shutil.copy2(lbl, lbl_dir / lbl.name)
        print(f"{split_name}: {len(items)}")

    # Histograma final en train
    hist: Counter[int] = Counter()
    for lbl in (YOLO_LS / "labels" / "train").glob("*.txt"):
        for line in lbl.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if parts:
                hist[int(parts[0])] += 1
    print("Cajas train por clase:")
    for i, name in enumerate(names):
        print(f"  {i:2d} {name}: {hist[i]}")
    missing = [i for i in range(len(names)) if hist[i] == 0]
    if missing:
        print(f"AVISO: clases sin cajas en train: {missing}")
    print(f"tasks.json actualizado ({len(tasks)} tareas). Omitidas: {skipped}")
    print("Revise/ajuste cajas en Label Studio antes del entrenamiento final en Colab.")


if __name__ == "__main__":
    main()
