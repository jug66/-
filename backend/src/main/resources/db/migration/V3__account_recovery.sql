ALTER TABLE board_user
  ADD COLUMN email varchar(320),
  ADD COLUMN email_verified_at timestamptz;

CREATE UNIQUE INDEX board_user_email_unique
  ON board_user (lower(email))
  WHERE email IS NOT NULL;

CREATE TABLE account_token (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES board_user(id) ON DELETE CASCADE,
  purpose varchar(32) NOT NULL,
  token_hash varchar(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET'))
);

CREATE INDEX account_token_lookup ON account_token(purpose, expires_at) WHERE used_at IS NULL;
