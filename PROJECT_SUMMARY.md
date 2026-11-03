# SumitDocMind — Project Summary

> **What:** A self-hosted "chat with your documents" web app.
> **How:** RAG (Retrieval-Augmented Generation) — PDFs are embedded into vectors, stored in MySQL, and answered over using open-source AI models (Ollama by default).
> **Why:** To get private, source-cited answers from your own PDFs without uploading them to a commercial cloud service.

---

## 1. What this project is

**SumitDocMind** is a **full-stack Java/Spring Boot application** that lets you:

- Create an account (email + password)
- Upload **PDF documents**
- Ask natural-language questions about the content of those documents
- Receive **answers that cite the exact source document**

It is a complete, runnable implementation of a **local RAG pipeline** — a small-scale version of tools like chat-with-PDF, Notion AI, or "Ask your documents" features found in enterprise platforms.

### Tech stack at a glance

| Layer        | Technology                                        |
|--------------|---------------------------------------------------|
| Backend      | Java 17, Spring Boot 3.4.5                        |
| AI models    | **Ollama (local, free)** — default; any OpenAI-compatible API also works (OpenAI, Groq, DeepSeek, Hugging Face) |
| Database     | MySQL 8 (Docker)                                  |
| Vector storage| JSON column in MySQL + cosine-similarity in Java  |
| Auth         | Optional JWT (HMAC-SHA256) — **disabled by default** for local dev |
| PDF parsing  | Apache PDFBox                                     |
| API docs     | Swagger / OpenAPI (SpringDoc)                     |
| Frontend     | Single-page HTML/JS UI served by Spring Boot      |

---

## 2. How it works (the RAG pipeline)

The app follows the classic **Retrieval-Augmented Generation** flow:

```
        ┌─────────────────────────────────────────────────────────┐
        │                      SUMITDOCMIND                       │
        │                                                         │
        │   ┌────────────┐   ┌──────────┐   ┌─────────────────┐  │
        │   │  INGEST    │→  │  EMBED   │→  │ VECTOR STORAGE  │  │
        │   └────────────┘   └──────────┘   └─────────────────┘  │
        │                                                         │
        │   ┌────────────┐   ┌──────────┐   ┌─────────────────┐  │
        │   │   QUERY    │→  │ RETRIEVAL│→  │   GENERATION    │  │
        │   └────────────┘   └──────────┘   └─────────────────┘  │
        └─────────────────────────────────────────────────────────┘
```

### Step 1 — Ingest a PDF (upload)

1. User uploads a `.pdf` (max 20 MB).
2. **PDFBox extracts the raw text**.
3. The text is **split into chunks** (~900 characters with 120-character overlap) so the AI model can "read" specific passages.

### Step 2 — Embed the chunks (searchable meaning)

1. Each chunk is sent to the **embedding model** (default: `nomic-embed-text` via Ollama).
2. The model converts the text into a **vector** — a list of hundreds of numbers representing the *meaning* of the text.
3. The vector is stored in the `document_chunk` table (as JSON) alongside the chunk text.

> Two chunks that mean similar things end up "close together" in vector space. This is what makes semantic search work.

### Step 3 — Ask a question (query)

1. User types a question.
2. The **same embedding model** converts the question into a vector.
3. The app computes **cosine similarity** between the question vector and every stored chunk vector.
4. The **top 5 most similar chunks** are selected — these are the passages most relevant to the question.

### Step 4 — Answer with citations (generation)

1. The app builds a prompt:
   - System instruction: *"Answer only from the supplied excerpts. If you can't find it, say so. Cite filenames inline."*
   - User content: the question + the 5 retrieved excerpts (each tagged with its source filename).
2. The **chat model** (default: `phi3:latest` via Ollama) reads the prompt and writes an answer **grounded only in those excerpts**.
3. The answer + source list is stored in the conversation history and returned to the UI, which displays it with the filenames.

### Conversation history

- Conversations and every user/assistant message are stored in MySQL.
- You can create multiple chats, reopen them, and see full history — just like ChatGPT.

---

## 3. What problem it solves

### The problem
Finding answers inside **PDF documents is hard**:

- Documents are long, unstructured, and not searchable by meaning (keyword search like Ctrl+F fails when the doc says "neural network" but you ask about "AI model").
- Reading a 200-page manual or research paper to answer one question takes forever.
- Existing chatbot solutions require **uploading sensitive documents to third-party clouds**, which raises privacy, compliance, and cost concerns.

### The solution this project provides

