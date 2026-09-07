"""
Importa un export de Label Studio al dataset YOLO del proyecto.

Formatos soportados:
  - ZIP o carpeta YOLO (images/, labels/, classes.txt)
  - JSON de Label Studio (RectangleLabels) + carpeta de imágenes

Ejemplos:
  python ml/scripts/import_labelstudio.py --src export.zip
  python ml/scripts/import_labelstudio.py --src ./labelstudio-yolo --split
  python ml/scripts/import_labelstudio.py --src tasks.json --images ./fotos
"""
from __future__ import annotations

import argparse
import json
import shutil
import tempfile
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset"
DEFAULT_YAML = DATASET / "data.yaml"


def load_target_names(data_yaml: Path) -> list[str]:
    cfg = yaml.safe_load(data_yaml.read_text(encoding="utf-8"))
    names = cfg.get("names", [])
    if isinstance(names, dict):
        return [names[i] for i in sorted(names, key=lambda k: int(k))]
    return list(names)


def write_data_yaml(data_yaml: Path, names: list[str]) -> None:
    payload = {
        "path": ".",
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "nc": len(names),
        "names": {i: n for i, n in enumerate(names)},
    }
    data_yaml.write_text(yaml.safe_dump(payload, sort_keys=False, allow_unicode=True), encoding="utf-8")


def find_yolo_root(base: Path) -> Path | None:
    if (base / "classes.txt").exists() and (base / "labels").is_dir():
        return base
    for child in base.rglob("classes.txt"):
        parent = child.parent
        if (parent / "labels").is_dir():
            return parent
    return None


