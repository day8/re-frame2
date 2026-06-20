(ns panel-gallery.core
  "Boot for the Xray panel gallery testbed (rf2-durls — redone against
  the de-singletoned shell + the four-bucket Story authoring model;
  supersedes the rf2-sszlr boot).

  ## What this testbed is

  A visual gallery of the six original L4 tab panels (Epoch · App-db ·
  Reactive · Trace · Machines · Routing) plus the full 4-layer Xray
  chrome, framed exactly like Storybook frames UI components. Issues is
  NOT a tab (rf2-gbz39 — Option (c)): issue surfacing folds INLINE into
  the Epoch panel + the L2 event-row pink-wash + the always-on issues
  ribbon signal, so there is no standalone Issues panel to gallery.
  Scroll the workspace; see what each panel looks like under varying
  state magnitude / payload shape / privacy posture. The gallery IS
  the surface a developer (Mike first) reaches for when asking 'what
  does this look like under load X?'.

  ## Intentional gallery exclusions (rf2-1sddi6 F3)

  The three cohesive-sub-domain / runtime-structure tabs added after
  this gallery's original six — **Resources** (`:resources`, EP-0016),
  **Graph** (`:derivation-graph`, EP-0014), and **Modules**
  (`:module-view`, EP-0013) — are deliberately NOT galleried here. They
  are visual *design* surfaces whose shipped-surface + focusability
  coverage lives in the feature-matrix browser sweep
  (`testbeds/feature_matrix/scenarios.cjs` `PANEL_HANDOFFS` — walks all
  nine live Dynamic tabs and asserts a real panel root, never the
  unknown-tab stub) and their own per-panel CLJS unit tests
  (`resources_cljs_test`, `derivation_graph_cljs_test`,
  `module_view_cljs_test`). The panel-gallery is the magnitude/payload
  *visual-design* harness for the six core lenses; adding the three is
  tracked separately if/when a Figma-design pass needs them. The
  exclusion is locked by an explicit assertion in
  `panel_gallery_inventory_smoke_cljs_test.cljs` so it can't silently
  rot into an unexplained gap.

  The standout is the **edn-inspector** widget gallery — the single
  CLJS-value renderer behind every panel (App-db, Trace payloads,
  Reactive sub values, Machine snapshots, Routing diffs). Its
  dedicated workspace exercises every input shape + the full opts
  surface in isolation; it is the gem of this testbed.

  Per `tools/xray/spec/018-Event-Spine.md` the chrome is four stacked
  layers — top ribbon + event list + tab bar + detail panel — with
  nine L4 tabs replacing the legacy sidebar's 16+ panels (Issues folded
  inline per rf2-gbz39; this gallery covers the six core lenses and
  intentionally excludes Resources / Graph / Modules — see the
  exclusions note above). Time Travel is folded into the spine.

  ## Per-variant frame isolation (de-singletoned shell — rf2-1w07r)

  Every gallery variant renders inside the Story canvas's per-variant
  `frame-provider`, so each cell is its own isolated re-frame frame
  (its own app-db, sub-cache, router). The per-tab galleries seed that
  frame directly with canonical Xray events (e.g. `:rf.xray/sync-epoch-
  history`). The chrome / settings / filters galleries mount the FULL
  shell — `shell/shell-view` now takes a `:frame-id` opt, and the
  `chrome-shell` wrapper threads `(rf/current-frame-id)` (the variant
  frame) into it, so the shell's own app-db also lives in the variant
  frame. N chrome cells therefore stay fully isolated in one grid; the
  variant's `:setup` events seed THAT frame, with no `:rf/xray` literal
  and no testbed-local re-dispatch shim.

  ## What this boot does

  1. Initialises re-frame with the Reagent adapter.
  2. Registers Xray's `:rf.xray/*` events / subs / fxs (without
     mounting Xray's own shell — we want the bare panels embedded
     in variants where relevant, plus the full shell mounted via the
     chrome gallery's per-variant frame, NOT auto-mounted).
  3. Installs Story's canonical vocabulary (the seven reg-* macros
     and the canonical tags / modes).
  4. Loads the per-tab gallery namespaces (their `register-all!`
     fires at namespace load).
  5. Mounts the Story shell into `#app` on `#/stories`; otherwise
     renders a tiny landing page with a link in.

  ## Why no Xray preload

  The Xray preload (`day8.re-frame2-xray.preload`) does three
  things: register handlers, register the trace collector, and
  auto-open the shell into the host's `[data-rf-xray-host]`. The
  gallery wants only the first; we don't want the shell auto-
  mounting and we don't need live trace collection (variants supply
  synthetic trace events). Calling `register-xray-handlers!`
  directly skips the parts we don't need.

  ## Bundle isolation

  This testbed is dev-only by construction: it `:requires` from
  `tools/xray/src/` and `tools/story/src/`, both of which are
  excluded from production builds via shadow-cljs build gates and
  the bundle-isolation enforcement at
  `implementation/scripts/test-bundle-isolation.sh`. Production app
  code never `:requires` anything under `tools/xray/testbeds/`."
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; rf2-2c5xb — Xray's `configure!` to seed `:project-root`
            ;; so the source-coord chips on registered handlers /
            ;; views / machines resolve their classpath-relative
            ;; `:file` slot to an absolute on-disk URI. Without this
            ;; the panel-gallery's `[open]` affordances ship the bare
            ;; classpath-relative path (`panel_gallery/foo.cljs`) to
            ;; the OS scheme handler, which rejects it.
            ;;
            ;; rf2-ymnfx (Issue A) — Story's parallel slot
            ;; (`:rf.story/project-root`) is seeded via
            ;; `story/configure!` below so the Story variant-toolbar
            ;; 'Open' button (which reads `re-frame.story.config/
            ;; project-root`, not Xray's slot) also ships an absolute
            ;; on-disk path. The Story → Xray bridge in
            ;; `xray-preset/propagate-project-root!` would otherwise
            ;; mean the Story configure! call ALSO writes Xray's slot;
            ;; we keep the explicit xray-config call so the load order
            ;; is unambiguous (Xray seeded first; Story's idempotent
            ;; re-seed of Xray is a no-op via `reset!`).
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.registry :as xray-registry]
            ;; rf2-pqulr — Xray's `:root` CSS-variable installer. Required
            ;; here because the panel-gallery embeds bare Xray widgets
            ;; without mounting the Xray shell; the shell normally calls
            ;; `global-styles/install!` from its `shell-view` reg-view body.
            ;; Without this call the tokens in
            ;; `day8.re-frame2-xray.theme.tokens` resolve their
            ;; `var(--rf-xray-*)` references to CSS fallback defaults and
            ;; every variant paints unstyled.
            [day8.re-frame2-xray.theme.global-styles :as global-styles]
            [panel-gallery.panel-views :as panel-views]
            ;; Side-effecting story registrations — namespaces fire
            ;; their `register-all!` at namespace load.
            ;; (rf2-5gl5r — `gallery-event` removed alongside the
            ;; retired Event/Handler panel; the Epoch gallery is the
            ;; canonical surface.)
            [panel-gallery.gallery-app-db]
            [panel-gallery.gallery-epoch]
            [panel-gallery.gallery-views]
            [panel-gallery.gallery-trace]
            [panel-gallery.gallery-machines]
            [panel-gallery.gallery-routing]
            ;; (rf2-gbz39 — `gallery-issues` removed alongside the
            ;; Issues tab; Option (c) folds issue surfacing into the
            ;; Epoch panel + L2 event-row pink-wash + the always-on
            ;; issues ribbon signal — no standalone Issues panel to
            ;; gallery. Chrome-under-issue-load coverage lives in
            ;; `gallery-chrome` `:story.xray.chrome/issue-load`.)
            [panel-gallery.gallery-chrome]
            ;; Chrome follow-on galleries — rf2-mpn8m settings popup,
            ;; rf2-kbrkx auto-filter pill / edit-popup.
            [panel-gallery.gallery-settings]
            [panel-gallery.gallery-filters]
            ;; Widget galleries — rf2-hp4ow edn-inspector isolation
            ;; coverage. The widget is exercised indirectly by every
            ;; L4 panel that mounts it; this gallery gives it a
            ;; dedicated harness with one variant per input shape /
            ;; opts combination.
            [panel-gallery.gallery-edn-inspector]
            ;; rf2-n2jig — mode-3 diff grammar Story set (R1-R8 +
            ;; combination + edge + theme/density). Shares the
            ;; `:panel-gallery.edn-inspector/fixture` slot + Panel
            ;; mount with the edn-inspector gallery above; the
            ;; variants pass `:full-with-diff? true` to opt into
            ;; the mode-3 chrome (R3 chip + R4 rail).
            [panel-gallery.gallery-diff-mode-3]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; Shared live-app↔Story-shell hash-router host (rf2-tq26t /
            ;; rf2-uv7sn). rf2-x31vn — panel-gallery adopts the helper so
            ;; its `hashchange` listener is installed via the helper's
            ;; remove-then-add `defonce` handle discipline rather than a
            ;; bare per-`run` `addEventListener` (which stacks a duplicate
            ;; listener on every CLJS hot-reload, since reload rebinds the
            ;; `defn` to a fresh fn the browser cannot dedupe by identity).
            [re-frame.testbed.story-host :as story-host]))

