/*
 * Routing example — smoke test.
 *
 * The example's route table maps :route/home -> "/", :route/articles ->
 * "/articles", :route/article-detail -> "/articles/:id". Because we serve
 * the bundle from "/routing/", the initial pathname does not match any
 * route and the runtime renders the :rf.route/not-found page.
 *
 * Smoke test verifies routing as a navigation system:
 * - Initial render at "/routing/": not-found page renders.
 * - Click "Home" link: pushState navigates; home page renders.
 * - Click "See the articles": articles page renders; list shows seeds.
 * - Click an article link: detail page renders.
 * - Click "Back": back to articles list.
 *
 * pushState does not reload the bundle, so the SPA's route state moves
 * even though the URLs would 404 on a refresh.
 */

const { expectTextContains } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'routing',
  url: '/routing/',
  run: async (page) => {
    // Anchor on data-testid attrs (rf2-0gdsb) — visible-text selectors
    // collide with siblings as the example evolves.
    //
    // Initial — pathname is /routing/, no matching route, so not-found page.
    await expectTextContains(page.locator('h1'), 'Not found', 10000);

    // Navigate to home via the link on not-found.
    await page.getByTestId('route-link-home').click();
    await expectTextContains(page.locator('h1'), 'Welcome');

    // Click into articles.
    await page.getByTestId('route-link-articles').click();
    await expectTextContains(page.locator('h1'), 'Articles');

    // Articles list contains the seeded entries.
    await expectTextContains(page.locator('ul'), 'Intro to re-frame2');

    // Click into the intro detail page.
    await page.getByTestId('route-link-article-intro').click();
    await expectTextContains(page.locator('h1'), 'Intro to re-frame2');

    // Back returns to the list.
    await page.getByTestId('route-link-back-to-articles').click();
    await expectTextContains(page.locator('h1'), 'Articles');
  },
};
