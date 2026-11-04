import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { CatalogPage } from './catalog-page';
import { CatalogListing } from './catalog.service';

const CATALOGO: CatalogListing = {
  sectors: [{ id: 's-cozinha', name: 'Cozinha', delayLimitMinutes: 15, version: 0 }],
  products: [
    {
      id: 'p-agua',
      name: 'Água 500 ml',
      category: 'Bebidas',
      description: null,
      price: 6.5,
      sectorId: null,
      available: true,
      photoFileId: null,
      version: 0,
    },
    {
      id: 'p-batata',
      name: 'Batata frita',
      category: 'Porções',
      description: 'Porção para dois',
      price: 35.9,
      sectorId: 's-cozinha',
      available: false,
      photoFileId: 'f-batata',
      version: 3,
    },
  ],
  categories: ['Bebidas', 'Porções'],
  page: 0,
  pageSize: 100,
  totalProducts: 2,
  pricesVisible: true,
};

const DONO: Permission[] = ['OPERAR_COMANDA', 'GERIR_CATALOGO', 'ALTERAR_DISPONIBILIDADE'];

describe('CatalogPage', () => {
  let fixture: ComponentFixture<CatalogPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const status = () => element().querySelector('[role="status"]')?.textContent ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.replace(/\s+/g, ' ').includes(rotulo),
    );
  const campo = (rotulo: string) =>
    Array.from(element().querySelectorAll('mat-form-field'))
      .find((f) => f.querySelector('mat-label')?.textContent?.trim() === rotulo)
      ?.querySelector<HTMLInputElement | HTMLSelectElement>('input, select, textarea');

  function abrir(permissions: Permission[], catalogo: CatalogListing = CATALOGO): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [CatalogPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'ADMIN_ESTABELECIMENTO',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CatalogPage);
    fixture.detectChanges();
    responderCatalogo(catalogo);
  }

  function responderCatalogo(catalogo: CatalogListing = CATALOGO): void {
    http.expectOne((r) => r.url === '/api/v1/catalog').flush(catalogo);
    fixture.detectChanges();
  }

  function digitar(rotulo: string, valor: string): void {
    const input = campo(rotulo)!;
    input.value = valor;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
  }

  afterEach(() => http.verify());

  it('mostra produtos por categoria, preço, setor, foto e esgotado', () => {
    abrir(DONO);
    const titulos = Array.from(element().querySelectorAll('h3')).map((h) => h.textContent?.trim());
    expect(titulos).toEqual(['Bebidas', 'Porções']);
    expect(texto()).toContain('Sem preparo');
    expect(texto()).toContain('Preparo: Cozinha');
    expect(texto()).toMatch(/R\$\s*35,90/);
    expect(texto()).toContain('Esgotado');
    const foto = element().querySelector<HTMLImageElement>('img.photo');
    expect(foto?.getAttribute('src')).toBe('/api/v1/files/f-batata');
    expect(foto?.alt).toBe('Foto de Batata frita');
  });

  it('produção vê o catálogo sem preços e sem botões', () => {
    abrir(['OPERAR_PRODUCAO'], {
      ...CATALOGO,
      pricesVisible: false,
      products: CATALOGO.products.map((p) => ({ ...p, price: null })),
    });
    expect(texto()).not.toContain('R$');
    expect(element().querySelectorAll('button').length).toBe(0);
    expect(element().querySelector('form')).toBeNull();
  });

  it('gerente só marca disponibilidade', () => {
    abrir(['OPERAR_COMANDA', 'ALTERAR_DISPONIBILIDADE']);
    expect(element().querySelector('form')).toBeNull();
    expect(botao('Editar Batata frita')).toBeUndefined();

    botao('Marcar como disponível: Batata frita')!.click();
    const pedido = http.expectOne('/api/v1/catalog/products/p-batata/availability');
    expect(pedido.request.method).toBe('PUT');
    expect(pedido.request.body).toEqual({ available: true });
    pedido.flush({ ...CATALOGO.products[1], available: true });
    responderCatalogo();
    expect(status()).toContain('Batata frita está disponível.');
  });

  it('o dono adiciona produto com preço em texto e setor', () => {
    abrir(DONO);
    digitar('Nome', 'Chopp 300 ml');
    digitar('Categoria', 'Bebidas');
    digitar('Preço (R$)', '9,90');
    digitar('Setor de preparo', 's-cozinha');
    fixture.detectChanges();
    botao('Adicionar produto')!.click();
    const pedido = http.expectOne('/api/v1/catalog/products');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({
      name: 'Chopp 300 ml',
      category: 'Bebidas',
      price: '9,90',
      description: '',
      sectorId: 's-cozinha',
    });
    pedido.flush(CATALOGO.products[0]);
    responderCatalogo();
    expect(status()).toContain('Chopp 300 ml adicionado ao catálogo.');
  });

  it('preço inválido não é enviado', () => {
    abrir(DONO);
    digitar('Nome', 'Chopp');
    digitar('Categoria', 'Bebidas');
    digitar('Preço (R$)', '9,999');
    fixture.detectChanges();
    botao('Adicionar produto')!.click();
    fixture.detectChanges();
    http.expectNone('/api/v1/catalog/products');
    expect(texto()).toContain('Informe o preço maior que zero');
  });

  it('edita com a versão lida e recarrega quando outra pessoa alterou antes', () => {
    abrir(DONO);
    botao('Editar Batata frita')!.click();
    fixture.detectChanges();
    expect(campo('Preço (R$)')!.value).toBe('35,90');
    digitar('Preço (R$)', '38');
    fixture.detectChanges();
    botao('Salvar alterações')!.click();
    const pedido = http.expectOne('/api/v1/catalog/products/p-batata');
    expect(pedido.request.method).toBe('PUT');
    expect(pedido.request.body).toMatchObject({ price: '38', version: 3, sectorId: 's-cozinha' });
    pedido.flush(
      {
        code: 'VERSAO_DESATUALIZADA',
        detail: 'Outra pessoa alterou este cadastro. Atualize a lista e confira antes de salvar.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    responderCatalogo();
    expect(alerta()).toContain('Outra pessoa alterou este cadastro');
    expect(botao('Salvar alterações')).toBeUndefined();
  });

  it('setores: adiciona, renomeia e mostra a recusa de arquivar setor em uso', () => {
    abrir(DONO);
    digitar('Novo setor', 'Bar');
    fixture.detectChanges();
    botao('Adicionar setor')!.click();
    http.expectOne('/api/v1/catalog/sectors').flush({ id: 's-bar', name: 'Bar', version: 0 });
    responderCatalogo();

    digitar('Nome do setor Cozinha', 'Cozinha quente');
    fixture.detectChanges();
    botao('Renomear Cozinha')!.click();
    const renomear = http.expectOne('/api/v1/catalog/sectors/s-cozinha');
    expect(renomear.request.body).toEqual({ name: 'Cozinha quente', version: 0 });
    renomear.flush({ id: 's-cozinha', name: 'Cozinha quente', delayLimitMinutes: 15, version: 1 });
    responderCatalogo();

    // Limite de atraso do setor (H18): só envia quando muda, com nome e versão lidos.
    expect(botao('Salvar atraso de Cozinha')!.disabled).toBe(true);
    digitar('Atraso de Cozinha (min)', '0');
    fixture.detectChanges();
    botao('Salvar atraso de Cozinha')!.click();
    fixture.detectChanges();
    expect(alerta()).toContain('de 1 a 240');
    digitar('Atraso de Cozinha (min)', '25');
    fixture.detectChanges();
    botao('Salvar atraso de Cozinha')!.click();
    const atraso = http.expectOne('/api/v1/catalog/sectors/s-cozinha');
    expect(atraso.request.body).toEqual({ name: 'Cozinha', delayLimitMinutes: 25, version: 0 });
    atraso.flush({ id: 's-cozinha', name: 'Cozinha', delayLimitMinutes: 25, version: 1 });
    responderCatalogo();

    botao('Arquivar Cozinha')!.click();
    http.expectOne('/api/v1/catalog/sectors/s-cozinha/archive').flush(
      {
        code: 'SETOR_EM_USO',
        detail: 'Há produtos ativos neste setor. Mude o setor deles ou arquive-os antes.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(alerta()).toContain('Há produtos ativos neste setor');
  });

  it('envia a foto como arquivo e recusa acima de 5 MB sem enviar', () => {
    abrir(DONO);
    const input = Array.from(element().querySelectorAll<HTMLInputElement>('input[type="file"]'))[0];
    Object.defineProperty(input, 'files', {
      value: [
        new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'grande.jpg', { type: 'image/jpeg' }),
      ],
      configurable: true,
    });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(alerta()).toContain('passa de 5 MB');

    Object.defineProperty(input, 'files', {
      value: [new File([new Uint8Array([1, 2])], 'agua.webp', { type: 'image/webp' })],
      configurable: true,
    });
    input.dispatchEvent(new Event('change'));
    const envio = http.expectOne('/api/v1/catalog/products/p-agua/photo');
    expect(envio.request.method).toBe('PUT');
    expect(envio.request.body).toBeInstanceOf(FormData);
    envio.flush({ ...CATALOGO.products[0], photoFileId: 'f-agua' });
    responderCatalogo();
    expect(status()).toContain('Foto de Água 500 ml salva.');
  });

  it('catálogo vazio e páginas', () => {
    abrir(DONO, { ...CATALOGO, products: [], totalProducts: 0 });
    expect(texto()).toContain('Nenhum produto cadastrado ainda.');
    expect(botao('Próxima página')).toBeUndefined();
  });

  it('navega entre páginas quando há mais de 100 produtos', () => {
    abrir(DONO, { ...CATALOGO, totalProducts: 230 });
    expect(texto()).toContain('Página 1 de 3');
    botao('Próxima página')!.click();
    const pedido = http.expectOne((r) => r.url === '/api/v1/catalog');
    expect(pedido.request.params.get('page')).toBe('1');
    pedido.flush({ ...CATALOGO, totalProducts: 230, page: 1 });
    fixture.detectChanges();
    expect(texto()).toContain('Página 2 de 3');
  });

  it('falha ao carregar oferece nova tentativa', () => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [CatalogPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CatalogPage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === '/api/v1/catalog')
      .flush(null, { status: 500, statusText: 'Erro' });
    fixture.detectChanges();
    expect(status()).toContain('Não foi possível carregar o catálogo');
    botao('Tentar novamente')!.click();
    responderCatalogo();
    expect(texto()).toContain('Água 500 ml');
  });
});
