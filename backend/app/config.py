from __future__ import annotations

from functools import lru_cache
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = BACKEND_ROOT.parent
LOCAL_PROPERTIES = PROJECT_ROOT / "local.properties"


def _load_local_properties() -> dict[str, str]:
    values: dict[str, str] = {}
    if not LOCAL_PROPERTIES.exists():
        return values
    for raw in LOCAL_PROPERTIES.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"')
    return values


PROPERTIES = _load_local_properties()


def prop(key: str, default: str = "") -> str:
    return PROPERTIES.get(key, default)


def _path_from_property(key: str, default: Path) -> Path:
    raw = prop(key)
    if not raw:
        return default.resolve()
    path = Path(raw)
    if not path.is_absolute():
        path = BACKEND_ROOT / path
    return path.resolve()


class Settings:
    openai_api_key: str = prop("openai.api.key")
    openai_model: str = prop("openai.model", "gpt-4o-mini")
    gemini_api_key: str = prop("gemini.api.key")
    host: str = prop("backend.host", "0.0.0.0")
    port: int = int(prop("backend.port", "8000"))
    docs_dir: Path = _path_from_property("backend.docs.dir", BACKEND_ROOT / "data" / "docs")
    knowledge_path: Path = _path_from_property(
        "backend.knowledge.path", BACKEND_ROOT / "data" / "equipment_knowledge.json"
    )
    llm_model: str = ""
    top_k: int = int(prop("openai.file.search.top.k", "4"))
    llm_provider: str = prop("llm.provider", "openai").strip().lower()

    @property
    def openai_configured(self) -> bool:
        key = (self.openai_api_key or "").strip()
        return bool(key) and key not in {"your_openai_api_key_here"}

    @property
    def gemini_configured(self) -> bool:
        key = (self.gemini_api_key or "").strip()
        return bool(key) and key not in {"your_gemini_api_key_here"}

    @property
    def active_provider(self) -> str:
        requested = self.llm_provider
        if requested in {"offline", "local", "docs"}:
            return "offline"
        if requested in {"openai", "openai_filesearch"}:
            return "openai" if self.openai_configured else "offline"
        if requested in {"gemini", "chroma"}:
            return "gemini" if self.gemini_configured else "offline"
        # auto: no gastar OpenAI; usar documentos locales. Gemini solo si hay clave.
        if self.gemini_configured:
            return "gemini"
        return "offline"


@lru_cache
def get_settings() -> Settings:
    return Settings()
