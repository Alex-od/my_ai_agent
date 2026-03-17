"""
RAG MCP Server — порт 8083
Протокол: JSON-RPC 2.0, эндпоинт POST /mcp
Инструменты: index_documents, search_documents, get_index_stats, compare_strategies
Эмбеддинги: Ollama nomic-embed-text (http://localhost:11434)
Индекс: FAISS IndexFlatL2, хранится в rag-index/
"""

import os
import re
import json
import math
import pathlib
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import numpy as np
import faiss
import requests
import fitz  # pymupdf
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
import uvicorn

# ── Константы ──────────────────────────────────────────────────────────────────

PORT = 8083
INDEX_DIR = pathlib.Path(__file__).parent.parent / "rag-index"
INDEX_DIR.mkdir(exist_ok=True)

FIXED_INDEX_FILE    = INDEX_DIR / "faiss_fixed.index"
FIXED_META_FILE     = INDEX_DIR / "metadata_fixed.json"
STRUCT_INDEX_FILE   = INDEX_DIR / "faiss_struct.index"
STRUCT_META_FILE    = INDEX_DIR / "metadata_struct.json"
STATS_FILE          = INDEX_DIR / "stats.json"

EMBED_DIM  = 768
OLLAMA_URL = "http://localhost:11434/api/embeddings"
EMBED_MODEL = "nomic-embed-text"

SUPPORTED_EXTENSIONS = {".txt", ".md", ".py", ".pdf"}

# ── FastAPI ────────────────────────────────────────────────────────────────────

app = FastAPI()

# ── Вспомогательные функции ────────────────────────────────────────────────────

def embed(text: str) -> list[float]:
    """Получить эмбеддинг от Ollama."""
    r = requests.post(OLLAMA_URL, json={"model": EMBED_MODEL, "prompt": text}, timeout=30)
    r.raise_for_status()
    return r.json()["embedding"]


def load_documents(folder_path: str) -> list[dict]:
    """Читаем все поддерживаемые файлы из папки, возвращаем [{filename, text}]."""
    folder = pathlib.Path(folder_path)
    if not folder.exists():
        raise ValueError(f"Папка не найдена: {folder_path}")

    docs = []
    for fp in sorted(folder.rglob("*")):
        if fp.suffix.lower() not in SUPPORTED_EXTENSIONS:
            continue
        try:
            if fp.suffix.lower() == ".pdf":
                doc = fitz.open(str(fp))
                text = "\n".join(page.get_text() for page in doc)
                doc.close()
            else:
                text = fp.read_text(encoding="utf-8", errors="ignore")
            if text.strip():
                docs.append({"filename": fp.name, "filepath": str(fp), "text": text})
        except Exception as e:
            print(f"[WARN] Не удалось прочитать {fp}: {e}")
    return docs


def chunk_fixed(text: str, size: int = 1000, overlap: int = 200) -> list[str]:
    """Стратегия 1: нарезаем текст посимвольно с перекрытием."""
    chunks = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        chunks.append(text[start:end])
        start += size - overlap
    return [c for c in chunks if c.strip()]


def chunk_structural(text: str, filename: str) -> list[str]:
    """Стратегия 2: сплит по структуре файла."""
    ext = pathlib.Path(filename).suffix.lower()
    raw_chunks: list[str] = []

    if ext == ".md":
        # Режем только по # и ## — ### остаётся внутри родительской секции
        parts = re.split(r'(?m)^#{1,2} ', text)
        raw_chunks = [p.strip() for p in parts if p.strip()]

    elif ext == ".py":
        parts = re.split(r'(?m)^(?=def |class )', text)
        raw_chunks = [p.strip() for p in parts if p.strip()]

    else:
        raw_chunks = [p.strip() for p in text.split("\n\n") if p.strip()]

    # Если чанк > 2000 символов — рекурсивно режем fixed
    result = []
    for chunk in raw_chunks:
        if len(chunk) > 2000:
            result.extend(chunk_fixed(chunk, size=1000, overlap=200))
        else:
            result.append(chunk)
    return result


def build_metadata(chunks: list[str], filename: str, strategy: str) -> list[dict]:
    """Создаём метаданные для каждого чанка."""
    meta = []
    stem = pathlib.Path(filename).stem.replace(" ", "_")[:20]
    pos = 0
    for i, chunk in enumerate(chunks):
        char_start = pos
        char_end = pos + len(chunk)
        pos = char_end

        # Пытаемся извлечь секцию из первой строки
        first_line = chunk.split("\n")[0].strip()[:80]
        meta.append({
            "chunk_id": f"{stem}_{i:03d}",
            "source": filename,
            "title": first_line or filename,
            "section": first_line,
            "strategy": strategy,
            "char_start": char_start,
            "char_end": char_end,
            "text": chunk,
        })
    return meta


