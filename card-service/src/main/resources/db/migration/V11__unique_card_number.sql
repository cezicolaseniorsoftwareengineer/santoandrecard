-- A card number identifies a card. Nothing enforced that: generation draws from
-- a random space large enough that a collision is unlikely, which is not the
-- same as impossible, and the backfill in V6 drew from that same space
-- independently for every pre-existing row.
--
-- Uniqueness is global rather than per tenant on purpose. A number is meant to
-- be the identifier of a physical artefact, and two tenants issuing the same one
-- would be indistinguishable to anything that reads it as a card number.
--
-- Any duplicate already present must be resolved before the index can exist, so
-- it is reported rather than silently rewritten: rewriting a number a customer
-- has already been shown is worse than refusing to migrate.
DO $$
DECLARE
    duplicates INTEGER;
BEGIN
    SELECT count(*) INTO duplicates FROM (
        SELECT card_number FROM cards GROUP BY card_number HAVING count(*) > 1
    ) AS collisions;

    IF duplicates > 0 THEN
        RAISE EXCEPTION 'cannot enforce unique card numbers: % duplicated value(s) exist', duplicates;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_cards_number ON cards (card_number);
