(ns re-frame2-pair-mcp.tools.precheck
  "Cache precheck — rf2-36xod. Server-side cheap-hash probe.

  The original rf2-3rt1f cache decides a hit AFTER running the tool by
  hashing the result text. That saves wire bytes but still pays the
  full nREPL round-trip + path-slice + transform pipeline. The rf2-36xod
  precheck moves the decision EARLIER: one bencode round-trip asks the
  runtime for `(hash (re-frame2-pair.runtime/snapshot frame))`. If the
  hash matches the stored entry for `(tool, args)`, we emit the cache-
  hit marker WITHOUT running the tool — saving both the wire bytes AND
  the full tool eval.

  Eligibility (today): single-frame `snapshot` whose resolved
  `:include` is app-db-derived only, and `get-path`. Their result is a
  function of `(frame, args, app-db@frame)`; same frame + same args +
  same db-hash ⇒ same result. Multi-frame `snapshot`
  (`:frames :all` or a vector) is NOT eligible because we'd need to
  hash every frame's db separately and combine — out of scope; the
  legacy post-eval `apply-cache` path still catches those.

  Snapshot's `:include` slices split by what the precheck hash sees
  (rf2-3ljsa). The precheck hash is `(re-frame2-pair.runtime/app-db-hash
  frame)` = `(hash app-db@frame)` only — it tracks `:db-after` at every
  settled mutation. So a slice is precheck-sound IFF it is a pure
  function of `app-db@frame`:

    - `:app-db`   — IS the frame db. Sound.
    - `:machines` — `:state` lives at `[:rf/runtime :machines :snapshots]`
                    INSIDE app-db; `:ids` is the install-time registrar
                    list (stable across event cascades). Sound.
    - `:sub-cache`— reactive cache over external inputs; can move without
                    an app-db write. NOT sound.
    - `:epochs`   — the per-frame epoch ring; a record is appended on
                    EVERY event cascade, including no-`:db` handlers, so
                    it changes while `app-db-hash` stays constant. NOT
                    sound.
    - `:traces`   — the per-frame trace ring; same accrue-without-db-
                    change behaviour as `:epochs`. NOT sound.

  A default snapshot (no `:include`) resolves to ALL FIVE slices, which
  contains the unsound three — so the default is NOT precheck-eligible
  and falls through to the post-eval `apply-cache`, which hashes the
  full result text and so cannot serve a stale `:epochs`/`:traces`/
  `:sub-cache` payload. Precheck is taken only when the caller narrows
  `:include` to the app-db-derived subset.

  Trace tools (`trace-window`, `watch-epochs`, `discover-app`) are not
  eligible — their result depends on the epoch ring / health surface,
  not just `(hash app-db)`. Plumbing a per-surface hash is the
  follow-on work (see `cache.cljs` docstring)."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]))

(def app-db-derived-snapshot-slices
  "Snapshot slices whose value is a pure function of `app-db@frame`
  (rf2-3ljsa). Only these are precheck-sound under the
  `(hash app-db@frame)` precheck hash; `:sub-cache`/`:epochs`/`:traces`
  can change while that hash stays constant, so a snapshot whose
  resolved `:include` is NOT a subset of this set is precheck-ineligible
  and falls back to the post-eval result-hash cache. See the ns
  docstring for the per-slice rationale."
  #{:app-db :machines})

(defn precheck-target
  "Resolve the precheck target for a single-frame precheck. Returns a
  tagged 2-tuple — one of:

    [:explicit       <frame-keyword>]   — caller named a specific frame.
    [:operating-frame nil]              — runtime resolves the frame.
    nil                                 — tool not precheck-eligible.

  Tagged-tuple dispatch supersedes the prior sentinel-keyword shape
  (`:rf.mcp.cache/operating-frame`) — `precheck-form` dispatches on
  the tag rather than pattern-matching against a magic keyword, and
  the eligibility / runtime-resolution distinction is now type-level,
  not value-level. Future targets (e.g. multi-frame combined-hash)
  add a new tag without colliding with a reserved keyword.

  `snapshot` is eligible only when BOTH (a) `:frames` resolves to a
  single frame (an explicit one-element vector or a single scalar) AND
  (b) the resolved `:include` is a subset of
  `app-db-derived-snapshot-slices` (rf2-3ljsa). `:all` or a
  multi-element vector — or an `:include` that retains
  `:sub-cache`/`:epochs`/`:traces` (including the default all-five
  include) — returns nil so the caller falls back to the post-eval
  cache, which hashes the full result text and so cannot serve stale
  non-app-db slices.

  `get-path` is always eligible (its `:frame` slot is single-valued by
  contract; absent means \"operating frame\"; it reads an app-db
  subtree, so any app-db change flips the precheck hash)."
  [tool raw-args]
  (case tool
    "snapshot"
    (let [raw-frames (when raw-args (j/get raw-args "frames"))
          frames     (args/parse-frames-arg raw-frames)
          include    (args/parse-include-arg (wire/arg raw-args :include))
          app-db-derived? (every? app-db-derived-snapshot-slices include)]
      (cond
        ;; an :include retaining a non-app-db-derived slice
        ;; (:sub-cache/:epochs/:traces) — the precheck hash can't see
        ;; those move, so NOT eligible (rf2-3ljsa).
        (not app-db-derived?)
        nil
        ;; explicit single-frame vector
        (and (vector? frames) (= 1 (count frames)))
        [:explicit (first frames)]
        ;; vector with multiple frames — not eligible
        (vector? frames)
        nil
        ;; :all — not eligible
        (= :all frames)
        nil
        :else nil))

    "get-path"
    ;; nil-frame is allowed — runtime resolves to operating frame, so
    ;; the eval form computes the hash over whichever frame the runtime
    ;; picks. The hash still keys on (tool, args), so the cache slot is
    ;; consistent.
    (if-let [f (some-> (wire/arg raw-args :frame) args/->frame-keyword)]
      [:explicit f]
      [:operating-frame nil])

    ;; Other tools — not precheck-eligible yet.
    nil))

(defn precheck-form
  "The CLJS eval form for the runtime-side cheap hash. Dispatches on
  the tag in `target` (`[:explicit <kw>]` / `[:operating-frame nil]`)
  — the keyword sentinel of the prior shape is gone.

  Threads through `re-frame2-pair.runtime/app-db-hash` (rf2-9pe31),
  which returns the per-frame cached `(hash app-db)` integer in O(1).
  The cache is maintained by the runtime's epoch listener at every
  settled mutation; lazy-computed on the first read for a frame whose
  hash hasn't been observed yet. The wire payload is a single integer
  regardless of app-db size."
  [[tag frame :as _target]]
  (case tag
    :explicit
    (ef/emit (ef/rt-call 'app-db-hash frame))

    :operating-frame
    (ef/emit (ef/rt-call 'app-db-hash))

    nil))

(defn fetch-precheck-hash
  "Issue the one-bencode-round-trip eval to fetch the runtime-side
  hash. Returns a Promise resolving to an integer hash, or `nil` on
  any failure (the caller treats nil as 'no precheck — proceed').

  `target` is the tagged tuple returned by `precheck-target` — see
  that fn's docstring for the tag vocabulary.

  Errors are swallowed by design: a failed precheck must NEVER block
  the actual tool call. The worst case is we lose the optimisation
  for this call; the post-eval cache still catches the wire-bytes
  saving."
  [conn raw-args target]
  (if-let [form (precheck-form target)]
    (let [build-id (wire/arg-build conn raw-args)]
      (-> (nrepl/cljs-eval-value conn build-id form)
          (.then (fn [v]
                   (cond
                     (integer? v) v
                     (number? v)  (long v)
                     :else        nil)))
          (.catch (fn [_] nil))))
    (js/Promise.resolve nil)))