def embed_chunks(chunks: list[str]) -> np.ndarray:
    """Эмбеддим список чанков, возвращаем float32 массив (N, DIM)."""
    vectors = []
    for i, chunk in enumerate(chunks):
        print(f"  Эмбеддинг {i+1}/{len(chunks)}...")
        vec = embed(chunk[:4096])  # ограничиваем длину промпта
        vectors.append(vec)
    arr = np.array(vectors, dtype=np.float32)
    if arr.ndim == 1:
        arr = arr.reshape(1, -1)
    return arr


def save_index(index: faiss.Index, metadata: list[dict],
               index_path: pathlib.Path, meta_path: pathlib.Path):
    faiss.write_index(index, str(index_path))
    meta_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def load_index(index_path: pathlib.Path, meta_path: pathlib.Path):
    if not index_path.exists() or not meta_path.exists():
        return None, []
    index = faiss.read_index(str(index_path))
    metadata = json.loads(meta_path.read_text(encoding="utf-8"))
    return index, metadata


def build_stats(fixed_meta: list[dict], struct_meta: list[dict]) -> dict:
    files_fixed  = len({m["source"] for m in fixed_meta})
    files_struct = len({m["source"] for m in struct_meta})
    avg_fixed    = int(sum(len(m["text"]) for m in fixed_meta) / max(len(fixed_meta), 1))
    avg_struct   = int(sum(len(m["text"]) for m in struct_meta) / max(len(struct_meta), 1))
    sections     = len({m["section"] for m in struct_meta if m["section"]})

    stats = {
        "fixed": {
            "chunks": len(fixed_meta),
            "avg_chars": avg_fixed,
            "overlap": 200,
            "files_covered": files_fixed,
        },
        "structural": {
            "chunks": len(struct_meta),
            "avg_chars": avg_struct,
            "overlap": 0,
            "sections_detected": sections,
            "files_covered": files_struct,
        },
        "recommendation": (
            "structural — меньше чанков, сохраняет смысловые блоки"
            if len(struct_meta) < len(fixed_meta)
            else "fixed — более равномерное покрытие"
        ),
    }
    STATS_FILE.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    return stats

# ── Состояние фоновой индексации ───────────────────────────────────────────────

_indexing_status = {"state": "idle", "progress": 0, "total": 0, "message": ""}
_status_lock = threading.Lock()

def _set_status(state: str, progress: int = 0, total: int = 0, message: str = ""):
    with _status_lock:
        _indexing_status.update(state=state, progress=progress, total=total, message=message)


def _run_indexing(docs_or_files, mode: str):
    """mode: 'path' — docs=[{filename,text}], 'content' — docs=[{name,content}]"""
    try:
        fixed_chunks, fixed_meta   = [], []
        struct_chunks, struct_meta = [], []

        items = docs_or_files
        _set_status("chunking", 0, len(items), "Нарезка чанков…")

        for item in items:
            fname = item.get("filename") or item.get("name", "unknown")
            text  = item.get("text") or item.get("content", "")
            if not text.strip():
                continue
            fc = chunk_fixed(text)
            fixed_meta.extend(build_metadata(fc, fname, "fixed"))
            fixed_chunks.extend(fc)
            sc = chunk_structural(text, fname)
            struct_meta.extend(build_metadata(sc, fname, "structural"))
            struct_chunks.extend(sc)

        total = len(fixed_chunks) + len(struct_chunks)
        _set_status("embedding", 0, total, f"Эмбеддинг {len(fixed_chunks)} fixed + {len(struct_chunks)} structural…")

        def make_embed_text(chunk: str, meta: dict) -> str:
            """Contextual Chunk Headers: добавляем заголовок к тексту для эмбеддинга."""
            header = f"[{meta['source']}] {meta['section']}\n" if meta.get("section") else f"[{meta['source']}]\n"
            return (header + chunk)[:4096]

        def embed_parallel(chunks: list[str], metas: list[dict], label: str, offset: int) -> list:
            results = [None] * len(chunks)
            done = [0]
            with ThreadPoolExecutor(max_workers=8) as ex:
                futures = {ex.submit(embed, make_embed_text(c, metas[i])): i for i, c in enumerate(chunks)}
                for fut in as_completed(futures):
                    i = futures[fut]
                    try:
                        results[i] = fut.result()
                    except Exception as e:
                        print(f"[WARN] Ошибка эмбеддинга чанка {i}: {e}")
                        results[i] = [0.0] * EMBED_DIM  # нулевой вектор вместо краша
                    done[0] += 1
                    _set_status("embedding", offset + done[0], total,
                                f"{label} {done[0]}/{len(chunks)}")
            return results

        fixed_vecs  = embed_parallel(fixed_chunks,  fixed_meta,  "fixed",      0)
        struct_vecs = embed_parallel(struct_chunks, struct_meta, "structural", len(fixed_chunks))

        _set_status("saving", total, total, "Сохранение индексов…")

        dim = len(fixed_vecs[0])

        fi = faiss.IndexFlatL2(dim)
        fi.add(np.array(fixed_vecs, dtype=np.float32))
        save_index(fi, fixed_meta, FIXED_INDEX_FILE, FIXED_META_FILE)

        si = faiss.IndexFlatL2(dim)
        si.add(np.array(struct_vecs, dtype=np.float32))
        save_index(si, struct_meta, STRUCT_INDEX_FILE, STRUCT_META_FILE)

        build_stats(fixed_meta, struct_meta)
        _set_status("done", total, total,
                    f"Готово: fixed={len(fixed_chunks)}, structural={len(struct_chunks)}, файлов={len(items)}")

    except Exception as e:
        _set_status("error", 0, 0, str(e))


