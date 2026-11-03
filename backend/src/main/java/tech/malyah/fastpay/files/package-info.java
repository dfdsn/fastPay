/**
 * Arquivos da unidade (H13, especificação §10): verificação, regravação e guarda de imagens (foto de produto e
 * logo), metadados em {@code file_object} com RLS e bytes no storage (local em dev/HML, S3 na H49). Download só
 * para quem tem vínculo ativo na unidade, por {@code GET /api/v1/files/{id}}. API pública:
 * {@link tech.malyah.fastpay.files.Images}, {@link tech.malyah.fastpay.files.ImagePurpose} e
 * {@link tech.malyah.fastpay.files.ImageRejection}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Arquivos")
package tech.malyah.fastpay.files;