def read_classes(path: Path) -> list[str]:
    return [ln.strip() for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def remap_label_file(src: Path, dst: Path, src_names: list[str], dst_names: list[str]) -> int:
    index_map: dict[int, int] = {}
    for i, name in enumerate(src_names):
        if name in dst_names:
            index_map[i] = dst_names.index(name)
    lines_out = []
    for line in src.read_text(encoding="utf-8").splitlines():
        parts = line.strip().split()
        if len(parts) < 5:
            continue
        old = int(float(parts[0]))
        if old not in index_map:
            continue
        parts[0] = str(index_map[old])
        lines_out.append(" ".join(parts))
    dst.write_text("\n".join(lines_out) + ("\n" if lines_out else ""), encoding="utf-8")
    return len(lines_out)


def resolve_image_stem(label_stem: str, image_index: dict[str, Path]) -> str | None:
    """Empareja etiquetas YOLO de Label Studio (a veces con prefijo UUID) con fotos locales."""
    if label_stem in image_index:
        return label_stem
    if "-" in label_stem:
        suffix = label_stem.split("-", 1)[1]
        if suffix in image_index:
            return suffix
    for stem in image_index:
        if label_stem.endswith(stem) or stem in label_stem:
            return stem
    return None


def build_image_index(images_dir: Path) -> dict[str, Path]:
    exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    return {
        p.stem: p
        for p in images_dir.iterdir()
        if p.is_file() and p.suffix.lower() in exts
    }


def copy_yolo_export(
    yolo_root: Path,
    out_images: Path,
    out_labels: Path,
    target_names: list[str],
    images_dir: Path | None = None,
) -> int:
    src_names = read_classes(yolo_root / "classes.txt")
    unknown = [n for n in src_names if n not in target_names]
    if unknown:
        print(
            "Aviso: clases del export que no están en data.yaml: "
            + ", ".join(unknown)
            + "\nSe omitirán esas cajas. Ajuste Label Studio o data.yaml."
        )
    export_images_dir = yolo_root / "images"
    labels_dir = yolo_root / "labels"
    out_images.mkdir(parents=True, exist_ok=True)
    out_labels.mkdir(parents=True, exist_ok=True)

    external_images: dict[str, Path] = {}
    if images_dir is not None:
        if not images_dir.is_dir():
            raise SystemExit(f"No existe la carpeta de imágenes: {images_dir}")
        external_images = build_image_index(images_dir)

    count = 0
    if export_images_dir.is_dir() and any(export_images_dir.iterdir()):
        for img in sorted(export_images_dir.iterdir()):
            if img.suffix.lower() not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
                continue
            lbl = labels_dir / f"{img.stem}.txt"
            if not lbl.exists():
                print(f"Sin etiqueta, se omite: {img.name}")
                continue
            dest_lbl = out_labels / f"{img.stem}.txt"
            n = remap_label_file(lbl, dest_lbl, src_names, target_names)
            if n == 0:
                dest_lbl.unlink(missing_ok=True)
                print(f"Sin cajas válidas, se omite: {img.name}")
                continue
            shutil.copy2(img, out_images / img.name)
            count += 1
        return count

    if not external_images:
        raise SystemExit(
            "El export YOLO no incluye imágenes (común si Label Studio las sirve por URL).\n"
            "Indique la carpeta original con --images, por ejemplo:\n"
            "  python ml/scripts/import_labelstudio.py --src export.zip --images ml/labelstudio/images --split"
        )

    for lbl in sorted(labels_dir.glob("*.txt")):
        image_stem = resolve_image_stem(lbl.stem, external_images)
        if image_stem is None:
            print(f"Imagen no encontrada para etiqueta: {lbl.name}")
            continue
        img = external_images[image_stem]
        dest_lbl = out_labels / f"{img.stem}.txt"
        n = remap_label_file(lbl, dest_lbl, src_names, target_names)
        if n == 0:
            dest_lbl.unlink(missing_ok=True)
            print(f"Sin cajas válidas, se omite: {img.name}")
            continue
        shutil.copy2(img, out_images / img.name)
        count += 1
    return count


def ls_percent_to_yolo(x: float, y: float, w: float, h: float) -> tuple[float, float, float, float]:
    """Label Studio usa porcentajes 0-100; YOLO usa centro y tamaño 0-1."""
    nx, ny, nw, nh = x / 100.0, y / 100.0, w / 100.0, h / 100.0
    return nx + nw / 2.0, ny + nh / 2.0, nw, nh


def import_json(json_path: Path, images_dir: Path, out_images: Path, out_labels: Path, target_names: list[str]) -> int:
    tasks = json.loads(json_path.read_text(encoding="utf-8"))
    if isinstance(tasks, dict):
        tasks = tasks.get("tasks") or [tasks]
    out_images.mkdir(parents=True, exist_ok=True)
    out_labels.mkdir(parents=True, exist_ok=True)
    count = 0
    for task in tasks:
        data = task.get("data") or {}
        image_ref = str(data.get("image") or data.get("img") or "")
        filename = Path(image_ref.split("?")[0]).name
        if not filename:
            continue
        src_img = images_dir / filename
        if not src_img.exists():
            matches = list(images_dir.rglob(filename))
            src_img = matches[0] if matches else None
        if src_img is None or not src_img.exists():
            print(f"Imagen no encontrada: {filename}")
            continue
        lines = []
        # Preferir anotaciones humanas; si no hay, usar predicciones (bootstrap / content_box).
        sources = list(task.get("annotations") or [])
        if not any((a.get("result") or []) for a in sources):
            sources = list(task.get("predictions") or [])
        for ann in sources:
            for result in ann.get("result") or []:
                if result.get("type") not in {"rectanglelabels", "rectangle"}:
                    continue
                value = result.get("value") or {}
                labels = value.get("rectanglelabels") or value.get("labels") or []
                if not labels:
                    continue
                name = str(labels[0]).strip()
                if name not in target_names:
                    print(f"Clase desconocida '{name}' en {filename}")
                    continue
                cx, cy, w, h = ls_percent_to_yolo(
                    float(value["x"]), float(value["y"]), float(value["width"]), float(value["height"])
                )
                cid = target_names.index(name)
                lines.append(f"{cid} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
        if not lines:
            continue
        shutil.copy2(src_img, out_images / src_img.name)
        (out_labels / f"{src_img.stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        count += 1
    return count


def extract_src(src: Path) -> tuple[Path, Path | None]:
    """Devuelve (carpeta de trabajo, temp dir a limpiar)."""
    if src.is_dir():
        return src, None
    if src.suffix.lower() == ".zip":
        tmp = Path(tempfile.mkdtemp(prefix="ls_yolo_"))
        with zipfile.ZipFile(src, "r") as zf:
            zf.extractall(tmp)
        return tmp, tmp
    return src.parent, None


def main() -> None:
    parser = argparse.ArgumentParser(description="Importar etiquetas Label Studio → YOLO")
    parser.add_argument("--src", type=Path, required=True, help="ZIP YOLO, carpeta YOLO o JSON de Label Studio")
    parser.add_argument(
        "--images",
        type=Path,
        default=ROOT / "labelstudio" / "images",
        help="Carpeta de fotos (JSON o export YOLO sin imágenes embebidas)",
    )
    parser.add_argument("--data", type=Path, default=DEFAULT_YAML)
    parser.add_argument("--out", type=Path, default=DATASET / "all")
    parser.add_argument("--split", action="store_true", help="Tras importar, generar train/val/test")
    args = parser.parse_args()

    if not args.src.exists():
        raise SystemExit(f"No existe {args.src}")

    target_names = load_target_names(args.data)
    out_images = args.out / "images"
    out_labels = args.out / "labels"

    count = 0
    tmp: Path | None = None
    try:
        if args.src.suffix.lower() == ".json":
            if args.images is None:
                raise SystemExit("Para JSON de Label Studio indique --images con la carpeta de fotos.")
            count = import_json(args.src, args.images, out_images, out_labels, target_names)
        else:
            work, tmp = extract_src(args.src)
            yolo_root = find_yolo_root(work)
            if yolo_root is None:
                raise SystemExit(
                    "No se encontró un export YOLO (classes.txt + labels/). "
                    "En Label Studio: Export → YOLO."
                )
            count = copy_yolo_export(
                yolo_root, out_images, out_labels, target_names, images_dir=args.images
            )
    finally:
        if tmp is not None:
            shutil.rmtree(tmp, ignore_errors=True)

    print(f"Importadas {count} imágenes etiquetadas en {args.out}")
    if count == 0:
        raise SystemExit(1)

    if args.split:
        import runpy
        import sys

        split_script = Path(__file__).resolve().parent / "split_dataset.py"
        sys.argv = [
            "split_dataset.py",
            "--images",
            str(out_images),
            "--labels",
            str(out_labels),
            "--out",
            str(DATASET),
        ]
        runpy.run_path(str(split_script), run_name="__main__")


if __name__ == "__main__":
    main()
