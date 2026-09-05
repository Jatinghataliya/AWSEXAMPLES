package com.aws.s3.service;

import com.aws.s3.dto.CompleteUploadRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class S3Service {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    /** Minimum part size: 5 MB (AWS requirement for all parts except the last). */
    private static final int PART_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    public S3Service(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    // ── 1. Simple Upload (files < 100 MB) ────────────────────────────────────

    /**
     * Uploads a file directly to S3 in a single PUT request.
     * Best for files smaller than 100 MB.
     *
     * @param file    the uploaded multipart file
     * @param s3Key   destination key in S3
     * @return public URL of the uploaded object
     */
    public String simpleUpload(MultipartFile file, String s3Key) throws IOException {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .contentType(file.getContentType())
                        .contentLength(file.getSize())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
        );
        return buildS3Url(s3Key);
    }

    // ── 2. Multipart Upload — Step 1: Initiate ────────────────────────────────

    /**
     * Initiates a multipart upload and returns the uploadId.
     * The client must use this uploadId for all subsequent UploadPart and Complete calls.
     *
     * @param s3Key destination key in S3
     * @return uploadId string from AWS
     */
    public String initiateMultipartUpload(String s3Key) {
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build()
        );
        return response.uploadId();
    }

    // ── 3. Multipart Upload — Step 2: Upload Part ─────────────────────────────

    /**
     * Uploads a single part of a multipart upload.
     *
     * @param uploadId   the uploadId from initiateMultipartUpload
     * @param s3Key      destination key in S3
     * @param partNumber part number (1-based, max 10000)
     * @param file       the chunk/part file data
     * @return ETag of the uploaded part (needed for CompleteMultipartUpload)
     */
    public String uploadPart(String uploadId, String s3Key, int partNumber,
                             MultipartFile file) throws IOException {
        UploadPartResponse response = s3Client.uploadPart(
                UploadPartRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(file.getSize())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize())
        );
        return response.eTag();
    }

    // ── 4. Multipart Upload — Step 3: Complete ────────────────────────────────

    /**
     * Finalizes the multipart upload by assembling all uploaded parts.
     *
     * @param s3Key   destination key in S3
     * @param request contains uploadId and list of {partNumber, eTag} pairs
     * @return public URL of the assembled object
     */
    public String completeMultipartUpload(String s3Key, CompleteUploadRequest request) {
        List<CompletedPart> completedParts = request.parts().stream()
                .map(p -> CompletedPart.builder()
                        .partNumber(p.partNumber())
                        .eTag(p.eTag())
                        .build())
                .toList();

        s3Client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .uploadId(request.uploadId())
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(completedParts)
                                .build())
                        .build()
        );
        return buildS3Url(s3Key);
    }

    // ── 5. Multipart Upload — Abort ───────────────────────────────────────────

    /**
     * Aborts an in-progress multipart upload, freeing all stored parts.
     * Always call this on failure to avoid storage charges for incomplete uploads.
     *
     * @param uploadId the uploadId to abort
     * @param s3Key    destination key in S3
     */
    public void abortMultipartUpload(String uploadId, String s3Key) {
        s3Client.abortMultipartUpload(
                AbortMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .uploadId(uploadId)
                        .build()
        );
    }

    // ── 6. Server-Side Streaming Multipart Upload (single call) ───────────────

    /**
     * Streams a large file from the HTTP request and uploads it to S3
     * using the Multipart Upload API — all in one API call.
     * Suitable for large files (> 100 MB) without client-side chunking.
     *
     * @param file  the uploaded multipart file
     * @param s3Key destination key in S3
     * @return public URL of the uploaded object
     */
    public String streamingMultipartUpload(MultipartFile file, String s3Key) throws IOException {
        String uploadId = initiateMultipartUpload(s3Key);
        List<CompletedPart> completedParts = new ArrayList<>();

        try (InputStream inputStream = file.getInputStream()) {
            byte[] buffer = new byte[PART_SIZE_BYTES];
            int partNumber = 1;
            int bytesRead;

            while ((bytesRead = inputStream.readNBytes(buffer, 0, PART_SIZE_BYTES)) > 0) {
                UploadPartResponse partResponse = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucketName)
                                .key(s3Key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) bytesRead)
                                .build(),
                        RequestBody.fromByteBuffer(java.nio.ByteBuffer.wrap(buffer, 0, bytesRead))
                );

                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(partResponse.eTag())
                        .build());

                partNumber++;
            }
        } catch (Exception e) {
            abortMultipartUpload(uploadId, s3Key);
            throw new RuntimeException("Multipart upload failed and was aborted: " + e.getMessage(), e);
        }

        s3Client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(completedParts)
                                .build())
                        .build()
        );

        return buildS3Url(s3Key);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String buildS3Url(String s3Key) {
        return String.format("https://%s.s3.amazonaws.com/%s", bucketName, s3Key);
    }
}
