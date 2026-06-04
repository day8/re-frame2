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
  new confirmation gate:

    - `:db-state-after-simulation` and every `:would-fire-effects[*]
      :args` slot are run through `re-frame.core/elide-wire-value`
      SERVER-SIDE (app-side, where the `[:rf/runtime :elision]`
      registry is reachable) before the EDN crosses the wire. Large
      slots collapse to `:rf.size/large-elided` markers; declared-
      sensitive slots redact to `:rf/redacted`.
    - The walker runs BY DEFAULT. The per-call `:elision false` /
      `:include-sensitive true` knobs are honoured ONLY when the
      operator launched with `--allow-sensitive-reads`
      (`raw-state/raw-state-allowed?` — same gate that governs the
      direct-read surfaces). When the gate is OFF the knobs are
      forced safe (`elision` true, `include-sensitive` false).
    - `:cascade-summary` is a depth-bounded projection (path lists +
      counts, not verbatim values) so it rides through unwalked, the
      same way `dispatch` / `reset-frame-db` / `restore-epoch` surface
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
  losing the dry-run's roll-back guarantee."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
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
             :hint "usage: dispatch-dry-run {event '[:ev/id ...]' [frame :foo] [fx-overrides {...}]}"}]

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

(defn- elide-envelope-src
  "CLJS source for a fn that walks the runtime envelope's egress slots
  through `re-frame.core/elide-wire-value` (rf2-z7roa). The two slots
  that can carry app-db-derived / fx-derived data:

    - `:db-state-after-simulation` — the would-be app-db verbatim.
    - `:would-fire-effects[*].args` — each recorded fx call's args.

  Both ride the same walker the direct-read surfaces use, so a
  declared-sensitive slot redacts to `:rf/redacted` and a declared-
  large slot collapses to `:rf.size/large-elided` — server-side,
  before the EDN crosses the wire. `:cascade-summary` is a depth-
  bounded projection (path lists + counts, not verbatim values) and
  is intentionally NOT walked, matching the other cascade-summary
  surfaces (rf2-6yqdl).

  Only the happy-path envelope (`:ok? true`) carries those slots, so a
  soft-failure envelope (`:no-epoch-recorded` / `:no-new-epoch`) flows
  through untouched.

  `frame-edn` is the source for the `:frame` opt (a quoted keyword or
  a runtime `current-frame` call) so the walker resolves the right
  `[:rf/runtime :elision]` registry; `elision-opts` is the rendered
  `elision-opts-edn` map threading the `--allow-sensitive-reads` gate
  through `:rf.size/include-sensitive?`."
  [frame-edn elision-opts]
  (str "(fn [env]"
       "  (if (and (map? env) (:ok? env))"
       "    (let [opts (merge {:frame " frame-edn "} " elision-opts ")"
       "          f    (fn [v] (re-frame.core/elide-wire-value v opts))"
       "          env  (if (contains? env :db-state-after-simulation)"
       "                 (update env :db-state-after-simulation f) env)"
       "          env  (if (contains? env :would-fire-effects)"
       "                 (update env :would-fire-effects"
       "                         (fn [fx] (mapv (fn [e]"
       "                                          (if (and (map? e) (contains? e :args))"
       "                                            (update e :args f) e))"
       "                                        fx)))"
       "                 env)]"
       "      env)"
       "    env))"))

(defn dispatch-dry-run-tool [conn raw-args]
  (let [event-str    (wire/arg raw-args :event)
        build-id     (wire/arg-build conn raw-args)
        frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        fx-overrides (when-let [o (wire/arg raw-args :fx-overrides)] (js->clj o :keywordize-keys true))
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
        ;; rf2-suoj2 — `elision-opts-edn` takes the walker-aligned
        ;; `include-large?` polarity directly. MCP `elision` true =
        ;; emit markers = `:include-large?` false; hence `(not elision?)`.
        elision-opts (elision/elision-opts-edn (not elision?) incl?)
        frame-edn    (if frame
                       (pr-str frame)
                       (ef/emit (ef/rt-call 'current-frame)))
        [tag payload] (parse-event-edn event-str)]
    (case tag
      :err
      (js/Promise.resolve (wire/err-text payload))

      :ok
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            ;; The runtime returns the structured dry-run envelope. When
            ;; elision is ON (the default) we wrap it server-side: the
            ;; `:db-state-after-simulation` + `:would-fire-effects[*]
            ;; :args` egress slots run through `re-frame.core/elide-wire-
            ;; value` (the walker reaches the live elision registry only
            ;; app-side), and the marker count piggybacks on the same
            ;; round-trip so the client doesn't re-walk. When elision is
            ;; OFF (operator opted in via --allow-sensitive-reads + per-
            ;; call :elision false) the bare envelope rides through.
            form (if elision?
                   (ef/emit
                     (ef/rt-let
                       ['env    (ef/rt-call 'dispatch-dry-run event-vec opts-form)
                        'walked (ef/rt-raw (str "(" (elide-envelope-src frame-edn elision-opts) " env)"))]
                       (ef/rt-raw
                         (str "{:value walked"
                              " :elided-count (count (filter #(and (map? %) (contains? % :rf.size/large-elided))"
                              "                              (tree-seq coll? seq walked)))}"))))
                   (ef/emit
                     (ef/rt-raw
                       (str "{:value "
                            (ef/emit (ef/rt-call 'dispatch-dry-run event-vec opts-form))
                            " :elided-count 0}"))))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [resp]
                     ;; Eval-form shape: `{:value <env> :elided-count N}`
                     ;; (matching snapshot / get-path, rf2-e35a5). The
                     ;; runtime fn ALWAYS returns a map, so an unwrapped
                     ;; non-map `:value` means a degraded / pre-rf2-17hvp
                     ;; runtime answered — surface it as `:unexpected-shape`.
                     (let [new-shape? (and (map? resp) (contains? resp :elided-count))
                           env        (if new-shape? (:value resp) resp)
                           elided     (when new-shape? (:elided-count resp))]
                       (wire/ok-text
                         (if (map? env)
                           (wire/with-indicators
                             (assoc env :elision elision?)
                             {:elided (or elided 0)})
                           {:ok? false :reason :unexpected-shape :value env})))))
            (.catch (fn [err] (probe/err->result :dispatch-dry-run-failed err))))))))
