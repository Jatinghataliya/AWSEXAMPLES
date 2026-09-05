# AWS S3 Multipart Upload — Spring Boot REST API

A Spring Boot REST API that demonstrates uploading large files to **AWS S3** using the **Multipart Upload API** (AWS SDK v2).

---

## 📐 Architecture

```
Client
  │
  ▼
Spring Boot REST API (port 8080)
  │
  ├── S3Controller   — exposes REST endpoints
  ├── S3Service      — multipart upload logic
  ├── S3Config       — S3Client bean (credentials)
  └── AWS S3 Bucket  — stores the uploaded files
```

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- AWS Account with an S3 bucket
- AWS credentials configured

### AWS Credentials Setup

**Option 1 — AWS CLI (recommended for local dev):**
```bash
aws configure
# Enter: Access Key, Secret Key, Region
```

**Option 2 — application.properties:**
```properties
aws.access-key=YOUR_ACCESS_KEY
aws.secret-key=YOUR_SECRET_KEY
```

**Option 3 — Environment variables:**
```bash
export AWS_ACCESS_KEY_ID=your_key
export AWS_SECRET_ACCESS_KEY=your_secret
```

### Configuration

Edit [`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
aws.region=us-east-1
aws.s3.bucket-name=your-bucket-name   # <-- set your bucket name

# Optional — leave blank to use DefaultCredentialsProvider
aws.access-key=
aws.secret-key=
```

### Run the Application

```bash
mvn spring-boot:run
```

Server starts at: `http://localhost:8080`

---

## 🌐 REST API Endpoints

### 1. Simple Upload *(files < 100 MB)*

```
POST /api/s3/upload/simple
```

| Parameter | Type | Required | Description |
|---|---|---|---|
| `file` | `MultipartFile` | ✅ | The file to upload |
| `key` | `String` | ❌ | S3 destination key (defaults to `uploads/<filename>`) |

```bash
curl -X POST http://localhost:8080/api/s3/upload/simple \
     -F "file=@/path/to/document.pdf" \
     -F "key=uploads/document.pdf"
```

**Response:**
```json
{
  "message": "File uploaded successfully",
  "key": "uploads/document.pdf",
  "url": "https://your-bucket.s3.amazonaws.com/uploads/document.pdf"
}
```

---

### 2. Large File Upload *(files > 100 MB)*

```
POST /api/s3/upload/large
```

Server automatically splits the file into 10 MB parts and uploads using S3 Multipart API.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `file` | `MultipartFile` | ✅ | The large file to upload |
| `key` | `String` | ❌ | S3 destination key |

```bash
curl -X POST http://localhost:8080/api/s3/upload/large \
     -F "file=@/path/to/large-video.mp4" \
     -F "key=uploads/large-video.mp4"
```

**Response:**
```json
{
  "message": "Large file uploaded successfully",
  "key": "uploads/large-video.mp4",
  "url": "https://your-bucket.s3.amazonaws.com/uploads/large-video.mp4"
}
```

---

### 3. Client-Driven Multipart Upload

Use these 3 endpoints when you want to **control chunking on the client side** (e.g., browser-based resumable uploads).

#### Step 1 — Initiate

```
POST /api/s3/multipart/initiate?key=uploads/file.zip
```

```bash
curl -X POST "http://localhost:8080/api/s3/multipart/initiate?key=uploads/file.zip"
```

**Response:**
```json
{
  "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
  "key": "uploads/file.zip",
  "message": "Multipart upload initiated. Use uploadId to upload parts."
}
```

#### Step 2 — Upload Part

```
POST /api/s3/multipart/upload-part
```

| Parameter | Type | Description |
|---|---|---|
| `file` | `MultipartFile` | Binary chunk (≥ 5 MB except last) |
| `key` | `String` | Same key from initiate |
| `uploadId` | `String` | From initiate response |
| `partNumber` | `int` | 1-based index (max 10,000) |

```bash
curl -X POST "http://localhost:8080/api/s3/multipart/upload-part" \
     -F "file=@chunk_001.bin" \
     -F "key=uploads/file.zip" \
     -F "uploadId=VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t" \
     -F "partNumber=1"
```

**Response:**
```json
{
  "partNumber": "1",
  "eTag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"",
  "message": "Part uploaded. Save the eTag for the complete call."
}
```

#### Step 3 — Complete

```
POST /api/s3/multipart/complete?key=uploads/file.zip
```

```bash
curl -X POST "http://localhost:8080/api/s3/multipart/complete?key=uploads/file.zip" \
     -H "Content-Type: application/json" \
     -d '{
       "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
       "parts": [
         { "partNumber": 1, "eTag": "\"d8e8fca2dc0f896fd7cb4cb0031ba249\"" },
         { "partNumber": 2, "eTag": "\"abc123def456abc123def456abc12345\"" }
       ]
     }'
```

**Response:**
```json
{
  "message": "Multipart upload completed successfully",
  "key": "uploads/file.zip",
  "url": "https://your-bucket.s3.amazonaws.com/uploads/file.zip"
}
```

---

### 4. Abort Multipart Upload

```
DELETE /api/s3/multipart/abort?key=uploads/file.zip&uploadId=xxxx
```

Call this whenever an upload fails to **avoid storage charges** for incomplete parts.

```bash
curl -X DELETE "http://localhost:8080/api/s3/multipart/abort?key=uploads/file.zip&uploadId=VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t"
```

**Response:**
```json
{
  "message": "Multipart upload aborted successfully",
  "uploadId": "VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5t",
  "key": "uploads/file.zip"
}
```

---

## 📋 Endpoint Summary

| Method | Endpoint | Use Case |
|---|---|---|
| `POST` | `/api/s3/upload/simple` | Single-call upload < 100 MB |
| `POST` | `/api/s3/upload/large` | Server-side multipart > 100 MB |
| `POST` | `/api/s3/multipart/initiate` | Client-driven: Step 1 — get `uploadId` |
| `POST` | `/api/s3/multipart/upload-part` | Client-driven: Step 2 — upload chunk |
| `POST` | `/api/s3/multipart/complete` | Client-driven: Step 3 — finalize |
| `DELETE` | `/api/s3/multipart/abort` | Cancel upload & free storage |

---

## 🔑 AWS S3 Multipart Upload Rules

| Rule | Detail |
|---|---|
| Minimum part size | **5 MB** (except the last part) |
| Maximum parts | **10,000** per upload |
| Maximum file size | **5 TB** |
| Always abort on failure | Incomplete uploads **still incur storage charges** |

---

## 🛠️ Tech Stack

| Technology | Version |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.3 |
| AWS SDK v2 — S3 | 2.25.0 |
| AWS S3 Transfer Manager | 2.25.0 |
| Maven | 3.8+ |

---

## 📁 Project Structure

```
S3MultipartUpload/
├── pom.xml
└── src/
    └── main/
        ├── java/com/aws/s3/
        │   ├── S3Application.java          ← Spring Boot entry point
        │   ├── config/
        │   │   └── S3Config.java           ← S3Client bean
        │   ├── dto/
        │   │   └── CompleteUploadRequest.java
        │   ├── service/
        │   │   └── S3Service.java          ← upload logic
        │   └── controller/
        │       └── S3Controller.java       ← REST endpoints
        └── resources/
            └── application.properties
```

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
