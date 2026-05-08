-- Per-user category filter preferences. iOS persists the same data in
-- UserDefaults for offline reads + skip-mode users, but we mirror it
-- here so a logged-in user installing on a 2nd device inherits their
-- picks. Same skip-the-onboarding-on-2nd-device pattern as the
-- notification frequency.
--
-- NULL means "user hasn't been asked / chose Skip" — UI treats that
-- identically to "show all". Empty array (`{}`) means "user explicitly
-- saved an empty list" which is the same UX outcome but a deliberate
-- record. Either way the UI falls back to the full backend taxonomy.
--
-- Stored as text[] rather than a join table because:
--   - the canonical list is bounded (~13 entries, see snelmind prompts),
--   - we always read/write the whole list at once (no per-row queries),
--   - PG handles array equality + GIN indexing if we ever need to.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS selected_categories TEXT[] NULL;
