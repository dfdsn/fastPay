import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { BrandingPage } from './branding-page';

describe('BrandingPage', () => {
  let fixture: ComponentFixture<BrandingPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';

  function abrir(permissions: Permission[], logoFileId: string | null): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [BrandingPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'ADMIN_ESTABELECIMENTO',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BrandingPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/establishment')
      .flush({ establishmentId: 'a', name: 'Bar da Praia', logoFileId });
    fixture.detectChanges();
  }

  function escolher(arquivo: File): void {
    const input = element().querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { value: [arquivo], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('sem logo mostra as iniciais e o dono inclui um', () => {
    abrir(['CONFIGURAR_ESTABELECIMENTO'], null);
    expect(element().querySelector('fp-unit-logo')?.textContent?.trim()).toBe('BP');
    expect(texto()).toContain('Sem logo: as telas mostram as iniciais.');

    escolher(new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' }));
    const envio = http.expectOne('/api/v1/establishment/logo');
    expect(envio.request.method).toBe('PUT');
    expect(envio.request.body).toBeInstanceOf(FormData);
    envio.flush({ establishmentId: 'a', name: 'Bar da Praia', logoFileId: 'f-1' });
    fixture.detectChanges();
    expect(texto()).toContain('Logo salvo.');
    expect(element().querySelector('fp-unit-logo img')?.getAttribute('src')).toBe(
      '/api/v1/files/f-1',
    );
  });

  it('remove o logo e volta às iniciais', () => {
    abrir(['CONFIGURAR_ESTABELECIMENTO'], 'f-1');
    const botao = Array.from(element().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Remover logo'),
    )!;
    botao.click();
    const pedido = http.expectOne('/api/v1/establishment/logo');
    expect(pedido.request.method).toBe('DELETE');
    pedido.flush({ establishmentId: 'a', name: 'Bar da Praia', logoFileId: null });
    fixture.detectChanges();
    expect(element().querySelector('fp-unit-logo')?.textContent?.trim()).toBe('BP');
  });

  it('arquivo acima de 5 MB nem é enviado; recusa do servidor aparece', () => {
    abrir(['CONFIGURAR_ESTABELECIMENTO'], null);
    escolher(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'grande.jpg', { type: 'image/jpeg' }));
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('passa de 5 MB');

    escolher(new File([new Uint8Array([1])], 'x.gif', { type: 'image/gif' }));
    http
      .expectOne('/api/v1/establishment/logo')
      .flush(
        {
          code: 'FORMATO_NAO_ACEITO',
          detail: 'Formato não aceito. Envie uma imagem JPEG, PNG ou WebP.',
        },
        { status: 422, statusText: 'Unprocessable Content' },
      );
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Formato não aceito');
  });

  it('quem não é administrador só vê', () => {
    abrir(['OPERAR_COMANDA'], null);
    expect(element().querySelector('input[type="file"]')).toBeNull();
    expect(texto()).toContain('Só o administrador do estabelecimento altera o logo.');
  });
});
