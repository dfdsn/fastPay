package tech.malyah.fastpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tech.malyah.fastpay.identity.MembershipRole.ADMIN_ESTABELECIMENTO;
import static tech.malyah.fastpay.identity.MembershipRole.GARCOM;
import static tech.malyah.fastpay.identity.MembershipRole.GERENTE;
import static tech.malyah.fastpay.identity.MembershipRole.PRODUCAO;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Permission;
import tech.malyah.fastpay.identity.domain.AccessPolicy.DelegationRefusal;

/**
 * Matriz de acesso por comando (H11, PRD §4.2), escrita aqui à mão a partir do PRD, linha por linha, e comparada
 * com a política inteira: um papel ganhando ou perdendo uma ação quebra este teste.
 */
class AccessPolicyTest {

    // Colunas: garçom (sem delegação), produção, gerente, administrador do estabelecimento.
    private static final Map<Permission, List<Boolean>> MATRIZ = Map.ofEntries(
            Map.entry(Permission.OPERAR_COMANDA, List.of(true, false, true, true)),
            Map.entry(Permission.OPERAR_PRODUCAO, List.of(false, true, true, true)),
            Map.entry(Permission.ATENDER_PROBLEMA, List.of(true, false, true, true)),
            // H20 (FR-23): o gerente reatribui o atendimento; não é delegável.
            Map.entry(Permission.REATRIBUIR_ATENDIMENTO, List.of(false, false, true, true)),
            Map.entry(Permission.CONCLUIR_PROBLEMA, List.of(false, false, true, true)),
            Map.entry(Permission.CANCELAR_ITEM, List.of(false, false, true, true)),
            Map.entry(Permission.CONCEDER_DESCONTO, List.of(false, false, true, true)),
            Map.entry(Permission.REABRIR_COMANDA, List.of(false, false, true, true)),
            Map.entry(Permission.TRANSFERIR_UNIDADES, List.of(false, false, true, true)),
            Map.entry(Permission.DESFAZER_ENTREGA, List.of(false, false, true, true)),
            Map.entry(Permission.ALTERAR_DISPONIBILIDADE, List.of(false, false, true, true)),
            Map.entry(Permission.REGISTRAR_RECEBIMENTO_EXTERNO, List.of(false, false, true, true)),
            Map.entry(Permission.RECUPERAR_ACESSO_CONSUMIDOR, List.of(false, false, true, true)),
            Map.entry(Permission.VINCULAR_CONTINUACAO_VISITA, List.of(false, false, true, true)),
            Map.entry(Permission.AUTORIZAR_DEVOLUCAO, List.of(false, false, true, true)),
            Map.entry(Permission.ENCERRAR_COM_DEBITO, List.of(false, false, true, true)),
            Map.entry(Permission.INICIAR_REGULARIZACAO, List.of(false, false, true, true)),
            Map.entry(Permission.INVALIDAR_RECEBIMENTO_EXTERNO, List.of(false, false, true, true)),
            Map.entry(Permission.VER_EQUIPE, List.of(false, false, true, true)),
            Map.entry(Permission.DELEGAR_PERMISSOES, List.of(false, false, true, true)),
            Map.entry(Permission.GERIR_EQUIPE, List.of(false, false, false, true)),
            Map.entry(Permission.CONECTAR_CONTA_PSP, List.of(false, false, false, true)),
            Map.entry(Permission.AUTORIZAR_SUPORTE, List.of(false, false, false, true)),
            // PRD §3: catálogo, setores e configurações são do administrador do estabelecimento (H13).
            Map.entry(Permission.GERIR_CATALOGO, List.of(false, false, false, true)),
            // H14: gerente consulta a configuração da unidade, só o administrador altera.
            Map.entry(Permission.VER_CONFIGURACAO, List.of(false, false, true, true)),
            Map.entry(Permission.CONFIGURAR_ESTABELECIMENTO, List.of(false, false, false, true)));

    // "Permissão específica" na coluna do garçom (PRD §4.2): o que gerente e administrador podem delegar.
    private static final Set<Permission> DELEGAVEIS = EnumSet.of(Permission.CONCLUIR_PROBLEMA, Permission.CANCELAR_ITEM,
            Permission.CONCEDER_DESCONTO, Permission.REABRIR_COMANDA, Permission.TRANSFERIR_UNIDADES,
            Permission.DESFAZER_ENTREGA, Permission.ALTERAR_DISPONIBILIDADE, Permission.REGISTRAR_RECEBIMENTO_EXTERNO,
            Permission.RECUPERAR_ACESSO_CONSUMIDOR, Permission.VINCULAR_CONTINUACAO_VISITA);

    private static final List<MembershipRole> COLUNAS = List.of(GARCOM, PRODUCAO, GERENTE, ADMIN_ESTABELECIMENTO);

