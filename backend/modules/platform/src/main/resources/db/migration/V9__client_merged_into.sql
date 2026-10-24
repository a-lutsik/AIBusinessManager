-- Client merge (owner use-case "merge duplicates"): the absorbed profile is anonymized and keeps a pointer to the
-- profile that took over its visits, packages and tasks. NULL for every client that was never absorbed.
ALTER TABLE client
    ADD COLUMN merged_into_client_id UUID NULL;

ALTER TABLE client
    ADD CONSTRAINT client_merged_into_fk FOREIGN KEY (merged_into_client_id) REFERENCES client (id);
