/**
 * Atendimento (H20, FR-23, FR-26, FR-27): avisos para quem atende — item pronto para entregar e "não é possível
 * atender" (problemas do consumidor chegam com a H21) — e quem responde por eles, conforme a modalidade capturada na
 * abertura da comanda. Garçom responsável: o dono do chamado é o responsável da comanda, e os demais ajudam. Central:
 * fila compartilhada, cada chamado é assumido por uma pessoa de forma exclusiva. O gerente reatribui. Ler não conclui:
 * a entrega conclui o aviso de pronto, e a retirada da sinalização (ou o cancelamento, H23) o de impossibilidade. API
 * pública: {@link tech.malyah.fastpay.attendance.Attendance}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Atendimento")
package tech.malyah.fastpay.attendance;
