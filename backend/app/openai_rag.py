"""
RAG con la herramienta FileSearch de OpenAI (Responses API).

Flujo:
  1. YOLO detecta el equipo y la app envía equipment_class.
  2. El backend resuelve los vector_store_ids de las guías/manuales de ESE equipo.
  3. GPT busca solo en esos almacenes y responde como lo haría el laboratorista.
"""
from __future__ import annotations

from typing import Any

from openai import OpenAI

from .config import get_settings
from .knowledge import display_name, resolve_vector_store_ids


INSTRUCTIONS = """Eres el asistente del Laboratorio de Rumiología de la UTEQ.
Responde únicamente con la información recuperada de las guías y manuales de uso
del equipo indicado (documentos elaborados por los laboratoristas).
Si File Search no encuentra información suficiente para contestar, responde
únicamente con el texto [[SIN_INFORMACION]].
Cita el nombre del documento fuente cuando esté disponible.
No inventes procedimientos, temperaturas, rpm ni normas que no estén en los archivos.
Equipo detectado: {equipment_name} (clase YOLO: {equipment_class}).
"""

WEB_INSTRUCTIONS = """Eres el asistente del Laboratorio de Rumiología de la UTEQ.
Los documentos internos no contenían la respuesta, por lo que debes buscar en la web.
Prioriza fuentes oficiales del fabricante y organismos técnicos. Aclara brevemente
que la información proviene de la web y que los procedimientos del laboratorio
tienen prioridad. No inventes parámetros ni indiques operaciones peligrosas.
Responde en español, de forma concreta y apropiada para ser leída en voz alta.
Equipo: {equipment_name}.
"""


def _as_dict(obj: Any) -> dict[str, Any]:
    if obj is None:
        return {}
    if isinstance(obj, dict):
        return obj
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if hasattr(obj, "dict"):
        return obj.dict()
    return getattr(obj, "__dict__", {}) or {}


def _snippet_from_result(item: dict[str, Any]) -> str | None:
    content = item.get("content")
    if isinstance(content, list) and content:
        first = content[0]
        if isinstance(first, dict):
            text = first.get("text") or first.get("content")
            if text:
                return str(text)[:240]
        text = getattr(first, "text", None)
        if text:
            return str(text)[:240]
    text = item.get("text")
    return str(text)[:240] if text else None


def _extract_sources(response: Any) -> list[dict[str, Any]]:
    sources: list[dict[str, Any]] = []
    seen: set[str] = set()

    def add(title: str | None, snippet: str | None = None) -> None:
        name = (title or "documento").strip()
        if not name or name in seen:
            return
        seen.add(name)
        sources.append({"title": name, "page": None, "snippet": snippet})

    for item in getattr(response, "output", None) or []:
        data = _as_dict(item)
        item_type = data.get("type") or getattr(item, "type", None)
        if item_type == "file_search_call":
            for hit in data.get("results") or getattr(item, "results", None) or []:
                hit_d = _as_dict(hit)
                add(hit_d.get("filename") or hit_d.get("file_id"), _snippet_from_result(hit_d))
        if item_type == "message":
            for part in data.get("content") or getattr(item, "content", None) or []:
                part_d = _as_dict(part)
                for ann in part_d.get("annotations") or []:
                    ann_d = _as_dict(ann)
                    add(ann_d.get("filename") or ann_d.get("file_id"))
    return sources


def _output_text(response: Any) -> str:
    text = getattr(response, "output_text", None)
    if text:
        return str(text).strip()
    chunks: list[str] = []
    for item in getattr(response, "output", None) or []:
        data = _as_dict(item)
        if data.get("type") != "message" and getattr(item, "type", None) != "message":
            continue
        for part in data.get("content") or getattr(item, "content", None) or []:
            part_d = _as_dict(part)
            piece = part_d.get("text") or getattr(part, "text", None)
            if piece:
                chunks.append(str(piece))
    return "\n".join(chunks).strip()


def _quota_error(exc: Exception) -> bool:
    text = str(exc).lower()
    return any(code in text for code in (
        "insufficient_quota", "credit_balance_exhausted", "no credits remaining"
    ))


