import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { UnitContextService } from './unit-context.service';
import { UnitHomePage } from './unit-home-page';

describe('UnitHomePage', () => {
  it('mostra a unidade e o papel ativos', () => {
    TestBed.configureTestingModule({ imports: [UnitHomePage], providers: [provideHttpClient()] });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'b',
      name: 'Adega Centro',
      role: 'GARCOM',
    });
    const fixture = TestBed.createComponent(UnitHomePage);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Você está em Adega Centro como Garçom.',
    );
  });
});
