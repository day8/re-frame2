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

      [resizable-table
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
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
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
                (some? (frame/frame frame-id)))
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

(defn- on-pointer-down
  "Begin a drag: capture both adjacent columns' starting widths
  from the live DOM (so the first drag works even when state
  carries no override), register window-level move/up handlers,
  dispatch resize-pair events on each move, clean up on up.

  `dispatch-fn` (rf2-r0o63) is the frame-bound `dispatch` the
  surrounding `resizable-table` `reg-view` body injects (the macro
  expands it over a `capture-frame` capturing the render frame). The
  pointer-move/up handlers run OUTSIDE the React tree (via raw
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
              on-up     (fn on-up [_]
                          (dispatch-fn [:rf.xray.column-widths/resize-pair-commit])
                          (.removeEventListener js/window "pointermove" on-move)
                          (.removeEventListener js/window "pointerup" on-up))]
          (.addEventListener js/window "pointermove" on-move)
          (.addEventListener js/window "pointerup" on-up))))))

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
(def ^:private gutter-base-style
  {:cursor      "col-resize"
   :background  "transparent"
   :border-left "1px solid var(--rf-xray-text-tertiary)"
   :transition  "background 0.15s ease"
   :user-select "none"
   :align-self  "stretch"})

(def ^:private gutter-hover-style
  (assoc gutter-base-style :background "var(--rf-xray-accent)"))

(defn- header-gutter
  "Render one interactive drag-handle between adjacent header
  columns. Local Reagent atom tracks hover (paint the 4px column
  with the accent colour on hover); pointer-down on the cell
  starts the drag flow.

  `:dispatch-fn` (rf2-r0o63) is the frame-aware dispatcher captured by
  the surrounding `resizable-table` `reg-view` body; threaded into the
  raw-window-listener drag flow so resize ticks land on the instance
  frame."
  [_props]
  (let [hover? (r/atom false)]
    (fn [{:keys [table-id left-id right-id dispatch-fn]}]
      [:div
       {:data-testid (str "rf-xray-resizable-gutter-"
                          (name table-id) "-" (name left-id))
        :data-rf-xray-resizable-gutter (name left-id)
        :style       (if @hover? gutter-hover-style gutter-base-style)
        :on-pointer-enter #(reset! hover? true)
        :on-pointer-leave #(reset! hover? false)
        :on-pointer-down  (fn [ev]
                            (on-pointer-down dispatch-fn table-id left-id right-id ev))}])))

;; ---- Body row spacer (non-interactive) ----------------------------------

(def ^:private body-spacer-style
  {:align-self "stretch"})

(defn- body-spacer
  "Empty 4px-wide div for a body row's gutter track. Non-
  interactive; lives only to occupy the grid slot so column cells
  line up with the header."
  [_left-id]
  [:div {:style body-spacer-style}])

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
                   [(with-meta cell {:key (str "h-" (name col-id))})
                    (with-meta
                      [header-gutter
                       {:table-id    table-id
                        :left-id     col-id
                        :right-id    (:id (nth columns (inc i)))
                        :dispatch-fn dispatch-fn}]
                      {:key (str "g-" (name col-id))})]
                   [(with-meta cell {:key (str "h-" (name col-id))})])))
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
                   [(with-meta cell {:key (str "c-" (name col-id))})
                    (with-meta (body-spacer col-id)
                               {:key (str "s-" (name col-id))})]
                   [(with-meta cell {:key (str "c-" (name col-id))})])))
             row-cells))))

;; ---- Top-level view ------------------------------------------------------

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

(rf/reg-view resizable-table
  "See the ns docstring for the consumer API.

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
    :or   {header? true}}]
  ;; rf2-r0o63 — the injected `subscribe` resolves to the surrounding
  ;; instance frame via React-context (the column-widths slot lives on
  ;; THIS shell's frame), and `dispatch-fn` is the injected frame-bound
  ;; `dispatch` for the raw-window-listener drag flow (which fires after
  ;; render unwinds).
  ;;
  ;; Defensive: `subscribe` returns nil when the sub isn't registered
  ;; (e.g. in pure-render tests that exercise a view directly without
  ;; running `registry/register-xray-handlers!`). A nil reaction would
  ;; throw on deref; treat it as "no overrides" so the test render path
  ;; sees default widths and a downstream install-before-mount in
  ;; production stays the canonical path.
  (let [dispatch-fn dispatch
        reaction   (subscribe [:rf.xray.column-widths/for-table table-id])
        overrides  (some-> reaction deref)
        template   (build-template columns overrides)
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
           ;; Header hiccup is nil-tolerated by React/Reagent.
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
                (with-meta
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
                  {:key (row-key row i)})))
            rows))))