    @Test
    void matrizCobreTodasAsPermissoes() {
        assertThat(MATRIZ.keySet()).containsExactlyInAnyOrder(Permission.values());
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void cadaPapelTemExatamenteAsAcoesDoPrd(Permission acao) {
        for (int i = 0; i < COLUNAS.size(); i++) {
            assertThat(AccessPolicy.byRole(COLUNAS.get(i)).contains(acao))
                    .as("%s para %s", acao, COLUNAS.get(i)).isEqualTo(MATRIZ.get(acao).get(i));
        }
    }

    @Test
    void delegaveisSaoSoAsPermissoesEspecificasDoGarcom() {
        assertThat(AccessPolicy.delegable()).isEqualTo(DELEGAVEIS);
        assertThat(AccessPolicy.delegable()).doesNotContain(Permission.AUTORIZAR_DEVOLUCAO,
                Permission.ENCERRAR_COM_DEBITO, Permission.INICIAR_REGULARIZACAO,
                Permission.INVALIDAR_RECEBIMENTO_EXTERNO, Permission.GERIR_EQUIPE, Permission.AUTORIZAR_SUPORTE);
    }

    @Test
    void delegacaoSoValeParaGarcomESoComAcaoDelegavel() {
        Set<Permission> garcom = AccessPolicy.effective(GARCOM,
                List.of(Permission.CANCELAR_ITEM, Permission.AUTORIZAR_DEVOLUCAO));
        assertThat(garcom).contains(Permission.CANCELAR_ITEM).doesNotContain(Permission.AUTORIZAR_DEVOLUCAO);

        // Linha antiga de quem deixou de ser garçom não concede nada além do papel.
        assertThat(AccessPolicy.effective(PRODUCAO, List.of(Permission.CANCELAR_ITEM)))
                .isEqualTo(AccessPolicy.byRole(PRODUCAO));
    }

    @Test
    void gerenteDelegaAcaoPermitidaANaoExclusivaEAoGarcom() {
        assertThat(AccessPolicy.delegation(GERENTE, false, GARCOM, DELEGAVEIS)).isEmpty();
        assertThat(AccessPolicy.delegation(GERENTE, false, GARCOM, Set.of())).isEmpty();
        assertThat(AccessPolicy.delegation(ADMIN_ESTABELECIMENTO, false, GARCOM, Set.of(Permission.DESFAZER_ENTREGA)))
                .isEmpty();

        assertThat(AccessPolicy.delegation(GERENTE, false, GARCOM, Set.of(Permission.AUTORIZAR_DEVOLUCAO)))
                .contains(DelegationRefusal.NAO_DELEGAVEL);
        assertThat(AccessPolicy.delegation(ADMIN_ESTABELECIMENTO, false, GARCOM, Set.of(Permission.GERIR_EQUIPE)))
                .contains(DelegationRefusal.NAO_DELEGAVEL);
        assertThat(AccessPolicy.delegation(GERENTE, false, PRODUCAO, Set.of(Permission.CANCELAR_ITEM)))
                .contains(DelegationRefusal.PAPEL_NAO_RECEBE);
        assertThat(AccessPolicy.delegation(GERENTE, false, GERENTE, Set.of()))
                .contains(DelegationRefusal.PAPEL_NAO_RECEBE);
        assertThat(AccessPolicy.delegation(GERENTE, true, GARCOM, Set.of(Permission.CANCELAR_ITEM)))
                .contains(DelegationRefusal.PROPRIO_VINCULO);
        assertThat(AccessPolicy.delegation(GARCOM, false, GARCOM, Set.of(Permission.CANCELAR_ITEM)))
                .contains(DelegationRefusal.SEM_AUTORIDADE);
        assertThat(AccessPolicy.delegation(PRODUCAO, false, GARCOM, Set.of()))
                .contains(DelegationRefusal.SEM_AUTORIDADE);
    }

    @Test
    void limiteDeDescontoPorPapelEQuemPodeMudar() {
        java.math.BigDecimal cem = new java.math.BigDecimal("100");
        java.math.BigDecimal vinte = new java.math.BigDecimal("20");
        assertThat(AccessPolicy.discountLimit(MembershipRole.ADMIN_ESTABELECIMENTO, vinte)).isEqualByComparingTo(cem);
        assertThat(AccessPolicy.discountLimit(MembershipRole.GERENTE, null)).isEqualByComparingTo(cem);
        assertThat(AccessPolicy.discountLimit(MembershipRole.GERENTE, vinte)).isEqualByComparingTo(vinte);
        assertThat(AccessPolicy.discountLimit(MembershipRole.GARCOM, null)).isZero();
        assertThat(AccessPolicy.discountLimit(MembershipRole.PRODUCAO, vinte)).isZero();

        assertThat(AccessPolicy.discountLimitChange(MembershipRole.ADMIN_ESTABELECIMENTO, cem, false,
                MembershipRole.GERENTE, vinte)).isEmpty();
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.GERENTE, vinte, false, MembershipRole.GARCOM,
                vinte)).isEmpty();
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.GERENTE, vinte, false, MembershipRole.GARCOM,
                new java.math.BigDecimal("20.0001"))).contains(AccessPolicy.DiscountLimitRefusal.ACIMA_DA_AUTORIDADE);
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.GERENTE, cem, false, MembershipRole.GERENTE,
                vinte)).contains(AccessPolicy.DiscountLimitRefusal.SEM_AUTORIDADE);
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.GARCOM, cem, false, MembershipRole.GARCOM,
                vinte)).contains(AccessPolicy.DiscountLimitRefusal.SEM_AUTORIDADE);
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.ADMIN_ESTABELECIMENTO, cem, true,
                MembershipRole.GERENTE, vinte)).contains(AccessPolicy.DiscountLimitRefusal.PROPRIO_VINCULO);
        assertThat(AccessPolicy.discountLimitChange(MembershipRole.ADMIN_ESTABELECIMENTO, cem, false,
                MembershipRole.PRODUCAO, vinte)).contains(AccessPolicy.DiscountLimitRefusal.PAPEL_NAO_RECEBE);
    }
}
