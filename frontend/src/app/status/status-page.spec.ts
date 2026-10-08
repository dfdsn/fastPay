import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { StatusPage } from './status-page';

describe('StatusPage', () => {
  const infoUrl = '/api/v1/platform/info';
  const okBody = { name: 'fastPay', version: '0.1.0', environment: 'development' };

  let fixture: ComponentFixture<StatusPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const statusText = () => element().querySelector('[role="status"]')?.textContent ?? '';
  const button = () => element().querySelector<HTMLButtonElement>('button');

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [StatusPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StatusPage);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('anuncia a verificação em região de status enquanto aguarda o servidor', () => {
    expect(statusText()).toContain('Verificando o servidor');
    http.expectOne(infoUrl);
  });

  it('mostra versão e ambiente quando o servidor responde', () => {
    http.expectOne(infoUrl).flush(okBody);
    fixture.detectChanges();

    expect(statusText()).toContain('Servidor disponível.');
    expect(statusText()).toContain('Versão 0.1.0 · ambiente development');
    expect(button()).toBeNull();
  });

  it('mostra erro em português e permite tentar novamente', () => {
    http.expectOne(infoUrl).flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(statusText()).toContain('Não foi possível contatar o servidor');
    expect(button()?.textContent).toContain('Tentar novamente');

    button()?.click();
    fixture.detectChanges();
    expect(statusText()).toContain('Verificando o servidor');

    http.expectOne(infoUrl).flush(okBody);
    fixture.detectChanges();
    expect(statusText()).toContain('Servidor disponível.');
  });
});
