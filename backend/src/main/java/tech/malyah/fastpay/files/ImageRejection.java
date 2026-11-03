package tech.malyah.fastpay.files;

/**
 * Por que uma imagem enviada foi recusada (H13). Código estável para a API; a mensagem com os limites
 * configurados vem de {@link Images#message(ImageRejection)}.
 */
public enum ImageRejection {
    ARQUIVO_VAZIO,
    ARQUIVO_GRANDE,
    FORMATO_NAO_ACEITO,
    IMAGEM_INVALIDA,
    IMAGEM_PEQUENA,
    IMAGEM_GRANDE
}
