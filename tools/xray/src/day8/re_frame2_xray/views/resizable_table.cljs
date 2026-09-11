(ns day8.re-frame2-xray.views.resizable-table
  "Shared draggable-column-resize affordance for Xray tables (rf2-uji72).

  Wraps a header row + N body rows in a CSS-grid layout where
  `grid-template-columns` is driven from column defs + a re-frame
  state slot of per-column pixel overrides. Adjacent column pairs
  share a 4px gutter; pointer-down on the gutter captures the
  starting widths off the live DOM (so the first drag works even
  before any override exists), pointer-move computes a delta,
  pointer-up releases the listeners.

  Widths persist to localStorage across sessions (rf2-xzg1y; see the
  §localStorage persistence section below). The view is mode-
  agnostic: consumers pass column defs, a row-key fn, a row-attrs fn,
  and a row-cells fn that returns one hiccup node per column. The
  wrapper interleaves empty spacer divs into the body-row gutter
  tracks so columns align with the header.

  ## Consumer API

      [resizable-table-view
       {:table-id   :rf.xray.epoch/subscriptions  ;; unique kw
        :columns    [{:id :sub    :label \"sub\"     :default-flex \"1fr\"}
                     {:id :inputs :label \"inputs\"  :default-flex \"1fr\"}
                     {:id :value  :label \"value\"   :default-flex \"1fr\"}]
        :rows       rows
        :row-key    (fn [row idx] (str \"sub-\" idx))
        :row-attrs  (fn [row idx] {:data-testid (str \"rf-xray-epoch-sub-row-\" idx)
                                   :style {...}})
        :row-cells  (fn [row idx] [cell-1-hiccup cell-2-hiccup cell-3-hiccup])
        :header-attrs    {...}    ;; optional
        :container-attrs {...}    ;; optional
        :header-cell-style {...}  ;; optional per-header cell style
        }]

  See `subscriptions-table` in `panels/epoch/view.cljs` for the
  canonical consumer.

  ## TWO HEADS, one renderer (rf2-fcy5)

  `resizable-table` is the Reagent `reg-view` head; `resizable-table-view`
  is the Fresco boundary. Same props, same output — both delegate to
  `render-table` and differ only in how they resolve the column-widths
  read and the frame-bound dispatcher. **Mount the one your parent is**: a
  Fresco boundary in a Reagent head position fails as loudly as the
  reverse, so a panel adopts `resizable-table-view` when its own mount
  becomes a boundary and not before.

  THAT MIGRATION IS DONE, AND `resizable-table-view` IS NOW THE ONLY MOUNT
  SPELLING IN THE TREE (rf2-k97c.3). Both consumer panels became Fresco
  boundaries — Trace in `04350d02e5`, Epoch in `5c6b56f7ab` — so all five
  production call sites head the boundary: `panels/epoch/view.cljs` ×3 and
  `panels/trace.cljs` ×2. **A new consumer mounts `resizable-table-view`.**
  The Reagent head keeps NO production call site and survives for the
  reasons its own docstring gives; do not mount it.

  ## localStorage persistence (rf2-xzg1y)

  Column-widths are durable per browser profile. The slot
  `{table-id {col-id px}}` round-trips to localStorage under the key
  `re-frame2.xray.column-widths.v1` via the
  `:rf.xray.column-widths/persist` fx (attached to every resize-pair
  + reset event) and hydrates via `hydrate!` at install-time / from
  `mount.cljs`'s first-mount hook. Mirrors the durable-pref pattern
  the Static-mode + Static-Machines selection slots use; differs from
  the transient filter / mute / frame slots that reset on every page
  load (rf2-swclw). Column widths are NOT exploration filters — they
  encode the operator's preferred reading shape for each table, so
  carrying them across sessions is the desired behaviour."
  (:require [clojure.string :as string]
            [cljs.reader :as reader]
            [re-frame.core :as rf]
            ;; rf2-fcy5 — `resizable-table-view` below is the Fresco
            ;; boundary; `resizable-table` stays the Reagent `reg-view`
            ;; head its call sites mount. Both delegate to `render-table`.
            [re-frame.fresco :as rf.fresco]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.local-storage :as ls]))

(def ^:private gutter-width-px 4)
(def ^:private min-col-width-px 24)

;; ---- localStorage round-trip (rf2-xzg1y) --------------------------------

(def default-storage-key
  "Default localStorage key the column-widths slot persists under.
  One root key carries the full `{table-id {col-id px}}` map per the
  bead's scope — no per-frame / per-host partitioning. Hand-edited
  values that fail to parse are ignored (the load falls back to the
  registry's empty default)."
  "re-frame2.xray.column-widths.v1")

(defonce ^:private storage-key (atom default-storage-key))

(defn set-storage-key!
  "Replace the localStorage key Xray uses for column-widths
  persistence. `nil` resets to the default. Hosts that mount multiple
  Xray instances (Story testbeds) can set distinct keys so each
  instance's widths stay isolated."
  [k]
  (reset! storage-key (or k default-storage-key))
  nil)

(defn get-storage-key
  "Return the current localStorage key for column-widths persistence."
  []
  @storage-key)

;; Raw browser access lives in the shared `local-storage` seam
;; (rf2-jkake.24). The key is resolved per call through
;; `get-storage-key` so a runtime `set-storage-key!` re-key takes
;; effect immediately.

(defn- read-raw
  []
  (ls/get-item (get-storage-key)))

(defn- write-raw!
  [s]
  (ls/set-item! (get-storage-key) s))

(defn clear!
  "Remove the persisted column-widths slot. Used by tests to reset
  between scenarios. No-op when localStorage is unavailable."
  []
  (ls/remove-item! (get-storage-key)))

(defn ->edn
  "Serialise `widths` (`{table-id {col-id px}}`) into a stable EDN
  string. The empty map round-trips as `\"{}\"` so the load path can
  distinguish 'empty slot' from 'no entry'."
  [widths]
  (pr-str (or widths {})))

(defn <-edn
  "Parse a stored EDN string. Returns the parsed `{table-id {col-id
  px}}` map on success, or `{}` on parse failure / unrecognised shape
  — the load path never throws into init.

  Every numeric value is coerced through `long` + clamped to
  `min-col-width-px` so a corrupted entry can't sneak a degenerate
  width past the resolver."
  [s]
  (let [parsed (try (reader/read-string s)
                    (catch :default _ nil))]
    (if (map? parsed)
      (into {}
            (keep (fn [[table-id col-map]]
                    (when (and (some? table-id) (map? col-map))
                      [table-id
                       (into {}
                             (keep (fn [[col-id px]]
                                     (when (and (some? col-id) (number? px))
                                       [col-id
                                        (long (max min-col-width-px
                                                   (long px)))])))
                             col-map)])))
            parsed)
      {})))

(defn load
  "Read + parse the persisted column-widths map. Returns `{}` when
  localStorage is unavailable / the slot is empty / the stored EDN
  is malformed."
  []
  (if-let [raw (read-raw)]
    (<-edn raw)
    {}))

(defn save!
  "Write `widths` into localStorage. No-op when localStorage is
  unavailable. Swallows quota / serialisation errors so a write
  failure cannot poison the dispatch chain."
  [widths]
  (write-raw! (->edn widths))
  nil)

;; ---- Grid-template builder ----------------------------------------------

(defn- template-track
  "Resolve one column's grid-template track — px override when
  present, the column's `:default-flex` string otherwise (typically
  `\"1fr\"` or a percentage)."
  [{:keys [id default-flex]} overrides]
  (if-let [px (get overrides id)]
    (str (max min-col-width-px (long px)) "px")
    (or default-flex "1fr")))

(defn build-template
  "Build the `grid-template-columns` string for the table — N
  tracks for columns interleaved with (N-1) `4px` gutter tracks.
  Pure-data; testable in isolation."
  [columns overrides]
  (let [tracks (map #(template-track % overrides) columns)
        gutter (str gutter-width-px "px")]
    (string/join " " (interpose gutter tracks))))

;; ---- State + events ------------------------------------------------------

(defn- write-pair
  "Pure reducer — write both adjacent columns' widths into the slot,
  clamped at the floor. Used by the `resize-pair` event-fx so the
  reducer and the persist fx land in one place."
  [db table-id left-id left-px right-id right-px]
  (-> db
      (assoc-in [:rf.xray/column-widths table-id left-id]
                (long (max min-col-width-px left-px)))
      (assoc-in [:rf.xray/column-widths table-id right-id]
                (long (max min-col-width-px right-px)))))

(defn install!
  "Register the `:rf.xray.column-widths/*` events + sub + persistence
  fx. Called from `registry/register-xray-handlers!`. Re-entrant:
  re-frame's registrar tolerates same-id re-registration.

  ## Persistence shape (rf2-xzg1y)

  Every mutation (resize-pair + reset) attaches the
  `:rf.xray.column-widths/persist` fx so the post-mutation slot lands
  in localStorage in one place — no fx-per-handler duplication. The
  `hydrate!` fn is called separately (from the orchestrator + from
  `mount.cljs`'s first-mount hook) so the persisted slot lifts into
  app-db once `:rf/xray` is registered."
  []
  ;; ---- fx ---------------------------------------------------------------
  (rf/reg-fx :rf.xray.column-widths/persist
    (fn [_ctx widths]
      (save! widths)))

  ;; ---- subs -------------------------------------------------------------
  (rf/reg-sub :rf.xray.column-widths/for-table
    (fn [db [_ table-id]]
      (get-in db [:rf.xray/column-widths table-id])))

  ;; ---- events -----------------------------------------------------------
  ;;
  ;; The drag flow is split into two events so the localStorage write
  ;; rides ONLY the commit, not every pointermove tick (rf2-xm1jy):
  ;;
  ;;   :resize-pair-tick   — pointermove cadence (~1 event/px).
  ;;                         Writes the slot in app-db; NO persist fx.
  ;;                         `:rf.trace/no-emit? true` keeps the trace
  ;;                         bus quiet at drag cadence (no panel
  ;;                         consumes the per-pixel shape).
  ;;
  ;;   :resize-pair-commit — pointerup. Persists the post-drag slot
  ;;                         exactly once. Trace-quiet too because the
  ;;                         tick event already covers the data side
  ;;                         and the operator's interest is the
  ;;                         settled widths, not the commit marker.
  ;;
  ;; Pre-rf2-xm1jy a single `:resize-pair` event-fx fired the persist
  ;; fx on every tick — ~200 localStorage writes per typical drag,
  ;; each serialising the full multi-table widths map. The split keeps
  ;; the data-flow shape explicit (one event per semantic step) and
  ;; trades a one-off pointerup commit for the bursty steady-state
  ;; pattern. `reset` keeps its single-event shape; it has no drag
  ;; cadence — one click → one mutation → one persist.

  (rf/reg-event :rf.xray.column-widths/resize-pair-tick
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ table-id left-id left-px right-id right-px]]
      {:db (write-pair db table-id left-id left-px right-id right-px)}))

  (rf/reg-event :rf.xray.column-widths/resize-pair-commit
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _]
      {:fx [[:rf.xray.column-widths/persist
             (get db :rf.xray/column-widths)]]}))

  (rf/reg-event :rf.xray.column-widths/reset
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ table-id]]
      (let [next-db (update db :rf.xray/column-widths dissoc table-id)]
        {:db next-db
         :fx [[:rf.xray.column-widths/persist
               (get next-db :rf.xray/column-widths)]]})))

  ;; ---- events: hydrate from localStorage --------------------------------
  ;;
  ;; The hydrate event is wholesale-assoc so re-running with the same
  ;; source produces the same slot. `:rf.trace/no-emit?` mirrors the
  ;; other hydrate handlers (the hydrate dispatch is a load-time
  ;; orchestration step, not a user-facing event).
  (rf/reg-event :rf.xray.column-widths/hydrate
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ widths]]
      {:db (assoc db :rf.xray/column-widths (or widths {}))})))

