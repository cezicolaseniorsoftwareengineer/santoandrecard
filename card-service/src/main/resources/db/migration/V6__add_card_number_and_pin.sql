-- The simulated card number, the derived PIN and the attempt counter that
-- defends it. The number carries a fictitious BIN that no network routes and it
-- authorises nothing; a real issuer would hold a token here and keep the PAN in
-- a vault outside this service.
ALTER TABLE cards ADD COLUMN card_number VARCHAR(16);
ALTER TABLE cards ADD COLUMN pin_salt VARCHAR(64);
ALTER TABLE cards ADD COLUMN pin_hash VARCHAR(128);

-- Four digits is ten thousand possibilities, so the derivation is not the
-- control against an online attacker: limiting attempts is. The counter belongs
-- to the card because the card is what gets locked.
ALTER TABLE cards ADD COLUMN pin_attempts INTEGER NOT NULL DEFAULT 0;

-- Cards issued before this column existed still need a number, and it has to
-- satisfy the same Luhn check the application enforces — a backfill that wrote
-- numbers the domain would reject would fail on the first read instead of here.
DO $$
DECLARE
    card RECORD;
    base TEXT;
    idx INTEGER;
    digit INTEGER;
    total INTEGER;
    doubling BOOLEAN;
BEGIN
    FOR card IN SELECT id FROM cards WHERE card_number IS NULL LOOP
        base := '999900' || LPAD(CAST(FLOOR(RANDOM() * 1000000000) AS TEXT), 9, '0');

        total := 0;
        doubling := TRUE;
        FOR idx IN REVERSE LENGTH(base)..1 LOOP
            digit := CAST(SUBSTRING(base FROM idx FOR 1) AS INTEGER);
            IF doubling THEN
                digit := digit * 2;
                IF digit > 9 THEN
                    digit := digit - 9;
                END IF;
            END IF;
            total := total + digit;
            doubling := NOT doubling;
        END LOOP;

        UPDATE cards
        SET card_number = base || CAST(((10 - (total % 10)) % 10) AS TEXT)
        WHERE id = card.id;
    END LOOP;
END $$;

ALTER TABLE cards ALTER COLUMN card_number SET NOT NULL;
ALTER TABLE cards ADD CONSTRAINT ck_cards_number_digits CHECK (card_number ~ '^[0-9]{16}$');
