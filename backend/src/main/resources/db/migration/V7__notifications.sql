CREATE TABLE notification (
  id uuid PRIMARY KEY,
  recipient_id uuid NOT NULL REFERENCES board_user(id),
  actor_id uuid NOT NULL REFERENCES board_user(id),
  comment_id uuid NOT NULL REFERENCES comment(id),
  type varchar(24) NOT NULL,
  dedupe_key varchar(160) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  read_at timestamptz,
  UNIQUE(recipient_id,dedupe_key)
);
CREATE INDEX notification_inbox ON notification(recipient_id,created_at DESC,id DESC);
CREATE INDEX notification_unread ON notification(recipient_id,created_at DESC) WHERE read_at IS NULL;
