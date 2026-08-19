import { expect, Page, test } from '@playwright/test';

/**
 * One cardholder, from having no account to holding a statement.
 *
 * <p>Everything here goes through the browser and through the real identity
 * provider. That is the point: the unit suite proves the arithmetic and the
 * Postgres suite proves the locking, but neither can prove that a token minted
 * by Keycloak is accepted by card-service, that the CORS allow-list lets the
 * call through, or that the PIN gate actually stands between a session and a
 * card number. Those only fail in a browser, so they are asserted in one.
 *
 * <p>The account is created fresh on every run. A test that depends on state
 * left by the previous run passes once and then reports history.
 */

/**
 * The host the application is served from, whatever it is.
 *
 * <p>The journey has to recognise coming back from the identity provider, and
 * hard-coding the development host meant the same test could not be pointed at
 * a deployed environment — which is the environment whose redirects are most
 * worth checking, because they are the ones configured by hand.
 */
const APP_HOST = new RegExp(
  new URL(process.env.E2E_BASE_URL ?? 'http://localhost:4420').host.replace(/[.]/g, '\.')
);

/** Registration through the provider's own page — the flow the product offers. */
async function registerAndSignIn(page: Page): Promise<string> {
  const suffix = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const username = `e2e-${suffix}`;
  const password = `E2e-${suffix}-Aa1!`;

  await page.goto('/');
  await page.getByRole('button', { name: 'Criar minha conta' }).click();

  // Now on Keycloak, not on the application. The application never sees the
  // password, which is exactly why it has to be typed here.
  await page.waitForURL(/\/realms\/card-platform\//);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#password-confirm').fill(password);
  await page.locator('#email').fill(`${username}@example.test`);
  await page.locator('#firstName').fill('Ana');
  await page.locator('#lastName').fill('Souza');
  await page.getByRole('button', { name: /Register|Cadastrar/i }).click();

  // Back on the application, carrying an authorization code that the front end
  // exchanges for a token with its PKCE verifier.
  await page.waitForURL(APP_HOST);
  await expect(page.getByRole('heading', { name: /Olá, / })).toBeVisible();
  return username;
}

/** Reads a BRL figure off the screen as a number, so amounts can be compared. */
async function amount(page: Page, locator: string): Promise<number> {
  const text = (await page.locator(locator).first().innerText()).trim();
  return Number(text.replace(/[^\d,-]/g, '').replace(/\./g, '').replace(',', '.'));
}

test.describe('cardholder journey', () => {
  test('registers, issues a card, funds it, buys and sees the purchase', async ({ page }) => {
    await registerAndSignIn(page);

    // A new account holds nothing and has no card.
    await expect(page.getByRole('button', { name: 'Gerar meu cartão' })).toBeVisible();

    await test.step('issues a card at any hour, on the issuer\'s limit', async () => {
      await page.getByRole('button', { name: 'Gerar meu cartão' }).click();
      await expect(page.locator('.credit-card')).toBeVisible();
      // The limit is issuer policy. The request never carried one.
      await expect(page.locator('.limit-card')).toContainText('5.000,00');
    });

    await test.step('funds the wallet and loads the card', async () => {
      await page.locator('#deposit').fill('1.000,00');
      await page.getByRole('button', { name: 'Adicionar', exact: true }).click();
      await expect(page.locator('.toast')).toContainText('Saldo adicionado');

      await page.locator('#transfer').fill('600,00');
      await page.getByRole('button', { name: 'Transferir', exact: true }).click();
      await expect(page.locator('.toast')).toContainText('Saldo transferido');

      // Nothing was created: the wallet lost what the card gained.
      await expect.poll(() => amount(page, '.balances .balance:nth-child(1) h2')).toBe(400);
      await expect.poll(() => amount(page, '.secondary-balance')).toBe(600);
    });

    await test.step('the API prices the purchase, and the purchase agrees with it', async () => {
      await page.getByRole('button', { name: /Comprar/ }).click();
      await page.locator('#amount').fill('300,00');
      await page.locator('#installments').selectOption('1');
      await page.getByRole('button', { name: 'Calcular condições' }).click();

      const panel = page.locator('.simulation-result');
      await expect(panel).toContainText('Condições retornadas pela API');
      // Cash never carries interest, whatever the administered rate is.
      await expect(panel).toContainText('1x de');

      await page.getByRole('button', { name: 'Confirmar compra' }).click();
      await expect(page.locator('.toast')).toContainText('Compra autorizada');
    });

    await test.step('the statement shows the purchase and the card paid for it', async () => {
      await page.getByRole('button', { name: /Extrato/ }).click();
      await expect(page.locator('.transaction').first()).toContainText('Shopping');
      await expect(page.locator('.transaction').first()).toContainText('300,00');

      await page.getByRole('button', { name: /Visão geral/ }).click();
      // The card paid, not the wallet: 600 loaded less a 300 purchase.
      await expect.poll(() => amount(page, '.secondary-balance')).toBe(300);
      await expect.poll(() => amount(page, '.balances .balance:nth-child(1) h2')).toBe(400);
    });
  });

  test('the card number is behind the PIN, not behind the session', async ({ page }) => {
    await registerAndSignIn(page);
    await page.getByRole('button', { name: 'Gerar meu cartão' }).click();
    await expect(page.locator('.credit-card')).toBeVisible();

    // Signed in, and the number is still masked. A session is not authorisation
    // to read a PAN.
    await expect(page.locator('.card-number')).toContainText('••••');

    const dialog = page.getByRole('dialog');
    await page.locator('.credit-card').click();
    await expect(dialog).toContainText('Crie seu PIN');
    await dialog.locator('#pin').fill('4271');
    await dialog.getByRole('button', { name: 'Salvar PIN' }).click();

    await test.step('a wrong PIN reveals nothing and says so', async () => {
      await page.locator('.credit-card').click();
      await dialog.locator('#pin').fill('0000');
      await dialog.getByRole('button', { name: 'Revelar' }).click();
      await expect(page.locator('.toast')).toBeVisible();
      await expect(page.locator('.card-number')).toContainText('••••');
    });

    await test.step('the right PIN reveals the number, and hiding it puts it back', async () => {
      await dialog.locator('#pin').fill('4271');
      await dialog.getByRole('button', { name: 'Revelar' }).click();

      const number = page.locator('.card-number');
      await expect(number).not.toContainText('••••');
      await expect(number).toHaveText(/\d{4} \d{4} \d{4} \d{4}/);

      await page.locator('.credit-card').click();
      await expect(number).toContainText('••••');
    });
  });

  test('an administrator sees the portfolio and never a cardholder screen', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Entrar com segurança' }).click();
    await page.waitForURL(/\/realms\/card-platform\//);
    await page.locator('#username').fill('santoandreadmin');
    await page.locator('#password').fill(process.env.E2E_ADMIN_PASSWORD ?? 'admin1234');
    await page.getByRole('button', { name: /Sign In|Entrar/i }).click();

    await page.waitForURL(APP_HOST);
    await expect(page.getByRole('heading', { name: 'Carteira consolidada' })).toBeVisible();
    await expect(page.locator('.metrics-grid .metric')).toHaveCount(4);

    // The role decides the screen. There is no cardholder navigation to reach.
    await expect(page.getByRole('button', { name: /Comprar/ })).toHaveCount(0);
    await expect(page.locator('.credit-card')).toHaveCount(0);
  });
});
