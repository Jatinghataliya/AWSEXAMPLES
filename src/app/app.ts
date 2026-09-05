import { Component } from '@angular/core';
import { UploadComponent } from './components/upload/upload';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [UploadComponent],
  template: `<app-upload></app-upload>`,
  styles: [`
    :host { display: block; min-height: 100vh; background: #f4f6f9; }
  `]
})
export class App {}
