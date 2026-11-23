ALTER TABLE board_user
  ADD COLUMN blocked_at timestamptz,
  ADD COLUMN blocked_reason varchar(240);

ALTER TABLE comment
  ADD COLUMN moderation_state varchar(16) NOT NULL DEFAULT 'visible'
    CHECK (moderation_state IN ('visible','hidden')),
  ADD COLUMN thread_state varchar(16) NOT NULL DEFAULT 'open'
    CHECK (thread_state IN ('open','resolved','locked','archived')),
  ADD COLUMN hidden_at timestamptz,
  ADD COLUMN hidden_by uuid REFERENCES board_user(id),
  ADD COLUMN hidden_reason varchar(240);

CREATE INDEX comment_moderation_state ON comment(moderation_state) WHERE moderation_state='hidden';
CREATE INDEX comment_thread_state ON comment(thread_state) WHERE parent_id IS NULL;

CREATE TABLE comment_report (
  id uuid PRIMARY KEY,
  comment_id uuid NOT NULL REFERENCES comment(id),
  reporter_id uuid NOT NULL REFERENCES board_user(id),
  reason varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'open' CHECK (status IN ('open','resolved','dismissed')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  resolved_at timestamptz,
  resolved_by uuid REFERENCES board_user(id),
  UNIQUE(comment_id,reporter_id)
);
CREATE INDEX comment_report_open ON comment_report(status,created_at) WHERE status='open';

CREATE TABLE moderation_audit (
  id uuid PRIMARY KEY,
  actor_id uuid NOT NULL REFERENCES board_user(id),
  action varchar(32) NOT NULL,
  target_type varchar(16) NOT NULL,
  target_id uuid NOT NULL,
  reason varchar(500),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX moderation_audit_recent ON moderation_audit(created_at DESC,id DESC);
