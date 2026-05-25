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
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

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

(defn dispatch-dry-run-tool [conn raw-args]
  (let [event-str    (wire/arg raw-args :event)
        build-id     (wire/arg-build conn raw-args)
        frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        fx-overrides (when-let [o (wire/arg raw-args :fx-overrides)] (js->clj o :keywordize-keys true))
        [tag payload] (parse-event-edn event-str)]
    (case tag
      :err
      (js/Promise.resolve (wire/err-text payload))

      :ok
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            form (ef/emit (ef/rt-call 'dispatch-dry-run event-vec opts-form))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     ;; The runtime returns a structured envelope
                     ;; carrying :ok? / :dry-run? / :rolled-back? /
                     ;; :cascade-summary / :would-fire-effects /
                     ;; :db-state-after-simulation on the happy path
                     ;; (or a structured failure envelope on the
                     ;; :no-epoch-recorded / :no-new-epoch paths).
                     (wire/ok-text
                       (if (map? v)
                         v
                         {:ok? false :reason :unexpected-shape :value v}))))
            (.catch (fn [err] (probe/err->result :dispatch-dry-run-failed err))))))))
