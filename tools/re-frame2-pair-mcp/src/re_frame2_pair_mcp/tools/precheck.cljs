(ns re-frame2-pair-mcp.tools.precheck
  "Cache precheck — server-side cheap-hash probe.

  The post-eval cache decides a hit AFTER running the tool by hashing
  the result text. That saves wire bytes but still pays the full nREPL
  round-trip + path-slice + transform pipeline. The precheck moves the
  decision EARLIER: one bencode round-trip asks the runtime for
  `(hash (re-frame2-pair.runtime/snapshot frame))`. If the hash matches
  the stored entry for `(tool, args)`, we emit the cache-hit marker
  WITHOUT running the tool — saving both the wire bytes AND the full
  tool eval.

  Eligibility (today): NONE. `snapshot` and `get-path` were the two
  candidate tools — both are precheck-INELIGIBLE, for the identical
  reason (rf2-ajhwbm, rf2-ww877w): see below. Every call therefore
  falls through to the post-eval `apply-cache`, which hashes the full
  serialized (post-elision) result text and so can never serve a stale
  payload. This namespace's tagged-tuple dispatch (`precheck-target` /
  `precheck-form`) stays in place as forward-looking infrastructure for
  a FUTURE tool whose result is a genuinely pure function of
  `app-db@frame` alone — see the `:explicit` tag note below.

  `snapshot`'s `:include` slices split by what the precheck hash sees.
  The precheck hash is `(re-frame2-pair.runtime/app-db-hash
  frame)` = `(hash app-db@frame)` only — it tracks `:db-after` at every
  settled mutation. So a slice is precheck-sound IFF it is a pure
  function of `app-db@frame`:

    - `:app-db`   — reads the frame db, but its WIRE result is
                    post-processed by `re-frame.core/elide-wire-value`
                    (rf2-ajhwbm — see below). NOT sound.
    - `:machines` — runtime-db state. Machine snapshots live in the
                    durable runtime-db partition (EP-0001), read via
                    `(:rf.db/runtime (rf/frame-state-value frame))` at
                    `[:rf.runtime/machines :snapshots]` (rf2-t3lftq —
                    API-shrink #3 retired the dedicated
                    `rf/runtime-db-value` reader; see runtime.cljs
                    `snapshot-frame-slice`).
                    A machine transition rewrites that runtime-db slot
                    WITHOUT an app-db write, so the slice can change while
                    `app-db-hash` stays constant. NOT sound.
    - `:sub-cache`— reactive cache over external inputs; can move without
                    an app-db write. NOT sound.
    - `:epochs`   — the per-frame epoch ring; a record is appended on
                    EVERY pipeline run, including no-`:db` handlers, so
                    it changes while `app-db-hash` stays constant. NOT
                    sound.
    - `:traces`   — the per-frame trace ring; same accrue-without-db-
                    change behaviour as `:epochs`. NOT sound.

  None of the five slices are precheck-sound (rf2-ajhwbm dropped
  `:app-db`, the last holdout — see below). So no resolved `:include`
  — not even the narrowest single-slice `{:app-db}` — is
  precheck-eligible; every `snapshot` call falls through to the
  post-eval `apply-cache`, which hashes the full result text and so
  cannot serve a stale `:app-db`/`:machines`/`:epochs`/`:traces`/
  `:sub-cache` payload.

  `:app-db` was believed sound (it IS the frame db) until rf2-ajhwbm:
  the snapshot tool wraps its resolved `:app-db` slice through
  `re-frame.core/elide-wire-value` before it crosses the wire (see
  `snapshot.cljs` `slice-walk-src`) — EXACTLY the same egress path
  `get-path` takes. The elision registry lives in the runtime-db
  partition (`[:rf.runtime/elision]`), not app-db, so a later elision
  declaration (or a sensitive/large classification flip) can re-shape
  the egress of an UNCHANGED app-db subtree while `(hash app-db)` stays
  constant. A precheck hit under the old `{:app-db}`-is-sound rule
  would re-serve the PRIOR, differently-redacted payload — a staleness
  / privacy regression, not just a correctness nit.

  `get-path` is NOT precheck-eligible (rf2-ww877w), for the identical
  elide-wire-value reason: its app-db subtree read is post-processed by
  `re-frame.core/elide-wire-value`, whose elision registry lives in the
  runtime-db partition (`[:rf.runtime/elision]` — see
  `implementation/core/src/re_frame/elision.cljc`). A later elision
  declaration (or sensitive/large classification flip) can change the
  egress shape of an unchanged app-db subtree, which the
  `(hash app-db)` precheck hash cannot observe. So get-path falls
  through to the post-eval `apply-cache`, which hashes the actual
  serialized (post-elision) result text and therefore recomputes when
  the egress shape changes.

  Trace tools (`trace-window`, `watch-epochs`, `discover-app`) are not
  eligible — their result depends on the epoch ring / health surface,
  not just `(hash app-db)`. Plumbing a per-surface hash is the
  follow-on work (see `cache.cljs` docstring)."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(defn precheck-target
  "Resolve the precheck target for a single-frame precheck. Returns a
  tagged 2-tuple — one of:

    [:explicit <frame-keyword>]   — caller named a specific frame.
    nil                           — tool not precheck-eligible.

  `precheck-form` dispatches on the tag rather than pattern-matching
  against a magic keyword, so the eligibility distinction is type-level,
  not value-level. Future targets (e.g. multi-frame combined-hash, or an
  operating-frame-resolved hash) add a new tag without colliding with a
  reserved keyword.

  No tool registers today (rf2-ajhwbm): both candidates fall through to
  `nil` below, for the SAME reason — their wire result is post-processed
  by `re-frame.core/elide-wire-value`, whose elision registry lives in
  the runtime-db partition (`[:rf.runtime/elision]`), not app-db. A
  later elision declaration (or sensitive/large classification flip)
  can re-shape the egress of an unchanged app-db subtree, which the
  `(hash app-db)` precheck hash cannot observe:

  - `snapshot` (rf2-ajhwbm) — even the narrowest single-slice
    `{:include [:app-db]}` walks its `:app-db` slice through
    `elide-wire-value` (see `snapshot.cljs` `slice-walk-src`) before it
    crosses the wire, so no resolved `:include` is precheck-sound. A
    stale-hash hit would re-serve a payload redacted under a PRIOR
    elision declaration — a staleness / privacy regression, not merely
    a missed optimisation.
  - `get-path` (rf2-ww877w) — its app-db subtree read takes the
    identical `elide-wire-value` egress path.

  Both fall through to the post-eval `apply-cache`, which hashes the
  actual serialized (post-elision) result text and therefore recomputes
  whenever the egress shape changes. The `:explicit` tag and
  `precheck-form`/`fetch-precheck-hash` plumbing below stay in place as
  the mechanism a FUTURE genuinely-app-db-pure tool would register
  against — not dead code, just currently unreached."
  [tool _raw-args]
  (case tool
    ;; `snapshot` — NOT precheck-eligible (rf2-ajhwbm). Every resolved
    ;; `:include`, even the narrowest `{:app-db}`, egresses through
    ;; `elide-wire-value`; the elision registry it reads lives in
    ;; runtime-db, invisible to the `(hash app-db)` precheck hash.
    ;;
    ;; `get-path` — NOT precheck-eligible (rf2-ww877w). Same
    ;; elide-wire-value egress hazard.
    ;;
    ;; Other tools — not precheck-eligible yet.
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

  A future operating-frame-resolved target would add a
  `:operating-frame` arm emitting the 0-arity `app-db-hash` call."
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
