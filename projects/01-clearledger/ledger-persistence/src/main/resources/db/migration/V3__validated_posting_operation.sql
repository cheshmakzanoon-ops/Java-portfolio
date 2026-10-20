-- Prior migrations and retained receipt digests are immutable. A caller must supply a
-- complete operation identity even when the operation already has a committed receipt.
ALTER TABLE ledger.idempotency_receipt
  ADD CONSTRAINT receipt_operation CHECK (operation IN ('POST', 'REVERSE'));

CREATE OR REPLACE FUNCTION ledger.post_journal(
  t uuid, k text, d text, j uuid, p uuid, dt date, c text, ref text,
  ls jsonb, rev uuid DEFAULT NULL, op text DEFAULT 'POST'
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, ledger
AS $$
DECLARE
  receipt ledger.idempotency_receipt;
  period ledger.accounting_period;
  debit_total numeric;
  credit_total numeric;
BEGIN
  IF t IS NULL OR j IS NULL OR p IS NULL OR dt IS NULL OR c IS NULL
     OR ref IS NULL OR k IS NULL OR d IS NULL OR op IS NULL
     OR length(k) NOT BETWEEN 1 AND 120 OR k !~ '[^[:space:]]'
     OR length(ref) > 120 OR d !~ '^[0-9a-f]{64}$'
     OR c NOT IN ('CAD', 'USD', 'JPY') OR op NOT IN ('POST', 'REVERSE')
     OR (op = 'POST' AND rev IS NOT NULL) OR (op = 'REVERSE' AND rev IS NULL)
  THEN
    RAISE EXCEPTION 'INPUT_BOUND' USING ERRCODE = 'P0422';
  END IF;

  SELECT * INTO receipt
  FROM ledger.idempotency_receipt
  WHERE tenant_id = t AND operation = op AND key = k;
  IF FOUND THEN
    IF receipt.digest IS DISTINCT FROM d THEN
      RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT' USING ERRCODE = 'P0409';
    END IF;
    RETURN receipt.journal_id;
  END IF;

  -- Period closure takes this same row lock. Participating accounts follow in UUID order.
  SELECT * INTO period
  FROM ledger.accounting_period
  WHERE tenant_id = t AND id = p
  FOR UPDATE;
  IF NOT FOUND OR period.closed OR dt < period.start_date OR dt > period.end_date THEN
    RAISE EXCEPTION 'PERIOD_CLOSED_OR_DATE' USING ERRCODE = 'P0422';
  END IF;
  IF ls IS NULL OR jsonb_typeof(ls) IS DISTINCT FROM 'array' THEN
    RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE = 'P0422';
  END IF;
  IF jsonb_array_length(ls) NOT BETWEEN 2 AND 100 THEN
    RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE = 'P0422';
  END IF;

  SELECT sum(CASE WHEN v->>'side' = 'DEBIT' THEN (v->>'amount')::numeric ELSE 0 END),
         sum(CASE WHEN v->>'side' = 'CREDIT' THEN (v->>'amount')::numeric ELSE 0 END)
  INTO debit_total, credit_total
  FROM jsonb_array_elements(ls) v;
  IF debit_total IS DISTINCT FROM credit_total OR debit_total <= 0
     OR debit_total > 999999999999999999999999999999
     OR EXISTS (
       SELECT FROM jsonb_array_elements(ls) v
       WHERE (v->>'amount')::numeric <= 0
          OR (v->>'amount')::numeric <> trunc((v->>'amount')::numeric)
          OR v->>'side' NOT IN ('DEBIT', 'CREDIT')
          OR v->>'amount' IS NULL OR v->>'side' IS NULL
     )
  THEN
    RAISE EXCEPTION 'UNBALANCED_OR_AMOUNT' USING ERRCODE = 'P0422';
  END IF;

  PERFORM 1 FROM ledger.account a
  WHERE a.tenant_id = t
    AND a.id IN (SELECT (v->>'account')::uuid FROM jsonb_array_elements(ls) v)
  ORDER BY a.id FOR UPDATE;
  IF EXISTS (
    SELECT FROM jsonb_array_elements(ls) v
    LEFT JOIN ledger.account a ON a.tenant_id = t AND a.id = (v->>'account')::uuid
    WHERE a.id IS NULL OR a.currency <> c
  ) THEN
    RAISE EXCEPTION 'ACCOUNT_CURRENCY_OR_TENANT' USING ERRCODE = 'P0422';
  END IF;

  IF rev IS NOT NULL THEN
    PERFORM 1 FROM ledger.journal WHERE tenant_id = t AND id = rev FOR UPDATE;
    IF NOT FOUND OR EXISTS (SELECT FROM ledger.journal WHERE tenant_id = t AND reverses = rev) THEN
      RAISE EXCEPTION 'ALREADY_REVERSED_OR_MISSING' USING ERRCODE = 'P0409';
    END IF;
    IF EXISTS (
      (SELECT account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, amount
       FROM ledger.posting WHERE tenant_id = t AND journal_id = rev
       EXCEPT ALL
       SELECT (v->>'account')::uuid, v->>'side', (v->>'amount')::numeric
       FROM jsonb_array_elements(ls) v)
      UNION ALL
      (SELECT (v->>'account')::uuid, v->>'side', (v->>'amount')::numeric
       FROM jsonb_array_elements(ls) v
       EXCEPT ALL
       SELECT account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, amount
       FROM ledger.posting WHERE tenant_id = t AND journal_id = rev)
    ) THEN
      RAISE EXCEPTION 'REVERSAL_MISMATCH' USING ERRCODE = 'P0422';
    END IF;
  END IF;

  INSERT INTO ledger.journal(tenant_id, id, period_id, currency, effective_date, reference, reverses)
  VALUES (t, j, p, c, dt, ref, rev);
  INSERT INTO ledger.posting(tenant_id, journal_id, line_no, account_id, side, amount)
  SELECT t, j, ord::int, (v->>'account')::uuid, v->>'side', (v->>'amount')::numeric
  FROM jsonb_array_elements(ls) WITH ORDINALITY a(v, ord);
  INSERT INTO ledger.account_balance(tenant_id, account_id, debits, credits)
  SELECT t, account_id,
         sum(CASE side WHEN 'DEBIT' THEN amount ELSE 0 END),
         sum(CASE side WHEN 'CREDIT' THEN amount ELSE 0 END)
  FROM ledger.posting WHERE tenant_id = t AND journal_id = j
  GROUP BY account_id
  ON CONFLICT(tenant_id, account_id) DO UPDATE
  SET debits = ledger.account_balance.debits + excluded.debits,
      credits = ledger.account_balance.credits + excluded.credits;
  INSERT INTO ledger.idempotency_receipt(tenant_id, operation, key, digest, journal_id, response)
  VALUES (t, op, k, d, j, jsonb_build_object('journalId', j));
  INSERT INTO ledger.outbox_event(tenant_id, journal_id) VALUES (t, j);
  RETURN j;
END;
$$;
