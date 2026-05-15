# Causa Panel Launch/Layout Options

Date: 2026-05-16

Scope: analysis for bead `rf2-eehov`. This report compares the current Causa model, the re-frame-10x model, and implementation options for making Causa default-open without obscuring the app.

## Short Answer

10x's method is not better for the requested default. It is better as a mature zero-code overlay devtool: it mounts early, defaults visible on first launch, isolates CSS with Shadow DOM, persists width/visibility, and has a proven resizer. But its main panel is still a fixed overlay that slides over the app.

For Causa, copy 10x's useful lessons where they fit: default-visible state, persisted width, resizer mechanics, and possibly Shadow DOM isolation later. Do not copy 10x's overlay layout as the primary UX.

Decision update: Causa should use a true app-provided inline layout host as the primary/default developer experience. A body-padding dock is only an approximation and should not be the default path.

## Current Causa

- Causa preload registers handlers, trace callback, epoch callback, browser API exports, and `Ctrl+Shift+C`.
- It does not mount the shell at preload time.
- `mount/open!` creates `#rf-causa-root` under `document.body` and renders `[shell/shell-view]`.
- `shell-view` is hard-coded as a fixed right-edge overlay, currently about 40% width with a high z-index.
- `dock!` exists but is shallow: it marks mode attrs and applies `body.style.paddingRight = "40%"`; it does not make the shell a real left-side layout panel.
- `popout!` exists and opens a same-origin window sharing the opener runtime.
- `mount-inline-panel!` exists for single-panel embedding, not full-shell embedding.

## 10x Reference

10x defaults visible and persists its panel state. Its setup creates a Shadow DOM root, mounts immediately, reads `panel-width-ratio` with default `0.35`, reads `show-panel` with default `true`, and supports drag resizing.

The important limitation: 10x renders a fixed panel over the page. It does not reserve layout space for the app.

## Options

- 10x-style overlay: low risk and proven, but fails the product requirement because it can still obscure the app.
- Body-padding dock: keep the shell fixed, move it to the left, and reserve host-app space with `body.style.paddingLeft`. This is pragmatic and zero-code for app authors, but it is not a true inline layout and can fail for `100vw`, canvas, or fixed-position apps. It should remain fallback/debug only, not the default.
- True inline side panel: semantically best, but requires taking over or wrapping the app root, which is fragile for arbitrary apps.
- App-provided layout host: best clean layout for serious integrations, but not zero-code.
- App-provided mount point: good optional precise mode, especially for examples/testbeds.
- Pop-out-first: leaves app unobscured, but browser popup blockers make it unsuitable as default.

## Recommendation

Implement a true inline default:

- The host app provides a Causa layout host in its normal page layout, for example a left-hand `<aside>` beside the application root.
- Causa opens automatically into that host once the substrate adapter is available.
- The app remains visible and usable because normal flex/grid layout owns the relationship, not `body.style.paddingLeft`.
- The full shell becomes mode-aware instead of hard-coded right-overlay, and it can render without viewport-fixed positioning when mounted into the inline host.
- Pop-out remains exported and documented.
- Overlay/body-padding dock can remain as optional/manual/debug modes, but not the default.
- Width resizer is desirable if cheap; otherwise use a conservative fixed width and file/follow a resizer bead.

Suggested default width: prefer pixels over a pure percentage. Start around `420px`, minimum around `320px`, and cap near `min(720px, 50vw)`. The current `40%` plus `min-width: 560px` is too aggressive for a default-open left panel.

## Implementation Notes

- Do not call `open!` blindly from preload. Preload can run before the substrate adapter exists and before the host element exists. Use an idempotent adapter-ready/host-ready hook or bounded retry.
- Define the host contract in code and docs: default selector/attribute, configuration override, expected markup, and ownership of sizing/layout.
- If the host is missing, fail loudly but safely. Prefer `console.error` plus inspectable Causa status/API state and an actionable message containing the missing selector and snippet. Avoid `alert()` because it blocks startup, disrupts tests, and trains developers to dismiss the message.
- Keep default launch behind debug/dev gating.
- `Ctrl+Shift+C` should still hide/show the panel without remounting the app or losing Causa state.
- Persisted closed state is probably right once settings/local storage exists: first launch defaults open, user close can persist closed.
- Shadow DOM isolation is valuable but separable; it should not block `rf2-eehov`.

## Tests Required

- Page load shows `#rf-causa-root` and `[data-testid="rf-causa-shell"]` by default.
- Shell is on the left with expected width.
- App controls remain visible and clickable to the right.
- The panel participates in the host page's normal layout; assertions should prove there is no body-padding trick and app content is not under the panel.
- Missing-host load emits the documented diagnostic without blocking startup.
- Hide/show preserves the inline host and does not remount the application.
- Pop-out API still opens from a user gesture and renders the shell.
- Small viewport policy is explicit and tested.
- Docs/specs replace overlay-default language with left-dock-default language.
- Relevant skills explain that the Causa layout host is required for the best/default developer experience.

## Decision Point

The decision is literally true inline layout. Causa needs an app-provided layout host. Any zero-code preload/body-padding path is an approximation and should be documented as fallback/debug, not the recommended default developer experience.
