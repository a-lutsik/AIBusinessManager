-- Decision A (stage 03): a MASTER who books by phone a client outside their own scope gets a PENDING visit that hides
-- the client from them until an OWNER confirms it. While the flag is set the master sees only the name they typed on
-- that visit (no stored name, no trust, no packages) and the visit does not put the client into the master's scope.
-- The owner's PENDING -> CONFIRMED clears it; a cancelled hidden visit keeps it, so cancelling never reveals the client.
-- Existing rows were never hidden.
ALTER TABLE appointment
    ADD COLUMN client_hidden_from_master BOOLEAN NOT NULL DEFAULT false;
