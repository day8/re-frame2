(ns day8.re-frame2-xray.panels.shared.coord-link
  "Shared `coord-link` — the canonical 'open in editor' LABEL-as-link
  affordance, companion to `coord-chip` (the icon-only chip).

  ## Why this exists (rf2-vw5pi)

  Two shapes of panel-side click-to-source affordance live in the Xray
  panels:

  - **icon-only** — a glyph appended after an id (`:counter/value ↗`).
    The canonical home is `panels/shared/coord_chip.cljs` (rf2-xjgdk).
  - **label-as-link** — the verb / label TEXT itself is the hyperlink,
    with the `external-link` glyph appended (`reg-event ↗`). Before
    rf2-vw5pi this shape was hand-rolled ~7 times across
    `panels/epoch/view.cljs` (the HANDLER verb-link, the DISPATCH
    source label, the COEFFECT / FLOW verb ids, the machine cascade
    verb-link, the machine state-path affordance) plus the schema-
    violation action button — each re-inlining the same
    `[:button {:on-click (rf/dispatch [:rf.xray/open-in-editor …])} …]`
    boilerplate + its own `clickable?` / plain-fallback branch.

  `coord-link` folds the button + dispatch + nil-when-no-`:file`
  fallback into one place, mirroring `coord-chip`. The per-site coord
  RESOLVERS (`sub-coord`, `view-coord`, `machine-state-path-coord`, …)
  stay where they are — they are legitimately per-site; only the
  button + dispatch + fallback RENDERING is shared.

  ## What this is NOT

  `open_in_editor/open-chip` is a SEPARATE surface (an `<a href>`
  anchor for the static page, rf2-evgf5 / rf2-g5q8d) and is excluded
  by design. `coord-link` (like `coord-chip`) dispatches via the trace
  bus so the click is observable as a first-class operation on the
  `:rf/xray` frame; the `:rf.xray/open-in-editor` reg-event →
  `:rf.editor/open` reg-fx pipeline resolves the URI.

  See `panels/shared/coord_chip.cljs` for the icon-only sibling and
  spec/021 §9.1.6.2 for the contract."
  (:require [re-frame.router :as router]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.panels.epoch.icons :as icons]))

;; ---- editor-open dispatch (shared with coord-chip's contract) -----------

(defn default-dispatch
  "Fallback dispatcher for the shared open-in-editor affordances when a
  call-site supplies no captured `dispatch-fn` (rf2-r0o63). Pins to the
  PRODUCTION singleton shell frame (`defaults/default-frame-id`) rather
  than the ambient `:rf/default` resolution — the open-in-editor
  event-fx is frame-agnostic, but a host frame whose flows throw on
  every event (the deliberate-throw testbed) would entangle the
  dispatch, and the editor-bridge expects it on Xray's frame. NOT a
  bare `{:frame :rf/xray}` literal — the frame is the named
  `default-frame-id` Var, so the singleton guard passes; migrated panel
  call-sites override with the captured instance dispatcher.

  Calls the owning-ns `re-frame.router/dispatch!` directly (not the
  `rf/dispatch` macro) — this fn's OWN definition site would otherwise be
  the captured call-site for every caller, which is not useful click-to-
  source info (the real click lives in the PANEL, not here)."
  ([event-v] (router/dispatch! event-v {:frame defaults/default-frame-id}))
  ([event-v opts] (router/dispatch! event-v (assoc opts :frame defaults/default-frame-id))))

(defn open-in-editor!
  "Dispatch `[:rf.xray/open-in-editor {:source-coord coord}]`, stopping
  event propagation so a click on the affordance does not also fire an
  enclosing row / node handler. This is the ONE dispatch every panel-
  side click-to-source affordance routes through (coord-chip,
  coord-link, and the SVG-node clicks in the Reactive panel).

  `dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding `reg-view` body so the open-in-editor event lands on
  the instance frame; defaults to `default-dispatch` (the production
  singleton frame) for call-sites the rf2-nesy9 sweep hasn't yet
  threaded a captured dispatcher through."
  ([coord e] (open-in-editor! coord e default-dispatch))
  ([coord e dispatch-fn]
   (when e (.stopPropagation e))
   ((or dispatch-fn default-dispatch)
    [:rf.xray/open-in-editor {:source-coord coord}])))

(defn- coord->title
  "`open <file>:<line> in editor` title string for the affordance's
  tooltip + aria-label. Mirrors the verbatim shape every hand-rolled
  site produced before consolidation."
  [coord]
  (str "open " (:file coord)
       (when (:line coord) (str ":" (:line coord)))
       " in editor"))

;; ---- public link --------------------------------------------------------

(defn coord-link
  "Render the canonical 'open in editor' LABEL-as-link affordance — a
  `<button>` carrying `label` text + the `external-link` glyph. Returns
  a plain `<span>` (no click affordance) when `coord` lacks a `:file`,
  so a missing coord degrades cleanly to non-clickable label text
  rather than a dead button.

  Clicking dispatches `[:rf.xray/open-in-editor {:source-coord coord}]`
  on the `:rf/xray` frame (via `open-in-editor!`).

  Arguments:

  - `coord` — the source-coord map `{:file :line …}`. Nil or any value
    without a `:file` renders the plain-span fallback.
  - `label` — the link text (a string or hiccup — e.g. an `ei/mini`
    span for a syntax-token-coloured id).
  - `testid` — the `data-testid` for the rendered node (button OR
    fallback span — both carry it so a test can pin the slot
    regardless of branch).
  - `opts` (optional):
    - `:style`          — the `<button>` style map (per-site link
                          chrome: accent colour, dotted underline,
                          font sizing). REQUIRED in practice — each
                          call-site owns its link style — but defaults
                          to nil (the browser button default) so a
                          bare call still renders.
    - `:plain-style`    — the fallback `<span>` style map when no
                          coord is captured. Defaults to nil.
    - `:glyph-leading?` — when true the glyph renders BEFORE the label
                          (`↗ label`) instead of after (`label ↗`).
                          Defaults false. Used by the schema-violation
                          action button, whose grammar leads with the
                          glyph.
    - `:glyph?`         — when false, NO `external-link` glyph renders;
                          the label text alone is the link (an inline
                          underlined `schema check` style link inside
                          a prose sentence). Defaults true.
    - `:dispatch-fn`    — (rf2-r0o63) the frame-aware dispatcher
                          captured by the surrounding `reg-view` body so
                          the open-in-editor click lands on the instance
                          frame. Defaults to `default-dispatch`."
  ([coord label testid]
   (coord-link coord label testid nil))
  ([coord label testid {:keys [style plain-style glyph-leading? glyph? dispatch-fn]
                        :or   {glyph? true}}]
   (if (and (map? coord) (seq (:file coord)))
     (into [:button {:data-testid testid
                     :aria-label  (coord->title coord)
                     :title       (coord->title coord)
                     :on-click    (fn [e] (open-in-editor! coord e dispatch-fn))
                     :style       style}]
           (cond
             (not glyph?)   [label]
             glyph-leading? [(icons/external-link) label]
             :else          [label (icons/external-link)]))
     [:span {:data-testid testid
             :style       plain-style}
      label])))
