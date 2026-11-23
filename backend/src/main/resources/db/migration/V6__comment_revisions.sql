ALTER TABLE comment
  ADD COLUMN updated_at timestamptz,
  ADD COLUMN edit_deadline timestamptz,
  ADD COLUMN revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0);

UPDATE comment
SET updated_at=created_at,
    edit_deadline=created_at + interval '15 minutes'
WHERE updated_at IS NULL OR edit_deadline IS NULL;

ALTER TABLE comment
  ALTER COLUMN updated_at SET NOT NULL,
  ALTER COLUMN edit_deadline SET NOT NULL,
  ALTER COLUMN updated_at SET DEFAULT clock_timestamp(),
  ALTER COLUMN edit_deadline SET DEFAULT clock_timestamp() + interval '15 minutes';

CREATE TABLE comment_revision (
  id uuid PRIMARY KEY,
  comment_id uuid NOT NULL REFERENCES comment(id),
  editor_id uuid NOT NULL REFERENCES board_user(id),
  revision integer NOT NULL CHECK (revision >= 0),
  content varchar(2000) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(comment_id,revision)
);
CREATE INDEX comment_revision_recent ON comment_revision(comment_id,revision DESC);
