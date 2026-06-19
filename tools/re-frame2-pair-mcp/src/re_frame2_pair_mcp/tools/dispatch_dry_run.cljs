(ns re-frame2-pair-mcp.tools.dispatch-dry-run
  "Tool: dispatch-dry-run — simulate a cascade without committing it (rf2-17hvp).

  Wraps the preload runtime's `dispatch-dry-run` primitive
  (`(rf/dispatch-dry-run event-v opts)`), which composes the
  framework's `:fx-overrides` seam + `restore-epoch` primitive into a
  true dry-run with no framework hack required (see runtime.cljs
  §Dispatch dry-run for the mechanism).

  ## Why this is NOT `--allow-writes`-gated

  Dry-run is the OPPOSITE of a write — its whole contract is 'no
  observable effect'. The fx-override set redirects every registered
  fx to a recording stub, so no http / navigation / persisted-write
  side-effect escapes. The framework's `restore-epoch` rewinds the
  app-db AND trims the assembled would-be epoch from the ring. There
  is no state change for the `--allow-writes` gate to protect against.

  ## Why this IS `--allow-sensitive-reads`-gated (rf2-z7roa)

  Dry-run mutates nothing, but it is an AI-facing READ surface: the
  happy-path envelope returns the would-be app-db verbatim under
  `:db-state-after-simulation` and each recorded fx's args under
  `:would-fire-effects[*].args`. Reducers / fx routinely derive
  tokens, auth headers, or other declared-sensitive / large values
  from app-db, so a stock MCP session could leak those to the model
  even though the `--allow-sensitive-reads` default is OFF.

  The fix reuses the EXISTING read-surface posture (snapshot /
  get-path / subscribe, rf2-c2dtu / rf2-vflrg) rather than minting a
  new confirmation gate. The two egress slots egress under DIFFERENT
  policies because they are different keyspaces:

    - `:db-state-after-simulation` (the would-be app-db) is run
      through `re-frame.core/elide-wire-value` SERVER-SIDE (app-side,
      where the `[:rf.runtime/elision]` runtime-db registry is
      reachable) before the EDN crosses the wire. Large slots collapse
      to `:rf.size/large-elided` markers; declared-sensitive slots
      redact to `:rf/redacted`. This slot IS rooted at the frame's
      app-db, so the schema-path walker can prove it safe.
      - The walker runs BY DEFAULT. The per-call `:elision false` /
        `:include-sensitive true` knobs are honoured ONLY when the
        operator launched with `--allow-sensitive-reads`
        (`raw-state/raw-state-allowed?` — same gate that governs the
        direct-read surfaces). When the gate is OFF the knobs are
        forced safe (`elision` true, `include-sensitive` false).

    - `:would-fire-effects[*].args` (the RAW fx-handler arguments — an
      HTTP request body, a dispatched event vector, a payment map) are
      NOT rooted at app-db, so the schema-path-keyed walker CANNOT
      prove them safe. This is the same leak class as an epoch
      record's `:effects[*].args`, which `projected-record` already
      FAILS CLOSED off-box (`elide-effect-row`). Consistency wins
      (rf2-6to9xj, EP-0015 §13 residual): off-box egress fails closed
      here too — `:args` redacts to `:rf/redacted` for EVERY recorded
      fx BY DEFAULT, while the value-free row structure (`:fx-id`, the
      effect kind) rides through so the operator still sees WHICH
      effects would fire. The fail-close runs on BOTH the elision-on
      and elision-off paths — turning the size walker off must not
      re-leak the unprovable fx args. The trusted-local
      `:include-fx-args true` opt-in keeps the raw args, honoured ONLY
      under `--allow-sensitive-reads` (the same two-key gate as
      `:include-sensitive`); it is ORTHOGONAL to `:include-sensitive`
      (a different keyspace, not app-db values).

    - `:cascade-summary` is a depth-bounded projection (path lists +
      counts, not verbatim values) so it rides through unwalked, the
      same way `dispatch` / `replace-app-db` / `restore-epoch` surface
      it (rf2-6yqdl).

  ## Raw-state tap signal (rf2-z7roa / rf2-c2dtu)

  The dry-run primitive internally calls `restore-epoch` to roll back,
  and the preload's tap-emitting surfaces default to RAW payloads until
  the server signals its boot-gate posture via `configure-raw-state!`.
  Like snapshot / get-path / subscribe, this tool issues
  `raw-state/signal-runtime!` between `ensure-runtime!` and the eval so
  the runtime's tap consumers see the gated (default-elided) posture
  too — not just the wire payload.

  ## event arg is EDN data (rf2-vflrg posture)

  Identical to `dispatch` — the `event` arg is parsed as EDN
  server-side and required to be a vector. Host-form source
  (`(println :x)`) is rejected the same way; the wire boundary is the
  same security gate.

  ## Composes with fx-overrides

  The caller MAY pass an `:fx-overrides` map that PRE-stubs some fx
  (e.g. redirecting `:rf.http/managed` to a canned stub-handler for
  the experiment). User-supplied overrides win on conflict — the
  recorder fires only for fx the caller did NOT pre-stub. This lets
  the experimenter compose realistic conditions (e.g. 'what would
  happen if the http call resolved to this stub response?') without
  losing the dry-run's roll-back guarantee.

  ## Accepts scripted recordable coeffects (rf2-3q7gep · EP-0017)

  EP-0017 makes `:rf.cofx` a first-class dispatch edge for exact
  recordable facts (`:rf/time-ms`, provided boundary facts, and
  app/subsystem recordable facts). Identical to `dispatch`, the caller
  MAY pass a `cofx` EDN-map arg threaded into the runtime opts under the
  flat `:rf.cofx` key. The router PRESERVES it verbatim (filling only
  `:rf/time-ms` when absent), so a dry-run of a time-dependent or
  provided-cofx event runs against the EXACT causal token the operator
  intends to test — not a freshly-stamped time, and not a
  `:rf.error/missing-required-cofx` failure for a provided fact a real
  dispatch/replay would supply. A malformed `cofx` (non-map / unreadable
  / non-integer `:rf/time-ms`) short-circuits to an honest isError before
  the eval, the same `args/parse-cofx` gate `dispatch` carries."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(defn- parse-event-edn
  "Mirror `dispatch.cljs/parse-event-edn` — same vector contract, same
  failure reasons. Kept inline here so the dry-run surface owns its
  parser; cross-tool sharing of the parser would entangle two
  separately-evolving surfaces."
  [event-str]
  (let [trimmed (some-> event-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err {:ok? false :reason :missing-event
             :hint "usage: dispatch-dry-run {event '[:ev/id ...]' [frame :foo] [fx-overrides {...}] [cofx '{:rf/time-ms ...}']}"}]

      :else
      (let [parsed (try
                     (cljs.reader/read-string trimmed)
                     (catch :default _e
                       ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok? false :reason :invalid-event-edn
                 :event event-str
                 :hint "event must be an EDN-readable vector, e.g. \"[:cart/checkout]\""}]

          (not (vector? parsed))
          [:err {:ok? false :reason :not-an-event-vector
                 :event event-str
                 :parsed-type (cond
                                (map? parsed)      :map
                                (keyword? parsed)  :keyword
                                (symbol? parsed)   :symbol
                                (sequential? parsed) :list
                                :else              :scalar)
                 :hint "event must be a vector, e.g. \"[:cart/checkout {:reason :user}]\""}]

          :else
          [:ok parsed])))))

(defn- redact-fx-args-src
  "CLJS source for a fn that FAIL-CLOSES the `:would-fire-effects[*]
  :args` slot of the runtime envelope (rf2-6to9xj, EP-0015 §13 residual).

  Each recorded fx call's `:args` is the RAW fx-handler argument — an
  HTTP request body, a dispatched event vector, a payment map. These are
  NOT rooted at the frame's app-db, so the schema-path-keyed
  `elide-wire-value` walker CANNOT prove them safe. This is the same leak
  class as an epoch record's `:effects[*].args`, which `projected-record`
  already fails closed off-box (`elide-effect-row`,
  `:include-fx-args? false`). Consistency wins: off-box egress FAILS
  CLOSED here too — `:args` is replaced with the `:rf/redacted` sentinel
  for EVERY recorded fx by default.

  The value-free row structure rides through unchanged — `:fx-id`, the
  effect kind, and any other non-`:args` row key — so the operator still
  sees WHICH effects would fire, just not their (unprovable) payloads.

  `redact?` is the already-gated boolean (`(not include-fx-args?)`):
  `true` (the default / gate-OFF posture) redacts; `false` (the
  trusted-local `--allow-sensitive-reads` + `:include-fx-args true`
  opt-in) keeps the raw `:args`. When `redact?` is false this returns the
  identity walk (no `:args` mutation), so the opt-in path ships the raw
  payload.

  Only the happy-path envelope (`:ok? true`) carries `:would-fire-effects`,
  so a soft-failure envelope (`:no-epoch-recorded` / `:no-new-epoch`)
  flows through untouched. Idempotent: a row whose `:args` already carries
  `:rf/redacted` re-redacts to the same sentinel."
  [redact?]
  (if-not redact?
    "(fn [env] env)"
    (str "(fn [env]"
         "  (if (and (map? env) (:ok? env) (contains? env :would-fire-effects))"
         "    (update env :would-fire-effects"
         "            (fn [fx] (mapv (fn [e]"
         "                             (if (and (map? e) (contains? e :args))"
         "                               (assoc e :args :rf/redacted) e))"
         "                           fx)))"
         "    env))")))

(defn- elide-envelope-src
  "CLJS source for a fn that walks the runtime envelope's app-db-rooted
  egress slot through `re-frame.core/elide-wire-value` (rf2-z7roa):

    - `:db-state-after-simulation` — the would-be app-db verbatim.

  This slot IS rooted at the frame's app-db, so the schema-path-keyed
  walker can prove it safe: a declared-sensitive slot redacts to
  `:rf/redacted` and a declared-large slot collapses to
  `:rf.size/large-elided` — server-side, before the EDN crosses the wire.

  The `:would-fire-effects[*].args` slot is NOT walked here — those are
  RAW fx-handler args (HTTP bodies, dispatched event vectors, payment
  maps) that are NOT app-db-rooted, so the walker cannot prove them safe.
  They fail closed via `redact-fx-args-src` (rf2-6to9xj), matching
  `projected-record`'s `:effects[*].args` posture, independently of
  whether this size-elision walker runs at all.

  `:cascade-summary` is a depth-bounded projection (path lists + counts,
  not verbatim values) and is intentionally NOT walked, matching the
  other cascade-summary surfaces (rf2-6yqdl).

  Only the happy-path envelope (`:ok? true`) carries the db slot, so a
  soft-failure envelope (`:no-epoch-recorded` / `:no-new-epoch`) flows
  through untouched.

  `frame-edn` is the source for the `:frame` opt (a quoted keyword or
  a runtime `current-frame` call) so the walker resolves the right
  `[:rf.runtime/elision]` runtime-db registry; `elision-opts` is the rendered
  `elision-opts-edn` map threading the `--allow-sensitive-reads` gate
  through `:rf.size/include-sensitive?`."
  [frame-edn elision-opts]
  (str "(fn [env]"
       "  (if (and (map? env) (:ok? env))"
       "    (let [opts (merge {:frame " frame-edn "} " elision-opts ")"
       "          f    (fn [v] (re-frame.core/elide-wire-value v opts))"
       "          env  (if (contains? env :db-state-after-simulation)"
       "                 (update env :db-state-after-simulation f) env)]"
       "      env)"
       "    env))"))

(defn dispatch-dry-run-tool [conn raw-args]
  (let [event-str    (wire/arg raw-args :event)
        build-id     (wire/arg-build conn raw-args)
        frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        ;; rf2-hf7m9j — coerce override VALUES (colon-prefixed string ->
        ;; keyword) and REJECT any other value. A raw `js->clj
        ;; :keywordize-keys true` leaves a `{":http": ":stub-http"}`
        ;; target as the string `":stub-http"`, which core silently falls
        ;; through to the original fx — for dry-run that defeats the
        ;; no-fx-execute guarantee (a string override can replace the
        ;; recording stub and fire the real effect). See `parse-fx-overrides`.
        fx-r         (args/parse-fx-overrides (wire/arg raw-args :fx-overrides))
        fx-overrides (when (= :ok (first fx-r)) (second fx-r))
        ;; rf2-3q7gep · EP-0017 — caller-scripted recordable coeffects, the
        ;; same data-not-source gate `dispatch` already carries. The agent
        ;; supplies `cofx "{:rf/time-ms …}"` (EDN data, parsed by the SHARED
        ;; `args/parse-cofx`) so a dry-run of a time-dependent or
        ;; provided-cofx event runs against the EXACT causal token the
        ;; operator intends to test — rather than the dry-run stamping a
        ;; fresh `:rf/time-ms` or failing `:rf.error/missing-required-cofx`
        ;; for a provided fact a real dispatch/replay would supply. The map
        ;; is PRESERVED verbatim (the router fills only `:rf/time-ms` when
        ;; absent), so the simulated state is REPRODUCIBLE. A malformed value
        ;; short-circuits to an honest isError below rather than threading a
        ;; value the runtime's `:rf/dispatch-opts` validation would reject.
        cofx-r       (args/parse-cofx (wire/arg raw-args :cofx))
        cofx         (when (= :ok (first cofx-r)) (second cofx-r))
        ;; rf2-z7roa — dry-run is an AI-facing READ surface (it returns
        ;; the would-be app-db + recorded fx args). Gate its egress on
        ;; the SAME `--allow-sensitive-reads` posture as snapshot /
        ;; get-path / subscribe (rf2-c2dtu / rf2-vflrg). Gate OFF (the
        ;; default) forces the walker on (`elision` true) and forces
        ;; sensitive slots to redact (`include-sensitive` false); gate
        ;; ON lets the per-call args win.
        elision?     (if (raw-state/raw-state-allowed?)
                       (args/parse-bool-arg raw-args :elision)
                       true)
        incl?        (if (raw-state/raw-state-allowed?)
                       (args/parse-bool-arg raw-args :include-sensitive)
                       false)
        ;; rf2-6to9xj — :would-fire-effects[*].args are RAW fx-handler
        ;; arguments NOT rooted at app-db, so `elide-wire-value` cannot
        ;; prove them safe (same leak class as an epoch record's
        ;; :effects[*].args). Off-box egress FAILS CLOSED: :args redacts to
        ;; :rf/redacted by default, matching `projected-record`. The
        ;; trusted-local :include-fx-args true opt-in is honoured ONLY under
        ;; --allow-sensitive-reads (same two-key gate as :include-sensitive);
        ;; gate OFF forces the redaction on regardless of the per-call knob.
        incl-fx-args? (if (raw-state/raw-state-allowed?)
                        (args/parse-bool-arg raw-args :include-fx-args)
                        false)
        ;; rf2-suoj2 — `elision-opts-edn` takes the walker-aligned
        ;; `include-large?` polarity directly. MCP `elision` true =
        ;; emit markers = `:include-large?` false; hence `(not elision?)`.
        elision-opts (elision/elision-opts-edn (not elision?) incl?)
        ;; rf2-t55hxg.13 — fail-CLOSED: the size walker over the app-db-
        ;; rooted `:db-state-after-simulation` slot runs UNLESS the caller
        ;; opted into BOTH raw axes (`:elision false` AND `:include-sensitive
        ;; true`). A bare `:elision false` still walks so a declared-
        ;; sensitive db slot redacts to `:rf/redacted` while large content
        ;; passes. Pre-fix the walker gated on `elision?` alone, so a gate-
        ;; ON `:elision false` leaked sensitive db state off-box. (The fx-
        ;; args fail-close at stage 1 is independent and unaffected.)
        walk?        (elision/walk-required? (not elision?) incl?)
        frame-edn    (if frame
                       (pr-str frame)
                       (ef/emit (ef/rt-call 'current-frame)))
        [tag payload] (parse-event-edn event-str)]
    (cond
      ;; rf2-hf7m9j — reject an invalid fx-overrides target up front so a
      ;; string stub can't silently defeat the no-fx-execute guarantee.
      (= :err (first fx-r))
      (js/Promise.resolve (wire/err-text (second fx-r)))

      ;; rf2-3q7gep · EP-0017 — a malformed `cofx` map (non-map / unreadable
      ;; / non-integer :rf/time-ms) short-circuits to an honest isError
      ;; before the dispatch eval, the same gate `dispatch` applies, rather
      ;; than threading a value the runtime would reject deep in the eval.
      (= :err (first cofx-r))
      (js/Promise.resolve (wire/err-text (second cofx-r)))

      (= :err tag)
      (js/Promise.resolve (wire/err-text payload))

      :else
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides)
                        ;; rf2-3q7gep · EP-0017 — thread the scripted
                        ;; recordable coeffects under the flat `:rf.cofx`
                        ;; opts key the router reads, exactly as `dispatch`
                        ;; does. The map is `pr-str`'d as an EDN literal by
                        ;; the arg-emit path, like `:fx-overrides`; the
                        ;; router preserves it verbatim (filling only
                        ;; `:rf/time-ms` when absent) so the simulated state
                        ;; is reproducible.
                        cofx         (assoc :rf.cofx cofx))
            ;; The runtime returns the structured dry-run envelope. We
            ;; transform it server-side in two composed stages (the walker
            ;; reaches the live elision registry only app-side):
            ;;
            ;;   1. fx-args fail-close (rf2-6to9xj, ALWAYS) — the
            ;;      `:would-fire-effects[*].args` slot carries RAW fx-handler
            ;;      arguments that are NOT app-db-rooted, so the size walker
            ;;      cannot prove them safe. Off-box egress fails closed:
            ;;      `:args` redacts to `:rf/redacted` for every fx unless the
            ;;      operator opted in (`--allow-sensitive-reads` +
            ;;      `:include-fx-args true`). This runs on BOTH the
            ;;      elision-on and elision-off paths — turning the size
            ;;      walker off must NOT re-leak the unprovable fx args.
            ;;   2. size-elision walk (rf2-z7roa, when `elision?`) — the
            ;;      app-db-rooted `:db-state-after-simulation` slot runs
            ;;      through `re-frame.core/elide-wire-value`; the marker
            ;;      count piggybacks on the same round-trip so the client
            ;;      doesn't re-walk. When elision is OFF (operator opted in
            ;;      via --allow-sensitive-reads + :elision false) the db slot
            ;;      rides raw — but stage 1 still fail-closes the fx args.
            redact-src   (redact-fx-args-src (not incl-fx-args?))
            form (if walk?
                   (ef/emit
                     (ef/rt-let
                       ['env      (ef/rt-call 'dispatch-dry-run event-vec opts-form)
                        'redacted (ef/rt-raw (str "(" redact-src " env)"))
                        'walked   (ef/rt-raw (str "(" (elide-envelope-src frame-edn elision-opts) " redacted)"))]
                       (ef/rt-raw
                         (str "{:value walked"
                              " :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                              "                              (tree-seq coll? seq walked)))}"))))
                   (ef/emit
                     (ef/rt-let
                       ['env      (ef/rt-call 'dispatch-dry-run event-vec opts-form)
                        'redacted (ef/rt-raw (str "(" redact-src " env)"))]
                       (ef/rt-raw "{:value redacted :elided-count 0}"))))]
        (probe/eval-after-runtime-signalled!
          conn build-id form :dispatch-dry-run-failed
          (fn [resp]
                     ;; Eval-form shape: `{:value <env> :elided-count N}`
                     ;; (matching snapshot / get-path, rf2-e35a5). The
                     ;; runtime fn ALWAYS returns a map, so an unwrapped
                     ;; non-map `:value` means a degraded / pre-rf2-17hvp
                     ;; runtime answered — surface it as `:unexpected-shape`.
                     (let [new-shape? (and (map? resp) (contains? resp :elided-count))
                           env        (if new-shape? (:value resp) resp)
                           elided     (when new-shape? (:elided-count resp))
                           result     (if (map? env)
                                        (wire/with-indicators
                                          (assoc env :elision elision?)
                                          {:elided (or elided 0)})
                                        {:ok? false :reason :unexpected-shape :value env})]
                       ;; rf2-wdxyx3 finding 2 — a known-tool runtime
                       ;; failure (`:ok? false`, e.g. the reducer rejected
                       ;; the event / an interceptor early-returned →
                       ;; `:no-new-epoch`, or a degraded runtime →
                       ;; `:unexpected-shape`) MUST ride back as an
                       ;; `isError` envelope, matching the dispatch /
                       ;; read-sub no-silent-success parity model and the
                       ;; published wire contract. The old `ok-text` shape
                       ;; let a non-landed dry-run read as green. The
                       ;; structured `:reason`/`:hint` rides through
                       ;; verbatim so the caller still sees why.
                       (if (false? (:ok? result))
                         (wire/err-text result)
                         (wire/ok-text result)))))))))
