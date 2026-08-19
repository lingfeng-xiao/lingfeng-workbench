CREATE TABLE IF NOT EXISTS gate0_machine_nonces (
  nonce_key TEXT PRIMARY KEY,
  expires_at_ms INTEGER NOT NULL,
  consumed_at_ms INTEGER NOT NULL
);
-- gate0:statement
CREATE INDEX IF NOT EXISTS idx_gate0_machine_nonces_expires_at
ON gate0_machine_nonces(expires_at_ms);
