package com.aws.s3.controller;

import com.aws.s3.dto.CompleteUploadRequest;
import com.aws.s3.service.S3Service;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/s3")
@Tag(name = "S3 Upload", description = "Endpoints for uploading files to AWS S3 using single and multipart strategies")
public class S3Controller {

    private final S3Service s3Service;

    public S3Controller(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    // ── 1. Simple Upload ──────────────────────────────────────────────────────

    @Operation(
        summary = "Simple file upload",
        description = "Uploads a file to S3 in a single PUT request. Best for files **smaller than 100 MB**."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "File uploaded successfully",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "message": "File uploaded successfully",
                      "key": "uploads/document.pdf",
                      "url": "https://your-bucket.s3.amazonaws.com/uploads/document.pdf"
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Upload failed", content = @Content)
    })
    @PostMapping(value = "/upload/simple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> simpleUpload(
            @Parameter(description = "File to upload", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Destination S3 key (e.g. uploads/file.pdf). Defaults to uploads/<filename>")
            @RequestParam(value = "key", required = false) String key) throws IOException {

        String s3Key = resolveKey(key, file.getOriginalFilename());
        String url = s3Service.simpleUpload(file, s3Key);

        return ResponseEntity.ok(Map.of(
                "message", "File uploaded successfully",
                "key", s3Key,
                "url", url
        ));
    }

    // ── 2. Large File Upload ──────────────────────────────────────────────────

    @Operation(
        summary = "Large file upload (server-side multipart)",
        description = """
            Uploads a large file to S3. The **server** automatically splits the file into 10 MB
            parts and uploads using the S3 Multipart Upload API. Best for files **larger than 100 MB**.
            No client-side chunking needed.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Large file uploaded successfully",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "message": "Large file uploaded successfully",
                      "key": "uploads/large-video.mp4",
                      "url": "https://your-bucket.s3.amazonaws.com/uploads/large-video.mp4"
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Upload failed or aborted", content = @Content)
    })
    @PostMapping(value = "/upload/large", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> largeUpload(
            @Parameter(description = "Large file to upload", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Destination S3 key. Defaults to uploads/<filename>")
            @RequestParam(value = "key", required = false) String key) throws IOException {

        String s3Key = resolveKey(key, file.getOriginalFilename());
        String url = s3Service.streamingMultipartUpload(file, s3Key);

        return ResponseEntity.ok(Map.of(
                "message", "Large file uploaded successfully",
                "key", s3Key,
                "url", url
        ));
    }

    // ── 3. Initiate Multipart ─────────────────────────────────────────────────

    @Operation(
        summary = "Initiate multipart upload — Step 1",
        description = """
            Starts a client-driven multipart upload session.
            Returns an `uploadId` that **must** be passed to every subsequent upload-part and complete call.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Multipart upload initiated",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
                      "key": "uploads/file.zip",
                      "message": "Multipart upload initiated. Use uploadId to upload parts."
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Failed to initiate", content = @Content)
    })
    @PostMapping("/multipart/initiate")
    public ResponseEntity<Map<String, String>> initiateMultipartUpload(
            @Parameter(description = "Destination S3 key (e.g. uploads/file.zip)", required = true)
            @RequestParam("key") String key) {

        String uploadId = s3Service.initiateMultipartUpload(key);

        return ResponseEntity.ok(Map.of(
                "uploadId", uploadId,
                "key", key,
                "message", "Multipart upload initiated. Use uploadId to upload parts."
        ));
    }

    // ── 4. Upload Part ────────────────────────────────────────────────────────

    @Operation(
        summary = "Upload a single part — Step 2",
        description = """
            Uploads one chunk of a multipart upload. Parts must be **≥ 5 MB** except the last part.
            Save the returned `eTag` — it is required to complete the upload.
            Maximum 10,000 parts per upload.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Part uploaded successfully",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "partNumber": "1",
                      "eTag": "\\"d8e8fca2dc0f896fd7cb4cb0031ba249\\"",
                      "message": "Part uploaded. Save the eTag for the complete call."
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Part upload failed", content = @Content)
    })
    @PostMapping(value = "/multipart/upload-part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadPart(
            @Parameter(description = "Binary chunk data (≥ 5 MB except last part)", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Same S3 key used in initiate", required = true)
            @RequestParam("key") String key,
            @Parameter(description = "uploadId from the initiate response", required = true)
            @RequestParam("uploadId") String uploadId,
            @Parameter(description = "1-based part number (max 10,000)", required = true, example = "1")
            @RequestParam("partNumber") int partNumber) throws IOException {

        String eTag = s3Service.uploadPart(uploadId, key, partNumber, file);

        return ResponseEntity.ok(Map.of(
                "partNumber", String.valueOf(partNumber),
                "eTag", eTag,
                "message", "Part uploaded. Save the eTag for the complete call."
        ));
    }

    // ── 5. Complete Multipart ─────────────────────────────────────────────────

    @Operation(
        summary = "Complete multipart upload — Step 3",
        description = """
            Finalizes the multipart upload by assembling all uploaded parts into a single S3 object.
            Provide the `uploadId` and the list of all `{ partNumber, eTag }` pairs collected from Step 2.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Multipart upload completed",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "message": "Multipart upload completed successfully",
                      "key": "uploads/file.zip",
                      "url": "https://your-bucket.s3.amazonaws.com/uploads/file.zip"
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Failed to complete", content = @Content)
    })
    @PostMapping("/multipart/complete")
    public ResponseEntity<Map<String, String>> completeMultipartUpload(
            @Parameter(description = "Destination S3 key", required = true)
            @RequestParam("key") String key,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "uploadId and list of { partNumber, eTag } from each uploaded part",
                content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(value = """
                        {
                          "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
                          "parts": [
                            { "partNumber": 1, "eTag": "\\"abc123\\"" },
                            { "partNumber": 2, "eTag": "\\"def456\\"" }
                          ]
                        }""")))
            @RequestBody CompleteUploadRequest request) {

        String url = s3Service.completeMultipartUpload(key, request);

        return ResponseEntity.ok(Map.of(
                "message", "Multipart upload completed successfully",
                "key", key,
                "url", url
        ));
    }

    // ── 6. Abort Multipart ────────────────────────────────────────────────────

    @Operation(
        summary = "Abort multipart upload",
        description = """
            Aborts an in-progress multipart upload and frees all stored parts.
            **Always call this on failure** to avoid storage charges for incomplete uploads.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Upload aborted successfully",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "message": "Multipart upload aborted successfully",
                      "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
                      "key": "uploads/file.zip"
                    }"""))),
        @ApiResponse(responseCode = "500", description = "Abort failed", content = @Content)
    })
    @DeleteMapping("/multipart/abort")
    public ResponseEntity<Map<String, String>> abortMultipartUpload(
            @Parameter(description = "uploadId to abort", required = true)
            @RequestParam("uploadId") String uploadId,
            @Parameter(description = "S3 key of the upload to abort", required = true)
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
