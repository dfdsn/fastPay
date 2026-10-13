import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AuthService, SessionView, stageRoute } from './auth.service';

/**
 * Navegação: rotas da equipe só com sessão confirmada pelo servidor; sem sessão, vai para o login, e
 * com a verificação em duas etapas pendente, para a tela do MFA (H09). Não é autorização: o backend
 * exige a sessão completa em cada operação.
 */
export const signedInGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const decide = (session: SessionView) =>
    session.stage === 'AUTENTICADO' ? true : router.createUrlTree([stageRoute(session.stage)]);
  const known = auth.session();
  if (known) {
    return decide(known);
  }
  return auth.refresh().pipe(
    map(decide),
    catchError(() => of(router.createUrlTree(['/entrar']))),
  );
};