;; ---- hydration ----------------------------------------------------------

(defn hydrate!
  "Lift the persisted column-widths slot into the shell frame's app-db.

  Mirrors `frame-switcher/hydrate!` / `static-machines/hydrate!`:
  re-entrant; safe to call from `install!` (preload-time, before the
  frame is registered) AND from `mount.cljs/ensure-xray-frame!`
  (first open, frame registered). Both invocations converge on the
  same slot because:

    - the load read is pure;
    - the hydrate dispatch is a wholesale `(assoc db
      :rf.xray/column-widths …)` so re-running with the same source
      produces the same slot;
    - the frame guard short-circuits the pre-mount call without
      losing state — the localStorage value is still readable at
      the second call.

  `frame-id` (rf2-lnluk) defaults to the production singleton
  `defaults/default-frame-id` (`:rf/xray`). A second shell instance
  passes its own frame-id (threaded by `ensure-xray-frame!`'s first-
  mount hook) so the durable column-widths land on that instance's
  app-db.

  Returns nil. No-op when localStorage has no stored map."
  ([] (hydrate! defaults/default-frame-id))
  ([frame-id]
   (let [loaded (load)]
     (when (and (seq loaded)
                (some? (rf.frame/frame frame-id)))
       (rf/with-frame frame-id
         (rf/dispatch-sync [:rf.xray.column-widths/hydrate loaded]))
       nil))))

