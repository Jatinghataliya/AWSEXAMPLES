import { ChangeDetectorRef, Component } from '@angular/core';
import { NgClass } from '@angular/common';
import { FileValidatorService } from '../../services/file-validator';
import { S3UploadService, UploadProgress } from '../../services/s3-upload';

interface UploadItem {
  file: File;
  status: 'pending' | 'validating' | 'uploading' | 'done' | 'error' | 'blocked';
  progress: number;
  error?: string;
  url?: string;
  s3Key?: string;
}

@Component({
  selector: 'app-upload',
  standalone: true,
  imports: [NgClass],
  templateUrl: './upload.html',
  styleUrl: './upload.scss',
})
export class UploadComponent {

  isDragOver = false;
  uploads: UploadItem[] = [];

  constructor(
    private validator: FileValidatorService,
    private uploadService: S3UploadService,
    private cdr: ChangeDetectorRef,
  ) {}

  // ── Drag & Drop ─────────────────────────────────────────────────────────────

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = true;
  }

  onDragLeave(): void {
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = false;
    const files = event.dataTransfer?.files;
    if (files) this.handleFiles(files);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) this.handleFiles(input.files);
    input.value = ''; // reset so same file can be re-selected
  }

  // ── File handling ───────────────────────────────────────────────────────────

  private async handleFiles(fileList: FileList): Promise<void> {
    for (const file of Array.from(fileList)) {
      const item: UploadItem = {
        file,
        status: 'validating',
        progress: 0,
        s3Key: `uploads/${Date.now()}-${file.name}`,
      };
      this.uploads.unshift(item);
      this.cdr.markForCheck();

      // Step 1: synchronous checks (extension, MIME, size, etc.)
      const syncResult = this.validator.validateSync(file);
      if (!syncResult.valid) {
        item.status = 'blocked';
        item.error = syncResult.error;
        this.cdr.markForCheck();
        continue;
      }

      // Step 2: async magic bytes check
      const magicResult = await this.validator.validateMagicBytes(file);
      if (!magicResult.valid) {
        item.status = 'blocked';
        item.error = magicResult.error;
        this.cdr.markForCheck();
        continue;
      }

      // Step 3: upload
      item.status = 'uploading';
      this.cdr.markForCheck();
      this.uploadService.upload(file, item.s3Key).subscribe((progress: UploadProgress) => {
        item.progress = progress.percentage;
        item.status = progress.status === 'done' ? 'done'
                    : progress.status === 'error' ? 'error'
                    : 'uploading';
        if (progress.url) item.url = progress.url;
        if (progress.error) item.error = progress.error;
        this.cdr.markForCheck();
      });
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  formatSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(1))} ${sizes[i]}`;
  }

  removeItem(index: number): void {
    this.uploads.splice(index, 1);
    this.cdr.markForCheck();
  }

  clearAll(): void {
    this.uploads = [];
    this.cdr.markForCheck();
  }

  get hasUploads(): boolean {
    return this.uploads.length > 0;
  }
}
