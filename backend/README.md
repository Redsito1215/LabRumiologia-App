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

Para desarrollo local, edite `../local.properties`. Para Vercel configure las
variables desde **Settings > Environment Variables**; nunca guarde claves en Git:

```text
OPENAI_API_KEY=secretoaqui
GEMINI_API_KEY=secretoaqui
LLM_PROVIDER=openai
OPENAI_MODEL=gpt-4o-mini
OPENAI_WEB_MODEL=gpt-5-mini
OPENAI_FILE_SEARCH_TOP_K=4
OPENAI_MAX_OUTPUT_TOKENS=300
REQUESTS_PER_MINUTE=20
APP_ACCESS_TOKEN=secretoaqui
ADMIN_ACCESS_TOKEN=secretoaqui
```

`APP_ACCESS_TOKEN` debe coincidir con `assistant.app.token` de `local.properties`
al compilar la APK. `ADMIN_ACCESS_TOKEN` debe ser diferente y permanece solamente
en Vercel. La APK nunca debe contener las claves de OpenAI, Gemini o administración.

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

Compruebe el mapeo enviando el token de la aplicación:

```bash
curl -H "X-App-Token: secretoaqui" http://127.0.0.1:8000/knowledge/balanza_analitica
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

Las rutas públicas de consulta exigen `X-App-Token` y aplican un límite por IP.
`POST /ingest` exige el secreto independiente `X-Admin-Token`.

La app **no** envía documentos ni IDs de OpenAI: solo pregunta + clase detectada.
Primero se consulta File Search. La búsqueda web de OpenAI se usa únicamente cuando
los documentos asignados no contienen una respuesta suficiente.

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
