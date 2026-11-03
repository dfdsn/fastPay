package tech.malyah.fastpay.files.application;

import java.util.Optional;

/**
 * Onde ficam os bytes dos arquivos (especificação §10): disco local em desenvolvimento e homologação, S3 privado
 * em produção (H49). A chave é aleatória e gerada pelo servidor; o metadado e a autorização ficam no banco.
 */
public interface StoragePort {

    /** Grava o conteúdo sob a chave; nunca sobrescreve uma chave existente. */
    void put(String key, byte[] content);

    /** Conteúdo da chave, se existir. */
    Optional<byte[]> get(String key);
}
