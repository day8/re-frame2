(ns panel-gallery.core
  "Boot for the Xray panel gallery testbed, built against the
  per-frame shell + the four-bucket Story authoring model.

  ## What this testbed is

  A visual gallery of the six core L4 tab panels (Epoch · App-db ·
  Reactive · Trace · Machines · Routing) plus the full 4-layer Xray
  chrome, framed exactly like Storybook frames UI components. Issues is
  NOT a tab: issue surfacing folds INLINE into
  the Epoch panel + the L2 event-row pink-wash + the always-on issues
  ribbon signal, so there is no standalone Issues panel to gallery.
  Scroll the workspace; see what each panel looks like under varying
  state magnitude / payload shape / privacy posture. The gallery IS
  the surface a developer (Mike first) reaches for when asking 'what
  does this look like under load X?'.

  ## Intentional gallery exclusions

  Four cohesive-sub-domain / runtime-structure tabs beyond the six
  core lenses — **Resources** (`:resources`, EP-0016),
  **Graph** (`:derivation-graph`, EP-0014), **Frames**
  (`:module-view`, EP-0013) and **Hicasso** (`:hicasso`, rf2-hic-023) —
  are deliberately NOT galleried here. They
  are visual *design* surfaces whose shipped-surface + focusability
  coverage lives in the feature-matrix browser sweep
  (`testbeds/feature_matrix/scenarios.cjs` `PANEL_HANDOFFS` — walks all
  ten live Dynamic tabs and asserts a real panel root, never the
  unknown-tab stub) and their own per-panel CLJS unit tests
  (`resources_cljs_test`, `derivation_graph_cljs_test`,
  `image_view_helpers_cljs_test`, `hicasso_cljs_test`). The
  panel-gallery is the magnitude/payload *visual-design* harness for the
  six core lenses; adding the four is
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
  ten L4 tabs (Issues folded inline; this gallery covers the six core
  lenses and intentionally excludes Resources / Graph / Frames / Hicasso
  — see the exclusions note above). Time Travel is folded into the spine.

  ## Per-variant frame isolation

  Every gallery variant renders inside the Story canvas's per-variant
  `frame-provider`, so each cell is its own isolated re-frame frame
  (its own app-db, sub-cache, router). The per-tab galleries seed that
  frame directly with canonical Xray events (e.g. `:rf.xray/sync-epoch-
  history`). The chrome / settings / filters galleries mount the FULL
  shell — `shell/shell-view` takes a `:frame-id` opt, and the
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
            [re-frame.story :as rf.story]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [day8.re-frame2-xray.registry :as xray-registry]
            ;; Xray's `:root` CSS-variable installer. Required
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
            ;; their `register-all!` at namespace load. The Epoch gallery
            ;; is the canonical event/handler surface.
            [panel-gallery.gallery-app-db]
            [panel-gallery.gallery-epoch]
            [panel-gallery.gallery-views]
            [panel-gallery.gallery-trace]
            [panel-gallery.gallery-machines]
            [panel-gallery.gallery-routing]
            ;; Issue surfacing folds into the Epoch panel + L2 event-row
            ;; pink-wash + the always-on issues ribbon signal, so there
            ;; is no standalone Issues panel to gallery. Chrome-under-
            ;; issue-load coverage lives in `gallery-chrome`
            ;; `:story.xray.chrome/issue-load`.
            [panel-gallery.gallery-chrome]
            ;; Chrome follow-on galleries — settings popup,
            ;; auto-filter pill / edit-popup.
            [panel-gallery.gallery-settings]
            [panel-gallery.gallery-filters]
            ;; Widget galleries — edn-inspector isolation
            ;; coverage. The widget is exercised indirectly by every
            ;; L4 panel that mounts it; this gallery gives it a
            ;; dedicated harness with one variant per input shape /
            ;; opts combination.
            [panel-gallery.gallery-edn-inspector]
            ;; Mode-3 diff grammar Story set (R1-R8 +
            ;; combination + edge + theme/density). Shares the
            ;; `:panel-gallery.edn-inspector/fixture` slot + Panel
            ;; mount with the edn-inspector gallery above; the
            ;; variants pass `:full-with-diff? true` to opt into
            ;; the mode-3 chrome (R3 chip + R4 rail).
            [panel-gallery.gallery-diff-mode-3]
            ;; Shared live-app↔Story-shell hash-router host. The
            ;; panel-gallery uses this helper so
            ;; its `hashchange` listener is installed via the helper's
            ;; remove-then-add `defonce` handle discipline rather than a
            ;; bare per-`run` `addEventListener` (which stacks a duplicate
            ;; listener on every CLJS hot-reload, since reload rebinds the
            ;; `defn` to a fresh fn the browser cannot dedupe by identity).
            [re-frame.testbed.story-host :as rf.testbed.story-host]))