;; ---- Pointer plumbing ----------------------------------------------------

(defn- find-col-cell
  "Locate the rendered header cell for `col-id` inside the
  grid-row element. Cells stamp `data-rf-xray-resizable-col=<id>`
  on the wrapper div the wrapper view emits."
  [grid-el col-id]
  (.querySelector grid-el
                  (str "[data-rf-xray-resizable-col='" (name col-id) "']")))

;; rf2-65015d — hold the active drag's window listeners so an
;; interrupted drag can always tear them down. Without this the
;; `pointermove` listener stays bound to `window` whenever `pointerup`
;; is never delivered (a `pointercancel` from a touch/gesture preempt,
;; a context menu, or the pointer leaving to another window), and the
;; next `pointerdown` piles a second listener pair on top — each orphan
;; re-dispatching `resize-pair-tick` on every subsequent window
;; pointermove. `defonce` so a shadow-cljs `:after-load` mid-drag
;; doesn't strand a half-installed listener (the next pointerdown
;; re-installs cleanly). Mirrors the sibling `resize_handle.cljs`
;; drag-state + `detach-…!` pattern — `resizable-table` was the lone
;; drag surface diverging from it.
(defonce ^:private drag-state
  ;; {:on-move <fn> :on-up <fn> :on-cancel <fn>} — the bound window
  ;; handlers for the in-progress drag; nil when no drag is running.
  (atom nil))

(defn dragging?
  "Test seam — true iff a column-resize drag is in progress. Pure read
  of the drag-state atom; the drag lifecycle tests (rf2-65015d) assert
  the start/teardown transitions through it."
  []
  (some? @drag-state))

