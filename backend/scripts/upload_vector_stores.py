"""
Sube las guías/manuales de cada equipo a OpenAI y crea un vector store por clase YOLO.

El ID resultante se guarda en backend/data/equipment_knowledge.local.json.
En /chat, el backend inyecta esos vector_store_ids en tools=[{type: file_search, ...}].

  cd backend
  python -m scripts.upload_vector_stores
  python -m scripts.upload_vector_stores --dry-run
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from openai import OpenAI

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.config import get_settings  # noqa: E402
from app.knowledge import list_doc_files, load_catalog, local_catalog_path  # noqa: E402


def wait_until_ready(client: OpenAI, vector_store_id: str, timeout_s: int = 180) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        listed = client.vector_stores.files.list(vector_store_id=vector_store_id)
        files = list(getattr(listed, "data", None) or [])
        if not files:
            return
        statuses = [getattr(f, "status", None) for f in files]
        if all(s in {"completed", "failed"} for s in statuses):
            failed = [getattr(f, "id", "?") for f, s in zip(files, statuses) if s == "failed"]
            if failed:
                print(f"  Aviso: archivos con status failed: {failed}")
            return
        time.sleep(2)
    print("  Aviso: indexado aún en curso (timeout). FileSearch puede tardar unos segundos más.")


def upload_folder(client: OpenAI, name: str, files: list[Path]) -> tuple[str, list[str]]:
    vs = client.vector_stores.create(name=name)
    file_ids: list[str] = []
    for path in files:
        with path.open("rb") as handle:
            uploaded = client.files.create(file=handle, purpose="assistants")
        client.vector_stores.files.create(vector_store_id=vs.id, file_id=uploaded.id)
        file_ids.append(uploaded.id)
        print(f"  + {path.name} -> {uploaded.id}")
    wait_until_ready(client, vs.id)
    return vs.id, file_ids


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    settings = get_settings()
    if not settings.openai_configured and not args.dry_run:
        raise SystemExit("Configure openai.api.key en local.properties")

    catalog = load_catalog()
    docs_dir = settings.docs_dir
    local: dict = {
        "shared_vector_store_ids": [],
        "equipments": {},
    }

    general_files = list_doc_files(docs_dir, "_general")
    print(f"Documentos generales: {len(general_files)}")
    client = None if args.dry_run else OpenAI(api_key=settings.openai_api_key)

    if general_files and not args.dry_run:
        vs_id, _ = upload_folder(client, "lab-rumiologia-general", general_files)
        local["shared_vector_store_ids"] = [vs_id]
        print(f"Vector store general: {vs_id}")
    elif general_files:
        print("  (dry-run) se crearía vector store general")

    for class_id, entry in (catalog.get("equipments") or {}).items():
        rel = entry.get("docs_dir") or class_id
        files = list_doc_files(docs_dir, rel)
        print(f"\n{class_id}: {len(files)} archivo(s)")
        if not files:
            continue
        if args.dry_run:
            for f in files:
                print(f"  (dry-run) {f.name}")
            continue
        vs_id, file_ids = upload_folder(client, f"lab-rumiologia-{class_id}", files)
        local["equipments"][class_id] = {
            "vector_store_ids": [vs_id],
            "file_ids": file_ids,
        }
        print(f"  vector_store_id = {vs_id}")

    if args.dry_run:
        return

    out = local_catalog_path()
    out.write_text(json.dumps(local, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nIDs guardados en {out}")
    print("Reinicie el backend (o POST /ingest no es necesario para FileSearch).")


if __name__ == "__main__":
    main()
