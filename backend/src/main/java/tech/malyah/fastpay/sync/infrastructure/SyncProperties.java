package tech.malyah.fastpay.sync.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sincronização operacional (H17). Configuração técnica, não limite de loja (proposta T-03).
 *
 * @param dispatchInterval    de quanto em quanto tempo o servidor procura avisos novos para as conexões abertas
 * @param heartbeatInterval   sinal de vida numa conexão sem avisos (o cliente desconfia depois de 2,5 vezes isso)
 * @param recheckInterval     de quanto em quanto tempo cada conexão confere de novo a sessão ou o acesso dela
 * @param streamLifetime      duração máxima de uma conexão; depois o servidor pede reconexão (com o mesmo cursor)
 * @param retention           quanto tempo os avisos ficam para reconexão sem leitura completa
 * @param maxReplay           avisos que uma reconexão recebe; atrás mais que isso, leitura completa
 * @param maxStreamsPerContext conexões simultâneas de uma sessão da equipe ou de um acesso de consumidor
 * @param maxStreams          conexões simultâneas no servidor
 * @param dispatcherEnabled   liga o envio periódico (os testes chamam o envio diretamente)
 */
@ConfigurationProperties("fastpay.sync")
public record SyncProperties(
        @DefaultValue("1s") Duration dispatchInterval,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("10s") Duration recheckInterval,
        @DefaultValue("30m") Duration streamLifetime,
        @DefaultValue("6h") Duration retention,
        @DefaultValue("500") int maxReplay,
        @DefaultValue("5") int maxStreamsPerContext,
        @DefaultValue("1000") int maxStreams,
        @DefaultValue("true") boolean dispatcherEnabled) {
}
