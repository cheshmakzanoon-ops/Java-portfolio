-- V1-V3 remain immutable. Accounting periods were previously created only by administrative SQL.
-- This restricted entry point lets the runtime role open a period for its own tenant without any
-- table-level write privilege. It is idempotent for an identical range and refuses any overlap.
CREATE FUNCTION ledger.open_period(t uuid, s date, e date) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,ledger AS $$
DECLARE existing uuid; created uuid;
BEGIN
 IF t IS NULL OR s IS NULL OR e IS NULL OR s > e OR e - s > 3660 THEN RAISE EXCEPTION 'PERIOD_RANGE' USING ERRCODE='P0422'; END IF;
 PERFORM 1 FROM ledger.tenant WHERE id=t FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'TENANT_NOT_FOUND' USING ERRCODE='P0404'; END IF;
 SELECT id INTO existing FROM ledger.accounting_period WHERE tenant_id=t AND start_date=s AND end_date=e;
 IF FOUND THEN RETURN existing; END IF;
 IF EXISTS(SELECT FROM ledger.accounting_period WHERE tenant_id=t AND start_date<=e AND end_date>=s) THEN RAISE EXCEPTION 'PERIOD_OVERLAP_CONFLICT' USING ERRCODE='P0409'; END IF;
 created := gen_random_uuid();
 INSERT INTO ledger.accounting_period(tenant_id,id,start_date,end_date) VALUES(t,created,s,e);
 RETURN created;
END $$;
REVOKE ALL ON FUNCTION ledger.open_period(uuid,date,date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.open_period(uuid,date,date) TO daybook_runtime;
