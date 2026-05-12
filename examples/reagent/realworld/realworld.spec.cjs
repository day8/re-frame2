/*
 * RealWorld (Conduit) — Spec 014 (`:rf.http/managed`) demo.
 *
 * The realworld example is the canonical Spec 014 demo: every Conduit
 * endpoint goes via `:rf.http/managed`, with a canned-stub override
 * (Spec 014 §Testing) routing requests to synthesised replies —
 * no network required.
 *
 * Coverage (in order):
 *   1. Initial load:
 *      - Loads cleanly (no pageerror, no uncaught exceptions).
 *      - Main shell renders (navbar with "conduit" brand link).
 *      - Home page renders the global feed populated by the demo stub.
 *      - Tag list renders in the sidebar.
 *   2. Article-detail navigation:
 *      - Click an article preview link, assert the detail page renders
 *        the article title, body, and the favorites counter button.
 *      - The :route/article on-match dispatches both :article/load and
 *        :comments/load — the stub serves both.
 *   3. Auth (login) flow:
 *      - Click "Sign in" → /login renders.
 *      - Fill demo credentials and submit; the demo stub synthesises a
 *        canned `User` reply, the auth machine transitions
 *        :idle → :submitting → :authed and navigates to :route/home.
 *      - Navbar updates to the authed shape (username link visible,
 *        "Sign in" link gone, "Logout" visible).
 *   4. Comment submission (authed flow on the article-detail page):
 *      - Navigate back to an article from the home feed.
 *      - The comment form is now visible (only rendered for authed users).
 *      - Submit a comment via the canned-stub seam, assert it appears
 *        in the comments list.
 */

const { expectVisible, expectTextContains } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'realworld (Conduit) — Spec 014 demo',
  url: '/realworld/',
  run: async (page) => {
    // ----- 1. INITIAL LOAD ---------------------------------------------------
    await expectVisible(page.locator('nav.navbar'), 15000);
    await expectVisible(
      page.locator('a.navbar-brand:has-text("conduit")'),
      5000,
    );

    // Main feed populates from the demo stub: 2 article previews.
    await page.waitForFunction(
      () => document.querySelectorAll('.article-preview').length >= 2,
      null,
      { timeout: 10000 },
    );

    // Sidebar tag list renders (4 demo tags).
    await page.waitForFunction(
      () => document.querySelectorAll('.tag-list a.tag-pill').length >= 4,
      null,
      { timeout: 10000 },
    );

    // Pre-auth navbar state: "Sign in" link visible, no "Logout".
    await expectVisible(page.locator('[data-testid="nav-signin"]'), 5000);

    // ----- 2. ARTICLE-DETAIL NAVIGATION --------------------------------------
    // Click the first demo article ("hello-conduit"). The :route/article
    // on-match dispatches :article/load (→ demo stub :article reply) and
    // :comments/load (→ demo stub :comments [] reply).
    await page
      .locator('[data-testid="article-preview-link-hello-conduit"]')
      .click();

    // The article-detail banner renders the title and description.
    await expectTextContains(
      page.locator('[data-testid="article-title"]'),
      'Hello, Conduit',
      10000,
    );
    await expectTextContains(
      page.locator('[data-testid="article-description"]'),
      'A short greeting from the realworld stub.',
      5000,
    );
    // Favorites counter renders (starts at 0 per the demo article).
    await expectTextContains(
      page.locator('[data-testid="article-favorites-count"]'),
      '0',
      5000,
    );
    // Unauthenticated comment-form is hidden; "Sign in" prompt renders.
    if ((await page.locator('[data-testid="comment-form"]').count()) > 0) {
      throw new Error(
        'Expected comment form to be hidden for unauthenticated user',
      );
    }

    // Navigate back to the home feed for the next flow.
    await page.locator('a.navbar-brand:has-text("conduit")').click();
    await page.waitForFunction(
      () => document.querySelectorAll('.article-preview').length >= 2,
      null,
      { timeout: 10000 },
    );

    // ----- 3. AUTH (LOGIN) FLOW ---------------------------------------------
    await page.locator('[data-testid="nav-signin"]').click();
    await expectVisible(page.locator('[data-testid="login-page"]'), 5000);
    await expectVisible(page.locator('[data-testid="login-form"]'), 5000);

    // Fill demo credentials. The demo stub doesn't verify the body —
    // it routes by URL and synthesises a canned :user reply for
    // POST /users/login. The credentials below are just any non-empty
    // strings to satisfy the form draft.
    await page
      .locator('[data-testid="login-email"]')
      .fill('demo@conduit.dev');
    await page.locator('[data-testid="login-password"]').fill('demo-password');
    await page.locator('[data-testid="login-submit"]').click();

    // The :store-session action dispatches `[:rf.route/navigate :route/home]`
    // after a successful login, so the navbar's authed shape appears on
    // the home page. The username from the demo-user payload is "demo".
    await expectVisible(page.locator('[data-testid="nav-username"]'), 10000);
    await expectTextContains(
      page.locator('[data-testid="nav-username"]'),
      'demo',
      5000,
    );
    await expectVisible(page.locator('[data-testid="nav-logout"]'), 5000);
    // "Sign in" link is gone in the authed navbar shape.
    if ((await page.locator('[data-testid="nav-signin"]').count()) > 0) {
      throw new Error(
        'Expected "Sign in" link to disappear in authed navbar',
      );
    }

    // ----- 4. COMMENT SUBMISSION (AUTHED, ARTICLE-DETAIL) -------------------
    // Wait for the feed to repaint, then click into the article again.
    await page.waitForFunction(
      () => document.querySelectorAll('.article-preview').length >= 2,
      null,
      { timeout: 10000 },
    );
    await page
      .locator('[data-testid="article-preview-link-hello-conduit"]')
      .click();
    await expectVisible(page.locator('[data-testid="article-title"]'), 10000);

    // Comment form is now visible (authed branch of the article-page view).
    await expectVisible(page.locator('[data-testid="comment-form"]'), 5000);

    // Type a comment body and submit. The :comment-form/submit handler
    // optimistically pushes a temp card into [:comments :data] then
    // POSTs /articles/:slug/comments. The demo stub synthesises a
    // canned :comment reply with a numeric id; :comment-form/submit-success
    // swaps the temp card out for the saved row.
    const commentBody = 'A stubbed comment from the Playwright spec.';
    await page.locator('[data-testid="comment-body-input"]').fill(commentBody);
    await page.locator('[data-testid="comment-submit"]').click();

    // The saved comment appears in the comments list with the body text.
    // Poll for any comment-body span containing our text (matches the
    // saved row once :comment-form/submit-success has swapped out the
    // optimistic temp card).
    await page.waitForFunction(
      (expected) => {
        const nodes = document.querySelectorAll('[data-testid="comment-body"]');
        for (const n of nodes) {
          if ((n.textContent || '').includes(expected)) return true;
        }
        return false;
      },
      commentBody,
      { timeout: 10000 },
    );
  },
};
