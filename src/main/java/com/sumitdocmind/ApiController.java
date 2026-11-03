package com.sumitdocmind;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.*;
import java.io.*;
import java.sql.*;
import java.util.stream.*;

@RestController
@RequestMapping("/api")
@Tag(name = "SumitDocMind API", description = "Account registration/login, PDF upload, RAG chat, and conversation management. " +
        "Endpoints under /docs and /conversations require a JWT Bearer token unless JWT is disabled in config (JWT_ENABLED=false, the default for local dev).")
public class ApiController {
    final JdbcTemplate db;
    final PasswordEncoder passwords;
    final JwtUtil jwt;
    final OpenAiService ai;
    final ObjectMapper json = new ObjectMapper();

    public ApiController(JdbcTemplate d, PasswordEncoder p, JwtUtil j, OpenAiService a) {
        db = d;
        passwords = p;
        jwt = j;
        ai = a;
    }

    long uid(Authentication a) {
        return ((Number) a.getPrincipal()).longValue();
    }

    private long insertAndReturnId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated key");
        }
        return key.longValue();
    }

    @PostMapping("/auth/register")
    @Operation(summary = "Register a new account",
            description = "Creates a user account and returns a JWT Bearer token. " +
                    "The password is stored as a BCrypt hash. If JWT is disabled (the default), the token is still returned for compat, but not required on later calls.",
            security = { @SecurityRequirement(name = "") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account created, token issued", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                {"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.example","email":"alice@example.com"}
                """))),
        @ApiResponse(responseCode = "400", description = "Invalid email or password shorter than 8 characters"),
        @ApiResponse(responseCode = "409", description = "Email already registered")
    })
    Map register(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "New account credentials", required = true,
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(name = "request", value = """
                    {"email":"alice@example.com","password":"secret123"}
                    """)))
            @RequestBody Map<String, String> b) {
        String email = b.getOrDefault("email", "").trim().toLowerCase(), pw = b.getOrDefault("password", "");
        if (!email.matches("^[^@\s]+@[^@\s]+\\.[^@\s]+$") || pw.length() < 8)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email and password (8+ chars) required");
        try {
            long id = insertAndReturnId("insert into app_user(email,password_hash) values (?,?)", email, passwords.encode(pw));
            return Map.of("token", jwt.issue(id), "email", email);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Log in",
            description = "Returns a JWT Bearer token for an existing account. Use the token as `Authorization: Bearer <token>` " +
                    "on protected endpoints when JWT is enabled.",
            security = { @SecurityRequirement(name = "") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login OK, token issued", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                {"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.example","email":"alice@example.com"}
                """))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    Map login(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Existing credentials", required = true,
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(name = "request", value = """
                    {"email":"alice@example.com","password":"secret123"}
                    """)))
            @RequestBody Map<String, String> b) {
        var rows = db.query("select id,password_hash from app_user where email=?", (r, n) -> Map.of("id", r.getLong(1), "pw", r.getString(2)), b.getOrDefault("email", "").toLowerCase());
        if (rows.isEmpty() || !passwords.matches(b.getOrDefault("password", ""), (String) rows.get(0).get("pw")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        return Map.of("token", jwt.issue((Long) rows.get(0).get("id")), "email", b.get("email"));
    }

    @GetMapping("/docs")
    @Operation(summary = "List my documents",
            description = "Returns all PDFs uploaded by the current user, newest first.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses(@ApiResponse(responseCode = "200", description = "List of documents", content = @Content(mediaType = "application/json",
        examples = @ExampleObject(name = "success", value = """
            [{"id":7,"filename":"employee-handbook.pdf","created_at":"2026-11-01T10:15:00"}]
            """))))
    List docs(Authentication a) {
        return db.queryForList("select id,filename,created_at from document_file where user_id=? order by id desc", uid(a));
    }

    @PostMapping("/docs")
    @Operation(summary = "Upload a PDF",
            description = "Uploads a text-based PDF. The text is extracted, chunked, embedded, and indexed for retrieval. " +
                    "Multipart/form-data with the file in the `file` field. Max size 20 MB.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF indexed", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                {"id":7,"filename":"employee-handbook.pdf","chunks":14}
                """))),
        @ApiResponse(responseCode = "400", description = "Not a non-empty PDF, or no extractable text (scanned PDFs need OCR)")
    })
    Map upload(@Parameter(description = "The PDF file to upload (multipart field `file`)")
            @RequestParam("file") MultipartFile file, Authentication a) throws Exception {
        if (file.isEmpty() || !file.getOriginalFilename().toLowerCase().endsWith(".pdf"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty PDF");
        String text;
        try (var pdf = Loader.loadPDF(file.getBytes())) {
            text = new PDFTextStripper().getText(pdf);
        }
        if (text.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No extractable text found (scanned PDFs need OCR)");
        long user = uid(a);
        long doc = insertAndReturnId("insert into document_file(user_id,filename) values (?,?)", user, file.getOriginalFilename());
        List<String> chunks = chunk(text, 900, 120);
        for (String c : chunks) {
            float[] v = ai.embed(c);
            db.update("insert into document_chunk(document_id,user_id,chunk_text,embedding) values (?,?,?,?)", doc, user, c, vector(v));
        }
        return Map.of("id", doc, "filename", file.getOriginalFilename(), "chunks", chunks.size());
    }

    private List<String> chunk(String s, int size, int overlap) {
        List<String> out = new ArrayList<>();
        s = s = s.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < s.length(); ) {
            int end = Math.min(s.length(), i + size);
            if (end < s.length()) {
                int space = s.lastIndexOf(' ', end);
                if (space > i + size / 2) end = space;
            }
            out.add(s.substring(i, end).trim());
            if (end >= s.length()) break;
            i = Math.max(i + 1, end - overlap);
        }
        return out;
    }

    private String vector(float[] a) {
        return "[" + IntStream.range(0, a.length).mapToObj(i -> Float.toString(a[i])).collect(Collectors.joining(",")) + "]";
    }

    private float[] parseVector(String json) {
        String value = json.trim();
        if (value.length() < 2 || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']') {
            throw new IllegalStateException("Stored embedding is not a JSON array");
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] components = body.split(",");
        float[] result = new float[components.length];
        for (int i = 0; i < components.length; i++) {
            result[i] = Float.parseFloat(components[i].trim());
        }
        return result;
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length || left.length == 0) {
            throw new IllegalStateException("Embedding dimensions do not match");
        }
        double dot = 0;
        double leftMagnitude = 0;
        double rightMagnitude = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftMagnitude += (double) left[i] * left[i];
            rightMagnitude += (double) right[i] * right[i];
        }
        if (leftMagnitude == 0 || rightMagnitude == 0) {
            throw new IllegalStateException("Cannot compare zero-length embeddings");
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    @GetMapping("/conversations")
    @Operation(summary = "List my conversations",
            description = "Returns all chats for the current user, newest first.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses(@ApiResponse(responseCode = "200", description = "List of conversations", content = @Content(mediaType = "application/json",
        examples = @ExampleObject(name = "success", value = """
            [{"id":3,"title":"Employee handbook","created_at":"2026-11-01T10:16:00"}]
            """))))
    List conversations(Authentication a) {
        return db.queryForList("select id,title,created_at from conversation where user_id=? order by id desc", uid(a));
    }

    @PostMapping("/conversations")
    @Operation(summary = "Create a conversation",
            description = "Creates a new chat. The `title` field is optional; it defaults to \"New chat\".",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Conversation created", content = @Content(mediaType = "application/json",
        examples = @ExampleObject(name = "success", value = """
            {"id":3,"title":"New chat"}
            """))))
    Map createConversation(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Optional title", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "request", value = """
                {"title":"Employee handbook"}
                """)))
            @RequestBody(required = false) Map<String, String> b, Authentication a) {
        String t = b == null ? "New chat" : b.getOrDefault("title", "New chat");
        long id = insertAndReturnId("insert into conversation(user_id,title) values (?,?)", uid(a), t);
        return Map.of("id", id, "title", t);
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "Get conversation history",
            description = "Returns all messages (user + assistant) in a conversation, in chronological order. " +
                    "The `sources` field on assistant messages lists the source documents cited in the answer.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Message list", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                [
                  {"id":1,"role":"user","content":"What is the leave policy?","sources":null,"created_at":"2026-11-01T10:17:00"},
                  {"id":2,"role":"assistant","content":"Per the handbook, you earn 15 days of leave...","sources":[{"filename":"employee-handbook.pdf","documentId":7}],"created_at":"2026-11-01T10:17:05"}
                ]
                """))),
        @ApiResponse(responseCode = "404", description = "Conversation not found or not owned by the current user")
    })
    List messages(@Parameter(description = "Conversation id") @PathVariable long id, Authentication a) {
        ownedConversation(id, uid(a));
        return db.queryForList("select id,message_role as role,content,sources,created_at from chat_message where conversation_id=? order by id", id);
    }

    @PostMapping("/conversations/{id}/chat")
    @Operation(summary = "Ask a question (RAG chat)",
            description = "Ask a question about your uploaded documents. The app embeds the question, retrieves the top matching chunks, " +
                    "and generates an answer grounded only in those excerpts, citing the source filenames. Requires at least one uploaded PDF.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Answer with sources", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                {
                  "answer": "You earn 15 days of paid leave per year, per employee-handbook.pdf. Unused days carry over up to 5 days.",
                  "sources": [{"filename":"employee-handbook.pdf","documentId":7}]
                }
                """))),
        @ApiResponse(responseCode = "400", description = "Empty message, or no PDFs uploaded yet"),
        @ApiResponse(responseCode = "404", description = "Conversation not found or not owned by the current user")
    })
    Map chat(@Parameter(description = "Conversation id") @PathVariable long id,
             @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Your question", required = true,
                     content = @Content(mediaType = "application/json",
                         examples = @ExampleObject(name = "request", value = """
                             {"message":"What is the leave policy?"}
                             """)))
             @RequestBody Map<String, String> b, Authentication a) {
        long user = uid(a);
        ownedConversation(id, user);
        String question = b.getOrDefault("message", "").trim();
        if (question.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required");
        float[] q = ai.embed(question);
        List<Map<String, Object>> hits = db.query("select c.chunk_text,d.filename,d.id as doc_id,c.embedding from document_chunk c join document_file d on d.id=c.document_id where c.user_id=?", (rs, n) -> new SearchHit(rs.getString("chunk_text"), rs.getString("filename"), rs.getLong("doc_id"), cosineSimilarity(q, parseVector(rs.getString("embedding")))), user)
                .stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(5)
                .map(hit -> Map.<String, Object>of("text", hit.text(), "filename", hit.filename(), "docId", hit.docId()))
                .toList();
        String context = hits.stream().map(h -> "[" + h.get("filename") + "] " + h.get("text")).collect(Collectors.joining("\n\n"));
        String response = ai.answer("You are SumitDocMind, a document question-answering assistant. Answer only from the supplied excerpts. If the excerpts do not contain the answer, say you couldn't find it in the uploaded documents. Cite filenames inline.", "Question: " + question + "\n\nDocument excerpts:\n" + context);
        db.update("insert into chat_message(conversation_id,message_role,content,sources) values (?,?,?,?)", id, "user", question, "[]");
        List<Map<String, Object>> sourceRecords = hits.stream()
                .map(h -> Map.<String, Object>of("filename", h.get("filename"), "documentId", h.get("docId")))
                .toList();
        db.update("insert into chat_message(conversation_id,message_role,content,sources) values (?,?,?,?)", id, "assistant", response, toJson(sourceRecords));
        return Map.of("answer", response, "sources", hits.stream().map(h -> Map.of("filename", h.get("filename"), "documentId", h.get("docId"))).distinct().toList());
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize chat sources", e);
        }
    }

    private record SearchHit(String text, String filename, long docId, double score) {}

    private void ownedConversation(long id, long u) {
        if (db.queryForList("select id from conversation where id=? and user_id=?", id, u).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }

    @DeleteMapping("/docs/{id}")
    @Operation(summary = "Delete a document",
            description = "Permanently deletes a document owned by the current user, along with all its chunks.",
            security = { @SecurityRequirement(name = "bearerAuth") })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Deleted", content = @Content(mediaType = "application/json",
            examples = @ExampleObject(name = "success", value = """
                {"deleted":true}
                """))),
        @ApiResponse(responseCode = "404", description = "Document not found or not owned by the current user")
    })
    Map deleteDoc(@Parameter(description = "Document id") @PathVariable long id, Authentication a) {
        int n = db.update("delete from document_file where id=? and user_id=?", id, uid(a));
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("deleted", true);
    }
}
