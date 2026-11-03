# SumitDocMind — original Java RAG document chat

A runnable learning project inspired by the feature set of the DocMind tutorial: accounts, PDF upload, vector retrieval, document-grounded chat, and conversation history. This is an independent implementation, not a copy of the tutorial repository.

> 📖 **Full guide:** see [PROJECT.md](./PROJECT.md) for architecture, configuration, API reference, troubleshooting, and setup with **free Hugging Face models**.
> 📄 **What & why:** see [PROJECT_SUMMARY.md](./PROJECT_SUMMARY.md) for a plain-English overview — what the project is, how RAG works under the hood, and what problems it solves.

## Requirements
- Java 21
- Docker + Docker Compose
- An AI API key: OpenAI (for embeddings and chat) or a Hugging Face token for free inference
- Maven 3.9+ (or use an IDE's Maven support)

## Run locally
1. Copy `.env.example` to `.env`, then set your API key (`OPENAI_API_KEY`) and replace `JWT_SECRET` with a long random secret.
2. To use free Hugging Face inference instead of OpenAI, set `AI_PROVIDER=huggingface`, `OPENAI_BASE_URL=https://api-inference.huggingface.co`, and your `hf_...` token as `OPENAI_API_KEY`. Pick a compatible chat model (e.g. `meta-llama/Llama-3.2-3B-Instruct`) and embedding model (e.g. `sentence-transformers/all-MiniLM-L6-v2`).
3. Start the database: `docker compose up -d db`
4. Export variables from `.env` into your shell (or configure them in IntelliJ Run Configuration). Example on macOS/Linux: `set -a; source .env; set +a`
5. Start the app: `mvn spring-boot:run`
6. Open http://localhost:8080 and register. Upload a text-based PDF, create a chat, and ask questions.

The app stores user passwords as BCrypt hashes. API routes are protected by stateless bearer tokens. User-scoped document and conversation queries prevent cross-account access.

**JWT auth is DISABLED by default** for local development (set `JWT_ENABLED=false` in `.env`). With auth disabled, requests run as the default user (`JWT_DEFAULT_USER=1`) and no `Authorization` header is needed. To require a Bearer token, set `JWT_ENABLED=true`.

## Features
- Register/login; HMAC-SHA256 bearer token (24-hour expiry)
- PDF upload and text extraction with PDFBox; chunking with overlap
- OpenAI `text-embedding-3-small` embeddings (1536 dimensions)
- MySQL with cosine-similarity retrieval over stored JSON embeddings
- OpenAI chat completions with retrieved passages and filenames
- Create/list chats, read chat history, delete owned PDFs
- Responsive single-page browser UI served by Spring Boot

## API quick reference
- `POST /api/auth/register` `{email,password}`
- `POST /api/auth/login` `{email,password}`
- `GET /api/docs`; `POST /api/docs` multipart field `file`; `DELETE /api/docs/{id}`
- `GET /api/conversations`; `POST /api/conversations` `{title?}`
- `GET /api/conversations/{id}/messages`
- `POST /api/conversations/{id}/chat` `{message}`

## Important limitations / production TODOs
- The starter is designed for local development. Configure HTTPS, CORS, rate limiting, secret management, backups, malware scanning, and durable/private file storage before deployment.
- PDF extraction is text-based; scanned PDFs need OCR. Upload is limited to 20 MB by default.
- The app currently sends the retrieved passages to OpenAI. Do not upload confidential documents unless your organization permits that.
- Conversation title is currently `New chat`; add title generation/renaming if desired.
- For production, consider refresh tokens, account verification/password reset, audit logging, and robust migrations (Flyway).
- Validate OpenAI account access and billing. API usage costs money.

## Troubleshooting
- `Set OPENAI_API_KEY`: set it in `.env` and ensure it is exported to the Java process.
- Database connection refused: run `docker compose up -d db` and check port 5432.
- `Embedding dimension`: this schema expects `text-embedding-3-small` with 1536 dimensions.
- Empty extraction: this PDF may be scanned/image-only; OCR is not included.
