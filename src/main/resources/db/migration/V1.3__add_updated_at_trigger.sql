-- =====================================================================
-- V1.3__add_updated_at_trigger.sql
-- =====================================================================
-- Make `created_at` and `updated_at` fully DB-managed:
--   • `created_at` already has `DEFAULT now()` from V1.0 → handled on INSERT.
--   • `updated_at` needs a trigger to bump on every UPDATE.
--
-- This migration:
--   1. Creates a re-usable `set_updated_at()` trigger function.
--   2. Auto-attaches a BEFORE UPDATE trigger on every existing table that
--      has an `updated_at` column. Idempotent — uses DROP IF EXISTS.
--
-- For future tables: either include an `updated_at` column + re-run a
-- similar DO block in a later migration, or attach the trigger explicitly
-- alongside the CREATE TABLE.
-- =====================================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOR t IN
        SELECT c.table_name
          FROM information_schema.columns c
          JOIN information_schema.tables   tb
            ON  tb.table_schema = c.table_schema
            AND tb.table_name   = c.table_name
         WHERE c.table_schema = 'public'
           AND c.column_name  = 'updated_at'
           AND tb.table_type  = 'BASE TABLE'
         ORDER BY c.table_name
    LOOP
        EXECUTE format(
            'DROP TRIGGER IF EXISTS trg_%I_set_updated_at ON %I;
             CREATE TRIGGER trg_%I_set_updated_at
                BEFORE UPDATE ON %I
                FOR EACH ROW EXECUTE FUNCTION set_updated_at();',
            t, t, t, t);
    END LOOP;
END;
$$;

