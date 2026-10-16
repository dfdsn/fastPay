import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { UnitContextService, UnitView } from './unit-context.service';
import { UnitHomePage } from './unit-home-page';

describe('UnitHomePage', () => {
  function abrir(antes: UnitView, servidor: UnitView): HTMLElement {
    TestBed.configureTestingModule({
      imports: [UnitHomePage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    TestBed.inject(UnitContextService).current.set(antes);
    const fixture = TestBed.createComponent(UnitHomePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/session/unit').flush(servidor);
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
    expect(links(element)).toEqual(['Equipe']);
  });

  it('o dono vê equipe e acesso de suporte', () => {
    const dono: UnitView = {
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'ADMIN_ESTABELECIMENTO',
      permissions: ['VER_EQUIPE', 'GERIR_EQUIPE', 'AUTORIZAR_SUPORTE'],
    };
    expect(links(abrir(dono, dono))).toEqual(['Equipe', 'Acesso de suporte']);
  });

  it('garçom não vê caminhos de administração', () => {
    const garcom: UnitView = {
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'GARCOM',
      permissions: ['OPERAR_COMANDA', 'ATENDER_PROBLEMA', 'CANCELAR_ITEM'],
    };
    expect(links(abrir(garcom, garcom))).toEqual([]);
  });
});
