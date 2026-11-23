CREATE TABLE upload_usage (
  scope_key varchar(120) NOT NULL,
  usage_day date NOT NULL,
  byte_size bigint NOT NULL CHECK (byte_size >= 0),
  PRIMARY KEY(scope_key,usage_day)
);
