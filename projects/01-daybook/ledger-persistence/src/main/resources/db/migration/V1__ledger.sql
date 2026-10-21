CREATE SCHEMA ledger;
DO $$ BEGIN IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='daybook_runtime') THEN CREATE ROLE daybook_runtime LOGIN PASSWORD 'local-only-daybook'; END IF;END $$;
CREATE TABLE ledger.tenant(id uuid PRIMARY KEY);
CREATE TABLE ledger.account(tenant_id uuid NOT NULL REFERENCES ledger.tenant, id uuid NOT NULL, currency text NOT NULL CHECK(currency IN ('CAD','USD','JPY')), normal_side text NOT NULL CHECK(normal_side IN('DEBIT','CREDIT')), name text NOT NULL CHECK(length(name) BETWEEN 1 AND 120), PRIMARY KEY(tenant_id,id));
CREATE TABLE ledger.accounting_period(tenant_id uuid NOT NULL REFERENCES ledger.tenant,id uuid NOT NULL,start_date date NOT NULL,end_date date NOT NULL,closed boolean NOT NULL DEFAULT false,CHECK(start_date<=end_date),PRIMARY KEY(tenant_id,id));
CREATE TABLE ledger.journal(tenant_id uuid NOT NULL,id uuid NOT NULL,period_id uuid NOT NULL,currency text NOT NULL CHECK(currency IN('CAD','USD','JPY')),effective_date date NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),recorded_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),reference text NOT NULL,reverses uuid,PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,reverses),FOREIGN KEY(tenant_id,period_id) REFERENCES ledger.accounting_period,FOREIGN KEY(tenant_id,reverses) REFERENCES ledger.journal);
CREATE INDEX journal_statement_order ON ledger.journal(tenant_id,effective_date,id);
CREATE TABLE ledger.posting(tenant_id uuid NOT NULL,journal_id uuid NOT NULL,line_no int NOT NULL,account_id uuid NOT NULL,side text NOT NULL CHECK(side IN('DEBIT','CREDIT')),amount numeric(30,0) NOT NULL CHECK(amount>0 AND amount<=999999999999999999999999999999),PRIMARY KEY(tenant_id,journal_id,line_no),FOREIGN KEY(tenant_id,journal_id) REFERENCES ledger.journal,FOREIGN KEY(tenant_id,account_id) REFERENCES ledger.account);
CREATE TABLE ledger.idempotency_receipt(tenant_id uuid NOT NULL REFERENCES ledger.tenant,operation text NOT NULL,key text NOT NULL CHECK(length(key) BETWEEN 1 AND 120),digest text NOT NULL,journal_id uuid NOT NULL,response jsonb NOT NULL,PRIMARY KEY(tenant_id,operation,key),FOREIGN KEY(tenant_id,journal_id) REFERENCES ledger.journal);
CREATE TABLE ledger.account_balance(tenant_id uuid NOT NULL,account_id uuid NOT NULL,debits numeric(30,0) NOT NULL DEFAULT 0 CHECK(debits>=0),credits numeric(30,0) NOT NULL DEFAULT 0 CHECK(credits>=0),PRIMARY KEY(tenant_id,account_id),FOREIGN KEY(tenant_id,account_id) REFERENCES ledger.account);
CREATE TABLE ledger.outbox_event(id bigserial PRIMARY KEY,tenant_id uuid NOT NULL,journal_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(tenant_id,journal_id),FOREIGN KEY(tenant_id,journal_id) REFERENCES ledger.journal);
CREATE TABLE ledger.projection_checkpoint(generation uuid NOT NULL,event_id bigint NOT NULL REFERENCES ledger.outbox_event,applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(generation,event_id));
CREATE TABLE ledger.reporting_balance(generation uuid NOT NULL,tenant_id uuid NOT NULL,account_id uuid NOT NULL,debits numeric(30,0) NOT NULL,credits numeric(30,0) NOT NULL,PRIMARY KEY(generation,tenant_id,account_id),FOREIGN KEY(tenant_id,account_id) REFERENCES ledger.account);
CREATE TABLE ledger.projection_head(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),generation uuid NOT NULL);
INSERT INTO ledger.projection_head VALUES(true,'00000000-0000-0000-0000-000000000001');
CREATE TABLE ledger.statement_import(tenant_id uuid NOT NULL REFERENCES ledger.tenant,id uuid NOT NULL,content_hash text NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,content_hash));
CREATE TABLE ledger.statement_row(tenant_id uuid NOT NULL,import_id uuid NOT NULL,row_no int NOT NULL,source_id text,reference text,currency text,amount numeric(30,0),status text NOT NULL,reason text NOT NULL DEFAULT '',PRIMARY KEY(tenant_id,import_id,row_no),FOREIGN KEY(tenant_id,import_id) REFERENCES ledger.statement_import);
CREATE TABLE ledger.reconciliation_match(tenant_id uuid NOT NULL,import_id uuid NOT NULL,row_no int NOT NULL,journal_id uuid NOT NULL,line_no int NOT NULL,PRIMARY KEY(tenant_id,import_id,row_no),UNIQUE(tenant_id,journal_id,line_no),FOREIGN KEY(tenant_id,import_id,row_no) REFERENCES ledger.statement_row,FOREIGN KEY(tenant_id,journal_id,line_no) REFERENCES ledger.posting);
CREATE TABLE ledger.reconciliation_decision(id bigserial PRIMARY KEY,tenant_id uuid NOT NULL,import_id uuid NOT NULL,row_no int NOT NULL,actor text NOT NULL,reason text NOT NULL,evidence text NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(tenant_id,import_id,row_no) REFERENCES ledger.statement_row);
CREATE FUNCTION ledger.post_journal(t uuid,k text,d text,j uuid,p uuid,dt date,c text,ref text,ls jsonb,rev uuid DEFAULT NULL,op text DEFAULT 'POST') RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
DECLARE r ledger.idempotency_receipt; pr ledger.accounting_period; x record; totald numeric; totalc numeric; n int;
BEGIN
 SELECT * INTO r FROM ledger.idempotency_receipt WHERE tenant_id=t AND operation=op AND key=k;
 IF FOUND THEN IF r.digest<>d THEN RAISE EXCEPTION 'IDEMPOTENCY_CONFLICT' USING ERRCODE='P0409'; END IF;RETURN r.journal_id;END IF;
 IF length(k) NOT BETWEEN 1 AND 120 OR length(ref)>120 OR length(d)<>64 THEN RAISE EXCEPTION 'INPUT_BOUND' USING ERRCODE='P0422';END IF;
 SELECT * INTO pr FROM ledger.accounting_period WHERE tenant_id=t AND id=p FOR UPDATE;
 IF NOT FOUND OR pr.closed OR dt<pr.start_date OR dt>pr.end_date THEN RAISE EXCEPTION 'PERIOD_CLOSED_OR_DATE' USING ERRCODE='P0422';END IF;
 IF jsonb_typeof(ls)<>'array' OR jsonb_array_length(ls) NOT BETWEEN 2 AND 100 THEN RAISE EXCEPTION 'BATCH_BOUND' USING ERRCODE='P0422';END IF;
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
CREATE FUNCTION ledger.close_period(t uuid,p uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$ BEGIN UPDATE ledger.accounting_period SET closed=true WHERE tenant_id=t AND id=p; IF NOT FOUND THEN RAISE EXCEPTION 'PERIOD_NOT_FOUND' USING ERRCODE='P0404';END IF; END $$;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ledger FROM PUBLIC;
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
GRANT USAGE ON SCHEMA ledger TO daybook_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO daybook_runtime;
GRANT INSERT ON ledger.account TO daybook_runtime;
GRANT INSERT,UPDATE ON ledger.statement_import,ledger.statement_row,ledger.reconciliation_match,ledger.reconciliation_decision,ledger.projection_checkpoint,ledger.reporting_balance,ledger.projection_head TO daybook_runtime;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA ledger TO daybook_runtime;
GRANT EXECUTE ON FUNCTION ledger.post_journal(uuid,text,text,uuid,uuid,date,text,text,jsonb,uuid,text),ledger.close_period(uuid,uuid) TO daybook_runtime;
-- SHARE locks require source-table privileges; keep that capability inside the restricted boundary.
CREATE FUNCTION ledger.promote_shadow(g uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
BEGIN
 LOCK TABLE ledger.outbox_event IN SHARE MODE;
 IF EXISTS((SELECT tenant_id,account_id,debits,credits FROM ledger.account_balance EXCEPT SELECT tenant_id,account_id,debits,credits FROM ledger.reporting_balance WHERE generation=g) UNION ALL (SELECT tenant_id,account_id,debits,credits FROM ledger.reporting_balance WHERE generation=g EXCEPT SELECT tenant_id,account_id,debits,credits FROM ledger.account_balance)) THEN RAISE EXCEPTION 'SHADOW_MISMATCH_RETRY_WITH_NEW_GENERATION' USING ERRCODE='P0422';END IF;
 UPDATE ledger.projection_head SET generation=g;
END $$;
REVOKE ALL ON FUNCTION ledger.promote_shadow(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.promote_shadow(uuid) TO daybook_runtime;
