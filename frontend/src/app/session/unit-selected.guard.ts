import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { UnitContextService } from './unit-context.service';

/**
 * Navegação: só entra em rotas da unidade com uma unidade ativa confirmada pelo servidor. Não é
 * autorização; o backend revalida o vínculo em cada operação.
 */
export const unitSelectedGuard: CanActivateFn = () => {
  const context = inject(UnitContextService);
  const router = inject(Router);
  if (context.current()) {
    return true;
  }
  return context.refresh().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/unidades']))),
  );
};
