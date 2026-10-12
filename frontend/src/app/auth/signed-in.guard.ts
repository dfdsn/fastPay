import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Navegação: rotas da equipe só com sessão confirmada pelo servidor; sem sessão, vai para o login.
 * Não é autorização: o backend exige a sessão em cada operação.
 */
export const signedInGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.session()) {
    return true;
  }
  return auth.refresh().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/entrar']))),
  );
};
