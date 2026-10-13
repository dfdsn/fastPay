import { Routes } from '@angular/router';

import { signedInGuard } from './auth/signed-in.guard';
import { unitSelectedGuard } from './session/unit-selected.guard';

export const routes: Routes = [
  {
    path: '',
    title: 'fastPay',
    loadComponent: () => import('./status/status-page').then((m) => m.StatusPage),
  },
  {
    path: 'entrar',
    title: 'Entrar · fastPay',
    loadComponent: () => import('./auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'esqueci-a-senha',
    title: 'Esqueci minha senha · fastPay',
    loadComponent: () => import('./auth/forgot-password-page').then((m) => m.ForgotPasswordPage),
  },
  {
    path: 'redefinir-senha',
    title: 'Criar nova senha · fastPay',
    loadComponent: () => import('./auth/reset-password-page').then((m) => m.ResetPasswordPage),
  },
  {
    path: 'verificacao',
    title: 'Verificação em duas etapas · fastPay',
    loadComponent: () => import('./auth/mfa-verify-page').then((m) => m.MfaVerifyPage),
  },
  {
    path: 'configurar-verificacao',
    title: 'Configurar verificação em duas etapas · fastPay',
    loadComponent: () => import('./auth/mfa-setup-page').then((m) => m.MfaSetupPage),
  },
  {
    path: 'unidades',
    title: 'Escolha a unidade · fastPay',
    canActivate: [signedInGuard],
    loadComponent: () => import('./session/unit-selection-page').then((m) => m.UnitSelectionPage),
  },
  {
    path: 'inicio',
    title: 'Início · fastPay',
    canActivate: [signedInGuard, unitSelectedGuard],
    loadComponent: () => import('./session/unit-home-page').then((m) => m.UnitHomePage),
  },
  { path: '**', redirectTo: '' },
];
