(ns re-frame2-pair-mcp.tools.precheck
  "Cache precheck — server-side cheap-hash probe.

  A precheck can avoid a full tool eval only when its runtime hash covers
  every input to the final wire value. No tool is currently eligible.
  `snapshot` reads runtime-db, subscription, epoch, and trace state in
  addition to app-db. Its app-db slice and `get-path` also depend on the
  runtime-db elision registry, so `app-db-hash` cannot detect a privacy
  classification change. Trace and health tools depend on their own live
  state.

  All calls therefore continue to the post-eval cache, which hashes the
  serialized, post-elision result. The tagged target and form helpers are
  retained for a tool whose result becomes a pure function of a covered
  runtime hash."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(defn precheck-target
  "Resolve the precheck target for a single-frame precheck. Returns a
  tagged 2-tuple — one of:

    [:explicit <frame-keyword>]   — caller named a specific frame.
    nil                           — tool not precheck-eligible.

  No tool currently registers a target; see the namespace docstring for
  the inputs that make app-db-only hashing unsound."
  [tool _raw-args]
  (case tool
    nil))

(defn precheck-form
  "The CLJS eval form for the runtime-side cheap hash. Dispatches on
  the tag in `target` (today only `[:explicit <kw>]`).

  Threads through `re-frame2-pair.runtime/app-db-hash`, which returns
  the per-frame cached `(hash app-db)` integer in O(1). The cache is
  maintained by the runtime's epoch listener at every settled mutation;
  lazy-computed on the first read for a frame whose hash hasn't been
  observed yet. The wire payload is a single integer regardless of
  app-db size.

  Additional target kinds can add arms without changing callers."
  [[tag frame :as _target]]
  (case tag
    :explicit
    (ef/emit (ef/rt-call 'app-db-hash frame))

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
