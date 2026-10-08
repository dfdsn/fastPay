import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'fastPay',
    loadComponent: () => import('./status/status-page').then((m) => m.StatusPage),
  },
  { path: '**', redirectTo: '' },
];
