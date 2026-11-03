import { Component, computed, input, linkedSignal } from '@angular/core';

import { fileUrl } from '../catalog/catalog.service';
import { initials } from './branding.service';

/** Logo da unidade ou, sem logo (ou se a imagem não carregar), as iniciais do nome (FR-67). */
@Component({
  selector: 'fp-unit-logo',
  template: `
    @if (src(); as url) {
      <img [src]="url" [alt]="'Logo de ' + name()" (error)="broken.set(true)" />
    } @else {
      <span class="initials" role="img" [attr.aria-label]="'Logo de ' + name()">{{
        letters()
      }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      width: var(--fp-logo-size, 56px);
      height: var(--fp-logo-size, 56px);
    }
    img {
      width: 100%;
      height: 100%;
      object-fit: contain;
      border-radius: 8px;
    }
    .initials {
      width: 100%;
      height: 100%;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 8px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
      font-weight: 600;
      font-size: calc(var(--fp-logo-size, 56px) * 0.4);
    }
  `,
})
export class UnitLogo {
  readonly name = input.required<string>();
  readonly logoFileId = input<string | null>(null);

  /** Imagem que não carregou (removida em outra aba, sessão perdida): volta às iniciais até trocar o logo. */
  protected readonly broken = linkedSignal({ source: this.logoFileId, computation: () => false });
  protected readonly letters = computed(() => initials(this.name()));
  protected readonly src = computed(() => {
    const id = this.logoFileId();
    return id && !this.broken() ? fileUrl(id) : null;
  });
}
