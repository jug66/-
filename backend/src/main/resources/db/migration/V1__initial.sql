CREATE TABLE board_user (
  id uuid PRIMARY KEY,
  login_name varchar(32) NOT NULL UNIQUE,
  display_name varchar(120) NOT NULL,
  password_hash varchar(512) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (login_name ~ '^[a-z0-9_]{3,32}$'),
  CHECK (char_length(display_name) BETWEEN 1 AND 30)
);
CREATE TABLE comment (
  id uuid PRIMARY KEY,
  author_id uuid NOT NULL REFERENCES board_user(id),
  parent_id uuid REFERENCES comment(id),
  root_id uuid NOT NULL REFERENCES comment(id) DEFERRABLE INITIALLY DEFERRED,
  content text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  CHECK ((parent_id IS NULL AND root_id = id) OR (parent_id IS NOT NULL AND parent_id <> id)),
  CHECK ((deleted_at IS NULL AND content IS NOT NULL AND char_length(content) BETWEEN 1 AND 2000)
      OR (deleted_at IS NOT NULL AND content IS NULL))
);
CREATE INDEX comment_roots_time ON comment(created_at, id) WHERE parent_id IS NULL;
CREATE INDEX comment_children_time ON comment(parent_id, created_at, id);
CREATE INDEX comment_root ON comment(root_id);
CREATE TABLE creation_request (
  user_id uuid NOT NULL REFERENCES board_user(id),
  request_id uuid NOT NULL,
  payload_hash varchar(64) NOT NULL,
  comment_id uuid NOT NULL REFERENCES comment(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, request_id)
);
-- 结构只允许在创建时建立。应用之外的误更新也不能移动节点或形成环。
CREATE FUNCTION check_comment_structure() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_root uuid;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.parent_id IS DISTINCT FROM OLD.parent_id OR NEW.root_id <> OLD.root_id
       OR NEW.id <> OLD.id OR NEW.author_id <> OLD.author_id OR NEW.created_at <> OLD.created_at THEN
      RAISE EXCEPTION 'immutable comment structure';
    END IF;
  ELSIF NEW.parent_id IS NOT NULL THEN
    SELECT root_id INTO parent_root FROM comment WHERE id = NEW.parent_id;
    IF parent_root IS NULL OR parent_root <> NEW.root_id THEN
      RAISE EXCEPTION 'invalid parent root';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER comment_structure BEFORE INSERT OR UPDATE ON comment
FOR EACH ROW EXECUTE FUNCTION check_comment_structure();
