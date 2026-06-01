(ns day8.re-frame2-xray.self-noise
  "Pure-data predicates that classify Xray's own machinery so it can be
  filtered out of the user-facing cascade list and trace surface.

  Xray's panels render INSIDE the host app. Every host dispatch dirties
  the host app-db, every layer-1 `:rf.xray/*` sub re-fires, every
  Xray panel that derefs them re-renders, and every (re-)render emits
  `:rf.sub/run` + `:rf.view/render` trace events. Without a guard
  those self-induced events would (a) flow through Xray's trace
  collector into the substrate Xray itself reads from, and (b) bucket
  as `:ungrouped :ungrounded` (they fire outside a host dispatch) —
  drowning the host event the user actually cared about.

  ## Two predicate flavours

  The framework's `:rf.trace/frame-no-emit?` flag on `:rf/xray` (per
  Spec 009 §Frame-level trace-emission opt-out and `mount.cljs/ensure-
  xray-frame!`) silences the bulk of the self-noise at the source —
  any emit inside `(rf/with-frame :rf/xray ...)` short-circuits in the
  framework's `emit!`. Two residual classes still need a Xray-side
  guard, both pure-data + JVM-runnable:

  1. **`xray-internal-event?`** — any trace event whose `:frame`
     (top-level or `:tags :frame`) resolves to `:rf/xray`. Belt-and-
     braces against reactive sub-read / view-render emits that slipped
     past the frame gate (e.g. an emit-site that hasn't been touched
     by the `:rf.trace/frame-no-emit?` migration).

  2. **`xray-internal-cascade?` / `xray-internal-event-id?`** —
     cascades whose `:event` vector's head is a keyword in the
     `rf.xray` namespace (`:rf.xray/focus-cascade`,
     `:rf.xray/select-tab`, `:rf.xray/open-settings`, etc.). These
     can be dispatched WITHOUT a `{:frame :rf/xray}` option (e.g. the
     palette's quick-actions, whose `:palette/select-panel` verb lowers
     into a plain `[:dispatch [:rf.xray/select-tab …]]` with no `:frame`);
     the framework chain-resolves them onto `:rf/default` and the
     trace envelope carries `:frame :rf/default` — so the frame gate +
     `xray-internal-event?` both miss them. The data-layer filter at
     the `:rf.xray/cascades` sub closes that hole structurally without
     forcing every call site to thread `:frame`.

  ## Pre-alpha posture

  Both predicates drop unconditionally — no \"show internals\" toggle.
  If Xray needs to introspect its own machinery, that's a separate
  feature (a parallel Xray-internal buffer would be the right shape),
  not an opt-out on the user-facing trace feed.

  ## Why a dedicated namespace

  Extracted from `trace_bus.cljc` per rf2-43koh + the rf2-3g9nw ruling
  (D4=a). The predicates are pure data with one job; sitting in a
  small, focused ns makes them discoverable + cheap to require from
  anywhere (the trace collector, the `:rf.xray/cascades` sub, the
  pre-mount seed in `mount.cljs`). The CLJC shape keeps them JVM-
  runnable so the JVM test corpus can drive every axis.")

;; ---- predicates ---------------------------------------------------------

(defn xray-internal-event?
  "True when `event`'s `:frame` slot is `:rf/xray` — i.e. the trace
  event was emitted by Xray's own subscriptions, views, or any other
  machinery running under `(rf/with-frame :rf/xray ...)`.

  Reads top-level `:frame` first, falling back to `(:tags :frame)`,
  matching the resolution order the framework's filter axis already
  uses. Both keys can carry the frame id depending on emit site (Spec
  009 §Core fields hoists some, leaves others under `:tags`).

  Pure-data + JVM-runnable so the predicate is testable without a CLJS
  runtime."
  [event]
  (= :rf/xray (or (:frame event) (get-in event [:tags :frame]))))

(defn xray-internal-event-id?
  "True when `event-id` is a keyword in the `rf.xray` namespace
  (spec/Conventions.md §Reserved namespaces — Xray's canonical
  devtool prefix).

  Pure-data + JVM-runnable; nil-safe.

  Examples:
    (xray-internal-event-id? :rf.xray/focus-cascade)  ;; true
    (xray-internal-event-id? :rf.xray/select-tab)     ;; true
    (xray-internal-event-id? :cart/add-item)           ;; false
    (xray-internal-event-id? :rf/init)                 ;; false
    (xray-internal-event-id? nil)                      ;; false
    (xray-internal-event-id? \"rf.xray/x\")           ;; false (non-kw)"
  [event-id]
  (and (keyword? event-id)
       (= "rf.xray" (namespace event-id))))

(defn xray-internal-cascade?
  "True when `cascade`'s `:event` vector's head is a Xray-internal
  event-id (see `xray-internal-event-id?`). False for cascades whose
  event vector is absent (e.g. the `:ungrouped` bucket — those are
  filtered separately by `cascade-has-event?` at the L2 boundary).

  Pure-data + JVM-runnable. Used by the `:rf.xray/cascades` sub to
  hard-filter Xray's own events out of every downstream consumer."
  [cascade]
  (let [ev (:event cascade)]
    (and (vector? ev)
         (xray-internal-event-id? (first ev)))))
