-- Switch operator authentication from WebAuthn passkeys to username + password.
-- `handle` is reused as the username; add a bcrypt password hash.
ALTER TABLE operator ADD COLUMN password_hash TEXT;