;; ============================================================================
;; LANDING — the URL `/` view (no `#/stories` hash)
;; ============================================================================

(defn- landing-view []
  [:div {:class "gallery-landing"}
   [:h1 "Xray panel gallery"]
   [:p "A visual gallery of the 4-layer Xray chrome (per "
    [:code "tools/xray/spec/018-Event-Spine.md"]
    ") and of six of its L4 tab panels — Epoch · App-db · Reactive ·
    Trace · Machines · Routing. Resources · Graph · Frames · Hicasso
    ship in the chrome but sit deliberately outside this gallery.
    Issues is not a tab; it surfaces inline
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
;; Source-file resolution
;; ----------------------------------------------------------------------------
;;
;; Source-coord `:file` slots captured at registration time may be classpath-
;; relative — the panel-gallery's source root is
;; `tools/xray/testbeds/panel_gallery/`, so a coord on a panel-gallery handler
;; can carry `:file "panel_gallery/foo.cljs"`.
;;
;; No project-root is configured here, and none is needed. The dev server
;; answers `POST /__rf-open-in-editor`
;; (`re-frame.testbed.open-in-editor-server`, wired on this build's
;; `:dev-http` entry) and resolves that relative coordinate against the live
;; JVM source paths at request time — the same `getResource` resolution
;; `re-frame.source-coords/absolutise-file` performs at macro-expansion, but
;; late enough to reach the cases the compile-time bake cannot. The client
;; ships the coordinate verbatim, so nothing here needs to know where the
;; checkout lives.
;;
;; `:rf.story/project-root` / `:rf.xray/project-root` remain available to
;; hosts running WITHOUT a re-frame2 dev server, where the client falls back
;; to an `editor://` URI that does need an absolute path. A repository
;; testbed is not such a host.
;;
;; The URI build stays invariant to the host page URL — `resolve-uri` reads
;; the configured root, not `window.location`. That is pinned by
;; `re-frame2-xray.open-in-editor-cljs-test/resolve-uri-invariant-to-host-url`.

;; -- Routing between landing and Story shell ------------------------------
;;
;; The live-app↔Story-shell hash router + React-root host handle live in the
;; shared `re-frame.testbed.story-host` helper; `run`
;; hands it `landing-view` as the live-app surface. The helper tears one React
;; root down before mounting the other on the same `#app` node, and installs
;; its `hashchange` listener via a `defonce` remove-then-add handle so a CLJS
;; hot-reload re-`run` never stacks a duplicate.

(defn ^:export run []
  (rf/init! rf.adapter.reagent/adapter)
  ;; Xray's :rf.xray/* events / subs / fxs land on the registry once.
  ;; The handlers operate on the current frame's app-db, so each
  ;; variant frame the Story canvas allocates becomes its own isolated
  ;; Xray instance for the duration of the variant render — the per-tab
  ;; galleries seed it directly, and the chrome / settings / filters
  ;; galleries thread that same variant frame into `shell-view`'s
  ;; `:frame-id` opt (per-frame shell). No `:rf/xray`
  ;; literal, no testbed-local seed event — the variant's `:setup`
  ;; dispatches the canonical Xray events into its own frame.
  (xray-registry/register-xray-handlers!)
  ;; Install Xray theme CSS variables on :root so
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
  (rf.story/install-canonical-vocabulary!)
  ;; Defensive — register the gallery view-ids even if the gallery
  ;; namespaces loaded before this fn ran (CLJS reload order isn't
  ;; guaranteed at the application level).
  (panel-views/register!)
  ;; Wire the live-app↔Story-shell hash router (shared helper) so reloading
  ;; `#/stories` lands on the shell — and so the `hashchange` listener is
  ;; installed via the helper's remove-then-add `defonce` handle rather than a
  ;; bare per-`run` `addEventListener` that stacks duplicates across hot-reload.
  ;; The helper renders `[landing-view]` for any non-`#/stories` hash.
  (rf.testbed.story-host/mount-with-hash-routing! landing-view))