def _quota_reply() -> dict[str, Any]:
    return {
        "answer": (
            "OpenAI File Search está configurado, pero la cuenta no tiene créditos "
            "disponibles. Agregue saldo en la facturación de OpenAI y vuelva a intentar."
        ),
        "sources": [],
    }


class OpenAIFileSearchRag:
    def __init__(self) -> None:
        settings = get_settings()
        self.model = settings.openai_model
        self.web_model = settings.openai_web_model
        self.top_k = settings.top_k
        self.max_output_tokens = settings.max_output_tokens
        # Evita que una pérdida de red o falta de saldo deje congelada la UI móvil.
        self._client = OpenAI(
            api_key=settings.openai_api_key,
            timeout=15.0,
            max_retries=0,
        )

    def chat(self, question: str, equipment_class: str | None = None) -> dict[str, Any]:
        vector_store_ids = resolve_vector_store_ids(equipment_class)
        if not vector_store_ids:
            return {
                "answer": (
                    "No hay una base de conocimiento (vector store) asignada a este equipo. "
                    "Suba las guías/manuales con backend/scripts/upload_vector_stores.py "
                    "o consulte al responsable del Laboratorio de Rumiología."
                ),
                "sources": [],
            }

        name = display_name(equipment_class)
        kwargs = {
            "model": self.model,
            "input": question,
            "instructions": INSTRUCTIONS.format(
                equipment_name=name,
                equipment_class=equipment_class or "no especificado",
            ),
            "max_output_tokens": self.max_output_tokens,
            "tools": [
                {
                    "type": "file_search",
                    "vector_store_ids": vector_store_ids,
                    "max_num_results": self.top_k,
                }
            ],
            "include": ["file_search_call.results"],
            "tool_choice": "required",
        }
        try:
            response = self._client.responses.create(**kwargs)
        except Exception as exc:
            if _quota_error(exc):
                return _quota_reply()
            kwargs.pop("include", None)
            kwargs.pop("tool_choice", None)
            try:
                response = self._client.responses.create(**kwargs)
            except Exception as exc:
                if _quota_error(exc):
                    return _quota_reply()
                alt = "gpt-4o-mini" if self.model != "gpt-4o-mini" else "gpt-4.1-mini"
                kwargs["model"] = alt
                try:
                    response = self._client.responses.create(**kwargs)
                except Exception as exc2:
                    if _quota_error(exc2):
                        return _quota_reply()
                    return {
                        "answer": (
                            "No se pudo consultar OpenAI. "
                            f"Modelo «{self.model}» / «{alt}»: {exc2}. "
                            "Compruebe saldo, openai.api.key en local.properties y que el backend esté encendido."
                        ),
                        "sources": [],
                    }
        answer = _output_text(response)
        sources = _extract_sources(response)
        if "[[SIN_INFORMACION]]" in answer:
            return self._search_web(question, name)
        if not answer:
            answer = (
                "No dispongo de información suficiente en los documentos del laboratorio "
                "para responder esa consulta. Recomiendo consultar al docente o al "
                "responsable del Laboratorio de Rumiología."
            )
        return {"answer": answer, "sources": sources}

    def _search_web(self, question: str, equipment_name: str) -> dict[str, Any]:
        """Fallback controlado: solo se ejecuta cuando File Search no encontró respuesta."""
        try:
            response = self._client.responses.create(
                model=self.web_model,
                input=question,
                instructions=WEB_INSTRUCTIONS.format(equipment_name=equipment_name),
                tools=[{"type": "web_search"}],
                tool_choice="required",
                max_tool_calls=1,
                max_output_tokens=self.max_output_tokens,
                include=["web_search_call.action.sources"],
            )
            answer = _output_text(response)
            if answer:
                return {"answer": answer, "sources": []}
        except Exception:
            pass
        return {
            "answer": (
                "No encontré esa información en los documentos del laboratorio ni pude "
                "verificarla en la web. Consulte al docente o al responsable del laboratorio."
            ),
            "sources": [],
        }