(defn- detach-window-listeners!
  "Remove the in-progress drag's window listeners and clear the drag
  state. Idempotent — a no-op when no drag is running. Runs on
  pointerup, on pointercancel, and defensively at the head of the next
  pointerdown so a stranded listener from a failed prior drag never
  double-fires or piles up. Guards on `js/window` (node-test has none)
  and swallows per-listener removal errors so teardown always reaches
  the `reset!`."
  []
  (when-let [{:keys [on-move on-up on-cancel]} @drag-state]
    (when (and (exists? js/window) (.-removeEventListener js/window))
      (try (.removeEventListener js/window "pointermove" on-move)   (catch :default _ nil))
      (try (.removeEventListener js/window "pointerup" on-up)       (catch :default _ nil))
      (try (.removeEventListener js/window "pointercancel" on-cancel) (catch :default _ nil)))
    (reset! drag-state nil)))

(defn on-pointer-down
  "Begin a drag: capture both adjacent columns' starting widths
  from the live DOM (so the first drag works even when state
  carries no override), register window-level move/up/cancel handlers,
  dispatch resize-pair events on each move, clean up on up OR cancel.

  Public for the drag-lifecycle test seam (rf2-65015d) — the shell
  never calls it except through the `header-gutter` `:on-pointer-down`.

  `dispatch-fn` (rf2-r0o63) is the frame-bound `dispatch` the
  surrounding head supplies — the `reg-view` head's injected `dispatch`
  (the macro expands it over a `capture-frame` capturing the render
  frame), or `(:dispatch (rf/capture-frame))` in the Fresco boundary,
  which is the same door written by hand. The
  pointer-move/up/cancel handlers run OUTSIDE the React tree (via raw
  `window.addEventListener`), so the dynamic frame
  context has unwound by the time they fire — but the injected
  `dispatch` already bound the instance frame synchronously during
  render, so every tick/commit lands on the SURROUNDING instance
  frame's app-db, not a `:rf/xray` literal and not the host's
  `:rf/default`. (Pre rf2-r0o63 this wrapped each dispatch in
  `(rf/with-frame :rf/xray …)` — correct for the single shell, but it
  entrenched the singleton: two shells would both write the global
  `:rf/xray` column-widths and clobber each other. The frame-bound
  `dispatch` keeps N instances isolated; it also preserves the
  Mike-pair-debug-2026-05-27 fix — dispatches never leak to the host's
  `:rf/default`.)"
  [dispatch-fn table-id left-id right-id ev]
  (.preventDefault ev)
  (.stopPropagation ev)
  ;; rf2-65015d — defensive: tear down any stranded prior drag before
  ;; attaching a fresh listener set (a missed pointerup / pointercancel
  ;; from a previous drag would otherwise leave its move listener bound
  ;; and pile a second pair here).
  (detach-window-listeners!)
  (when-let [gutter-el (.-currentTarget ev)]
    (let [grid-el  (.-parentElement gutter-el)
          left-el  (find-col-cell grid-el left-id)
          right-el (find-col-cell grid-el right-id)]
      (when (and left-el right-el)
        (let [left-px0  (.-offsetWidth left-el)
              right-px0 (.-offsetWidth right-el)
              start-x   (.-clientX ev)
              ;; rf2-xm1jy — pointermove dispatches the no-persist
              ;; tick; pointerup dispatches the commit (one
              ;; localStorage write per drag, not one per pixel).
              on-move   (fn [m-ev]
                          (let [delta     (- (.-clientX m-ev) start-x)
                                new-left  (max min-col-width-px (+ left-px0 delta))
                                new-right (max min-col-width-px (- right-px0 delta))]
                            (dispatch-fn [:rf.xray.column-widths/resize-pair-tick
                                          table-id
                                          left-id new-left
                                          right-id new-right])))
              ;; rf2-65015d — pointerup AND an interrupted-drag
              ;; pointercancel both commit the last tick (persisting the
              ;; widths already painted on screen, so stored never
              ;; diverges from displayed after a preempted drag) and
              ;; tear the listeners down. A preempted drag therefore
              ;; never strands a listener.
              finish!   (fn []
                          (dispatch-fn [:rf.xray.column-widths/resize-pair-commit])
                          (detach-window-listeners!))
              on-up     (fn [_] (finish!))
              on-cancel (fn [_] (finish!))]
          (reset! drag-state {:on-move on-move :on-up on-up :on-cancel on-cancel})
          (when (and (exists? js/window) (.-addEventListener js/window))
            (try (.addEventListener js/window "pointermove" on-move)     (catch :default _ nil))
            (try (.addEventListener js/window "pointerup" on-up)         (catch :default _ nil))
            (try (.addEventListener js/window "pointercancel" on-cancel) (catch :default _ nil))))))))

;; ---- test seams (rf2-65015d) --------------------------------------------
;; Drive the window-level drag handlers without a real DOM — node-test
;; has no `js/window`, so `on-pointer-down` sets the drag state but
;; never attaches real listeners. Mirrors resize_handle.cljs's
;; simulate-* seams: the drag-state lifecycle is the observable proxy
;; for the listener lifecycle (detach removes the listeners AND clears
;; the state in one place).

