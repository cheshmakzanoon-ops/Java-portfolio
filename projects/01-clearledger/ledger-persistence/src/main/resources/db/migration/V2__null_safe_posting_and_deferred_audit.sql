-- V1 remains immutable. Reject SQLNULL before JSON predicates and audit every journal at commit.
CREATE OR REPLACE FUNCTION ledger.post_journal(t uuid,k text,d text,j uuid,p uuid,dt date,c text,ref text,ls jsonb,rev uuid DEFAULT NULL,op text DEFAULT 'POST') RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
DECLARE r ledger.idempotency_receipt; pr ledger.accounting_period; x record; totald numeric; totalc numeric; n int;
BEGIN
 SELECT * INTO r FROM ledger.idempotency_receipt WHERE tenant_id=t AND operation=op AND key=k;
 IF FOUND THEN IF r.digest<>d THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT' USING ERRCODE='P0409'; END IF;RETURN r.journal_id;END IF;
 IF length(k) NOT BETWEEN 1 AND 120 OR length(ref)>120 OR length(d)<>64 THEN RAISE EXCEPTION 'INPUT_BOUND' USING ERRCODE='P0422';END IF;
 SELECT * INTO pr FROM ledger.accounting_period WHERE tenant_id=t AND id=p FOR UPDATE;
 IF NOT FOUND OR pr.closed OR dt<pr.start_date OR dt>pr.end_date THEN RAISE EXCEPTION 'PERIOD_CLOSED_OR_DATE' USING ERRCODE='P0422';END IF;
 IF ls IS NULL OR jsonb_typeof(ls) IS DISTINCT FROM 'array' THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;
 IF jsonb_array_length(ls) NOT BETWEEN 2 AND 100 THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;
 SELECT count(*),sum(CASE WHEN v->>'side'='DEBIT' THEN (v->>'amount')::numeric ELSE 0 END),sum(CASE WHEN v->>'side'='CREDIT' THEN (v->>'amount')::numeric ELSE 0 END) INTO n,totald,totalc FROM jsonb_array_elements(ls) v;
 IF totald IS DISTINCT FROM totalc OR totald<=0 OR totald>999999999999999999999999999999 OR EXISTS(SELECT FROM jsonb_array_elements(ls) v WHERE (v->>'amount')::numeric<=0 OR (v->>'amount')::numeric<>trunc((v->>'amount')::numeric) OR v->>'side' NOT IN('DEBIT','CREDIT') OR v->>'amount' IS NULL OR v->>'side' IS NULL) THEN RAISE EXCEPTION 'UNBALANCED_OR_AMOUNT' USING ERRCODE='P0422'; END IF;
 PERFORM 1 FROM ledger.account a WHERE a.tenant_id=t AND a.id IN(SELECT (v->>'account')::uuid FROM jsonb_array_elements(ls) v) ORDER BY a.id FOR UPDATE;
 IF EXISTS(SELECT FROM jsonb_array_elements(ls) v LEFT JOIN ledger.account a ON a.tenant_id=t AND a.id=(v->>'account')::uuid WHERE a.id IS NULL OR a.currency<>c) THEN RAISE EXCEPTION 'ACCOUNT_CURRENCY_OR_TENANT' USING ERRCODE='P0422';END IF;
 IF rev IS NOT NULL THEN
 PERFORM 1 FROM ledger.journal WHERE tenant_id=t AND id=rev FOR UPDATE;
 IF NOT FOUND OR EXISTS(SELECT FROM ledger.journal WHERE tenant_id=t AND reverses=rev) THEN RAISE EXCEPTION 'ALREADY_REVERSED_OR_MISSING' USING ERRCODE='P0409'; END IF;
 IF EXISTS((SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,amount FROM ledger.posting WHERE tenant_id=t AND journal_id=rev EXCEPT ALL SELECT (v->>'account')::uuid,v->>'side',(v->>'amount')::numeric FROM jsonb_array_elements(ls)v) UNION ALL (SELECT (v->>'account')::uuid,v->>'side',(v->>'amount')::numeric FROM jsonb_array_elements(ls)v EXCEPT ALL SELECT account_id,CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,amount FROM ledger.posting WHERE tenant_id=t AND journal_id=rev)) THEN RAISE EXCEPTION 'REVERSAL_MISMATCH' USING ERRCODE='P0422'; END IF;
 END IF;
 INSERT INTO ledger.journal(tenant_id,id,period_id,currency,effective_date,reference,reverses) VALUES(t,j,p,c,dt,ref,rev);
 INSERT INTO ledger.posting SELECT t,j,ord::int,(v->>'account')::uuid,v->>'side',(v->>'amount')::numeric FROM jsonb_array_elements(ls) WITH ORDINALITY a(v,ord);
 INSERT INTO ledger.account_balance SELECT t,account_id,sum(CASE side WHEN 'DEBIT' THEN amount ELSE 0 END),sum(CASE side WHEN 'CREDIT' THEN amount ELSE 0 END) FROM ledger.posting WHERE tenant_id=t AND journal_id=j GROUP BY account_id ON CONFLICT(tenant_id,account_id) DO UPDATE SET debits=ledger.account_balance.debits+excluded.debits,credits=ledger.account_balance.credits+excluded.credits;
 INSERT INTO ledger.idempotency_receipt VALUES(t,op,k,d,j,jsonb_build_object('journalId',j));
 INSERT INTO ledger.outbox_event(tenant_id,journal_id) VALUES(t,j);
 RETURN j;
END $$;

CREATE FUNCTION ledger.assert_journal_invariants() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
DECLARE n bigint; debit numeric; credit numeric;
BEGIN
 SELECT count(*),coalesce(sum(CASE side WHEN 'DEBIT' THEN amount ELSE 0 END),0),coalesce(sum(CASE side WHEN 'CREDIT' THEN amount ELSE 0 END),0) INTO n,debit,credit FROM ledger.posting WHERE tenant_id=NEW.tenant_id AND journal_id=NEW.id;
 IF n NOT BETWEEN 2 AND 100 OR debit<>credit OR debit<=0 OR debit>999999999999999999999999999999 OR EXISTS(SELECT FROM ledger.posting p JOIN ledger.account a ON a.tenant_id=p.tenant_id AND a.id=p.account_id WHERE p.tenant_id=NEW.tenant_id AND p.journal_id=NEW.id AND a.currency<>NEW.currency) THEN RAISE EXCEPTION 'POSTING_INVARIANT_BACKSTOP' USING ERRCODE='P0422';END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION ledger.assert_journal_invariants() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER journal_invariants AFTER INSERT ON ledger.journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.assert_journal_invariants();
DO $$ BEGIN
 IF EXISTS(SELECT FROM ledger.journal j LEFT JOIN ledger.posting p ON p.tenant_id=j.tenant_id AND p.journal_id=j.id GROUP BY j.tenant_id,j.id HAVING count(p.line_no) NOT BETWEEN 2 AND 100 OR coalesce(sum(CASE p.side WHEN 'DEBIT' THEN p.amount ELSE -p.amount END),0)<>0) THEN RAISE EXCEPTION 'PREEXISTING_POSTING_INVARIANT_VIOLATIONS: stop and investigate; no history is rewritten';END IF;
END $$;
