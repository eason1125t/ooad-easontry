-- Preserve the original feature description separately from its stable resource code and name.
ALTER TABLE resource ADD COLUMN description VARCHAR(1000) NOT NULL DEFAULT '';
