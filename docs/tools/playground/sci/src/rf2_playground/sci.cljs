(ns rf2-playground.sci
  "SCI eval bundle for the docs/cljs playground's `cljs-rf2` cells
  (rf2-00zvt, Phase 3).

  A `cljs-rf2` cell calls re-frame2's OWN public API (`re-frame.core`
  v2) and renders via reagent2 (the reagent-slim rewrite re-frame2
  actually uses). Scittle's plugins ship STOCK reagent / re-frame and
  there is no published `scittle.core` artefact a standalone plugin
  build can `:require`, so this is NOT a Scittle plugin — it is a
  self-contained SCI eval bundle (findings doc §6 option B): a
  shadow-cljs `:browser` build that depends on `org.babashka/sci` +
  re-frame2 core + reagent-slim, builds an SCI context exposing
  re-frame2's runtime API, and exports the `renderLast` JS entry point
  the playground bootstrap calls.

  How re-frame2's API reaches a cell:

    - In compiled CLJS, `re-frame.core` carries plain-fn *aliases* for
      every `reg-*` registration (`reg-event-db`, `reg-sub`, `reg-fx`,
      `reg-cofx`, ... — the macro forms are JVM-only and only add
      source-coord capture, which a browser cell does not need). So
      `sci/copy-ns` over `re-frame.core` exposes those fn-aliases under
      their plain names; a cell writes `(rf/reg-event-db :id (fn ...))`
      and it resolves to the fn-alias. No macro support needed.

    - `dispatch` / `dispatch-sync` / `subscribe` are macro-only on the
      public surface (no same-named fn-alias — the fns are
      `dispatch*` / `dispatch-sync*` / `subscribe*`). We add explicit
      `dispatch` / `dispatch-sync` / `subscribe` entries to the SCI
      `re-frame.core` namespace bound to those `*` fns, so cells call
      `rf/dispatch` / `rf/subscribe` exactly as in real code.

  Rendering: re-frame2 renders through reagent2 with a substrate adapter
  installed (`re-frame.adapter.reagent-slim/adapter` via `rf/init!`).
  reagent2 targets React 19 (`reagent2.dom.client/create-root`). React 19
  DROPPED its UMD build, so the global-`React`-from-CDN trick is
  unavailable — this bundle BUNDLES react@19 + react-dom@19 (the
  impl-pinned versions, resolved from `sci/package.json`) directly. The
  result is one fully self-contained `playground-rf2.js`: no external
  React, no CDN, no version-mismatch risk (see `sci/shadow-cljs.edn`).

  Machines (rf2-ldgpd): `re-frame.machines` is `:require`d at the top
  of this ns so the machines artefact's late-bind hooks register at
  bundle init — that activates the `:machines/*` slots `re-frame.core-
  machines` reads on every call (`reg-machine*`, `make-machine-handler`,
  `machine-transition`, `machines`, `machine-meta`, `machine-by-system-
  id`), and registers the `:rf/machine` / `:rf/machine-has-tag?`
  framework subs + the `:rf.machine/spawn` / `:rf.machine/destroy` /
  `:rf.machine/spawn-all-init` / `:rf.machine/after-schedule` /
  `:rf.machine/after-cancel` / `:rf.machine/update-snapshot` fxs from
  the namespace's top-level forms. `sci/copy-ns re-frame.core` already
  exposes the machine aliases under their plain names; the SCI
  `re-frame.core` namespace below adds a `reg-machine` entry bound to
  the `reg-machine*` fn-alias (same pattern as the
  `dispatch`/`subscribe`/`inject-cofx` macro→fn shims) so the guide's
  ch12 cells can write `(rf/reg-machine ...)` exactly as in real code
  rather than the macro-less `reg-machine*` variant."
  (:require [sci.core :as sci]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.machines]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [reagent2.core :as r]
            [reagent2.ratom :as ratom]
            [reagent2.dom.client :as rdc]))

;; ---------------------------------------------------------------------------
;; SCI namespace configs
;; ---------------------------------------------------------------------------

