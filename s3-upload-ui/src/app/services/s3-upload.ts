import { Injectable } from '@angular/core';
import { HttpClient, HttpEventType, HttpRequest } from '@angular/common/http';
import { Observable, Subject } from 'rxjs';
import { filter, map } from 'rxjs/operators';

export interface UploadProgress {
  percentage: number;
  status: 'validating' | 'uploading' | 'done' | 'error';
  url?: string;
  error?: string;
}

@Injectable({ providedIn: 'root' })
export class S3UploadService {

  // In Docker: nginx proxies /api/ → backend container.
  // In local dev: Spring Boot runs on localhost:8080.
  private readonly API_BASE = '/api/s3';

  constructor(private http: HttpClient) {}

  /**
   * Uploads a file to the Spring Boot API with real-time progress reporting.
   * - Files < 100 MB → POST /api/s3/upload/simple
   * - Files >= 100 MB → POST /api/s3/upload/large  (server handles multipart)
   *
   * @param file   the validated file to upload
   * @param s3Key  optional destination S3 key
   * @returns Observable<UploadProgress> emitting progress updates
   */
  upload(file: File, s3Key?: string): Observable<UploadProgress> {
    const subject = new Subject<UploadProgress>();

    const SIZE_THRESHOLD = 100 * 1024 * 1024; // 100 MB
    const endpoint = file.size >= SIZE_THRESHOLD
      ? `${this.API_BASE}/upload/large`
      : `${this.API_BASE}/upload/simple`;

    const formData = new FormData();
    formData.append('file', file);
    if (s3Key) formData.append('key', s3Key);

    const req = new HttpRequest('POST', endpoint, formData, {
      reportProgress: true,
    });

    this.http.request(req).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          const percentage = Math.round((event.loaded / event.total) * 100);
          subject.next({ percentage, status: 'uploading' });
        } else if (event.type === HttpEventType.Response) {
          const body = event.body as { url: string };
          subject.next({ percentage: 100, status: 'done', url: body?.url });
          subject.complete();
        }
      },
      error: (err) => {
        const message = err?.error?.message ?? err?.message ?? 'Upload failed';
        subject.next({ percentage: 0, status: 'error', error: message });
        subject.complete();
      },
    });

    return subject.asObservable();
  }
}
