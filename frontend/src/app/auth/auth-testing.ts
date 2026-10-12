import { SessionView } from './auth.service';

/** Apoio aos testes de autenticação. */
export const SESSAO: SessionView = {
  displayName: 'Ana',
  idleTimeoutSeconds: 1800,
  idleExpiresAt: '2026-10-12T18:30:00Z',
  absoluteExpiresAt: '2026-10-13T06:00:00Z',
};

export function comCookieCsrf(presente: boolean): void {
  document.cookie = presente
    ? 'XSRF-TOKEN=token; path=/'
    : 'XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
}
