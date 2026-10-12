import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { problemCode } from '../session/unit-context.service';
import { AuthService } from './auth.service';

/**
 * Sessão vencida, encerrada ou revogada (401 {@code SESSAO_AUSENTE}): esquece os dados locais e volta
 * para o login. O erro segue para quem fez a chamada.
 */
export const sessionExpiredInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        problemCode(error) === 'SESSAO_AUSENTE' &&
        auth.session()
      ) {
        auth.forget();
        void router.navigate(['/entrar']);
      }
      return throwError(() => error);
    }),
  );
};
