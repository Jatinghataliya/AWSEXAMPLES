package com.aws.s3;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates AWS S3 Large File Upload using Multipart Upload API.
 *
 * Flow:
 *  1. CreateMultipartUpload  → get uploadId
 *  2. UploadPart × N         → collect ETags
 *  3. CompleteMultipartUpload → finalize the object
 *  4. AbortMultipartUpload   → called on any failure to avoid storage charges
 *
 * Minimum part size : 5 MB  (except the last part)
 * Maximum parts     : 10,000
 * Maximum file size : 5 TB
 */
public class S3MultipartUpload {

    // ── Configuration ────────────────────────────────────────────────────────
    private static final String BUCKET_NAME = "your-bucket-name";   // <-- change this
    private static final String S3_KEY      = "uploads/large-file.zip"; // destination key in S3
    private static final Region AWS_REGION  = Region.US_EAST_1;

    /** 10 MB per part — must be ≥ 5 MB for all but the last part */
    private static final int PART_SIZE_BYTES = 10 * 1024 * 1024;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        String filePath = args.length > 0 ? args[0] : "large-file.zip"; // pass file path as arg
        File file = new File(filePath);

        if (!file.exists()) {
            System.err.println("File not found: " + file.getAbsolutePath());
            System.exit(1);
        }

        S3Client s3 = S3Client.builder()
                .region(AWS_REGION)
                .build();

        try {
            uploadLargeFile(s3, file);
        } finally {
            s3.close();
        }
    }

    /**
     * Uploads a large file to S3 using the Multipart Upload API.
     *
     * @param s3   pre-built S3Client
     * @param file the local file to upload
     */
    public static void uploadLargeFile(S3Client s3, File file) {
        System.out.printf("Starting multipart upload for: %s (%.2f MB)%n",
                file.getName(), file.length() / 1024.0 / 1024.0);

        // ── Step 1: Initiate multipart upload ────────────────────────────────
        String uploadId = initiateMultipartUpload(s3);
        System.out.println("Multipart upload initiated. UploadId: " + uploadId);

        List<CompletedPart> completedParts = new ArrayList<>();

        try {
            // ── Step 2: Upload file in parts ──────────────────────────────────
            completedParts = uploadParts(s3, file, uploadId);

            // ── Step 3: Complete the upload ───────────────────────────────────
            completeMultipartUpload(s3, uploadId, completedParts);
            System.out.println("✅ Multipart upload completed successfully!");
            System.out.printf("   s3://%s/%s%n", BUCKET_NAME, S3_KEY);

        } catch (Exception e) {
            // ── Step 4: Abort on failure to avoid orphaned part charges ───────
            System.err.println("❌ Upload failed: " + e.getMessage());
            abortMultipartUpload(s3, uploadId);
            throw new RuntimeException("Multipart upload aborted due to error.", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Step 1 — CreateMultipartUpload */
    private static String initiateMultipartUpload(S3Client s3) {
        CreateMultipartUploadResponse response = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(S3_KEY)
                        .build()
        );
        return response.uploadId();
    }

    /** Step 2 — UploadPart in a loop, collecting ETags */
    private static List<CompletedPart> uploadParts(S3Client s3, File file, String uploadId)
            throws IOException {

        List<CompletedPart> completedParts = new ArrayList<>();
        long fileSize  = file.length();
        long position  = 0;
        int  partNumber = 1;

        try (FileInputStream fis = new FileInputStream(file)) {
            while (position < fileSize) {
                int readSize = (int) Math.min(PART_SIZE_BYTES, fileSize - position);
                byte[] buffer = new byte[readSize];
                int bytesRead = fis.read(buffer, 0, readSize);

                if (bytesRead <= 0) break;

                System.out.printf("  Uploading part %d / %d  (%.2f MB)...%n",
                        partNumber,
                        calculateTotalParts(fileSize),
                        readSize / 1024.0 / 1024.0);

                UploadPartResponse partResponse = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(BUCKET_NAME)
                                .key(S3_KEY)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) bytesRead)
                                .build(),
                        RequestBody.fromByteBuffer(ByteBuffer.wrap(buffer, 0, bytesRead))
                );

                completedParts.add(
                        CompletedPart.builder()
                                .partNumber(partNumber)
                                .eTag(partResponse.eTag())
                                .build()
                );

                position += bytesRead;
                partNumber++;
            }
        }

        System.out.printf("  All %d parts uploaded.%n", completedParts.size());
        return completedParts;
    }

    /** Step 3 — CompleteMultipartUpload */
    private static void completeMultipartUpload(S3Client s3, String uploadId,
                                                 List<CompletedPart> completedParts) {
        s3.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                        .bucket(BUCKET_NAME)
                        .key(S3_KEY)
                        .uploadId(uploadId)
                        .multipartUpload(
                                CompletedMultipartUpload.builder()
                                        .parts(completedParts)
                                        .build()
                        )
                        .build()
        );
    }

    /** Step 4 — AbortMultipartUpload (called on failure to stop accumulating charges) */
    private static void abortMultipartUpload(S3Client s3, String uploadId) {
        try {
            s3.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(S3_KEY)
                            .uploadId(uploadId)
                            .build()
            );
            System.out.println("Multipart upload aborted — no storage charges will apply.");
        } catch (Exception abortEx) {
            System.err.println("Warning: failed to abort upload: " + abortEx.getMessage());
        }
    }

    /** Helper — estimate total number of parts for progress display */
    private static int calculateTotalParts(long fileSize) {
        return (int) Math.ceil((double) fileSize / PART_SIZE_BYTES);
    }
}
