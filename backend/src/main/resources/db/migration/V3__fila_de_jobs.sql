-- H04: intenção durável de efeito assíncrono (outbox/fila de jobs) por estabelecimento.
--   * A intenção é gravada na mesma transação do fato de negócio; rollback não deixa job.
--   * Um efeito por (estabelecimento, tipo, chave): evento repetido não cria segundo job.
--   * O worker reserva trabalho por claim_due_jobs(), que só devolve metadados mínimos, e executa
--     cada job dentro do contexto da própria unidade (RLS).
--   * Não financeiros têm teto de tentativas (max_attempts); financeiros incertos não têm (NULL).
--   * Esgotado (EXHAUSTED) é a fila de falhas; retomada só por ação autorizada da aplicação.

-- Auditoria passa a apontar o registro afetado pela ação (ex.: job retomado).
ALTER TABLE audit_event ADD COLUMN subject_id uuid;

CREATE TABLE job (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    kind             text        NOT NULL CHECK (kind ~ '^[a-z][a-z0-9_.]{2,79}$'),
    dedup_key        text        NOT NULL CHECK (char_length(dedup_key) BETWEEN 1 AND 200),
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status           text        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'EXHAUSTED')),
    attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts     integer     CHECK (max_attempts > 0),
    next_run_at      timestamptz NOT NULL DEFAULT now(),
    lease_until      timestamptz,
    lease_token      uuid,
    last_error       text        CHECK (char_length(last_error) <= 300),
    correlation_id   text        CHECK (char_length(correlation_id) <= 100),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (establishment_id, kind, dedup_key),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

CREATE INDEX job_due_idx ON job (next_run_at) WHERE status = 'PENDING';
CREATE INDEX job_lease_idx ON job (lease_until) WHERE status = 'RUNNING';
CREATE INDEX job_establishment_status_idx ON job (establishment_id, status);

ALTER TABLE job ENABLE ROW LEVEL SECURITY;
ALTER TABLE job FORCE ROW LEVEL SECURITY;

-- Runtime: só a unidade do contexto; sem DELETE (histórico de execução preservado).
CREATE POLICY job_runtime ON job
    FOR ALL TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT, INSERT, UPDATE ON job TO ${app_role};

-- Dono da tabela (papel de migração), usado apenas pela função de reserva abaixo, que roda com
-- os direitos dele. Com FORCE, sem esta policy nem o dono enxergaria linhas.
CREATE POLICY job_dispatch ON job
    FOR ALL TO CURRENT_USER
    USING (true)
    WITH CHECK (true);

-- Reserva até p_limit jobs vencidos de qualquer unidade e devolve só id, unidade e lease.
-- Também retoma jobs cujo lease expirou (worker caiu entre reserva e conclusão). Conta a
-- tentativa na reserva, então um job interrompido gasta tentativa como uma falha comum. Linhas
-- presas por um worker ativo (lock de linha) são puladas: duas execuções simultâneas do mesmo job
-- não acontecem.
CREATE FUNCTION claim_due_jobs(p_limit integer, p_lease_seconds integer)
    RETURNS TABLE (job_id uuid, job_establishment_id uuid, job_lease_token uuid)
    LANGUAGE plpgsql
    VOLATILE
    SECURITY DEFINER
    SET search_path = fastpay, pg_temp
AS $$
BEGIN
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'p_limit fora do intervalo 1..100';
    END IF;
    IF p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 3600 THEN
        RAISE EXCEPTION 'p_lease_seconds fora do intervalo 1..3600';
    END IF;

    -- Interrompido na última tentativa permitida: vai para a fila de falhas em vez de rodar de novo.
    UPDATE job j
       SET status = 'EXHAUSTED', lease_until = NULL, lease_token = NULL, updated_at = now(),
           last_error = 'Execução interrompida antes de concluir (lease expirado).'
     WHERE j.id IN (SELECT e.id FROM job e
                     WHERE e.status = 'RUNNING' AND e.lease_until <= now()
                       AND e.max_attempts IS NOT NULL AND e.attempts >= e.max_attempts
                     FOR UPDATE SKIP LOCKED);

    RETURN QUERY
    WITH due AS (
        SELECT d.id
          FROM job d
         WHERE (d.status = 'PENDING' AND d.next_run_at <= now())
            OR (d.status = 'RUNNING' AND d.lease_until <= now())
         ORDER BY d.next_run_at, d.id
         LIMIT p_limit
           FOR UPDATE SKIP LOCKED
    )
    UPDATE job j
       SET status = 'RUNNING',
           attempts = j.attempts + 1,
           lease_until = now() + make_interval(secs => p_lease_seconds),
           lease_token = gen_random_uuid(),
           updated_at = now()
      FROM due
     WHERE j.id = due.id
    RETURNING j.id, j.establishment_id, j.lease_token;
END
$$;

REVOKE ALL ON FUNCTION claim_due_jobs(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_due_jobs(integer, integer) TO ${app_role};
