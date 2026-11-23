CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX comment_visible_content_trgm ON comment USING gin (content gin_trgm_ops)
  WHERE deleted_at IS NULL AND moderation_state='visible';
