-- V1 declared these columns as CHAR (bpchar), while the JPA entities map them as
-- VARCHAR. H2 in PostgreSQL compatibility mode accepted the divergence; real
-- PostgreSQL fails Hibernate schema validation at startup. Convert to VARCHAR and
-- strip the blank padding bpchar may have introduced. The existing CHECK
-- constraints remain valid and are not recreated.
ALTER TABLE cards
    ALTER COLUMN currency TYPE VARCHAR(3) USING rtrim(currency);

ALTER TABLE cards
    ALTER COLUMN last_four_digits TYPE VARCHAR(4) USING rtrim(last_four_digits);
