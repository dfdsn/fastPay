import { Component, Injectable, inject, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';

const KEY = 'fastpay.som.v1';

/**
 * Som opcional para item novo (FR-27, H20): desligado por padrão, escolha guardada só neste aparelho. O aviso visual
 * continua sendo o principal; sem permissão de áudio no navegador, nada acontece.
 */
@Injectable({ providedIn: 'root' })
export class ChimeService {
  readonly enabled = signal(read());
  private context: AudioContext | null = null;

  toggle(on: boolean): void {
    this.enabled.set(on);
    try {
      localStorage.setItem(KEY, on ? '1' : '0');
    } catch {
      // Armazenamento bloqueado: vale só nesta tela.
    }
  }

  play(): void {
    if (!this.enabled()) {
      return;
    }
    try {
      this.context ??= new AudioContext();
      const oscillator = this.context.createOscillator();
      const gain = this.context.createGain();
      oscillator.frequency.value = 880;
      gain.gain.value = 0.15;
      oscillator.connect(gain).connect(this.context.destination);
      oscillator.start();
      oscillator.stop(this.context.currentTime + 0.2);
    } catch {
      // Navegador sem áudio: fica só o sinal visual.
    }
  }
}

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

/** Liga ou desliga o som de item novo neste aparelho. */
@Component({
  selector: 'fp-sound-toggle',
  imports: [MatCheckboxModule],
  template: `
    <mat-checkbox [checked]="chime.enabled()" (change)="chime.toggle($event.checked)">
      Tocar som quando chegar item novo neste aparelho
    </mat-checkbox>
  `,
})
export class SoundToggle {
  protected readonly chime = inject(ChimeService);
}