# ── MCP-инструменты ────────────────────────────────────────────────────────────

TOOLS = [
    {
        "name": "index_documents",
        "description": (
            "Индексирует документы из указанной папки. "
            "Строит два FAISS-индекса: fixed (по символам) и structural (по заголовкам/функциям). "
            "Использует Ollama nomic-embed-text для эмбеддингов. "
            "Возвращает статистику по обеим стратегиям."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "folder_path": {
                    "type": "string",
                    "description": "Абсолютный путь к папке с документами, например C:\\\\MyDocs",
                },
            },
            "required": ["folder_path"],
        },
    },
    {
        "name": "search_documents",
        "description": (
            "Семантический поиск по проиндексированным документам. "
            "Конвертирует запрос в эмбеддинг, ищет top-k похожих чанков через FAISS."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Текст запроса для семантического поиска",
                },
                "strategy": {
                    "type": "string",
                    "enum": ["fixed", "structural"],
                    "description": "Индекс для поиска (default: structural)",
                    "default": "structural",
                },
                "top_k": {
                    "type": "integer",
                    "description": "Кол-во результатов (default: 3)",
                    "default": 3,
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "get_index_stats",
        "description": "Возвращает статистику текущего индекса: кол-во чанков, файлов, стратегии.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_indexing_status",
        "description": "Возвращает статус фоновой индексации: idle / chunking / embedding / saving / done / error.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "compare_strategies",
        "description": (
            "Сравнивает две стратегии чанкинга — fixed vs structural: "
            "кол-во чанков, средняя длина, покрытие файлов."
        ),
        "inputSchema": {"type": "object", "properties": {}},
    },
]

# index_documents_content не включён в TOOLS — вызывается напрямую из Android-приложения,
# не через AI-агента, поэтому OpenAI его не видит.


def handle_index_documents(args: dict) -> dict:
    folder_path = args.get("folder_path", "")
    if not folder_path:
        return {"error": "folder_path обязателен"}
    if _indexing_status["state"] in ("chunking", "embedding", "saving"):
        return {"error": "Индексация уже запущена", "status": _indexing_status}
    docs = load_documents(folder_path)
    if not docs:
        return {"error": f"Нет поддерживаемых файлов в {folder_path}"}
    _set_status("chunking", 0, len(docs), "Запуск…")
    threading.Thread(target=_run_indexing, args=(docs, "path"), daemon=True).start()
    return {"status": "started", "files_found": len(docs),
            "message": "Индексация запущена в фоне. Проверяй get_indexing_status"}


def handle_search_documents(args: dict) -> dict:
    query    = args.get("query", "")
    strategy = args.get("strategy", "structural")
    top_k    = int(args.get("top_k", 3))

    if not query:
        return {"error": "query обязателен"}

    if strategy == "fixed":
        index, metadata = load_index(FIXED_INDEX_FILE, FIXED_META_FILE)
    else:
        index, metadata = load_index(STRUCT_INDEX_FILE, STRUCT_META_FILE)

    if index is None:
        return {"error": "Индекс не найден. Сначала вызовите index_documents."}

    vec = embed(query)
    q = np.array([vec], dtype=np.float32)
    k = min(top_k, index.ntotal)
    distances, indices = index.search(q, k)

    results = []
    for dist, idx in zip(distances[0], indices[0]):
        if idx < 0 or idx >= len(metadata):
            continue
        meta = metadata[idx]
        results.append({
            "chunk_id":   meta["chunk_id"],
            "source":     meta["source"],
            "section":    meta["section"],
            "score":      round(float(dist), 4),
            "chunk_size": len(meta["text"]),
            "strategy":   strategy,
            "text":       meta["text"][:500] + ("..." if len(meta["text"]) > 500 else ""),
        })
    return {"strategy": strategy, "query": query, "results": results}


