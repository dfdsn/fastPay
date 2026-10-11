import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { UNIT_LOST_CODES, UnitContextService, problemCode } from './unit-context.service';

/**
 * Se o servidor disser que a unidade da sessão não vale mais (vínculo desativado, nenhuma escolhida),
 * limpa os dados locais da unidade e volta para a seleção. O erro segue para quem fez a chamada.
 */
export const unitContextInterceptor: HttpInterceptorFn = (request, next) => {
  const context = inject(UnitContextService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      const code = problemCode(error);
      if (code && UNIT_LOST_CODES.has(code)) {
        context.clear();
        void router.navigate(['/unidades']);
      }
      return throwError(() => error);
    }),
  );
};
