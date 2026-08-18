-- The product is what determines a card's issuing limit, so it has to be stored
-- with the card rather than inferred by whoever renders it.
ALTER TABLE cards ADD COLUMN product VARCHAR(16) NOT NULL DEFAULT 'PLATINUM';

-- The default exists only to backfill the rows issued before this column. New
-- cards must state their product explicitly: a silent default would let an
-- issuing path forget it and still succeed.
ALTER TABLE cards ALTER COLUMN product DROP DEFAULT;
