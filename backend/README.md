# Backend RAG — guías locales + Gemini (OpenAI opcional)

El LLM **no** identifica equipos. YOLO detecta la clase y el backend busca las
guías de **ese** equipo en `data/docs/<clase>/` (+ `_general/`).

```
YOLO → equipment_class → guías .md locales → Gemini → respuesta
```

## Requisitos

```bash
cd backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

Para OpenAI File Search, edite `../local.properties` y agregue
`openai.api.key=...` y `llm.provider=openai`.
La clave permanece exclusivamente en el backend; nunca se empaqueta en Android.

## Subir manuales a OpenAI (FileSearch)

1. Coloque PDF/MD/TXT por equipo en `data/docs/<clase_yolo>/` (p. ej. `data/docs/balanza_analitica/`).
2. Documentos comunes (seguridad, guía de prácticas) van en `data/docs/_general/`.
3. Cree un vector store por equipo:

```bash
python -m scripts.upload_vector_stores
```

Los IDs se guardan en `data/equipment_knowledge.local.json` (no se versiona).
En cada `/chat` el backend llama a la API así:

```python
tools=[{"type": "file_search", "vector_store_ids": ["vs_del_equipo", "vs_general"]}]
```

Compruebe el mapeo:

```bash
curl http://127.0.0.1:8000/knowledge/balanza_analitica
```

## Arrancar

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

## API Android

- `GET /health`
- `GET /knowledge/{equipment_class}`
- `POST /chat` body: `{ "question": "...", "equipment_class": "incubadora" }`
- `POST /tools/search_lab_docs` (fragmentos de las guías `.md` locales)

La app **no** envía documentos ni IDs de OpenAI: solo pregunta + clase detectada.

## Proveedor

Con `llm.provider=openai` se usa OpenAI File Search. Use
`llm.provider=offline` para responder únicamente con extractos locales.

## Servidor MCP

```bash
python -m app.mcp_server
```

Herramienta: `search_lab_docs(query, equipment_class, top_k)`.

## Emulador Android

`BuildConfig.RAG_BASE_URL` se lee de `local.properties` (`rag.base.url=`).
En emulador use `http://10.0.2.2:8000/`; en teléfono, la IP LAN del PC.
