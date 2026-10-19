package tech.malyah.fastpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Identidade global e vínculos por unidade com PostgreSQL real e o papel de runtime (H07, FR-03, FR-06,
 * CA-03): duas lojas, mesma pessoa com papéis distintos, vínculo desativado, acesso global sem vínculo e
 * visibilidade dos vínculos entre lojas.
 */
@SpringBootTest
class MembershipsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    Memberships memberships;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());

    UUID lojaA;
    UUID lojaB;
    UUID ana;
    UUID bruno;
    UUID carla;
    UUID vinculoAnaA;
    UUID vinculoAnaB;

    @BeforeEach
    void duasLojas() {
        lojaA = fixtures.establishment("Bar da Praia");
        lojaB = fixtures.establishment("Adega Centro");
        ana = fixtures.identity("Ana");
        bruno = fixtures.identity("Bruno");
        carla = fixtures.identity("Carla");
        vinculoAnaA = fixtures.membership(ana, lojaA, MembershipRole.GERENTE);
        vinculoAnaB = fixtures.membership(ana, lojaB, MembershipRole.GARCOM);
        fixtures.membership(bruno, lojaA, MembershipRole.PRODUCAO);
        fixtures.membership(carla, lojaA, MembershipRole.ADMIN_ESTABELECIMENTO);
    }

    @Test
    void mesmaIdentidadeTemPapeisDistintosPorUnidade() {
        assertThat(memberships.activeUnits(ana))
                .extracting(UnitMembership::establishmentName, UnitMembership::role)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Adega Centro", MembershipRole.GARCOM),
                        org.assertj.core.groups.Tuple.tuple("Bar da Praia", MembershipRole.GERENTE));

        assertThat(memberships.inUnit(ana, lojaA, UnitMembership::role)).isEqualTo(MembershipRole.GERENTE);
        assertThat(memberships.inUnit(ana, lojaB, UnitMembership::role)).isEqualTo(MembershipRole.GARCOM);
        assertThat(memberships.identity(ana)).contains(new IdentitySummary(ana, "Ana"));
    }

    @Test
    void unidadeSemVinculoInexistenteOuNulaEhRecusadaDoMesmoJeito() {
        assertThat(memberships.activeUnits(bruno)).extracting(UnitMembership::establishmentId).containsExactly(lojaA);

        assertThatThrownBy(() -> memberships.inUnit(bruno, lojaB, m -> m)).isInstanceOf(UnitAccessDeniedException.class);
        assertThatThrownBy(() -> memberships.inUnit(bruno, UUID.randomUUID(), m -> m))
                .isInstanceOf(UnitAccessDeniedException.class);
        assertThatThrownBy(() -> memberships.inUnit(bruno, null, m -> m)).isInstanceOf(UnitAccessDeniedException.class);
    }

    @Test
    void trabalhoNaUnidadeSoEnxergaDadosDela() {
        admin().update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.evento_b')",
                UUID.randomUUID(), lojaB);

        Integer eventosDeBVistosEmA = memberships.inUnit(ana, lojaA, m -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE establishment_id = ?", Integer.class, lojaB));
        assertThat(eventosDeBVistosEmA).isZero();
        Integer eventosDeBVistosEmB = memberships.inUnit(ana, lojaB, m -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE establishment_id = ?", Integer.class, lojaB));
        assertThat(eventosDeBVistosEmB).isEqualTo(1);
    }

    @Test
    void administradorDeUmaLojaNaoVeVinculosEmOutras() {
        // Dentro da loja A (contexto de Carla, admin de A), só os vínculos de A aparecem; o de Ana em B não.
        var vistos = memberships.inUnit(carla, lojaA,
                m -> jdbc.queryForList("SELECT id FROM membership", UUID.class));
        assertThat(vistos).contains(vinculoAnaA).doesNotContain(vinculoAnaB).hasSize(3);

        // Sem unidade, uma identidade só vê os próprios vínculos e o nome das próprias unidades.
        var deBruno = tenants.asIdentity(bruno, () -> jdbc.queryForList("SELECT identity_id FROM membership", UUID.class));
        assertThat(deBruno).containsOnly(bruno);
        var lojasDeBruno = tenants.asIdentity(bruno, () -> jdbc.queryForList("SELECT id FROM establishment", UUID.class));
        assertThat(lojasDeBruno).containsExactly(lojaA);
        var identidadesVistas = tenants.asIdentity(bruno, () -> jdbc.queryForList("SELECT id FROM user_identity", UUID.class));
        assertThat(identidadesVistas).containsExactly(bruno);
    }

    @Test
    void semContextoNenhumVinculoOuIdentidadeEhVisivel() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity", Integer.class)).isZero();
    }

    @Test
    void acessoGlobalNaoConcedeOperacaoDeLoja() {
        UUID adminFastPay = fixtures.identity("Admin plataforma", "ADMIN_FASTPAY");

        assertThat(memberships.activeUnits(adminFastPay)).isEmpty();
        assertThatThrownBy(() -> memberships.inUnit(adminFastPay, lojaA, m -> m))
                .isInstanceOf(UnitAccessDeniedException.class);
    }

    @Test
    void desativarVinculoRevogaSoAquelaUnidadeEPreservaHistorico() {
        assertThat(memberships.deactivate(lojaA, vinculoAnaA, carla, "corr-h07")).isTrue();

        assertThatThrownBy(() -> memberships.inUnit(ana, lojaA, m -> m)).isInstanceOf(UnitAccessDeniedException.class);
        assertThat(memberships.inUnit(ana, lojaB, UnitMembership::role)).isEqualTo(MembershipRole.GARCOM);
        assertThat(memberships.activeUnits(ana)).extracting(UnitMembership::establishmentId).containsExactly(lojaB);

        Map<String, Object> linha = admin().queryForMap(
                "SELECT status, deactivated_by, deactivated_at IS NOT NULL AS com_data, role FROM fastpay.membership WHERE id = ?",
                vinculoAnaA);
        assertThat(linha).containsEntry("status", "INACTIVE").containsEntry("deactivated_by", carla)
                .containsEntry("com_data", true).containsEntry("role", "GERENTE");
        Map<String, Object> auditoria = admin().queryForMap(
                "SELECT action, actor_id, correlation_id FROM fastpay.audit_event WHERE establishment_id = ? AND subject_id = ?",
                lojaA, vinculoAnaA);
        assertThat(auditoria).containsEntry("action", "vinculo.desativado").containsEntry("actor_id", carla)
                .containsEntry("correlation_id", "corr-h07");

        assertThat(memberships.deactivate(lojaA, vinculoAnaA, carla, null)).as("já inativo").isFalse();
    }

    @Test
    void desativarVinculoDeOutraUnidadeNaoTemEfeito() {
        assertThat(memberships.deactivate(lojaA, vinculoAnaB, carla, null)).isFalse();
        assertThat(memberships.inUnit(ana, lojaB, UnitMembership::role)).isEqualTo(MembershipRole.GARCOM);
    }

    @Test
    void novoVinculoDepoisDeDesativadoMantemOAntigoComoHistorico() {
        memberships.deactivate(lojaA, vinculoAnaA, carla, null);
        fixtures.membership(ana, lojaA, MembershipRole.GARCOM);

        assertThat(memberships.inUnit(ana, lojaA, UnitMembership::role)).isEqualTo(MembershipRole.GARCOM);
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.membership WHERE identity_id = ? AND establishment_id = ?",
                Integer.class, ana, lojaA)).isEqualTo(2);
        assertThatThrownBy(() -> fixtures.membership(ana, lojaA, MembershipRole.GERENTE))
                .as("um vínculo ativo por pessoa e unidade").isInstanceOf(DataAccessException.class);
    }

    @Test
    void runtimeNaoApagaVinculosNemCriaSemConvite() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> jdbc.update("DELETE FROM membership")))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> jdbc.update(
                "INSERT INTO membership (id, establishment_id, identity_id, role) VALUES (?, ?, ?, 'GERENTE')",
                UUID.randomUUID(), lojaA, bruno)))
                .as("vínculo só nasce do aceite de convite (H10)")
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> jdbc.update(
                "UPDATE membership SET identity_id = ? WHERE id = ?", bruno, vinculoAnaA)))
                .as("o vínculo não muda de pessoa")
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThat(tenants.inEstablishment(lojaA, () -> jdbc.update(
                "UPDATE membership SET role = 'ADMIN_ESTABELECIMENTO' WHERE id = ?", vinculoAnaB)))
                .as("troca de papel (H11) só alcança vínculos da unidade do contexto")
                .isZero();
    }
}
