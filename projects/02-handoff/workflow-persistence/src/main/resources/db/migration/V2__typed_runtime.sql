-- Existing definitions/executions remain semantics 1; newly deployed definitions pin semantics 2.
ALTER TABLE workflow_definition ADD COLUMN semantics text NOT NULL DEFAULT '1';
ALTER TABLE workflow_history ADD COLUMN result text NOT NULL DEFAULT '[]';
ALTER TABLE activity_task ADD COLUMN output_type text NOT NULL DEFAULT 'TEXT';
