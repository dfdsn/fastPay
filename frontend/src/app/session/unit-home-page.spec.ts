import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { UnitContextService, UnitView } from './unit-context.service';
import { UnitHomePage } from './unit-home-page';

describe('UnitHomePage', () => {
  function abrir(
    antes: UnitView,
    servidor: UnitView,
    logoFileId: string | null = null,
  ): HTMLElement {
    TestBed.configureTestingModule({
      imports: [UnitHomePage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    TestBed.inject(UnitContextService).current.set(antes);
    const fixture = TestBed.createComponent(UnitHomePage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/session/unit').flush(servidor);
    http
      .expectOne('/api/v1/establishment')
      .flush({ establishmentId: servidor.establishmentId, name: servidor.name, logoFileId });
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const links = (element: HTMLElement) =>
    Array.from(element.querySelectorAll('nav a')).map((a) => a.textContent?.trim());

  it('mostra a unidade e o papel atuais, conferidos no servidor', () => {
    const element = abrir(
      { establishmentId: 'b', name: 'Adega Centro', role: 'GARCOM' },
      { establishmentId: 'b', name: 'Adega Centro', role: 'GERENTE', permissions: ['VER_EQUIPE'] },
    );
    expect(element.textContent).toContain('Você está em Adega Centro como Gerente.');
    expect(links(element)).toEqual(['Catálogo', 'Equipe']);
    // Sem logo: iniciais do nome.
    expect(element.querySelector('fp-unit-logo')?.textContent?.trim()).toBe('AC');
  });

  it('o dono vê equipe e acesso de suporte', () => {
    const dono: UnitView = {
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'ADMIN_ESTABELECIMENTO',
      permissions: [
        'VER_EQUIPE',
        'GERIR_EQUIPE',
        'AUTORIZAR_SUPORTE',
        'CONFIGURAR_ESTABELECIMENTO',
      ],
    };
    const element = abrir(dono, dono, 'logo-1');
    expect(links(element)).toEqual(['Catálogo', 'Logo da unidade', 'Equipe', 'Acesso de suporte']);
    const logo = element.querySelector<HTMLImageElement>('fp-unit-logo img');
    expect(logo?.getAttribute('src')).toBe('/api/v1/files/logo-1');
    expect(logo?.alt).toBe('Logo de Bar da Praia');
  });

  it('garçom vê comandas e catálogo', () => {
    const garcom: UnitView = {
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'GARCOM',
      permissions: ['OPERAR_COMANDA', 'ATENDER_PROBLEMA', 'CANCELAR_ITEM'],
    };
    expect(links(abrir(garcom, garcom))).toEqual(['Comandas', 'Catálogo']);
  });
});
