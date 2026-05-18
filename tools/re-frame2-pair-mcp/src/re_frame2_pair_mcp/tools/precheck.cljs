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

  Eligibility (today): single-frame `snapshot` and `get-path`. Their
  result is a function of `(frame, args, app-db@frame)`; same frame +
  same args + same db-hash ⇒ same result. Multi-frame `snapshot`
  (`:frames :all` or a vector) is NOT eligible because we'd need to
  hash every frame's db separately and combine — out of scope; the
  legacy post-eval `apply-cache` path still catches those.

  Trace tools (`trace-window`, `watch-epochs`, `discover-app`) are not
  eligible — their result depends on the epoch ring / health surface,
  not just `(hash app-db)`. Plumbing a per-surface hash is the
  follow-on work (see `cache.cljs` docstring)."
  (:require [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]))

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

  `snapshot` is eligible only when `:frames` resolves to a single frame
  (an explicit one-element vector or a single scalar). `:all` or a
  multi-element vector returns nil — those callers fall back to the
  post-eval cache.

  `get-path` is always eligible (its `:frame` slot is single-valued by
  contract; absent means \"operating frame\")."
  [tool raw-args]
  (case tool
    "snapshot"
    (let [raw-frames (when raw-args (j/get raw-args "frames"))
          frames     (args/parse-frames-arg raw-frames)]
      (cond
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
    (let [build-id (wire/arg-build raw-args)]
      (-> (nrepl/cljs-eval-value conn build-id form)
          (.then (fn [v]
                   (cond
                     (integer? v) v
                     (number? v)  (long v)
                     :else        nil)))
          (.catch (fn [_] nil))))
    (js/Promise.resolve nil)))
