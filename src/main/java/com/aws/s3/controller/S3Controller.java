package com.aws.s3.controller;

import com.aws.s3.dto.CompleteUploadRequest;
import com.aws.s3.service.S3Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * REST API for uploading files to AWS S3.
 *
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  Endpoint                          │ Purpose                               │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  POST /api/s3/upload/simple        │ Single-call upload  (< 100 MB)        │
 * │  POST /api/s3/upload/large         │ Server-side streaming multipart       │
 * │  POST /api/s3/multipart/initiate   │ Step 1 — get uploadId                 │
 * │  POST /api/s3/multipart/upload-part│ Step 2 — upload one chunk             │
 * │  POST /api/s3/multipart/complete   │ Step 3 — assemble parts               │
 * │  DELETE /api/s3/multipart/abort    │ Abort & clean up on failure           │
 * └─────────────────────────────────────────────────────────────────────────────┘
 */
@RestController
@RequestMapping("/api/s3")
public class S3Controller {

    private final S3Service s3Service;

    public S3Controller(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    // ── 1. Simple Upload ──────────────────────────────────────────────────────

    /**
     * POST /api/s3/upload/simple
     *
     * Upload a file in a single request (best for files < 100 MB).
     *
     * Form params:
     *   file  — the file to upload
     *   key   — (optional) destination S3 key; defaults to original filename
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/s3/upload/simple \
     *        -F "file=@/path/to/file.pdf" \
     *        -F "key=uploads/file.pdf"
     */
    @PostMapping("/upload/simple")
    public ResponseEntity<Map<String, String>> simpleUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "key", required = false) String key) throws IOException {

        String s3Key = resolveKey(key, file.getOriginalFilename());
        String url = s3Service.simpleUpload(file, s3Key);

        return ResponseEntity.ok(Map.of(
                "message", "File uploaded successfully",
                "key", s3Key,
                "url", url
        ));
    }

    // ── 2. Server-Side Streaming Multipart Upload ─────────────────────────────

    /**
     * POST /api/s3/upload/large
     *
     * Upload a large file in a single API call — the server handles
     * splitting it into 10 MB parts and uploading via S3 Multipart API.
     * Best for files > 100 MB sent directly from a client.
     *
     * Form params:
     *   file  — the file to upload
     *   key   — (optional) destination S3 key; defaults to original filename
     *
     * Example:
     *   curl -X POST http://localhost:8080/api/s3/upload/large \
     *        -F "file=@/path/to/large-file.zip" \
     *        -F "key=uploads/large-file.zip"
     */
    @PostMapping("/upload/large")
    public ResponseEntity<Map<String, String>> largeUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "key", required = false) String key) throws IOException {

        String s3Key = resolveKey(key, file.getOriginalFilename());
        String url = s3Service.streamingMultipartUpload(file, s3Key);

        return ResponseEntity.ok(Map.of(
                "message", "Large file uploaded successfully",
                "key", s3Key,
                "url", url
        ));
    }

    // ── 3. Client-Driven Multipart: Step 1 — Initiate ────────────────────────

    /**
     * POST /api/s3/multipart/initiate?key=uploads/file.zip
     *
     * Starts a multipart upload session.
     * Returns an uploadId that must be passed to every subsequent call.
     *
     * Example:
     *   curl -X POST "http://localhost:8080/api/s3/multipart/initiate?key=uploads/file.zip"
     */
    @PostMapping("/multipart/initiate")
    public ResponseEntity<Map<String, String>> initiateMultipartUpload(
            @RequestParam("key") String key) {

        String uploadId = s3Service.initiateMultipartUpload(key);

        return ResponseEntity.ok(Map.of(
                "uploadId", uploadId,
                "key", key,
                "message", "Multipart upload initiated. Use uploadId to upload parts."
        ));
    }

    // ── 4. Client-Driven Multipart: Step 2 — Upload Part ─────────────────────

    /**
     * POST /api/s3/multipart/upload-part?key=...&uploadId=...&partNumber=1
     *
     * Uploads a single part (chunk). Parts must be ≥ 5 MB except the last.
     * Returns the ETag — save it, you need it to complete the upload.
     *
     * Form params:
     *   file        — the binary chunk
     *   key         — same S3 key used in initiate
     *   uploadId    — from the initiate response
     *   partNumber  — 1-based index (max 10000)
     *
     * Example:
     *   curl -X POST "http://localhost:8080/api/s3/multipart/upload-part" \
     *        -F "file=@chunk_001.bin" \
     *        -F "key=uploads/file.zip" \
     *        -F "uploadId=xxxx" \
     *        -F "partNumber=1"
     */
    @PostMapping("/multipart/upload-part")
    public ResponseEntity<Map<String, String>> uploadPart(
            @RequestParam("file") MultipartFile file,
            @RequestParam("key") String key,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("partNumber") int partNumber) throws IOException {

        String eTag = s3Service.uploadPart(uploadId, key, partNumber, file);

        return ResponseEntity.ok(Map.of(
                "partNumber", String.valueOf(partNumber),
                "eTag", eTag,
                "message", "Part uploaded. Save the eTag for the complete call."
        ));
    }

    // ── 5. Client-Driven Multipart: Step 3 — Complete ────────────────────────

    /**
     * POST /api/s3/multipart/complete?key=uploads/file.zip
     *
     * Assembles all uploaded parts into the final S3 object.
     *
     * Request body (JSON):
     * {
     *   "uploadId": "xxxx",
     *   "parts": [
     *     { "partNumber": 1, "eTag": "\"abc123\"" },
     *     { "partNumber": 2, "eTag": "\"def456\"" }
     *   ]
     * }
     *
     * Example:
     *   curl -X POST "http://localhost:8080/api/s3/multipart/complete?key=uploads/file.zip" \
     *        -H "Content-Type: application/json" \
     *        -d '{"uploadId":"xxxx","parts":[{"partNumber":1,"eTag":"\"abc\""}]}'
     */
    @PostMapping("/multipart/complete")
    public ResponseEntity<Map<String, String>> completeMultipartUpload(
            @RequestParam("key") String key,
            @RequestBody CompleteUploadRequest request) {

        String url = s3Service.completeMultipartUpload(key, request);

        return ResponseEntity.ok(Map.of(
                "message", "Multipart upload completed successfully",
                "key", key,
                "url", url
        ));
    }

    // ── 6. Abort Multipart Upload ─────────────────────────────────────────────

    /**
     * DELETE /api/s3/multipart/abort?key=uploads/file.zip&uploadId=xxxx
     *
     * Aborts an in-progress multipart upload and frees all stored parts.
     * Always call this when an upload fails to avoid storage charges.
     *
     * Example:
     *   curl -X DELETE "http://localhost:8080/api/s3/multipart/abort?key=uploads/file.zip&uploadId=xxxx"
     */
    @DeleteMapping("/multipart/abort")
    public ResponseEntity<Map<String, String>> abortMultipartUpload(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("key") String key) {

        s3Service.abortMultipartUpload(uploadId, key);

        return ResponseEntity.ok(Map.of(
                "message", "Multipart upload aborted successfully",
                "uploadId", uploadId,
                "key", key
        ));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String resolveKey(String key, String originalFilename) {
        if (key != null && !key.isBlank()) return key;
        return "uploads/" + (originalFilename != null ? originalFilename : "file");
    }
}
