(ns day8.re-frame2-causa.panels.shared.focus-resolver
  "Shared focus-resolver — one source of truth for the focus +
  epoch-history pair every L4 panel reads (rf2-o9suo).

  ## Why this lives in `panels/shared/`

  Multiple L4 panels (Issues, App-db, Trace, Reactive, Machine
  Inspector, …) need to translate the spine's `:rf.causa/focus`
  (carrying `:epoch-id`) + `:rf.causa/epoch-history` (the framework's
  ring buffer of `:rf/epoch-record` maps) into:

    1. a focus-status discriminator (`:no-focus` / `:focused` /
       `:epoch-evicted`) the view branches on for empty-state copy;
    2. the looked-up epoch record the panel projects from.

  Pre-extraction the contract lived inline in
  `issues_ribbon_helpers.cljc` (rf2-h0120's head-fallback patch). When
  follow-on panels (App-db downstream popover, Trace, future Reactive
  Inspector) re-implemented the same lookup, the head-fallback
  semantics drifted: each panel decided independently whether nil
  focus + non-empty history meant 'empty state' or 'show the head'.
  Centralising the algebra here keeps the head-fallback discipline a
  one-line refactor away from every panel that needs it.

  ## Head-fallback (rf2-h0120)

  When `:rf.causa/focus` carries no `:epoch-id` (cold start before any
  user click; test rigs that don't pre-set focus) BUT
  `:rf.causa/epoch-history` is non-empty, the resolver falls back to
  the HEAD of `epoch-history` (the most recent epoch — recall
  `epoch-history` is oldest-first per `re-frame.epoch/epoch-history`,
  so head = `peek`). This is the natural debugging UX: show the
  latest unless the operator explicitly clicks an earlier row. The
  resolver returns `:focused` for this case; `find-epoch-record`
  returns the head record. The `:no-focus` empty-state is reserved
  for the truly degenerate case where focus is nil AND history is
  empty (no cascades have settled yet).

  ## Pure-data + JVM-testable

  Both fns are pure data → data with no `:require` on the framework
  runtime, so `clojure -M:test` can exercise them under the JVM
  unit-test target per the standing rule
  `feedback_jvm_interop_must_work.md`.")

;; ---- focus-status resolver ----------------------------------------------

(defn resolve-focus-status
  "Classify the focus + history pair into one of the three focus
  statuses panels consume. Pure data → keyword; JVM-testable.

      :no-focus       — focus carries no :epoch-id AND epoch-history
                        is empty (cold start, no cascades yet)
      :epoch-evicted  — focus has :epoch-id but no matching record
                        survives in epoch-history
      :focused        — focus has :epoch-id and matches a record, OR
                        focus is nil but epoch-history has at least
                        one record (head-fallback per rf2-h0120)

  `focus-epoch-id` is `(:epoch-id focus)`. `epoch-history` is the
  vector of `:rf/epoch-record` maps the framework keeps (oldest-first
  per `re-frame.epoch/epoch-history`)."
  [focus-epoch-id epoch-history]
  (cond
    ;; Head-fallback: focus unset but history exists. Per rf2-h0120
    ;; this is the natural debugging UX — show the latest epoch
    ;; rather than the empty 'no focus' line.
    (and (nil? focus-epoch-id)
         (seq epoch-history))                 :focused
    (nil? focus-epoch-id)                     :no-focus
    (some (fn [r] (= focus-epoch-id (:epoch-id r)))
          epoch-history)                      :focused
    :else                                     :epoch-evicted))

(defn find-epoch-record
  "Look up the `:rf/epoch-record` in `epoch-history` whose `:epoch-id`
  matches `focus-epoch-id`. When `focus-epoch-id` is nil but
  `epoch-history` is non-empty, returns the HEAD (most-recent) record
  per the head-fallback contract (rf2-h0120) — `epoch-history` is
  oldest-first, so the head is `(peek epoch-history)`. Returns nil
  when no match (and no history). Pure data → record-or-nil; JVM-
  testable."
  [focus-epoch-id epoch-history]
  (cond
    (and (nil? focus-epoch-id) (seq epoch-history))
    ;; `peek` on a vector is O(1); `last` is the safe fall-back when
    ;; a caller hands in a seq. Production sub joins on the
    ;; framework's vector-backed `:rf.causa/epoch-history`, so the
    ;; vector branch is the hot path.
    (if (vector? epoch-history)
      (peek epoch-history)
      (last epoch-history))

    (some? focus-epoch-id)
    (some (fn [r] (when (= focus-epoch-id (:epoch-id r)) r))
          epoch-history)

    :else nil))
