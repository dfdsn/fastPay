import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { BindingPage } from './binding-page';

describe('BindingPage', () => {
  let http: HttpTestingController;

  function abrir(fragment: string | null) {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [BindingPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BindingPage);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('mostra de quem é a comanda sem consumir e confirma só no botão', () => {
    const fixture = abrir('tok');
    const router = TestBed.inject(Router);
    const navegar = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const previa = http.expectOne('/api/v1/guest/bindings/preview');
    expect(previa.request.body).toEqual({ token: 'tok' });
    previa.flush({ establishmentName: 'Bar da Praia', number: 7, name: 'Ana', expiresAt: '' });
    fixture.detectChanges();
    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('Bar da Praia');
    expect(texto).toContain('Comanda 7 · Ana');
    http.expectNone('/api/v1/guest/bindings/confirm');

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    const confirmacao = http.expectOne('/api/v1/guest/bindings/confirm');
    expect(confirmacao.request.body).toEqual({ token: 'tok' });
    confirmacao.flush({ tabId: 't-7' });
    expect(navegar).toHaveBeenCalledWith(['/minha-comanda', 't-7']);
  });

  it('QR vencido ou usado: orienta a pedir outro', () => {
    const fixture = abrir('tok');
    http.expectOne('/api/v1/guest/bindings/preview').flush(
      { code: 'QR_INVALIDO', detail: 'Este QR não vale mais. Peça ao atendente um QR novo.' },
      { status: 410, statusText: 'Gone' },
    );
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Peça ao atendente um QR novo');
    expect(element.querySelector('button')).toBeNull();
  });

  it('link sem token nem chama o servidor', () => {
    const fixture = abrir(null);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Link incompleto');
  });
});
