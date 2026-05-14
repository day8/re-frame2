(ns day8.re-frame2-causa.panels.time-travel-helpers
  "Pure-data helpers for Causa's Time Travel scrubber panel
  (Phase 3, rf2-t53ze).

  ## Why a separate `.cljc` ns

  The panel view in `time_travel.cljs` touches Reagent ratoms and the
  DOM (cursor drag, keyboard nudge, pin chip click handlers). The
  *logic* — pin into a store, enforce cap, locate an epoch in a
  history vector, derive cascade-ids for chips — is pure data → data.
  Splitting that logic out into `.cljc` so it runs under the JVM unit-
  test target (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Pin shape — the 4-tuple

  Per `tools/causa/spec/002-Time-Travel.md` §Pinned snapshots §What a
  pin captures, a pin is the 4-tuple
  `(epoch-id × frame-db-value × dispatch-id × user-label)` captured
  eagerly at pin time:

      {:epoch-id    <opaque>     ; :rf/epoch-record :epoch-id
       :frame-db    <value>      ; :rf/epoch-record :db-after
       :dispatch-id <int-or-nil> ; cascade root from :trace-events
       :label       <string>}    ; user-supplied or pin-<n> default

  ## Pin store shape

  The store is per-frame: `{frame-id [pin-1 pin-2 ...]}`. A vector
  preserves insertion order so chip rendering is stable. The cap is
  per-frame (32 by default per spec §Pin store capacity); adding the
  33rd drops the oldest with a `:pin-store-overflow` flag the view
  reads to surface a toast.

  ## Why not a set

  Pins are equality-distinct on the 4-tuple (rename rewrites the
  `:label` slot in-place, not via remove + add), so a vector with
  ordered iteration is the right shape. Lookup by id is O(n) — fine
  at the 32-pin cap.

  ## Per spec — Lock 4

  Pins are session-scoped (per spec §Session-scoped — pins do not
  survive reload + DESIGN-RATIONALE.md Lock 4). The helpers here are
  pure-data; the live store lives in the Causa frame's app-db, which
  is itself memory-only (never written to localStorage / disk).
  Refusing to persist sidesteps the corruption surface a partial
  session-export would create."
  (:require [clojure.string :as str]))

;; ---- defaults ------------------------------------------------------------

(def default-pin-cap
  "Default per-frame pin cap. Per spec §Pin store capacity (32 chosen
  empirically — '~10 surfaces a UI-density problem; 32 is the cliff at
  which the user is using pins as session-export, which Lock #4 says
  no'). Configurable via Settings → `pin-store-capacity` once Settings
  ships; until then, callers may override via the 3-arity
  form of `pin-snapshot`."
  32)

(def default-pin-label-prefix
  "Default label prefix for unnamed pins (`pin-1`, `pin-2`, ...). Per
  spec §What a pin captures — `:label` defaults to `pin-<n>`
  (incrementing per session) if the user dismisses the label prompt."
  "pin-")

;; ---- pin construction ----------------------------------------------------

(defn dispatch-id-from-epoch
  "Walk an `:rf/epoch-record`'s `:trace-events` for the first
  cascade-root `:dispatch-id` tag and return it; nil when no
  dispatch-id-bearing event is present (synthetic epochs from
  `reset-frame-db!` record `:trace-events []`).

  Pure data → cascade-id-or-nil. Used both by `pin-from-epoch` (when
  building a fresh pin) and `chip-state` (when deciding chip
  presentation against the current history).

  Mirrors `re-frame.story.ui.scrubber-xref/cascade-id-from-trace-events`
  — same algebra, different home. Both walk `:trace-events` for
  `:tags :dispatch-id` / `:tags :parent-dispatch-id`."
  [epoch-record]
  (some (fn [ev]
          (or (get-in ev [:tags :dispatch-id])
              (get-in ev [:tags :parent-dispatch-id])))
        (:trace-events epoch-record)))

(defn- next-default-label
  "Return the next `pin-<n>` default label that does not collide with
  any existing `pin-<k>` label in `pins`. Falls back to
  `(count pins) + 1` when no existing pin matches the default pattern.

  Per spec §What a pin captures — `:label` defaults to `pin-<n>`
  (incrementing per session) if the user dismisses the label prompt."
  [pins]
  (let [pattern    (re-pattern (str "^" default-pin-label-prefix "(\\d+)$"))
        used-nums  (->> pins
                        (keep (fn [{:keys [label]}]
                                (when (string? label)
                                  (let [m (re-matches pattern label)]
                                    (when m
                                      #?(:clj  (Long/parseLong (second m))
                                         :cljs (js/parseInt (second m) 10))))))))
        next-n     (if (seq used-nums)
                     (inc (apply max used-nums))
                     (inc (count pins)))]
    (str default-pin-label-prefix next-n)))

