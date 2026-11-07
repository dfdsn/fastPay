import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { GOOGLE_RESULT_MESSAGE } from './consumer.service';

/** Mensagem do resultado do login Google na volta para a tela ({@code ?google=}), H27. */
@Component({
  selector: 'fp-google-result',
  template: `
    <div role="status" aria-live="polite">
      @if (message; as text) {
        <p class="google-result">{{ text }}</p>
      }
    </div>
  `,
  styles: `
    .google-result {
      background: #f1f4f9;
      padding: 8px 12px;
      border-radius: 4px;
    }
  `,
})
export class GoogleResult {
  protected readonly message =
    GOOGLE_RESULT_MESSAGE[inject(ActivatedRoute).snapshot.queryParamMap.get('google') ?? ''] ?? null;
}
