CREATE TABLE comment_attachment (
  id uuid PRIMARY KEY,
  owner_id uuid NOT NULL REFERENCES board_user(id),
  batch_id uuid NOT NULL,
  comment_id uuid REFERENCES comment(id),
  position smallint,
  storage_key varchar(255) NOT NULL UNIQUE,
  original_name varchar(120) NOT NULL,
  media_type varchar(32) NOT NULL CHECK (media_type IN ('image/jpeg','image/png')),
  byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
  width int NOT NULL CHECK (width BETWEEN 1 AND 4096),
  height int NOT NULL CHECK (height BETWEEN 1 AND 4096),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  deleted_at timestamptz,
  purged_at timestamptz,
  UNIQUE (owner_id,batch_id,position),
  UNIQUE (comment_id,position),
  CHECK ((comment_id IS NULL AND expires_at IS NOT NULL AND position BETWEEN 0 AND 2)
      OR (comment_id IS NOT NULL AND expires_at IS NULL AND position BETWEEN 0 AND 2))
);
CREATE INDEX comment_attachment_comment ON comment_attachment(comment_id,position) WHERE deleted_at IS NULL;
CREATE INDEX comment_attachment_cleanup ON comment_attachment(created_at) WHERE purged_at IS NULL;
CREATE INDEX comment_attachment_pending ON comment_attachment(owner_id,expires_at) WHERE comment_id IS NULL AND deleted_at IS NULL;
