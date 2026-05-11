/*
 * 7GUIs #2 — Temperature Converter — smoke test.
 *
 * Tests bidirectional conversion driven through one source of truth:
 *
 * - Initial render: Celsius input shows "0", Fahrenheit shows "32.00".
 * - Type 100 in Celsius: Fahrenheit derives to 212.00.
 * - Type 32 in Fahrenheit: Celsius derives to 0.00.
 *
 * Note: this example previously failed to render because :temp/initialise
 * was registered but never dispatched. The fix calls dispatch-sync in
 * `run` before mounting.
 */

const { expectInputValue } = require('../../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'temperature (7guis #2)',
  url: '/temperature/',
  run: async (page) => {
    const inputs = page.locator('input[type="text"]');
    const celsius = inputs.nth(0);
    const fahrenheit = inputs.nth(1);

    // Initial render — :temp/initialise sets celsius to 0, typing to "0".
    await expectInputValue(celsius, '0', 10000);

    // Edit Celsius → Fahrenheit derives.
    await celsius.fill('100');
    await expectInputValue(fahrenheit, '212.00');

    // Edit Fahrenheit → Celsius derives.
    await fahrenheit.fill('32');
    await expectInputValue(celsius, '0.00');
  },
};
