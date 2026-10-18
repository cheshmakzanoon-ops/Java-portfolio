CREATE TABLE IF NOT EXISTS problem_snapshot (
 tenant varchar(200) NOT NULL, problem_id varchar(200) NOT NULL, version bigint NOT NULL,
 matrix_version varchar(200) NOT NULL, input_hash char(64) NOT NULL, snapshot text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant,problem_id,version));
CREATE TABLE IF NOT EXISTS active_problem (
 tenant varchar(200) NOT NULL, problem_id varchar(200) NOT NULL, version bigint NOT NULL,
 PRIMARY KEY(tenant,problem_id), FOREIGN KEY(tenant,problem_id,version) REFERENCES problem_snapshot(tenant,problem_id,version));
CREATE TABLE IF NOT EXISTS solve_job (
 tenant varchar(200) NOT NULL, id uuid NOT NULL, problem_id varchar(200) NOT NULL, problem_version bigint NOT NULL,
 input_hash char(64) NOT NULL, matrix_version varchar(200) NOT NULL, request_key varchar(200) NOT NULL,
 algorithm_version varchar(200) NOT NULL, config_version varchar(200) NOT NULL, source_revision varchar(200) NOT NULL,
 seed bigint NOT NULL, budget_ms int NOT NULL CHECK(budget_ms BETWEEN 1 AND 30000), steps int NOT NULL CHECK(steps BETWEEN 1 AND 100000),
 state varchar(30) NOT NULL CHECK(state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','COMPLETED','CANCELLED','FAILED')),
 fence bigint NOT NULL DEFAULT 0, owner varchar(200), lease_until timestamptz, best_candidate uuid,
 stop_condition varchar(200), failure varchar(500), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), started_at timestamptz, finished_at timestamptz,
 PRIMARY KEY(tenant,id), UNIQUE(tenant,request_key), FOREIGN KEY(tenant,problem_id,problem_version) REFERENCES problem_snapshot(tenant,problem_id,version));
CREATE TABLE IF NOT EXISTS solution_candidate (
 tenant varchar(200) NOT NULL, id uuid NOT NULL, job_id uuid NOT NULL, fence bigint NOT NULL,
 authoritative boolean NOT NULL, input_hash char(64) NOT NULL, validated text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant,id),
 FOREIGN KEY(tenant,job_id) REFERENCES solve_job(tenant,id));
CREATE TABLE IF NOT EXISTS published_plan (
 tenant varchar(200) NOT NULL, id varchar(200) NOT NULL, revision bigint NOT NULL DEFAULT 0,
 problem_version bigint NOT NULL, candidate_id uuid,
 PRIMARY KEY(tenant,id));
CREATE TABLE IF NOT EXISTS plan_event (
 sequence bigserial PRIMARY KEY, tenant varchar(200) NOT NULL, problem_id varchar(200) NOT NULL,
 problem_version bigint NOT NULL, kind varchar(100) NOT NULL, details text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE INDEX IF NOT EXISTS solve_job_dispatch ON solve_job(state,created_at);

CREATE OR REPLACE FUNCTION routeweaver_immutable_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'immutable problem or candidate snapshot cannot be updated' USING ERRCODE='55000';
END $$;
CREATE OR REPLACE TRIGGER problem_snapshot_immutable BEFORE UPDATE ON problem_snapshot
 FOR EACH ROW EXECUTE FUNCTION routeweaver_immutable_snapshot();
CREATE OR REPLACE TRIGGER candidate_snapshot_immutable BEFORE UPDATE ON solution_candidate
 FOR EACH ROW EXECUTE FUNCTION routeweaver_immutable_snapshot();
