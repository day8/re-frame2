(ns day8.re-frame2-causa.panels.trace-helpers
  "Pure-data helpers for Causa's Trace panel (Phase 5, rf2-argrj;
  epoch-scoped rework rf2-td380 + rf2-gkczt).

  ## Why a separate `.cljc` ns

  The panel view in `trace.cljs` paints a scrollable, timestamped
  ribbon of the focused epoch's trace events and dispatches into the
  Causa frame. The *logic* — projecting raw events into row shape and
  classifying the empty state — is pure data → data. Splitting the
  algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Epoch-scoped feed (rf2-td380)

  Per spec/018 §6 every L4 panel is a lens on the spine's focused
  event, not a global ribbon. The Trace tab reads the FOCUSED EPOCH's
  `:trace-events` (the per-frame settling epoch record's raw trace
  slice — `re-frame.epoch/epoch-history` keeps oldest-first records,
  each carrying the complete domino trail for one event: the
  synchronous event-side dispatch-id-N events AND the async
  nil-dispatch-id reactive events — `:sub/run` / `:view/render` —
  that fire post-cascade for that settling). The prior shape scoped
  the global trace bus by `:dispatch-id`, which DROPPED those async
  reactive rows (they carry a nil dispatch-id), so the rendered trail
  was incomplete (e.g. 4 rows shown vs the epoch's real 12). Reading
  the epoch record's `:trace-events` folds both sides, so rendered
  rows = the complete domino trail (event → db-changed → subs ran →
  views rendered).

  ## No chip filtering (rf2-gkczt)

  Per Mike-direction 2026-05-22 the Trace panel surfaces the
  epoch-scoped rows with NO filtering UI — the focused epoch IS the
  scope, and the per-row payload-expand affordance is the drill-down.
  The 13-axis filter vocabulary, per-axis chip enumeration, and the
  buffer-snapshot incremental projection are gone."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- short-description ---------------------------------------------------

(defn short-description
  "Build a one-line per-row description. Reads (in priority order):

    1. `[:tags :event]`             — dispatched event vector
    2. `[:tags :reason]`            — most error categories carry this
    3. `[:tags :exception-message]` — handler / fx exceptions
    4. `[:tags :sub-id]`            — sub-run / sub-create
    5. `[:tags :fx-id]`             — fx invocations
    6. `[:tags :render-key]`        — view renders
    7. `(str operation)` only       — fallback

  Pure data → string; JVM-testable."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (when (vector? (:event tags))
                     (try (pr-str (:event tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                   (:reason tags)
                   (:exception-message tags)
                   (when (some? (:sub-id tags))
                     (str (:sub-id tags)))
                   (when (some? (:fx-id tags))
                     (str (:fx-id tags)))
                   (when (some? (:render-key tags))
                     (try (pr-str (:render-key tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil))))]
    (if (and detail (not (str/blank? (str detail))))
      (str op-str " — " detail)
      op-str)))

;; ---- source-coord projection --------------------------------------------

(defn source-coord
  "Extract a `file:line` string from `:rf.trace/trigger-handler`'s
  `:source-coord` slot. Per Spec 009 §Source-coord every emit inside
  a dispatch carries this slot when handler scope is bound (per
  rf2-3nn8 / rf2-lf84g). Pure data → string-or-nil; JVM-testable."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- per-row projection -------------------------------------------------

(defn frame-of
  "Project the event's frame routing key. Per Spec 009 §Canonical
  per-frame routing key (rf2-shaa1) every trace event that names a
  frame uses `:frame` under `:tags`; consumers also fall back to a
  top-level `:frame` for events emitted before the canonical move
  landed (defensive — the framework no longer emits the alias)."
  [ev]
  (or (get-in ev [:tags :frame])
      (:frame ev)))

(defn origin-of
  "Project the dispatch-origin slot per Spec 009 §Origin tagging
  (`:tags :origin`). Defensive against absence — returns nil."
  [ev]
  (get-in ev [:tags :origin]))

(defn project-row
  "Project one raw trace event into the panel's row shape:

      {:id              <int>
       :time            <ms>
       :op-type         <kw>
       :operation       <kw>
       :severity        <:error/:warning/:info-or-nil>
       :source          <kw-or-nil>
       :origin          <kw-or-nil>
       :frame           <kw-or-nil>
       :event-id        <kw-or-nil>
       :handler-id      <kw-or-nil>
       :dispatch-id     <int-or-nil>
       :description     <string>
       :source-coord    <string-or-nil>
       :tags            <map>                ;; full tags for the detail view
       :raw             <trace-event>}

  Pure data → data; JVM-testable."
  [{:keys [id time op-type operation source tags] :as ev}]
  {:id              id
   :time            time
   :op-type         op-type
   :operation       operation
   ;; :severity is the synonym axis Spec 009 documents — set when the
   ;; op-type is one of the three severity tiers, nil otherwise.
   :severity        (case op-type
                      :error   :error
                      :warning :warning
                      :info    :info
                      nil)
   :source          (or source (get-in ev [:tags :source]))
   :origin          (origin-of ev)
   :frame           (frame-of ev)
   :event-id        (get-in ev [:tags :event-id])
   :handler-id      (get-in ev [:tags :handler-id])
   :dispatch-id     (get-in ev [:tags :dispatch-id])
   :description     (short-description ev)
   :source-coord    (source-coord ev)
   :tags            tags
   :raw             ev})

(defn project-rows
  "Project every event in `events` into a row. Returns a vector in
  chronological order (oldest first). Pure data → data."
  [events]
  (mapv project-row events))

;; ---- epoch-scoped feed projection (the panel reads this) ----------------

(defn project-feed-from-epoch
  "Top-level projection — produces every slot the Trace view needs,
  scoped to the FOCUSED EPOCH's `:trace-events` (rf2-td380). Pure
  data → data; JVM-testable.

  `epoch-record` is the `:rf/epoch-record` looked up from
  `:rf.causa/epoch-history` whose `:epoch-id` matches the focused
  `:epoch-id` from `:rf.causa/focus` (resolved via the shared
  `panels.shared.focus-resolver`). Its `:trace-events` slot carries
  the complete domino trail for one settling — both the synchronous
  event-side rows (dispatch-id N) and the async reactive rows
  (`:sub/run` / `:view/render`, nil dispatch-id) — oldest-first.

  `focus-status` is the discriminator from
  `focus-resolver/resolve-focus-status`:

    :no-focus       — no focused epoch AND no history (cold start
                      before any cascade has settled)
    :epoch-evicted  — focus has an :epoch-id but the matching record
                      is gone from history (capped per :epoch-history)
    :focused        — focus resolved to a real epoch record (either
                      explicit pin or head-fallback per rf2-h0120)

  Returns:

      {:rows        [<row> ...]   ;; the epoch's domino trail, newest first
       :total       <int>         ;; the epoch's trace-event count
       :rendered    <int>         ;; same as :total (no filtering, rf2-gkczt)
       :epoch-id    <int-or-nil>  ;; the focused epoch's id
       :empty-kind  <:no-events / :no-focus / :epoch-evicted / nil>}

  `:empty-kind` discriminates the empty-state branches:

      :no-focus       — spine carries no focused epoch AND history is
                        empty (cold start, no cascades have settled).
      :epoch-evicted  — focused epoch's record has aged out of the
                        history ring buffer.
      :no-events      — focused epoch carries no trace events.
      nil             — at least one row; render the ribbon.

  No `:no-matches` branch — there is no user filter to hide rows
  (rf2-gkczt)."
  [epoch-record focus-status]
  (let [record-present? (= :focused focus-status)
        trace-events    (when record-present?
                          (:trace-events epoch-record))
        rows            (project-rows (or trace-events []))
        ;; Newest first for display parity with the issues ribbon.
        display-rows    (vec (reverse rows))
        n               (count rows)
        empty-kind      (cond
                          (= focus-status :no-focus)      :no-focus
                          (= focus-status :epoch-evicted) :epoch-evicted
                          (zero? n)                       :no-events
                          :else                           nil)]
    {:rows       display-rows
     :total      n
     :rendered   n
     :epoch-id   (:epoch-id epoch-record)
     :empty-kind empty-kind}))

;; ---- React keys ---------------------------------------------------------

(defn row-key
  "Stable React key for one projected trace row.

  Per rf2-z4fza (sibling of rf2-kgn0c — same React-key discipline):
  the trace ribbon's earlier shape keyed each `<li>` on a tuple that
  included the row's positional index inside the visible viewport. A
  new trace push shifts every visible row's index down by one, which
  changes every key, which makes React's reconciler unmount the
  entire viewport and remount it on EVERY push — the dominant frame
  cost under burst event rate.

  The framework's `re-frame.trace` allocates a monotonically-
  increasing `:id` per emit (`next-id!`), and the same trace event
  is never re-projected — so `:id` is a stable, unique identity for
  the row across the panel's lifetime. We namespace it with `t:` to
  mirror the rf2-kgn0c discipline (`v:<variant-id>` in the story
  workspace) so future positional fallbacks can't silently collide
  with these keys.

  Pure data → string; JVM-testable."
  [{:keys [id] :as _row}]
  (str "t:" (pr-str id)))

;; ---- selection ----------------------------------------------------------

(defn find-row
  "Look up a projected row by `:id` in `rows`. Returns nil when not
  found. Pure data → row-or-nil; JVM-testable."
  [rows row-id]
  (some (fn [v] (when (= row-id (:id v)) v)) rows))

;; ---- formatting ---------------------------------------------------------

;; Re-export the shared `HH:MM:SS.mmm` formatter so the panel surface
;; keeps a stable `format-time` symbol while the body lives once in
;; `common-helpers` (alongside issues-ribbon, routes, mcp-server).
(def format-time common/format-time-hms)

(def op-type->token
  "Pure semantic map from op-type keyword to token keyword. The hex
  resolution happens via `op-type-colour`, which looks up
  `theme/tokens`. Splitting the semantic mapping from the hex lookup
  keeps the map pure-data + tokens consolidated (rf2-5kfxe.4)."
  {:error              :red
   :warning            :yellow
   :info               :cyan
   :event              :accent-violet
   :event/db-changed   :accent-violet
   :fx                 :green
   :sub/run            :cyan
   :sub/create         :cyan
   :view/render        :magenta
   :frame              :text-secondary})

(defn op-type-colour
  "Colour swatch for an op-type. Drives the per-row dot styling.
  Resolves the semantic token keyword through `theme/tokens`
  (rf2-5kfxe.4) so the palette has exactly one source of truth. Falls
  back to `:text-secondary` for unknown op-types."
  [op-type]
  (get tokens/tokens
       (get op-type->token op-type :text-secondary)))
