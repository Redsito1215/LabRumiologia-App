"""
Empaqueta ml/dataset/yolo_ls + data.yaml para subir a Google Colab / Drive.

  python ml/scripts/package_colab_dataset.py
  # Genera: ml/exports/rumiologia_yolo_dataset.zip
"""
from __future__ import annotations

import argparse
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset"
YOLO_LS = DATASET / "yolo_ls"
DATA_YAML = DATASET / "data.yaml"
OUT_DIR = ROOT / "exports"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=OUT_DIR / "rumiologia_yolo_dataset.zip")
    args = parser.parse_args()

    if not YOLO_LS.is_dir():
        raise SystemExit(
            f"No existe {YOLO_LS}. Ejecute antes:\n"
            "  python ml/scripts/build_yolo_dataset.py"
        )
    if not DATA_YAML.exists():
        raise SystemExit(f"Falta {DATA_YAML}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(DATA_YAML, arcname="data.yaml")
        for path in YOLO_LS.rglob("*"):
            if path.is_file():
                zf.write(path, arcname=str(Path("yolo_ls") / path.relative_to(YOLO_LS)))
    print(f"Dataset empaquetado: {args.out} ({args.out.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
