import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export interface Sector {
  id: string;
  name: string;
  /** Limite de atraso em minutos (H18, FR-22): passou disso desde o envio, a fila destaca o item. */
  delayLimitMinutes: number;
  version: number;
}

export interface Product {
  id: string;
  name: string;
  category: string;
  description: string | null;
  /** Ausente para quem não vê valores (produção). */
  price: number | null;
  /** Ausente: o produto não vai para produção. */
  sectorId: string | null;
  available: boolean;
  photoFileId: string | null;
  version: number;
}

export interface CatalogListing {
  sectors: Sector[];
  products: Product[];
  categories: string[];
  page: number;
  pageSize: number;
  totalProducts: number;
  pricesVisible: boolean;
}

/** O que a pessoa digitou; o preço vai como texto ("12,50") e é validado no servidor sem arredondar. */
export interface ProductInput {
  name: string;
  category: string;
  description: string;
  price: string;
  sectorId: string | null;
}

/** Endereço da imagem da unidade; o servidor confere o vínculo a cada download. */
export function fileUrl(fileId: string): string {
  return `/api/v1/files/${fileId}`;
}

/** Catálogo da unidade da sessão (H13). Cada chamada é autorizada no servidor pela permissão da ação. */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  load(page = 0): Observable<CatalogListing> {
    return this.http.get<CatalogListing>('/api/v1/catalog', {
      params: new HttpParams().set('page', page),
    });
  }

  createSector(name: string): Observable<Sector> {
    return this.auth.withCsrf(() => this.http.post<Sector>('/api/v1/catalog/sectors', { name }));
  }

  renameSector(sector: Sector, name: string): Observable<Sector> {
    return this.auth.withCsrf(() =>
      this.http.put<Sector>(`/api/v1/catalog/sectors/${sector.id}`, {
        name,
        version: sector.version,
      }),
    );
  }

  changeSectorDelay(sector: Sector, delayLimitMinutes: number): Observable<Sector> {
    return this.auth.withCsrf(() =>
      this.http.put<Sector>(`/api/v1/catalog/sectors/${sector.id}`, {
        name: sector.name,
        delayLimitMinutes,
        version: sector.version,
      }),
    );
  }

  archiveSector(sector: Sector): Observable<Sector> {
    return this.auth.withCsrf(() =>
      this.http.post<Sector>(`/api/v1/catalog/sectors/${sector.id}/archive`, {
        version: sector.version,
      }),
    );
  }

  createProduct(input: ProductInput): Observable<Product> {
    return this.auth.withCsrf(() => this.http.post<Product>('/api/v1/catalog/products', input));
  }

  updateProduct(product: Product, input: ProductInput): Observable<Product> {
    return this.auth.withCsrf(() =>
      this.http.put<Product>(`/api/v1/catalog/products/${product.id}`, {
        ...input,
        version: product.version,
      }),
    );
  }

  archiveProduct(product: Product): Observable<Product> {
    return this.auth.withCsrf(() =>
      this.http.post<Product>(`/api/v1/catalog/products/${product.id}/archive`, {
        version: product.version,
      }),
    );
  }

  setAvailability(product: Product, available: boolean): Observable<Product> {
    return this.auth.withCsrf(() =>
      this.http.put<Product>(`/api/v1/catalog/products/${product.id}/availability`, { available }),
    );
  }

  setPhoto(product: Product, file: File): Observable<Product> {
    const body = new FormData();
    body.append('file', file);
    return this.auth.withCsrf(() =>
      this.http.put<Product>(`/api/v1/catalog/products/${product.id}/photo`, body),
    );
  }

  removePhoto(product: Product): Observable<Product> {
    return this.auth.withCsrf(() =>
      this.http.delete<Product>(`/api/v1/catalog/products/${product.id}/photo`),
    );
  }
}
