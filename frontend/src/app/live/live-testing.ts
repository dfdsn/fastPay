import { Provider } from '@angular/core';

import { EVENT_SOURCE_FACTORY, EventSourceLike } from './live-updates';

/** EventSource de teste: a tela abre, o teste emite os eventos do servidor ou a queda. */
export class FakeEventSource implements EventSourceLike {
  static opened: FakeEventSource[] = [];

  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  constructor(readonly url: string) {}

  static last(): FakeEventSource {
    const last = FakeEventSource.opened.at(-1);
    if (!last) {
      throw new Error('Nenhuma conexão aberta');
    }
    return last;
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  emit(type: string, data: object): void {
    const event = new MessageEvent<string>(type, { data: JSON.stringify(data) });
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event);
    }
  }

  fail(): void {
    this.onerror?.(new Event('error'));
  }

  close(): void {
    this.closed = true;
  }
}

/** Troca o EventSource do navegador pelo de teste (jsdom não tem EventSource). */
export function provideFakeEventSource(): Provider {
  FakeEventSource.opened = [];
  return {
    provide: EVENT_SOURCE_FACTORY,
    useValue: (url: string) => {
      const source = new FakeEventSource(url);
      FakeEventSource.opened.push(source);
      return source;
    },
  };
}
