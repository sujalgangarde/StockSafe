-- Run ONCE against the new RDS database, as the owner user "stocksafe".
--   psql "host=<rds_address> dbname=stocksafe user=stocksafe sslmode=require" -v app_pw="<db_app_password>" -f scripts/rds-init.sql
-- Same as docker/postgres/init/01-roles.sql, but the password comes from Secrets Manager.
CREATE ROLE stocksafe_app LOGIN PASSWORD :'app_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO stocksafe_app;
ALTER DEFAULT PRIVILEGES FOR ROLE stocksafe IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stocksafe_app;
ALTER DEFAULT PRIVILEGES FOR ROLE stocksafe IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO stocksafe_app;
