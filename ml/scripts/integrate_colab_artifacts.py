"""
Integra artefactos descargados de Colab en el monorepo y assets Android.

Esperado (cualquiera de estas ubicaciones):
  ml/exports/colab_artifacts/best.pt
  ml/exports/colab_artifacts/model.tflite
  ml/exports/colab_artifacts/labels.txt

O pase --dir con la carpeta de descarga.

  python ml/scripts/integrate_colab_artifacts.py
  python ml/scripts/integrate_colab_artifacts.py --dir ~/Downloads/rumiologia_weights
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.parent
MODELS = ROOT / "models"
DEFAULT_DIR = ROOT / "exports" / "colab_artifacts"
ASSETS = PROJECT / "app" / "src" / "main" / "assets"
DATA_YAML = ROOT / "dataset" / "data.yaml"


def find_file(directory: Path, names: list[str]) -> Path | None:
    for name in names:
        p = directory / name
        if p.exists():
            return p
    # buscar un nivel abajo
    for name in names:
        matches = list(directory.rglob(name))
        if matches:
            return matches[0]
    return None


def labels_from_yaml(path: Path) -> list[str]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    names = data.get("names", {})
    if isinstance(names, dict):
        return [names[i] for i in sorted(names, key=lambda k: int(k))]
    return list(names)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR)
    parser.add_argument("--assets", type=Path, default=ASSETS)
    args = parser.parse_args()

    if not args.dir.is_dir():
        raise SystemExit(
            f"No existe {args.dir}.\n"
            "Descargue de Colab best.pt + model.tflite (+ labels.txt) ahí, o pase --dir."
        )

    MODELS.mkdir(parents=True, exist_ok=True)
    args.assets.mkdir(parents=True, exist_ok=True)

    best = find_file(args.dir, ["best.pt"])
    tflite = find_file(args.dir, ["model.tflite", "best_float16.tflite", "best_float32.tflite", "best.tflite"])
    labels = find_file(args.dir, ["labels.txt"])

    copied = []
    if best:
        dest = MODELS / "best.pt"
        shutil.copy2(best, dest)
        copied.append(str(dest))
    else:
        print("Aviso: no se encontró best.pt (opcional si ya tiene model.tflite).")

    if not tflite:
        raise SystemExit("Falta model.tflite en la carpeta de artefactos.")
    shutil.copy2(tflite, MODELS / "model.tflite")
    shutil.copy2(tflite, args.assets / "model.tflite")
    copied.append(str(args.assets / "model.tflite"))

    if labels:
        text = labels.read_text(encoding="utf-8")
    else:
        text = "\n".join(labels_from_yaml(DATA_YAML)) + "\n"
    (MODELS / "labels.txt").write_text(text, encoding="utf-8")
    (args.assets / "labels.txt").write_text(text, encoding="utf-8")
    copied.append(str(args.assets / "labels.txt"))

    print("Integrado:")
    for c in copied:
        print(" ", c)
    print("Recompile la app Android (Gradle syncModel / Rebuild).")


if __name__ == "__main__":
    main()
