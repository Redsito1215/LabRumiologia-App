from __future__ import annotations

from collections import defaultdict, deque
import logging
import secrets
from threading import Lock
import time
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import get_settings
from .knowledge import display_name, reload_catalog, resolve_file_ids, resolve_vector_store_ids
from .rag import get_rag

logger = logging.getLogger(__name__)
settings = get_settings()
app = FastAPI(title="Lab Rumiología RAG/MCP", version="1.2.0")
if settings.cors_allowed_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_allowed_origins),
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type", "X-App-Token", "X-Equipment-Id"],
    )

_request_times: dict[str, deque[float]] = defaultdict(deque)
_rate_lock = Lock()


def _authorize(request: Request, token: str | None) -> None:
    expected = get_settings().app_access_token.strip()
    if not expected:
        raise HTTPException(status_code=503, detail="Servicio no configurado")
    if not token or not secrets.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="No autorizado")

    forwarded = request.headers.get("x-forwarded-for", "").split(",", 1)[0].strip()
    client = forwarded or (request.client.host if request.client else "unknown")
    now = time.monotonic()
    limit = get_settings().requests_per_minute
    with _rate_lock:
        times = _request_times[client]
        while times and times[0] <= now - 60:
            times.popleft()
        if len(times) >= limit:
            raise HTTPException(
                status_code=429,
                detail="Demasiadas solicitudes. Intente nuevamente en un minuto.",
                headers={"Retry-After": "60"},
            )
        times.append(now)


def _authorize_admin(token: str | None) -> None:
    expected = get_settings().admin_access_token.strip()
    if not expected:
        raise HTTPException(status_code=503, detail="Administración no configurada")
    if not token or not secrets.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="No autorizado")


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=500)
    equipment_class: str | None = None


class ChatSource(BaseModel):
    title: str
    page: int | None = None
    snippet: str | None = None


class ChatResponse(BaseModel):
    answer: str
    sources: list[ChatSource]


class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=500)
    equipment_class: str | None = None
    top_k: int | None = None


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "docs_indexed": get_rag().docs_indexed(),
    }


@app.get("/knowledge/{equipment_class}")
def knowledge(
    equipment_class: str,
    request: Request,
    x_app_token: str | None = Header(default=None, alias="X-App-Token"),
) -> dict[str, Any]:
    """IDs de FileSearch que se inyectarán para el equipo detectado por YOLO."""
    _authorize(request, x_app_token)
    reload_catalog()
    return {
        "equipment_class": equipment_class,
        "display_name": display_name(equipment_class),
        "vector_store_ids": resolve_vector_store_ids(equipment_class),
        "file_ids": resolve_file_ids(equipment_class),
    }


@app.post("/ingest")
def ingest(
    x_admin_token: str | None = Header(default=None, alias="X-Admin-Token"),
) -> dict[str, Any]:
    _authorize_admin(x_admin_token)
    n = get_rag().ingest()
    return {"indexed_chunks": n}


@app.post("/chat", response_model=ChatResponse)
def chat(
    body: ChatRequest,
    request: Request,
    response: Response,
    x_equipment_id: str | None = Header(default=None, alias="X-Equipment-Id"),
    x_app_token: str | None = Header(default=None, alias="X-App-Token"),
) -> ChatResponse:
    # El encabezado evita repetir metadatos en el prompt. Nunca aceptamos IDs de
    # archivos/vector stores arbitrarios del cliente: se resuelven en el catálogo.
    _authorize(request, x_app_token)
    equipment_class = x_equipment_id or body.equipment_class
    file_ids = resolve_file_ids(equipment_class)
    response.headers["X-Knowledge-File-Count"] = str(len(file_ids))
    try:
        result = get_rag().chat(body.question, equipment_class)
    except Exception as exc:
        logger.exception("Assistant request failed")
        raise HTTPException(status_code=500, detail="No se pudo procesar la consulta") from exc
    return ChatResponse(
        answer=result["answer"],
        sources=[ChatSource(**s) for s in result["sources"]],
    )


@app.post("/tools/search_lab_docs")
def search_lab_docs(
    body: SearchRequest,
    request: Request,
    x_app_token: str | None = Header(default=None, alias="X-App-Token"),
) -> dict[str, Any]:
    """Herramienta MCP/HTTP: recupera fragmentos, nunca documentos completos."""
    _authorize(request, x_app_token)
    hits = get_rag().search_lab_docs(body.query, body.equipment_class, body.top_k)
    slim = [
        {"title": h["title"], "page": h["page"], "snippet": h["snippet"], "score": h.get("score")}
        for h in hits
    ]
    return {"results": slim}