;; ============================================================================
;; LANDING — the URL `/` view (no `#/stories` hash)
;; ============================================================================

(defn- landing-view []
  [:div {:class "gallery-landing"}
   [:h1 "Xray panel gallery"]
   [:p "A visual gallery of the 6-tab Xray chrome (per "
    [:code "tools/xray/spec/018-Event-Spine.md"]
    ") and each of the six L4 tab panels — Epoch · App-db · Reactive ·
    Trace · Machines · Routing. Issues is not a tab; it surfaces inline
    in the Epoch panel + the L2 event-row pink-wash + the ribbon signal
    (rf2-gbz39)."]
   [:p "The centrepiece is the "
    [:strong "edn-inspector"]
    " widget gallery — the single CLJS-value renderer behind every
    panel — exercised in isolation across every input shape + the full
    opts surface."]
   [:p "Open the gallery at "
    [:a {:href "#/stories"} [:code "#/stories"]]
    " to scroll the workspaces."]
   [:p {:style {:margin-top "2em" :font-size "13px" :color "#7c8088"}}
    "Each variant seeds its OWN isolated frame's slots (trace-buffer,
    epoch-history, filters, machine overrides) via real Xray init
    events. The chrome / settings / filters galleries mount the full
    shell, which threads the per-variant frame into "
    [:code "shell-view"]
    "'s "
    [:code ":frame-id"]
    " opt — so N chrome cells render in one "
    [:code ":variants-grid"]
    " and stay fully isolated (driving one does not move the others)."]])

;; ============================================================================
;; MOUNT
;; ============================================================================

;; ----------------------------------------------------------------------------
;; Project-root resolution (rf2-2c5xb)
;; ----------------------------------------------------------------------------
;;
;; Source-coord `:file` slots captured at registration time are classpath-
;; relative — the panel-gallery's source root is
;; `tools/xray/testbeds/panel_gallery/`, so a coord on a panel-gallery handler
;; carries `:file "panel_gallery/foo.cljs"`. Xray's open-in-editor URI builder
;; (`re-frame.source-coords.editor-uri/editor-uri`) requires an on-disk root
;; to prepend — without it, the URI ships the bare relative path and the
;; OS-side editor handler rejects it ("Path does not exist").
;;
;; Per the rf2-5m5n2 contract Xray exposes `:rf.xray/project-root`. The
;; panel-gallery boots with its known on-disk repo position by default; a
;; `?checkout-root=<path>` query string overrides for cross-machine portability
;; (mirroring the `standard_epochs` testbed's resolver).
;;
;; The URI build is invariant to the host page URL — `resolve-uri` reads the
;; configured root, not `window.location`. The query-param branch only
;; influences which root we PASS to the configure! call; the resolver itself
;; never reaches into the document. Two real tests pin this contract: the
;; URI-build invariance to host URL is pinned by
;; `re-frame2-xray.open-in-editor-cljs-test/resolve-uri-invariant-to-host-url`,
;; and the `?checkout-root=` override branch (parse / URL-decode / trim and
;; its precedence over the build-time repo-root) is pinned by
;; `re-frame.testbed.config-cljs-test` in tools/testbed-support.

;; rf2-5dphw — open-in-editor project-root derived from the build
;; environment (the build-time `re-frame.testbed.config/checkout-root`
;; goog-define joined with this testbed's tool-relative subdir), not a
;; hardcoded personal path. `?checkout-root=<path>` still overrides per
;; session. The URI build stays invariant to the host page URL — the
;; resolver reads the configured root, not `window.location`; the
;; query-param branch only influences which root we PASS to `configure!`.
;; The host-URL invariance is pinned by
;; `re-frame2-xray.open-in-editor-cljs-test/resolve-uri-invariant-to-host-url`;
;; the `?checkout-root=` override parse + precedence is pinned by
;; `re-frame.testbed.config-cljs-test` in tools/testbed-support.
(defn- resolve-source-root []
  (testbed-config/resolve-source-root "tools/xray/testbeds"))

;; -- Routing between landing and Story shell ------------------------------
;;
;; The live-app↔Story-shell hash router + React-root host handle live in the
;; shared `re-frame.testbed.story-host` helper (rf2-tq26t / rf2-uv7sn); `run`
;; hands it `landing-view` as the live-app surface. The helper tears one React
;; root down before mounting the other on the same `#app` node, and installs
;; its `hashchange` listener via a `defonce` remove-then-add handle so a CLJS
;; hot-reload re-`run` never stacks a duplicate (rf2-x31vn).

(defn ^:export run []
  ;; rf2-2c5xb — seed `:rf.xray/project-root` BEFORE the Xray handlers
  ;; register so the first chip render reads the configured root. The
  ;; resolver reads only the configured atom; the URI build is independent
  ;; of `window.location`, so the panel-gallery's source links resolve to
  ;; the same on-disk paths regardless of which port shadow-cljs serves
  ;; the testbed from.
  (xray-config/configure! {:rf.xray/project-root (resolve-source-root)})
  ;; rf2-ymnfx (Issue A) — seed `:rf.story/project-root` for the SAME
  ;; on-disk root so the Story shell's variant-toolbar 'Open' button
  ;; (re-frame.story.ui.open-in-editor/open-chip) ships an absolute
  ;; path. Story's own atom is independent of Xray's; without this
  ;; call Story's project-root stays nil and the Open chip falls back
  ;; to the bare classpath-relative `:file` (which the OS-side editor
  ;; URI handler rejects). The Story → Xray bridge re-writes Xray's
  ;; slot to the same value — idempotent under `set-project-root!`'s
  ;; `reset!`. Mirrors the pattern in
  ;; `tools/story/testbeds/login_form/core.cljs` and
  ;; `tools/story/testbeds/counter_with_stories/core.cljs`.
  (story/configure! {:rf.story/project-root (resolve-source-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Xray's :rf.xray/* events / subs / fxs land on the registry once.
  ;; The handlers operate on the current frame's app-db, so each
  ;; variant frame the Story canvas allocates becomes its own isolated
  ;; Xray instance for the duration of the variant render — the per-tab
  ;; galleries seed it directly, and the chrome / settings / filters
  ;; galleries thread that same variant frame into `shell-view`'s
  ;; `:frame-id` opt (de-singletoned shell, rf2-1w07r). No `:rf/xray`
  ;; literal, no testbed-local seed event — the variant's `:setup`
  ;; dispatches the canonical Xray events into its own frame.
  (xray-registry/register-xray-handlers!)
  ;; rf2-pqulr — install Xray theme CSS variables on :root so
  ;; embedded widgets paint with the themed palette rather than
  ;; browser defaults. Shell normally calls this from shell-view;
  ;; the gallery bypasses the shell so we call directly.
  ;; `global-styles/install!` is idempotent (defonce-guarded +
  ;; DOM-probed via fixed id attributes per its docstring) so this
  ;; call is safe to make regardless of boot order or hot-reload.
  (global-styles/install!)
  ;; Story's canonical vocabulary (seven reg-* macros / tags / modes
  ;; / canvas decorators) installed once at boot. Each
  ;; `gallery_<tab>.cljs` namespace also calls
  ;; `install-canonical-vocabulary!` defensively at registration so
  ;; ns reload after `:after-load` is safe.
  (story/install-canonical-vocabulary!)
  ;; Defensive — register the gallery view-ids even if the gallery
  ;; namespaces loaded before this fn ran (CLJS reload order isn't
  ;; guaranteed at the application level).
  (panel-views/register!)
  ;; Wire the live-app↔Story-shell hash router (shared helper) so reloading
  ;; `#/stories` lands on the shell — and so the `hashchange` listener is
  ;; installed via the helper's remove-then-add `defonce` handle rather than a
  ;; bare per-`run` `addEventListener` that stacks duplicates across hot-reload
  ;; (rf2-x31vn). The helper renders `[landing-view]` for any non-`#/stories`
  ;; hash, matching the prior `mount-landing!`.
  (story-host/mount-with-hash-routing! landing-view))