(def rf-ns (sci/create-ns 're-frame.core nil))

;; copy-ns brings every public runtime var of re-frame.core into SCI —
;; that includes the reg-* fn-aliases (reg-event-db, reg-event-fx,
;; reg-sub, reg-fx, reg-cofx, ...), plus init!, configure, clear-event,
;; current-frame-id, frame-handle, frame-bound-fn*, app-db-value, etc.
;; The macro-only public names (dispatch/dispatch-sync/subscribe/
;; inject-cofx) have no same-named runtime var so they are NOT in the
;; copy; we add them below.
;;
;; Machines (rf2-ldgpd): `reg-machine` is also a JVM-only macro on the
;; public surface (per-element source-coord stamping at expansion time);
;; CLJS code reaches it via the plain-fn alias `reg-machine*`. For cells
;; the source-coord story doesn't apply, so we bind `reg-machine` to
;; `reg-machine*` exactly as we do for `dispatch`/`subscribe`/`inject-cofx`.
;; A cell calls `(rf/reg-machine :auth/flow login-flow)` and resolves to
;; the runtime fn — same shape as the chapter's prose.
(def re-frame-core-namespace
  (merge
   (sci/copy-ns re-frame.core rf-ns)
   {'dispatch      (sci/copy-var rf/dispatch* rf-ns)
    'dispatch-sync (sci/copy-var rf/dispatch-sync* rf-ns)
    'subscribe     (sci/copy-var rf/subscribe* rf-ns)
    'inject-cofx   (sci/copy-var rf/inject-cofx* rf-ns)
    'reg-machine   (sci/copy-var rf/reg-machine* rf-ns)}))

(def r-ns (sci/create-ns 'reagent2.core nil))
(def reagent2-core-namespace (sci/copy-ns reagent2.core r-ns))

(def ratom-ns (sci/create-ns 'reagent2.ratom nil))
(def reagent2-ratom-namespace (sci/copy-ns reagent2.ratom ratom-ns))

(def rdc-ns (sci/create-ns 'reagent2.dom.client nil))
(def reagent2-dom-client-namespace (sci/copy-ns reagent2.dom.client rdc-ns))

;; The SCI context. Cells require these as
;;   (require '[re-frame.core :as rf] '[reagent2.core :as r])
;; Aliases reagent.core -> reagent2.core / re-frame.core for cells that
;; copy stock-style import vectors, so the docs can use the v2 names but
;; a paste of stock idiom still resolves to the v2 surface.
(def sci-ctx
  (sci/init
   {:namespaces {'re-frame.core        re-frame-core-namespace
                 'reagent2.core        reagent2-core-namespace
                 'reagent2.ratom       reagent2-ratom-namespace
                 'reagent2.dom.client  reagent2-dom-client-namespace
                 ;; convenience aliases — cells may say `reagent.core`
                 'reagent.core         reagent2-core-namespace}}))

;; ---------------------------------------------------------------------------
;; Adapter bootstrap (idempotent)
;; ---------------------------------------------------------------------------
;;
;; re-frame2's views layer needs a substrate adapter installed before any
;; subscribe-in-component render works. Install the reagent-slim adapter
;; once, the first time the bundle is asked to eval/render anything.

(defonce ^:private inited? (atom false))

(defn- ensure-init! []
  (when-not @inited?
    (rf/init! reagent-slim-adapter/adapter)
    (reset! inited? true)))

;; ---------------------------------------------------------------------------
;; JS entry points
;; ---------------------------------------------------------------------------

;; render-root cache, keyed by the target DOM element, so re-render on
;; Mod-Enter reuses the same React root (and a Reaction stays live).
(defonce ^:private roots (atom {}))

(defn ^:export renderLast
  "Render entry (cljs-rf2 cells). Eval `src` at the SCI top level (so
  a leading `(require ...)`'s aliases reach sibling forms — same reason
  the JS bootstrap does NOT wrap render-cell source in `(do ...)`), then
  mount the LAST form's value (a hiccup vector or component vector) into
  `target-el` via reagent2's React-19 root API. Reuses an existing root
  for `target-el` so edits re-render in place."
  [src target-el]
  (ensure-init!)
  (let [component (sci/eval-string* sci-ctx src)
        root (or (get @roots target-el)
                 (let [rt (rdc/create-root target-el)]
                   (swap! roots assoc target-el rt)
                   rt))]
    (rdc/render root component)
    nil))

;; ---------------------------------------------------------------------------
;; Install the JS-visible global the bootstrap reads.
;; ---------------------------------------------------------------------------

(defn ^:export init []
  (set! (.-rf2sci js/window)
        #js {:renderLast renderLast}))

(init)
