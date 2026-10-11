import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { App } from './app';
import { UnitContextService } from './session/unit-context.service';

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture;
  }

  it('exibe a marca no cabeçalho e uma região principal', () => {
    const element = render().nativeElement as HTMLElement;

    expect(element.querySelector('header mat-toolbar')?.textContent).toContain('fastPay');
    expect(element.querySelector('main')).not.toBeNull();
    expect(element.querySelector('header .unit')).toBeNull();
  });

  it('mantém a unidade ativa visível e permite trocar', () => {
    const context = TestBed.inject(UnitContextService);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const limpeza = vi.fn();
    context.onUnitCleared(limpeza);
    context.current.set({ establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' });
    const fixture = render();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('header .unit')?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Unidade ativa: Bar da Praia · Gerente',
    );

    element.querySelector<HTMLButtonElement>('header button')?.click();
    http.expectOne('/api/v1/session/unit').flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(limpeza).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/unidades']);
    expect(element.querySelector('header .unit')).toBeNull();
  });

  it('sem resposta do servidor ao trocar, ainda limpa a tela', () => {
    const context = TestBed.inject(UnitContextService);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    context.current.set({ establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' });
    const fixture = render();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('header button')
      ?.click();
    http.expectOne('/api/v1/session/unit').flush(null, { status: 503, statusText: 'Unavailable' });

    expect(context.current()).toBeNull();
  });
});
