(ns day8.re-frame2-xray.panels.shared.focus-resolver
  "Shared focus-resolver — one source of truth for the focus +
  epoch-history pair every L4 panel reads (rf2-o9suo).

  ## Why this lives in `panels/shared/`

  Multiple L4 panels (Issues, App-db, Trace, Reactive, Machine
  Inspector, …) need to translate the spine's `:rf.xray/focus`
  (carrying `:epoch-id`) + `:rf.xray/epoch-history` (the framework's
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

  When `:rf.xray/focus` carries no `:epoch-id` (cold start before any
  user click; test rigs that don't pre-set focus) BUT
  `:rf.xray/epoch-history` is non-empty, the resolver falls back to
  the HEAD of `epoch-history` (the most recent epoch — recall
  `epoch-history` is oldest-first per `re-frame.epoch/epoch-history`,
  so head = `peek`). This is the natural debugging UX: show the
  latest unless the operator explicitly clicks an earlier row. The
  resolver returns `:focused` for this case; `find-epoch-record`
  returns the head record. The `:no-focus` empty-state is reserved
  for the truly degenerate case where focus is nil AND history is
  empty (no event-bundles have settled yet).

  ## `:no-epoch` — the pinned bundle that settled nothing (rf2-y8doi.19)

  Head-fallback is right for an UNSET focus and wrong for a focus the
  operator SET to an event bundle that resolved to no epoch. The two
  present identically to the 2-arity above — `:epoch-id` nil — because
  `spine/focus-event-bundle-reducer` stamps `:epoch-id` from
  `spine/epoch-id-for-event-bundle`, which answers nil whenever no
  record matches the clicked `:dispatch-id`. So clicking the red L2 row
  of a dispatch with no registered handler fell through to head-fallback
  and the panel rendered the HEAD epoch's cascade underneath it: a
  complete, plausible pipeline belonging to a DIFFERENT event, with
  nothing on screen saying so.

  The 3-arities take `focus-dispatch-id` as well and answer `:no-epoch`
  for that case, so the panel can say the pinned bundle settled no epoch
  instead of answering a question nobody asked. `find-epoch-record`'s
  3-arity returns nil there rather than the head.

  **The status is deliberately cause-NEUTRAL.** At least four things
  produce it and this resolver can tell none of them apart from focus
  alone: a dispatch refused before any handler ran, a bundle still
  mid-build, a bundle whose epoch was evicted from the ring, and a focus
  pinning `:ungrouped` (`spine/epoch-id-for-event-bundle`'s own docstring
  names the last three). Empty-state copy that named the refusal would be
  a fresh falsehood on the other three, so consumers state what is known
  — this bundle settled no epoch — and nothing more.

  The 2-arities are unchanged in behaviour and remain what the Trace and
  Issues-ribbon consumers call; only a caller that passes the pinned
  `:dispatch-id` can see `:no-epoch`.

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
                        is empty (cold start, no event-bundles yet)
      :no-epoch       — focus PINS a :dispatch-id but carries no
                        :epoch-id, and history is non-empty: the
                        operator selected an event bundle that settled
                        no epoch (3-arity only, rf2-y8doi.19)
      :epoch-evicted  — focus has :epoch-id but no matching record
                        survives in epoch-history
      :focused        — focus has :epoch-id and matches a record, OR
                        focus is nil but epoch-history has at least
                        one record (head-fallback per rf2-h0120)

  `focus-epoch-id` is `(:epoch-id focus)`; `focus-dispatch-id` (3-arity)
  is `(:dispatch-id focus)`. `epoch-history` is the vector of
  `:rf/epoch-record` maps the framework keeps (oldest-first per
  `re-frame.epoch/epoch-history`).

  The 2-arity is the pre-rf2-y8doi.19 behaviour verbatim — it cannot
  answer `:no-epoch`, having nothing to answer it from — and stays the
  form the Trace + Issues-ribbon consumers call."
  ([focus-epoch-id epoch-history]
   (resolve-focus-status focus-epoch-id nil epoch-history))
  ([focus-epoch-id focus-dispatch-id epoch-history]
   (cond
     ;; An EMPTY history is `:no-focus` whichever way focus is set —
     ;; tested FIRST so a stale pinned `:dispatch-id` cannot turn the
     ;; cold-start empty state into `:no-epoch`. `:rf.xray/sync-epoch-
     ;; history` reaches exactly that shape when a seed redacts away to
     ;; nothing: it dissocs `:epoch-id` and leaves `:dispatch-id` alone.
     (and (nil? focus-epoch-id)
          (empty? epoch-history))              :no-focus

     ;; rf2-y8doi.19 — the pinned bundle that settled no epoch. Must be
     ;; tested BEFORE head-fallback: both shapes carry a nil
     ;; `:epoch-id`, and answering the head here is answering about a
     ;; different event.
     (and (nil? focus-epoch-id)
          (some? focus-dispatch-id))           :no-epoch

     ;; Head-fallback: focus unset but history exists. Per rf2-h0120
     ;; this is the natural debugging UX — show the latest epoch
     ;; rather than the empty 'no focus' line.
     (nil? focus-epoch-id)                     :focused

     (some (fn [r] (= focus-epoch-id (:epoch-id r)))
           epoch-history)                      :focused
     :else                                     :epoch-evicted)))

(defn find-epoch-record
  "Look up the `:rf/epoch-record` in `epoch-history` whose `:epoch-id`
  matches `focus-epoch-id`. When `focus-epoch-id` is nil but
  `epoch-history` is non-empty, returns the HEAD (most-recent) record
  per the head-fallback contract (rf2-h0120) — `epoch-history` is
  oldest-first, so the head is `(peek epoch-history)`. Returns nil
  when no match (and no history). Pure data → record-or-nil; JVM-
  testable.

  The 3-arity (rf2-y8doi.19) additionally takes the focus's pinned
  `:dispatch-id` and returns nil — never the head — when `:epoch-id` is
  nil while a `:dispatch-id` is pinned, the `:no-epoch` case
  `resolve-focus-status`'s 3-arity classifies. Head-fallback is for an
  UNSET focus; handing the head to a caller who pinned a bundle that
  settled nothing is how the Epoch panel came to render one event's
  cascade under another event's row. The 2-arity is unchanged."
  ([focus-epoch-id epoch-history]
   (find-epoch-record focus-epoch-id nil epoch-history))
  ([focus-epoch-id focus-dispatch-id epoch-history]
   (cond
     ;; rf2-y8doi.19 — a pinned bundle with no epoch resolves to NO
     ;; record. Tested before head-fallback, which would otherwise
     ;; answer with an unrelated epoch.
     (and (nil? focus-epoch-id) (some? focus-dispatch-id))
     nil

     (and (nil? focus-epoch-id) (seq epoch-history))
     ;; `peek` on a vector is O(1); `last` is the safe fall-back when
     ;; a caller hands in a seq. Production sub joins on the
     ;; framework's vector-backed `:rf.xray/epoch-history`, so the
     ;; vector branch is the hot path.
     (if (vector? epoch-history)
       (peek epoch-history)
       (last epoch-history))

     (some? focus-epoch-id)
     (some (fn [r] (when (= focus-epoch-id (:epoch-id r)) r))
           epoch-history)

     :else nil)))