(defn simulate-move!
  "Test-only: drive the in-progress drag's pointermove handler with a
  clientX. No-op when no drag is running."
  [client-x]
  (when-let [{:keys [on-move]} @drag-state]
    (on-move #js {:clientX client-x})))

(defn simulate-up!
  "Test-only: drive the pointerup handler (commit + teardown). No-op
  when no drag is running."
  []
  (when-let [{:keys [on-up]} @drag-state]
    (on-up nil)))

(defn simulate-cancel!
  "Test-only: drive the pointercancel handler (commit + teardown).
  Covers the system-preempt path — a touch gesture override, a context
  menu, or the pointer leaving to another window. No-op when no drag is
  running."
  []
  (when-let [{:keys [on-cancel]} @drag-state]
    (on-cancel nil)))

;; ---- Header gutter (interactive) ----------------------------------------

;; Subtle 1px column-boundary line painted at the LEFT edge of the 4px
;; gutter track — i.e. flush with the right edge of the preceding header
;; cell. Anchored on `--rf-xray-text-tertiary` because the obvious choice
;; (`--rf-xray-border-subtle`) resolves to `#e8e8e8` in the current theme
;; — IDENTICAL to `--rf-xray-bg-3` (the header background), so the line
;; would be invisible. text-tertiary (`#8c959f`) is the muted-but-readable
;; mid-gray; sits a notch above the bg without shouting. On hover the
;; gutter fills with accent (the drag affordance signal); the border-left
;; disappears under the fill which is fine — the operator is grabbing the
;; handle, not reading the boundary.
;;
;; THE HOVER FILL IS CSS, AND THAT IS WHAT MAKES THE WIDGET PORTABLE
;; (rf2-fcy5). `background: transparent` is the only state this map
;; carries; `theme/global_styles.cljs` §motion-css paints the accent under
;; `[data-testid^="rf-xray-resizable-gutter-"]:hover`, with `!important`
;; because this inline declaration would otherwise win. Same shape the
;; sibling `rf-xray-event-list-col-divider-` handle already uses.
;;
;; It used to be a Form-2 Reagent component holding a `r/atom` hover flag.
;; That flag was this file's ONLY component-local state and its only
;; `reagent.core` use, and it was the one thing standing between the
;; widget and a Fresco boundary: a React function component has no form-2
;; outer body to allocate per-instance state in, and `rf.fresco/reg-state`
;; consumes an instance key rather than minting one — so keeping the hover
;; as STATE would have forced a stable instance key onto every call site.
;; With the flag gone the gutter is a pure function CALLED like
;; `body-spacer`, so it is no longer a head at all and the codec has
;; nothing to grade `:invalid`.
(def ^:private gutter-style
  {:cursor      "col-resize"
   :background  "transparent"
   :border-left "1px solid var(--rf-xray-text-tertiary)"
   :transition  "background 0.15s ease"
   :user-select "none"
   :align-self  "stretch"})

(defn- header-gutter
  "Render one interactive drag-handle between adjacent header columns.
  Pointer-down on the cell starts the drag flow; the hover paint is the
  CSS rule named above, so this holds no state and is CALLED rather than
  mounted.

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding head's body; threaded into the raw-window-listener drag
  flow so resize ticks land on the instance frame."
  [{:keys [table-id left-id right-id dispatch-fn]}]
  [:div
   {:data-testid (str "rf-xray-resizable-gutter-"
                      (name table-id) "-" (name left-id))
    :data-rf-xray-resizable-gutter (name left-id)
    :style       gutter-style
    :on-pointer-down (fn [ev]
                       (on-pointer-down dispatch-fn table-id left-id right-id ev))}])

;; ---- Body row spacer (non-interactive) ----------------------------------

(def ^:private body-spacer-style
  {:align-self "stretch"})

(defn- body-spacer
  "Empty 4px-wide div for a body row's gutter track. Non-
  interactive; lives only to occupy the grid slot so column cells
  line up with the header."
  [_left-id]
  [:div {:style body-spacer-style}])

;; ---- React keys ride the ATTRS MAP, never metadata (rf2-fcy5) ------------
;;
;; Every woven cell, gutter and row below used to be keyed with `with-meta`
;; on a vector literal. That reaches React under Reagent — `reagent.impl.
;; template` reads meta FIRST and the props map second — and reaches it
;; NOWHERE under Fresco: `re-frame.fresco.impl.codec`'s component-ABI table
;; (HD-016) takes a literal `:key` from the ATTRIBUTE MAP for every head
;; kind it accepts (native tag, `defview` boundary, host, fragment), and
;; the words `meta` / `with-meta` do not occur anywhere in that file.
;;
;; So this is not the `^{:key …}`-on-a-call-form defect rf2-hxfy fixed —
;; these keys DO work today. The hazard is that they stop working silently
;; the moment the shared widget renders under a Fresco boundary: every row
;; and every header cell in BOTH consumer panels (`panels/trace.cljs`,
;; `panels/epoch/view.cljs`) would lose its key with no error and no
;; warning, leaving React to reconcile by position.
;;
;; A `:key` in the attrs map is honoured by BOTH substrates, so moving it
;; there is a no-op today and a fix on migration — which is why it is worth
;; doing on its own terms, before anything migrates. Same repair rf2-hxfy
;; and rf2-twil made at their own sites; this is the shared widget's half.

(defn- keyed
  "Return hiccup `node` carrying React key `k` in its ATTRIBUTE MAP — the
  one place both substrates look (see the comment above).

  `node` is frequently NOT ours. `weave-header` and `weave-body` key a
  `cell` the CONSUMER built via `:row-cells`, whose second element may be
  an attrs map, a string, a nested vector, or absent. So the obvious
  `(assoc-in cell [1 :key] k)` is wrong: against `[:span \"txt\"]` it
  would REPLACE the child with a map and the text would silently vanish.
  Three cases instead:

    - second element is a map        → `k` is assoc'd into that map;
    - vector with no attrs map       → one is INSERTED at position 1 and
                                       the children shift right;
    - anything but a non-empty vector (a bare string child, nil, a seq)
                                     → returned untouched, because there
                                       is no attrs map to reach.

  An existing `:key` is overwritten — the weavers own their children's
  sibling keys. Note this deliberately does NOT clear a `:key` left in
  Clojure metadata by a caller: Reagent would still prefer that stale meta
  key over the attrs one, but no consumer puts key-meta on the cells it
  hands in (checked at tip across both panels), so policing it here would
  be machinery for a case that does not exist."
  [node k]
  (if (and (vector? node) (seq node))
    (if (map? (nth node 1 nil))
      (assoc-in node [1 :key] k)
      (into [(nth node 0) {:key k}] (subvec node 1)))
    node))

;; ---- Header + body row weavers -------------------------------------------

(defn- weave-header
  "Interleave interactive gutter handles between N header cells.
  Returns a flat seq of (2N-1) hiccup nodes. `dispatch-fn` (rf2-r0o63)
  is threaded to each gutter so the raw-window-listener drag flow
  dispatches on the surrounding instance frame."
  [header-cells columns table-id dispatch-fn]
  (let [n (count columns)]
    (apply concat
           (map-indexed
             (fn [i cell]
               (let [col-id (:id (nth columns i))]
                 (if (< i (dec n))
                   [(keyed cell (str "h-" (name col-id)))
                    ;; CALLED, not mounted (rf2-fcy5) — `header-gutter` is
                    ;; a pure fn since its hover flag became a CSS rule, so
                    ;; what lands here is the gutter's own `[:div …]` and
                    ;; its key rides that div's ATTRS MAP like every other
                    ;; woven node. Before, it was a component call form
                    ;; `[header-gutter {…}]` whose key rode the props map —
                    ;; a plain `defn` in head position, which the Fresco
                    ;; codec grades `:invalid` and refuses.
                    (keyed (header-gutter
                             {:table-id    table-id
                              :left-id     col-id
                              :right-id    (:id (nth columns (inc i)))
                              :dispatch-fn dispatch-fn})
                           (str "g-" (name col-id)))]
                   [(keyed cell (str "h-" (name col-id)))])))
             header-cells))))

(defn- weave-body
  "Interleave empty spacer divs between N body-row cells. Returns
  a flat seq of (2N-1) hiccup nodes."
  [row-cells columns]
  (let [n (count columns)]
    (apply concat
           (map-indexed
             (fn [i cell]
               (let [col-id (:id (nth columns i))]
                 (if (< i (dec n))
                   [(keyed cell (str "c-" (name col-id)))
                    (keyed (body-spacer col-id) (str "s-" (name col-id)))]
                   [(keyed cell (str "c-" (name col-id)))])))
             row-cells))))

;; ---- Header cell ---------------------------------------------------------

(defn- header-cell
  "Wrap a header label so the wrapper carries the
  `data-rf-xray-resizable-col` marker (used by the gutter
  pointer-down to find adjacent cells via DOM query)."
  [{:keys [id label header-cell-style]}]
  [:div {:data-rf-xray-resizable-col (name id)
         :style (merge {:padding "5px 8px"
                        :min-width 0}
                       header-cell-style)}
   label])

;; ---- The renderer, shared by both heads -----------------------------------

(defn- render-table
  "The widget's ENTIRE rendering, given the consumer's props, the resolved
  column-width `overrides` for this table, and the frame-bound
  `dispatch-fn` the drag flow runs on.

  Substrate-pure: each head below resolves those two values its own way
  and hands them here, so there is one renderer and nothing can drift
  between the Reagent head and the Fresco boundary. Same split
  `views/edn_inspector.cljs` uses for its own pair (§`render-inspector`).

  `overrides` may be nil — an unregistered sub, which is what a pure-render
  test that never ran `registry/register-xray-handlers!` sees. Both heads
  pass that nil straight through: `template-track` reads the map with
  `get`, so nil means 'no overrides' and the default flex tracks apply.

  ## Optional `:row-extras` (rf2-jnxfj)

  Some consumers (the Trace panel's op rows) need to render content
  BELOW a row's grid cells — sub-lists (the per-path db-diff triple
  block) and inline expansion payloads. Passing `:row-extras` as a
  `(fn [row idx] hiccup-or-nil)` switches the row layout to:

      [:div row-attrs                       ;; outer wrapper, NOT grid
        [:div {:style grid-style} ...cells/gutters...]
        ...extras...]

  Without `:row-extras` the row keeps the original layout where the
  consumer's row-attrs `:div` IS the grid (the subscriptions-table
  shape — no behavioural change for existing callers).

  ## Optional `:header?` (rf2-jnxfj)

  Defaults to `true`. The Trace panel renders a single header
  resizable-table at the top of the panel (carrying the drag-gutters)
  and then ONE resizable-table per phase band sharing the same
  `:table-id` with `:header? false` so all the bands' rows align under
  the one shared header. The `:header? false` resizable-table simply
  omits the header row; column widths are still read from the
  per-table-id slot and applied to body rows."
  [{:keys [table-id columns rows row-key row-attrs row-cells row-extras
           header-attrs container-attrs header-cell-style header?]
    :or   {header? true}}
   overrides
   dispatch-fn]
  (let [template   (build-template columns overrides)
        grid-style {:display "grid"
                    :grid-template-columns template
                    :align-items "stretch"}
        header-hiccup
        (when header?
          ;; Header — merge the consumer's :style UNDER our grid-style
          ;; so the grid layout always wins (consumer styles like
          ;; `table-header-row-style` carry `display: flex` which would
          ;; silently break the column tracks if it won).
          (into [:div (-> (or header-attrs {})
                          (assoc :style (merge (:style header-attrs)
                                               grid-style)))]
                (weave-header
                  (for [col columns]
                    (header-cell (merge col
                                        (when header-cell-style
                                          {:header-cell-style header-cell-style}))))
                  columns
                  table-id
                  dispatch-fn)))]
    (into [:div (merge {:data-rf-xray-resizable-table (name table-id)}
                       container-attrs)
           ;; Header hiccup is nil-tolerated by BOTH substrates — Reagent
           ;; skips it, and the Fresco codec's `:nothing` arm renders nil
           ;; and false as nothing (HD-016).
           header-hiccup]
          ;; Body rows — eager `map-indexed` (per rf2-9ec65 / spec/006
          ;; §Lazy-seq deref tracking, rf2-atqkg): a substrate widget
          ;; must NOT hand React a `LazySeq` chunk because any future
          ;; consumer that derefs a `(rf/subscribe ...)` inside its
          ;; `:row-cells` fn would have the deref fall outside the
          ;; parent render's reactive scope at the chunk boundary.
          ;; The shared resizable-table is the canonical place to
          ;; enforce eager realisation, not each call site.
          (map-indexed
            (fn [i row]
              (let [attrs (or (when row-attrs (row-attrs row i)) {})
                    cells (row-cells row i)
                    extras (when row-extras (row-extras row i))
                    woven (into [:<>] (weave-body cells columns))]
                (keyed
                  (if (some? extras)
                    ;; Extras path — outer wrapper carries the consumer's
                    ;; attrs untouched (preserves border-bottom, click
                    ;; handlers, etc.); an inner div carries the grid
                    ;; layout for the cells; extras render below the
                    ;; inner grid.
                    [:div attrs
                     [:div {:style grid-style} woven]
                     extras]
                    ;; Default path — the outer wrapper IS the grid
                    ;; (the subscriptions-table shape).
                    [:div (-> attrs
                              (assoc :style (merge (:style attrs) grid-style)))
                     woven])
                  (row-key row i))))
            rows))))

;; ---- The two heads -------------------------------------------------------
;;
;; BOTH SHIP, and that is the sequencing rather than an indecision
;; (rf2-fcy5). A Fresco boundary in a Reagent head position fails exactly
;; as loudly as the reverse, so the boundary could not be ADOPTED until
;; each consumer's own mount was one. Same pair, same reason, as
;; `views/edn_inspector.cljs`'s `edn-inspector` / `edn-inspector-view`.
;;
;; THAT CONDITION IS NOW MET AND THE SEQUENCING IS SPENT (rf2-k97c.3).
;; `panels/trace.cljs` became a boundary in `04350d02e5` and
;; `panels/epoch/view.cljs` in `5c6b56f7ab`, and both adopted
;; `resizable-table-view` in the same commit. Censused at the tip that
;; carries this comment: ZERO code references to the Reagent head remain
;; under `tools/xray/src` — head-position, alias-qualified or by-name via
;; `(rf/view …)` — against a control of 5 live `resizable-table-view`
;; mounts on the same instrument.
;;
;; THE REAGENT HEAD IS NOT RETIRED, AND THAT IS A DECISION RATHER THAN AN
;; OVERSIGHT. `render-table` is `defn-`, so the Reagent head is the ONLY
;; public pure-render door into it, and four test namespaces depend on
;; that door — see its docstring for the inventory. Retiring it would
;; delete real coverage to remove a var that costs a bundle-isolated dev
;; tool almost nothing. Nothing here blocks a later retirement; it would
;; just have to re-point those namespaces first.
;;
;; Neither head has any state to hold, so neither needs an instance key:
;; the widget's one piece of component-local state was the gutter's hover
;; flag, and §Header gutter is where that went.

(rf/reg-view resizable-table
  "THE REAGENT HEAD. See the ns docstring for the consumer API and
  `render-table` for the option inventory.

  ## NO PRODUCTION CONSUMER — do not mount this (rf2-k97c.3)

  Every production call site heads `resizable-table-view` since Trace and
  Epoch became Fresco boundaries; a censused ZERO code references survive
  under `tools/xray/src`. Mounting this head inside either panel now
  raises `:rf.error/fresco-bad-head`, which is the refusal
  `reagent-head-is-invalid-to-the-codec` pins.

  ## WHY IT STILL SHIPS — it is the tree's only pure-render door

  `render-table` is `defn-`. This head is the one PUBLIC way to drive it
  and get hiccup back without entering a React render window, and nine
  live call sites across four test namespaces depend on exactly that:

    - `views/resizable_table_key_cljs_test`     renders through it to
      grade React keys at both substrate doors.
    - `views/resizable_table_fresco_head_cljs_test`  grades it `:invalid`
      to the codec, and drives it for `both-heads-resolve-the-same-widths`.
    - `panels/epoch/view_cljs_test`             substitutes it for the
      boundary in `expand-widgets` / `call-widget-head`, which is what
      lets ~40 rows assert on the markup EPOCH supplies; and counts it as
      the negative control in `panel-emits-the-fresco-widget-heads-test`.
    - `panels/trace_view_cljs_test`             the same substitution in
      `lower-head`, plus the `:invalid` control in
      `panel-heads-are-the-ones-the-codec-accepts`.

  The last two roles are the ones a reader is most likely to mistake for
  dead weight: those suites assert this head does NOT appear in a panel
  tree, so the var is the REFERENT that makes a revert detectable. Delete
  it and those rows cannot be written, let alone go red.

  ## Frame-aware via `reg-view` (rf2-r0o63)

  `resizable-table` is `reg-view`-registered so its rendered component
  carries `:contextType frame-context`: the injected `subscribe` /
  `dispatch` resolve to the SURROUNDING instance frame (the shell's
  `frame-id`) through React-context. The column-widths slot is read via
  the injected `subscribe` and the raw-window-listener drag flow
  dispatches via the injected frame-bound `dispatch` — so N shells keep
  independent column widths. (Pre rf2-r0o63 this was a plain `defn`
  that escaped to a hardcoded `:rf/xray` frame via `rf/with-frame`,
  which entrenched the singleton; the `reg-view` registration is the
  same shape every other Xray panel uses.)

  Defensive nil: `subscribe` returns nil when the sub isn't registered
  (a pure-render test that never ran `registry/register-xray-handlers!`).
  A nil reaction would throw on deref, so `some->` keeps it nil and
  `render-table` reads that as 'no overrides'."
  [props]
  (render-table props
                (some-> (subscribe [:rf.xray.column-widths/for-table
                                    (:table-id props)])
                        deref)
                dispatch))

(rf.fresco/defview resizable-table-view
  "THE FRESCO BOUNDARY — a real React function component, mounted the same
  way as the Reagent head:

      [resizable-table-view {:table-id :rf.xray.epoch/subscriptions …}]

  Identical props, identical rendering: both heads hand the same map to
  `render-table` and differ only in HOW they resolve the two things the
  renderer cannot resolve for itself.

  ## The read

  `rf.fresco/sub` in place of `@(subscribe …)`. It returns the VALUE, not
  a reaction, and the edge is recorded by Fresco's own collector where the
  read happens — which is the coupling this migration exists to sever: a
  `reg-view` body's reads are tracked only by whichever reaction machinery
  the installed adapter happens to ship. No `some->` guard is needed here
  and its absence is not an oversight: an unregistered query is
  `cold-read!`'s recovery path, which answers nil (emitting
  `:rf.error/no-such-sub` once per run) rather than handing back a
  reaction to deref.

  ## The dispatcher

  `(:dispatch (rf/capture-frame))` — core's own door, answering the frame
  THIS boundary renders under. `:dispatch` rather than a bare `rf/dispatch`
  because the drag flow's ticks fire from raw `window` listeners, long
  after the render extent has unwound (see `on-pointer-down`): the
  dynamic frame context is gone by then, so the dispatcher has to have
  been bound during render. That is the case `capture-frame` documents
  itself for, and it is what keeps N shells' column widths independent.

  ## NO instance key, and that is the whole of why this head is cheap

  `edn-inspector-view` hard-throws without a caller-supplied `:mount-id`
  because a React function component has no form-2 outer body to allocate
  per-mount identity in. This widget needs none: its state lives in app-db
  keyed by the consumer's own `:table-id`, its drag bookkeeping is a
  module-level `defonce`, and the gutter's hover flag — the one piece of
  genuinely component-local state it ever had — is now a CSS rule."
  [props]
  (render-table props
                (rf.fresco/sub [:rf.xray.column-widths/for-table
                                (:table-id props)])
                (:dispatch (rf/capture-frame))))
