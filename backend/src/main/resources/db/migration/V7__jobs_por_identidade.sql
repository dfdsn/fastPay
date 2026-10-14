-- H12: jobs da conta de uma pessoa, sem unidade (e-mail de redefinição de senha, aviso de MFA).
--   * Cada job pertence a exatamente um escopo: um estabelecimento (H04) ou uma identidade.
--   * Escopo de identidade usa o contexto app.identity_id (TenantTransactions.asIdentity): a pessoa
--     só enxerga os próprios jobs; nenhuma unidade enxerga jobs de conta.
--   * Mesma deduplicação por efeito: um job por (identidade, tipo, chave).
--   * claim_due_jobs passa a devolver também a identidade, para o worker abrir o contexto certo.

ALTER TABLE job ALTER COLUMN establishment_id DROP NOT NULL;
ALTER TABLE job ADD COLUMN identity_id uuid REFERENCES user_identity (id);
ALTER TABLE job ADD CONSTRAINT job_um_escopo CHECK (num_nonnulls(establishment_id, identity_id) = 1);

CREATE UNIQUE INDEX job_identity_dedup_idx ON job (identity_id, kind, dedup_key) WHERE identity_id IS NOT NULL;

CREATE POLICY job_runtime_identity ON job
    FOR ALL TO ${app_role}
    USING (establishment_id IS NULL AND identity_id = current_identity_id())
    WITH CHECK (establishment_id IS NULL AND identity_id = current_identity_id());

-- O tipo de retorno muda: a função antiga é removida e recriada com a mesma lógica da V3.
DROP FUNCTION claim_due_jobs(integer, integer);

CREATE FUNCTION claim_due_jobs(p_limit integer, p_lease_seconds integer)
    RETURNS TABLE (job_id uuid, job_establishment_id uuid, job_identity_id uuid, job_lease_token uuid)
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
    RETURNING j.id, j.establishment_id, j.identity_id, j.lease_token;
END
$$;

REVOKE ALL ON FUNCTION claim_due_jobs(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_due_jobs(integer, integer) TO ${app_role};
