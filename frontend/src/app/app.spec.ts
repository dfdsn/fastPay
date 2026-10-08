import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App', () => {
  it('exibe a marca no cabeçalho e uma região principal', () => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('header mat-toolbar')?.textContent).toContain('fastPay');
    expect(element.querySelector('main')).not.toBeNull();
  });
});
