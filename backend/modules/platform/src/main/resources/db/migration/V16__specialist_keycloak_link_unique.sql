-- One sign-in resolves to one specialist of a tenant (TenantContextFilter finds a master by the token's subject). The
-- catalog refuses a second link with KEYCLOAK_USER_ALREADY_LINKED; this index closes the race behind that check.
-- No data changes. If a tenant already holds a duplicate, the migration fails and names it, and no version with the owner
-- route for removing a link is running yet. The schema owner clears the wrong one and restarts:
--   UPDATE specialist SET keycloak_user_id = NULL WHERE id = '<specialist id>';
-- Undo: DROP INDEX specialist_keycloak_user_uq.
CREATE UNIQUE INDEX specialist_keycloak_user_uq ON specialist (tenant_id, keycloak_user_id) WHERE keycloak_user_id IS NOT NULL;
