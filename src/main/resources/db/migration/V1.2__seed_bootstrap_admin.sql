-- =====================================================================
-- V1.2__seed_bootstrap_admin.sql
-- =====================================================================
-- Intentional NO-OP placeholder.
--
-- The first ADMIN user is inserted on app boot by `AuthBootstrapRunner`
-- (a CommandLineRunner) reading these env vars:
--
--   BOOTSTRAP_ADMIN_EMPLOYEE_ID
--   BOOTSTRAP_ADMIN_PASSWORD            (BCrypt-hashed at runtime)
--   BOOTSTRAP_ADMIN_SUBDIVISION_CODE    (must already exist in `subdivision`)
--
-- The runner is a no-op once an ADMIN already exists. The bootstrap admin
-- row is created with password_reset_required = TRUE so the first login
-- forces an immediate password change.
--
-- This keeps the password hash out of source control and out of Flyway SQL.
-- =====================================================================

SELECT 1;   -- so Flyway records the migration cleanly even though it does nothing