(defn pin-from-epoch
  "Build a fresh 4-tuple pin from an `:rf/epoch-record` + a `label`.
  Pure data → pin map. The pin captures `:db-after` eagerly so the
  pin survives ring-buffer age-out — Reset to pinned can still write
  the value back via `reset-frame-db!` even after the epoch itself
  has dropped off the scrubber.

  Returns nil when `epoch-record` is nil (caller can no-op rather
  than store a degenerate pin). A label of nil / blank-string yields
  a `pin-<n>` default at pin-store insertion time (see `pin-snapshot`)."
  [epoch-record label]
  (when (some? epoch-record)
    {:epoch-id    (:epoch-id epoch-record)
     :frame-db    (:db-after epoch-record)
     :dispatch-id (dispatch-id-from-epoch epoch-record)
     :label       label}))

;; ---- pin-store transitions (pure data → data) ----------------------------

(defn- normalise-label
  "If `label` is nil / blank, fall back to the next `pin-<n>` default
  computed against `pins`. Otherwise return the original label."
  [label pins]
  (if (or (nil? label)
          (and (string? label) (str/blank? label)))
    (next-default-label pins)
    label))

(defn pin-snapshot
  "Add `pin` to `store` under `frame-id`, enforcing the per-frame cap.

  `store` shape: `{frame-id [pin-1 pin-2 ...]}`. Returns a map:

      {:store        <updated-store>
       :overflow?    <bool>            ; true when oldest pin was dropped
       :dropped-pin  <pin-or-nil>      ; the pin removed, if any
       :added-pin    <pin>}            ; the pin actually inserted

  The :added-pin slot reflects the canonical pin shape — `:label` is
  resolved to a `pin-<n>` default when the caller passed nil / blank.
  Overflow drops the *oldest* pin (head of the vector) per spec §Pin
  store capacity — 'Adding a 33rd pin drops the oldest pin with a
  toast notification'.

  Pure data → data. JVM-testable. `cap` defaults to `default-pin-cap`
  (32); the 3-arity form lets Settings-bound overrides land without
  rewriting every call site."
  ([store frame-id pin]
   (pin-snapshot store frame-id pin default-pin-cap))
  ([store frame-id pin cap]
   (let [existing      (vec (get store frame-id []))
         canonical-pin (update pin :label normalise-label existing)
         appended      (conj existing canonical-pin)
         over?         (and (number? cap)
                            (pos? cap)
                            (> (count appended) cap))
         dropped       (when over? (first appended))
         final         (if over?
                         (vec (rest appended))
                         appended)]
     {:store       (assoc store frame-id final)
      :overflow?   (boolean over?)
      :dropped-pin dropped
      :added-pin   canonical-pin})))

(defn unpin-snapshot
  "Remove the pin at `epoch-id` from `store`'s entry for `frame-id`.
  Pure data → updated-store. Returns `store` unchanged when no pin
  matches.

  Match is on `:epoch-id` — the 4-tuple's other slots are immutable
  and the pin store rejects duplicates by id at insert time, so this
  is the unambiguous remove key."
  [store frame-id epoch-id]
  (update store frame-id
          (fn [pins]
            (vec (remove (fn [pin] (= epoch-id (:epoch-id pin)))
                         (or pins []))))))

(defn rename-pin
  "Rewrite the `:label` slot of the pin at `epoch-id` in `store` under
  `frame-id`. The 4-tuple's other slots are immutable per spec
  §Pin actions §Rename pin — rename rewrites only `:label`. Returns
  the updated store; no-op when no pin matches."
  [store frame-id epoch-id new-label]
  (update store frame-id
          (fn [pins]
            (mapv (fn [pin]
                    (if (= epoch-id (:epoch-id pin))
                      (assoc pin :label new-label)
                      pin))
                  (or pins [])))))