| Problem | How SumitDocMind solves it |
|---------|----------------------------|
| **"Ctrl+F isn't enough"** | Embedding vectors capture *meaning*, not just keywords. You can find "recommendations for improving performance" even if the doc says "optimization guidelines". |
| **Reading everything takes too long** | RAG retrieves only the 3–5 relevant passages before generating an answer. No need to read the whole PDF. |
| **AI models answer from their own knowledge (hallucinate)** | Answers are constrained to the *retrieved excerpts*; the app instructs the model to say "not found" when the document lacks the answer, and to cite filenames. This "grounding" drastically reduces hallucination. |
| **Privacy — "I don't want my documents on OpenAI's servers"** | With the default **Ollama** provider, everything runs **100% locally**: your PDFs, embeddings, and the model itself never leave your machine. |
| **Cost** | Ollama is **free** and runs offline. No API keys, no token billing, no rate limits. (Commercial providers are supported as an option.) |
| **No context / no memory** | Full conversation history is stored, so you can ask follow-up questions in context. |
| **Multi-user data isolation** | All documents and chats are scoped per user (each row carries `user_id`), and ownership checks prevent users from reading each other's data. |

### Example use cases
- **Legal/HR teams:** "Summarize the leave policy section of our employee handbook PDF."
- **Students/researchers:** "What were the methods used in this paper? What are its limitations?"
- **Developers:** "Which configuration option controls connection timeouts in this manual?"
- **Anyone:** "Extract all action items from this meeting notes PDF."

---

## 4. Project layout

```
sumit-docmind/
├── pom.xml                      # Maven build & dependencies (Spring Boot, PDFBox, SpringDoc)
├── docker-compose.yml           # MySQL 8.4 service
├── .env.example                 # Environment variable template
├── README.md                    # Quick overview
├── PROJECT.md                   # Full setup + run guide (incl. Ollama & Hugging Face)
├── PROJECT_SUMMARY.md           # This document
└── src/main/
    ├── java/com/sumitdocmind/
    │   ├── SumitDocMindApplication.java   # Entry point
    │   ├── ApiController.java             # REST endpoints: auth, docs, conversations, chat
    │   ├── OpenAiService.java             # Embeddings + chat completions client
    │   ├── JwtUtil.java                   # JWT issue/verify (HMAC-SHA256)
    │   ├── JwtFilter.java                 # Extracts Bearer token → authentication
    │   └── SecurityConfig.java            # Security rules (JWT on/off)
    └── resources/
        ├── application.yml                # Config (env-driven)
        ├── schema.sql                     # MySQL schema (auto-applied on startup)
        └── static/index.html              # Single-page UI
```

### Key design decisions

1. **MySQL as the vector store** — no separate vector database needed. For a learning/small-scale project, cosine similarity over JSON vectors in Java is simple and adequate. *(TODO for scale: Milvus, pgvector, or Qdrant.)*
2. **OpenAI-compatible client abstraction** — one service (`OpenAiService`) speaks the OpenAI wire format, so you can swap providers with a config change: Ollama ↔ OpenAI ↔ Groq ↔ DeepSeek ↔ Hugging Face.
3. **JWT auth as an on/off flag** — off by default for frictionless local dev; flip `JWT_ENABLED=true` to require tokens.
4. **Single-file HTML UI** — the whole frontend is served by Spring Boot; no separate Node build pipeline.

---

## 5. Current configuration (defaults)

| Setting            | Default value                                    | Notes                          |
|--------------------|--------------------------------------------------|--------------------------------|
| AI provider        | **Ollama (local)**                               | `OPENAI_BASE_URL=http://localhost:11434/v1` |
| Chat model         | `phi3:latest`                                    | Local, free                    |
| Embedding model    | `nomic-embed-text`                               | 768-dim, local, free           |
| JWT auth           | **Disabled** (all requests act as user 1)        | Flip `JWT_ENABLED=true` to enable |
| Database           | MySQL on `localhost:3306/sumitdocmind`         | Docker container               |

> See **[PROJECT.md](./PROJECT.md)** for full setup, provider switching (Ollama / Hugging Face / OpenAI), API reference, and troubleshooting.

---

## 6. Quick start

```bash
# 1. Start MySQL
docker compose up -d db

# 2. Configure environment
cp .env.example .env        # then edit as needed

# 3. Ensure Ollama models are pulled
ollama pull phi3:latest
ollama pull nomic-embed-text

# 4. Run the app
set -a; source .env; set +a
mvn spring-boot:run

# 5. Open the UI
open http://localhost:8080
```

Upload a PDF → create a chat → ask a question → get a cited answer **from your own document, entirely on your machine.**

---

## 7. Limitations & roadmap

**Current limitations**
- Text-based PDFs only (scanned/image PDFs need OCR — not included).
- Cosine similarity is computed in Java over all chunks; fine for small corpora, not for millions of chunks.
- Conversation auto-titling not implemented (title stays "New chat").
- No chunk deduplication/cross-doc summarization.

**Possible next steps**
- OCR support (Tesseract) for scanned PDFs.
- Real vector database (pgvector / Qdrant) + ANN index for scale.
- Multi-file cross-document Q&A and conversational follow-ups with full context.
- File cleanup/purge jobs, upload virus scanning, HTTPS + rate limiting for production.
