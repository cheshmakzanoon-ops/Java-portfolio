-- Instance scheduling failures are isolated from unrelated timers and retained across restart.
-- History and interpreter semantics are unchanged; these columns describe runtime retry admission.
ALTER TABLE workflow_instance
  ADD COLUMN scheduler_retry_at timestamptz NOT NULL DEFAULT '-infinity',
  ADD COLUMN scheduler_failures bigint NOT NULL DEFAULT 0 CHECK (scheduler_failures >= 0),
  ADD COLUMN last_scheduler_error text;
CREATE INDEX instance_scheduler_retry ON workflow_instance(scheduler_retry_at)
  WHERE last_scheduler_error IS NOT NULL;
