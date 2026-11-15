-- Storage cleanup: an administrator can remove a stored WAR file to free space while keeping the
-- release records, deployment history and audit trail. A removed file cannot be deployed or used
-- for a rollback; uploading the identical WAR again (same SHA-256) restores it.
ALTER TABLE stored_artifacts ADD COLUMN file_removed_at TIMESTAMPTZ;
ALTER TABLE stored_artifacts ADD COLUMN file_removed_by VARCHAR(60);
ALTER TABLE stored_artifacts ADD COLUMN file_removed_reason VARCHAR(500);
