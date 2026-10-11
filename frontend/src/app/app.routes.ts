import { Routes } from '@angular/router';

import { unitSelectedGuard } from './session/unit-selected.guard';

export const routes: Routes = [
  {
    path: '',
    title: 'fastPay',
    loadComponent: () => import('./status/status-page').then((m) => m.StatusPage),
  },
  {
    path: 'unidades',
    title: 'Escolha a unidade · fastPay',
    loadComponent: () => import('./session/unit-selection-page').then((m) => m.UnitSelectionPage),
  },
  {
    path: 'inicio',
    title: 'Início · fastPay',
    canActivate: [unitSelectedGuard],
    loadComponent: () => import('./session/unit-home-page').then((m) => m.UnitHomePage),
  },
  { path: '**', redirectTo: '' },
];
