from __future__ import annotations

from typing import Any

from .config import Settings, get_settings
from .ingest import _chunk_text, _read_file
from .knowledge import (
    canonical_equipment_id,
    display_name,
    equipment_entry,
    list_doc_files,
    reload_catalog,
)


class RagService:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self._use_gemini = self.settings.gemini_configured
        self._openai_rag = None
        if self._use_gemini:
            import google.generativeai as genai

            genai.configure(api_key=self.settings.gemini_api_key)

    def docs_indexed(self) -> int:
        docs = self.settings.docs_dir
        if not docs.is_dir():
            return 0
        return sum(1 for p in docs.rglob("*") if p.suffix.lower() in {".md", ".txt"})

    def ingest(self) -> int:
        return self.docs_indexed()

    def search_lab_docs(
        self, query: str, equipment_class: str | None = None, top_k: int | None = None
    ) -> list[dict[str, Any]]:
        return self._search_markdown(
            query, canonical_equipment_id(equipment_class) or equipment_class, top_k or self.settings.top_k
        )

    def chat(self, question: str, equipment_class: str | None = None) -> dict[str, Any]:
        reload_catalog()
        equipment_class = canonical_equipment_id(equipment_class) or equipment_class
        if self.settings.active_provider == "openai":
            result = self._openai().chat(question, equipment_class)
            text = (result.get("answer") or "").lower()
            return result
        return self._docs_chat(question, equipment_class)

    def _openai(self):
        if self._openai_rag is None:
            from .openai_rag import OpenAIFileSearchRag

            self._openai_rag = OpenAIFileSearchRag()
        return self._openai_rag

    def _docs_chat(self, question: str, equipment_class: str | None) -> dict[str, Any]:
        hits = self._search_markdown(question, equipment_class, self.settings.top_k)
        if not hits:
            return {
                "answer": (
                    "No encontré esa información en las guías locales del laboratorio. "
                    "Pruebe con otra pregunta (uso, seguridad, temperatura, procedimiento) "
                    "o consulte al docente."
                ),
                "sources": [],
            }
        name = display_name(equipment_class)
        sources = [{"title": h["title"], "page": h.get("page"), "snippet": h["text"][:240]} for h in hits]
        if self._use_gemini:
            context = "\n\n".join(f"Fuente: {h['title']}\n{h['text']}" for h in hits)
            prompt = (
                "Eres el asistente del Laboratorio de Rumiología de la UTEQ.\n"
                "Responde en español, claro y breve, SOLO con el contexto. No inventes datos.\n"
                "Si el contexto no alcanza, dilo y recomienda consultar al docente.\n"
                "Cita el nombre del archivo fuente.\n"
                f"Equipo: {name} ({equipment_class or 'no especificado'})\n\n"
                f"Contexto:\n{context}\n\nPregunta:\n{question}\n"
            )
            return {"answer": self._generate(prompt), "sources": sources}
        extractos = "\n\n".join(h["text"].strip() for h in hits)
        return {
            "answer": (
                f"Según las guías de {name}:\n\n{extractos}\n\n"
                "Si necesita un dato que no aparece aquí, consulte al docente o al responsable del laboratorio."
            ),
            "sources": sources,
        }

    def _search_markdown(self, query: str, equipment_class: str | None, k: int) -> list[dict[str, Any]]:
        rels: list[str] = ["_general"]
        entry = equipment_entry(equipment_class)
        if entry:
            rels.insert(0, str(entry.get("docs_dir") or equipment_class))
        elif equipment_class:
            rels.insert(0, equipment_class)

        tokens = [t for t in (query or "").lower().replace("¿", " ").replace("?", " ").split() if len(t) > 2]
        scored: list[tuple[float, dict[str, Any]]] = []
        for rel in rels:
            for path in list_doc_files(self.settings.docs_dir, rel):
                if path.suffix.lower() not in {".md", ".txt"}:
                    continue
                for text, page in _read_file(path):
                    for chunk in _chunk_text(text, size=700, overlap=80):
                        low = chunk.lower()
                        score = sum(1.5 for t in tokens if t in low)
                        if equipment_class and equipment_class.replace("_", " ") in low:
                            score += 2.0
                        if rel != "_general":
                            score += 0.4
                        if score <= 0:
                            continue
                        scored.append(
                            (
                                score,
                                {
                                    "title": path.name,
                                    "page": page,
                                    "text": chunk,
                                    "snippet": chunk[:240],
                                    "score": score,
                                },
                            )
                        )
        scored.sort(key=lambda x: x[0], reverse=True)
        seen: set[str] = set()
        out: list[dict[str, Any]] = []
        for _, hit in scored:
            key = hit["text"][:80]
            if key in seen:
                continue
            seen.add(key)
            out.append(hit)
            if len(out) >= k:
                break
        return out

    def _generate(self, prompt: str) -> str:
        if not self._use_gemini:
            return self._offline_answer(prompt)
        import google.generativeai as genai

        for name in dict.fromkeys([self.settings.llm_model, "gemini-3.1-flash-lite", "gemini-3.1-pro"]):
            if not name:
                continue
            try:
                text = (genai.GenerativeModel(name).generate_content(prompt).text or "").strip()
                if text:
                    return text
            except Exception:
                continue
        return self._offline_answer(prompt)

    def _offline_answer(self, prompt: str) -> str:
        if "Contexto:" in prompt and "Pregunta:" in prompt:
            ctx = prompt.split("Contexto:", 1)[1].split("Pregunta:", 1)[0].strip()
            question = prompt.split("Pregunta:", 1)[1].strip()
            if ctx:
                return (
                    f"(Modo local) Según los documentos recuperados para "
                    f"«{question}»:\n\n{ctx[:900]}\n\n"
                    "Configure openai.api.key en local.properties para usar OpenAI File Search."
                )
        return (
            "No dispongo de información suficiente en los documentos del laboratorio "
            "para responder esa consulta. Recomiendo consultar al docente o al "
            "responsable del Laboratorio de Rumiología."
        )


_rag: RagService | None = None


def get_rag() -> RagService:
    global _rag
    if _rag is None:
        _rag = RagService()
    return _rag
