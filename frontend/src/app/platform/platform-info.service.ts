import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface ApplicationInfo {
  name: string;
  version: string;
  environment: string;
}

@Injectable({ providedIn: 'root' })
export class PlatformInfoService {
  private readonly http = inject(HttpClient);

  info(): Observable<ApplicationInfo> {
    return this.http.get<ApplicationInfo>('/api/v1/platform/info');
  }
}
