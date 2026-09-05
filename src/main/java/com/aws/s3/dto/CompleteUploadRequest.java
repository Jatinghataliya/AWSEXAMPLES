package com.aws.s3.dto;

import java.util.List;

/** Request body for completing a multipart upload. */
public record CompleteUploadRequest(String uploadId, List<PartDetail> parts) {

    public record PartDetail(int partNumber, String eTag) {}
}