(defn pins-for-frame
  "Return the pin vector for `frame-id`, or `[]` when none. Pure-data
  accessor — useful both for tests and for the sub-projection that
  flattens the per-frame map into the view's flat chip list."
  [store frame-id]
  (vec (get store frame-id [])))

(defn find-pin
  "Look up a pin in `store` by `frame-id` + `epoch-id`. nil when not
  found. Used by the `:rf.causa/reset-to-pinned` event handler to
  fetch the pin's `:frame-db` for `reset-frame-db!`."
  [store frame-id epoch-id]
  (some (fn [pin] (when (= epoch-id (:epoch-id pin)) pin))
        (get store frame-id [])))

;; ---- epoch / history navigation -----------------------------------------

(defn find-epoch-in-history
  "Return the `:rf/epoch-record` in `history` whose `:epoch-id` matches
  `epoch-id`, or nil if absent. Pure data → record-or-nil.

  Used by:
    - the scrubber's 'click cascade → scroll-to' affordance (locate
      the cascade's settling epoch in the history vector so the
      scrubber cursor can rebase visually);
    - the `:rf.causa/reset-to-epoch` event handler (assert the epoch
      is still in the ring buffer before calling `restore-epoch`);
    - the chip-state derivation (decide attached vs detached chip
      rendering — a pin whose `:epoch-id` no longer appears in
      `history` renders detached per spec §Pins on the scrubber)."
  [history epoch-id]
  (when (some? epoch-id)
    (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
          history)))

(defn epoch-index-in-history
  "Return the 0-based index of `epoch-id` in `history`, or nil when not
  present. Used by the scrubber slider to set its position from the
  selection without re-walking the history twice (once for the record,
  once for the index)."
  [history epoch-id]
  (when (some? epoch-id)
    (first
      (keep-indexed
        (fn [i r] (when (= epoch-id (:epoch-id r)) i))
        history))))

(defn epoch-id-at-index
  "Return the `:epoch-id` at `idx` in `history`, or nil when out of
  range. Used by the scrubber's slider on-change handler to translate
  a slot-index to the stable :epoch-id (per the same rationale Story's
  scrubber gives in `scrubber.cljs`: hold the stable id, not the slot
  index, so ring-buffer eviction can't silently re-point selection)."
  [history idx]
  (when (and (integer? idx) (not (neg? idx)))
    (:epoch-id (nth history idx nil))))

(defn newest-epoch-id
  "Return the `:epoch-id` of the newest record in `history`, or nil
  when the vector is empty. The scrubber's per-frame re-bind on frame-
  switch resets the cursor to this id (per spec §Cross-frame
  scrubbing)."
  [history]
  (:epoch-id (peek (vec history))))

(defn step-epoch
  "Step `epoch-id`'s position in `history` by `delta` (an integer).
  Returns the new `:epoch-id`, clamped to `[0 (dec (count history))]`.
  When `epoch-id` is nil or absent from history, steps from the
  newest epoch.

  Used by the `[` / `]` keyboard nav (delta = -1 / +1) and `Shift+[`
  / `Shift+]` (cascade-root step — out of scope for Phase 3 minimal
  view; passthrough to per-epoch step is the safe placeholder)."
  [history epoch-id delta]
  (when (seq history)
    (let [n      (count history)
          cur    (or (epoch-index-in-history history epoch-id)
                     (dec n))
          target (max 0 (min (dec n) (+ cur (long delta))))]
      (:epoch-id (nth history target nil)))))

;; ---- pin chip projection -------------------------------------------------

(defn chip-state
  "Derive the per-chip presentation map for `pin` against the current
  `history`. Pure data → data.

  Returns:

      {:pin      <pin>            ; full 4-tuple as stored
       :attached <bool>           ; true iff :epoch-id is still in history
       :index    <int-or-nil>     ; slot index in history when attached}

  Per spec §Pins on the scrubber: detached chips (the pin's epoch
  aged out of the ring buffer) still render — they keep `:frame-db`
  so 'Reset to pinned' is still callable via `reset-frame-db!`."
  [history pin]
  (let [idx (epoch-index-in-history history (:epoch-id pin))]
    {:pin      pin
     :attached (some? idx)
     :index    idx}))

(defn chip-states
  "Map `chip-state` over a vector of pins. Returns a vector of chip
  presentation maps. Order preserved (insertion order matches the
  pin-store vector)."
  [history pins]
  (mapv #(chip-state history %) pins))
