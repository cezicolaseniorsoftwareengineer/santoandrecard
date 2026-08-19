-- A purchase recorded only its resulting amounts, not the rate that produced
-- them. The administered rate changes over time, so interest already charged
-- could not be traced back to the policy in force when it was charged. The rate
-- is now part of the purchase record.
--
-- The last instalment is stored for the same reason: it carries the rounding
-- remainder, and a statement that cannot state the final instalment cannot be
-- reconciled against the total.
ALTER TABLE purchases
    ADD COLUMN monthly_rate NUMERIC(9, 6),
    ADD COLUMN last_installment_amount NUMERIC(19, 2);

-- Rows written before this migration were priced under the policy that is still
-- on record for the tenant, so that is the rate they are given: no row is
-- assigned a rate its tenant never had.
--
-- Their instalments were all equal, which is exactly the defect being closed:
-- equal instalments do not generally add up to the total, so those rows are
-- short or over by a few cents against their own total. The last instalment is
-- therefore derived rather than copied — it absorbs whatever the equal split
-- left over, which is the same rule new purchases now follow. The total is not
-- touched: it is what the customer was actually charged, and rewriting it would
-- change history rather than describe it.
UPDATE purchases p
SET monthly_rate = COALESCE(
        (SELECT ip.monthly_rate FROM interest_policies ip WHERE ip.tenant_id = p.tenant_id), 0),
    last_installment_amount = p.total - p.installment_amount * (p.installments - 1);

ALTER TABLE purchases
    ALTER COLUMN monthly_rate SET NOT NULL,
    ALTER COLUMN last_installment_amount SET NOT NULL,
    ADD CONSTRAINT purchases_monthly_rate_range CHECK (monthly_rate >= 0 AND monthly_rate <= 0.60),
    ADD CONSTRAINT purchases_last_installment_positive CHECK (last_installment_amount > 0),
    -- The plan must add up. Enforced by the database because it is the one place
    -- no application path can bypass.
    ADD CONSTRAINT purchases_installments_sum_to_total
        CHECK (installment_amount * (installments - 1) + last_installment_amount = total);

-- The administered rate is capped at 60% a month. The previous bound of 100%
-- allowed a policy the product does not offer.
ALTER TABLE interest_policies
    DROP CONSTRAINT IF EXISTS interest_policies_monthly_rate_check;
UPDATE interest_policies SET monthly_rate = 0.60 WHERE monthly_rate > 0.60;
ALTER TABLE interest_policies
    ADD CONSTRAINT interest_policies_monthly_rate_range
        CHECK (monthly_rate >= 0 AND monthly_rate <= 0.60);
