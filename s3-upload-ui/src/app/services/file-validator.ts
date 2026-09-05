import { Injectable } from '@angular/core';

export interface ValidationResult {
  valid: boolean;
  error?: string;
}

/**
 * FileValidatorService
 *
 * Performs client-side security checks before any file is uploaded to S3.
 * These checks are a first line of defence — server-side validation should
 * always be the authoritative guard.
 *
 * Checks performed:
 *  1. Blocked extension list  — rejects known dangerous extensions
 *  2. MIME type allowlist      — only permits safe, expected MIME types
 *  3. Magic bytes (file signature) — detects disguised files by reading
 *     the first bytes of the file, independent of filename/extension
 *  4. File size limit          — rejects files above the configured max
 *  5. Double extension attack  — e.g. "invoice.pdf.exe"
 *  6. Null-byte injection      — filenames containing null chars
 */
@Injectable({ providedIn: 'root' })
export class FileValidatorService {

  // ── Config ──────────────────────────────────────────────────────────────────

  /** Maximum allowed file size: 5 GB */
  private readonly MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 * 1024;

  /** Extensions that are always blocked regardless of MIME type */
  private readonly BLOCKED_EXTENSIONS = new Set([
    // Executables
    'exe', 'com', 'bat', 'cmd', 'msi', 'dll', 'sys', 'drv',
    // Scripts
    'sh', 'bash', 'ps1', 'psm1', 'psd1', 'vbs', 'vbe', 'js', 'jse',
    'ws', 'wsf', 'wsc', 'wsh', 'hta',
    // Compiled / bytecode
    'class', 'jar', 'pyc', 'pyo',
    // Archives that commonly contain malware
    'scr', 'pif', 'cpl', 'reg',
    // Office macros
    'xlsm', 'xlsb', 'xltm', 'docm', 'dotm', 'pptm', 'potm',
  ]);

  /** MIME types that are explicitly allowed */
  private readonly ALLOWED_MIME_TYPES = new Set([
    // Documents
    'application/pdf',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.ms-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-powerpoint',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    // Images
    'image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/svg+xml',
    'image/bmp', 'image/tiff',
    // Video
    'video/mp4', 'video/mpeg', 'video/quicktime', 'video/x-msvideo',
    'video/x-matroska', 'video/webm',
    // Audio
    'audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/flac', 'audio/mp4',
    // Archives (safe ones)
    'application/zip', 'application/x-tar', 'application/gzip',
    'application/x-7z-compressed', 'application/x-rar-compressed',
    // Text / Code
    'text/plain', 'text/csv', 'text/html', 'text/xml', 'application/json',
    'application/xml',
  ]);

  /**
   * Magic byte signatures for known dangerous file types.
   * Key: hex string of the magic bytes.
   * Value: human-readable label.
   */
  private readonly DANGEROUS_MAGIC_BYTES: { signature: number[]; label: string }[] = [
    { signature: [0x4D, 0x5A],                               label: 'Windows Executable (MZ)' },
    { signature: [0x7F, 0x45, 0x4C, 0x46],                   label: 'Linux ELF Executable' },
    { signature: [0xCA, 0xFE, 0xBA, 0xBE],                   label: 'Java Class File' },
    { signature: [0x50, 0x4B, 0x03, 0x04],                   label: 'ZIP / JAR Archive' },
    { signature: [0x23, 0x21],                                label: 'Script Shebang (#!)' },
    { signature: [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1], label: 'OLE2 Compound (legacy Office/macro)' },
  ];

  // ── Public API ──────────────────────────────────────────────────────────────

  /**
   * Runs all synchronous checks (extension, MIME, size, double-extension, null-byte).
   * Call this immediately when a file is selected.
   */
  validateSync(file: File): ValidationResult {
    const nullByteCheck = this.checkNullByte(file.name);
    if (!nullByteCheck.valid) return nullByteCheck;

    const doubleExtCheck = this.checkDoubleExtension(file.name);
    if (!doubleExtCheck.valid) return doubleExtCheck;

    const extCheck = this.checkExtension(file.name);
    if (!extCheck.valid) return extCheck;

    const mimeCheck = this.checkMimeType(file.type);
    if (!mimeCheck.valid) return mimeCheck;

    const sizeCheck = this.checkFileSize(file.size);
    if (!sizeCheck.valid) return sizeCheck;

    return { valid: true };
  }

  /**
   * Reads the first 8 bytes of the file and checks against known dangerous
   * magic byte signatures. Returns a Promise because it requires async file reading.
   */
  validateMagicBytes(file: File): Promise<ValidationResult> {
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = (e) => {
        const buffer = e.target?.result as ArrayBuffer;
        const bytes = new Uint8Array(buffer);

        for (const entry of this.DANGEROUS_MAGIC_BYTES) {
          if (this.startsWith(bytes, entry.signature)) {
            resolve({
              valid: false,
              error: `Blocked: file content matches "${entry.label}" signature. Disguised malicious files are not allowed.`,
            });
            return;
          }
        }
        resolve({ valid: true });
      };
      reader.onerror = () => resolve({ valid: true }); // fail open on read error
      reader.readAsArrayBuffer(file.slice(0, 8));
    });
  }

  // ── Private checks ──────────────────────────────────────────────────────────

  private checkExtension(filename: string): ValidationResult {
    const ext = filename.split('.').pop()?.toLowerCase() ?? '';
    if (this.BLOCKED_EXTENSIONS.has(ext)) {
      return { valid: false, error: `File type ".${ext}" is not allowed for security reasons.` };
    }
    return { valid: true };
  }

  private checkMimeType(mimeType: string): ValidationResult {
    if (!mimeType) {
      return { valid: false, error: 'Cannot determine file type. Upload rejected.' };
    }
    if (!this.ALLOWED_MIME_TYPES.has(mimeType)) {
      return { valid: false, error: `MIME type "${mimeType}" is not permitted.` };
    }
    return { valid: true };
  }

  private checkFileSize(size: number): ValidationResult {
    if (size === 0) {
      return { valid: false, error: 'Empty files are not allowed.' };
    }
    if (size > this.MAX_FILE_SIZE_BYTES) {
      return { valid: false, error: `File exceeds the maximum allowed size of 5 GB.` };
    }
    return { valid: true };
  }

  private checkDoubleExtension(filename: string): ValidationResult {
    const parts = filename.split('.');
    if (parts.length > 2) {
      const secondLastExt = parts[parts.length - 2].toLowerCase();
      if (this.BLOCKED_EXTENSIONS.has(secondLastExt)) {
        return {
          valid: false,
          error: `Double extension attack detected: ".${secondLastExt}" is not permitted as an intermediate extension.`,
        };
      }
    }
    return { valid: true };
  }

  private checkNullByte(filename: string): ValidationResult {
    if (filename.includes('\0')) {
      return { valid: false, error: 'Filename contains null bytes. Upload rejected.' };
    }
    return { valid: true };
  }

  private startsWith(bytes: Uint8Array, signature: number[]): boolean {
    return signature.every((byte, i) => bytes[i] === byte);
  }
}