def handle_get_index_stats(_args: dict) -> dict:
    _, fixed_meta  = load_index(FIXED_INDEX_FILE, FIXED_META_FILE)
    _, struct_meta = load_index(STRUCT_INDEX_FILE, STRUCT_META_FILE)

    if not fixed_meta and not struct_meta:
        return {"status": "empty", "message": "Индекс не найден. Вызовите index_documents."}

    return {
        "status": "ok",
        "fixed": {
            "chunks": len(fixed_meta),
            "files":  len({m["source"] for m in fixed_meta}),
            "index_size_bytes": FIXED_INDEX_FILE.stat().st_size if FIXED_INDEX_FILE.exists() else 0,
        },
        "structural": {
            "chunks": len(struct_meta),
            "files":  len({m["source"] for m in struct_meta}),
            "index_size_bytes": STRUCT_INDEX_FILE.stat().st_size if STRUCT_INDEX_FILE.exists() else 0,
        },
    }


def handle_compare_strategies(_args: dict) -> dict:
    if STATS_FILE.exists():
        return json.loads(STATS_FILE.read_text(encoding="utf-8"))

    _, fixed_meta  = load_index(FIXED_INDEX_FILE, FIXED_META_FILE)
    _, struct_meta = load_index(STRUCT_INDEX_FILE, STRUCT_META_FILE)

    if not fixed_meta and not struct_meta:
        return {"error": "Индекс не найден. Сначала вызовите index_documents."}

    return build_stats(fixed_meta, struct_meta)

# ── JSON-RPC роутер ────────────────────────────────────────────────────────────

def handle_index_documents_content(args: dict) -> dict:
    files = args.get("files", [])
    if not files:
        return {"error": "files обязателен — список [{name, content}]"}
    if _indexing_status["state"] in ("chunking", "embedding", "saving"):
        return {"error": "Индексация уже запущена", "status": _indexing_status}
    _set_status("chunking", 0, len(files), "Запуск…")
    threading.Thread(target=_run_indexing, args=(files, "content"), daemon=True).start()
    return {"status": "started", "files_received": len(files),
            "message": "Индексация запущена в фоне. Проверяй get_indexing_status"}


def handle_get_indexing_status(_args: dict) -> dict:
    with _status_lock:
        return dict(_indexing_status)


TOOL_HANDLERS = {
    "index_documents":         handle_index_documents,
    "index_documents_content": handle_index_documents_content,
    "get_indexing_status":     handle_get_indexing_status,
    "search_documents":        handle_search_documents,
    "get_index_stats":         handle_get_index_stats,
    "compare_strategies":      handle_compare_strategies,
}


def jsonrpc_error(req_id, code: int, message: str):
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}


def jsonrpc_ok(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


@app.post("/mcp")
async def mcp_endpoint(request: Request):
    body = await request.json()
    req_id = body.get("id")
    method = body.get("method", "")
    params = body.get("params", {})

    # ── initialize ─────────────────────────────────────────────────────────────
    if method == "initialize":
        return JSONResponse(jsonrpc_ok(req_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "rag-server", "version": "1.0.0"},
        }))

    # ── tools/list ─────────────────────────────────────────────────────────────
    if method == "tools/list":
        return JSONResponse(jsonrpc_ok(req_id, {"tools": TOOLS}))

    # ── tools/call ─────────────────────────────────────────────────────────────
    if method == "tools/call":
        tool_name = params.get("name", "")
        tool_args = params.get("arguments", {})

        handler = TOOL_HANDLERS.get(tool_name)
        if not handler:
            return JSONResponse(jsonrpc_error(req_id, -32601, f"Инструмент не найден: {tool_name}"))

        try:
            result = handler(tool_args)
        except Exception as e:
            return JSONResponse(jsonrpc_error(req_id, -32000, str(e)))

        return JSONResponse(jsonrpc_ok(req_id, {
            "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False, indent=2)}]
        }))

    return JSONResponse(jsonrpc_error(req_id, -32601, f"Метод не найден: {method}"))


# ── Запуск ─────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print(f"RAG MCP Server запущен на порту {PORT}")
    print(f"Индекс хранится в: {INDEX_DIR}")
    uvicorn.run(app, host="0.0.0.0", port=PORT)
