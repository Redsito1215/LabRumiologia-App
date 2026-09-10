from __future__ import annotations

from functools import lru_cache
import os
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


def setting(env_key: str, property_key: str, default: str = "") -> str:
    """Use deployment secrets first and local.properties only for local development."""
    return os.getenv(env_key, "").strip() or prop(property_key, default)


def _path_from_property(key: str, default: Path) -> Path:
    env_key = key.upper().replace(".", "_")
    raw = setting(env_key, key)
    if not raw:
        return default.resolve()
    path = Path(raw)
    if not path.is_absolute():
        path = BACKEND_ROOT / path
    return path.resolve()


class Settings:
    openai_api_key: str = setting("OPENAI_API_KEY", "openai.api.key")
    openai_model: str = setting("OPENAI_MODEL", "openai.model", "gpt-4o-mini")
    openai_web_model: str = setting("OPENAI_WEB_MODEL", "openai.web.model", "gpt-5-mini")
    gemini_api_key: str = setting("GEMINI_API_KEY", "gemini.api.key")
    host: str = setting("BACKEND_HOST", "backend.host", "0.0.0.0")
    port: int = int(setting("BACKEND_PORT", "backend.port", "8000"))
    docs_dir: Path = _path_from_property("backend.docs.dir", BACKEND_ROOT / "data" / "docs")
    knowledge_path: Path = _path_from_property(
        "backend.knowledge.path", BACKEND_ROOT / "data" / "equipment_knowledge.json"
    )
    llm_model: str = ""
    top_k: int = max(1, min(10, int(setting("OPENAI_FILE_SEARCH_TOP_K", "openai.file.search.top.k", "4"))))
    max_output_tokens: int = max(100, min(1000, int(setting("OPENAI_MAX_OUTPUT_TOKENS", "openai.max.output.tokens", "300"))))
    llm_provider: str = setting("LLM_PROVIDER", "llm.provider", "openai").strip().lower()
    app_access_token: str = setting("APP_ACCESS_TOKEN", "assistant.app.token")
    admin_access_token: str = setting("ADMIN_ACCESS_TOKEN", "backend.admin.token")
    requests_per_minute: int = max(1, min(120, int(setting("REQUESTS_PER_MINUTE", "requests.per.minute", "20"))))
    cors_allowed_origins: tuple[str, ...] = tuple(
        origin.strip()
        for origin in setting("CORS_ALLOWED_ORIGINS", "cors.allowed.origins").split(",")
        if origin.strip()
    )

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
