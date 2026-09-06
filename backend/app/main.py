from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Header, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from .config import get_settings
from .knowledge import display_name, reload_catalog, resolve_file_ids, resolve_vector_store_ids
from .rag import get_rag

app = FastAPI(title="Lab Rumiología RAG/MCP", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    question: str = Field(..., min_length=1)
    equipment_class: str | None = None


class ChatSource(BaseModel):
    title: str
    page: int | None = None
    snippet: str | None = None


class ChatResponse(BaseModel):
    answer: str
    sources: list[ChatSource]


class SearchRequest(BaseModel):
    query: str
    equipment_class: str | None = None
    top_k: int | None = None


@app.get("/health")
def health() -> dict[str, Any]:
    settings = get_settings()
    return {
        "status": "ok",
        "docs_indexed": get_rag().docs_indexed(),
        "provider": settings.active_provider,
        "openai_configured": settings.openai_configured,
        "gemini_configured": settings.gemini_configured,
        "openai_model": settings.openai_model if settings.openai_configured else None,
    }


@app.get("/knowledge/{equipment_class}")
def knowledge(equipment_class: str) -> dict[str, Any]:
    """IDs de FileSearch que se inyectarán para el equipo detectado por YOLO."""
    reload_catalog()
    return {
        "equipment_class": equipment_class,
        "display_name": display_name(equipment_class),
        "vector_store_ids": resolve_vector_store_ids(equipment_class),
        "file_ids": resolve_file_ids(equipment_class),
    }


@app.post("/ingest")
def ingest() -> dict[str, Any]:
    n = get_rag().ingest()
    return {"indexed_chunks": n}


@app.post("/chat", response_model=ChatResponse)
def chat(
    body: ChatRequest,
    response: Response,
    x_equipment_id: str | None = Header(default=None, alias="X-Equipment-Id"),
) -> ChatResponse:
    # El encabezado evita repetir metadatos en el prompt. Nunca aceptamos IDs de
    # archivos/vector stores arbitrarios del cliente: se resuelven en el catálogo.
    equipment_class = x_equipment_id or body.equipment_class
    file_ids = resolve_file_ids(equipment_class)
    response.headers["X-Knowledge-File-Count"] = str(len(file_ids))
    try:
        result = get_rag().chat(body.question, equipment_class)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)[:400]) from exc
    return ChatResponse(
        answer=result["answer"],
        sources=[ChatSource(**s) for s in result["sources"]],
    )


@app.post("/tools/search_lab_docs")
def search_lab_docs(body: SearchRequest) -> dict[str, Any]:
    """Herramienta MCP/HTTP: recupera fragmentos, nunca documentos completos."""
    hits = get_rag().search_lab_docs(body.query, body.equipment_class, body.top_k)
    slim = [
        {"title": h["title"], "page": h["page"], "snippet": h["snippet"], "score": h.get("score")}
        for h in hits
    ]
    return {"results": slim}
