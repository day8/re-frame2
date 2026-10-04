(ns re-frame2-pair-mcp.dispatch-dry-run-test
  "Unit tests for the dispatch-dry-run tool.

  Simulate a re-frame2 cascade without committing — the framework's
  dry-run effect sink (`re-frame.fx/*effect-sink*`) + a
  `replace-frame-state!` rollback compose into a dry-run that is
  STRUCTURALLY unable to execute a declared effect on the runtime side.
  The MCP tool is a thin wrapper that parses the event-vector arg (same
  EDN-data posture as `dispatch`)
  and surfaces the structured runtime envelope; a caller `:fx-overrides`
  is rejected (the sink records every fx before override resolution).

  Dry-run is an AI-facing READ surface: its
  `:db-state-after-simulation` (would-be app-db) and
  `:would-fire-effects[*].args` (fx-derived) slots are off-box egress.
  The tool runs them through the elision walker server-side ALWAYS
  (gate OFF forces `:include-sensitive false`, which wins only under
  `--allow-sensitive-reads`; the `:elision false` size override is
  honoured on every launch). It also
  issues `configure-raw-state!` between the preload probe and the eval
  (raw-state tap posture). These tests pin the wire boundary: the arg
  parser, the runtime call shape, the elision-form shape, and the
  envelope unwrap — NOT the runtime semantics themselves (those are
  exercised in the runtime tests at skills/re-frame2-pair/tests/runtime/,
  which run against a live shadow-cljs build with the preload installed)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.dispatch-dry-run :as dry-run]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; The tool issues TWO evals on the happy path: the
;; `configure-raw-state!` signal (raw-state/signal-runtime!) and the
;; `dispatch-dry-run` form. The stub matches by substring — the
;; configure eval resolves to nil (swallowed); the dispatch eval
;; resolves to `dispatch-canned`. `forms*` records EVERY form so a
;; test can assert the dispatch form's shape (the dispatch form is the
;; one that mentions `dispatch-dry-run`).
(defn- with-captured-eval!
  [forms* dispatch-canned body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (swap! forms* conj form-str)
               (js/Promise.resolve
                 (if (str/includes? form-str "configure-raw-state!")
                   nil
                   dispatch-canned)))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- dispatch-form
  "The recorded form that mentions the runtime `dispatch-dry-run` call."
  [forms*]
  (some #(when (str/includes? % "dispatch-dry-run") %) @forms*))

;; The runtime returns the bare envelope; the eval form wraps it as
;; `{:value <env> :elided-count N}` (matching snapshot / get-path). The
;; stub canned-value must therefore be the WRAPPED shape.
(defn- wrap [env elided]
  {:value env :elided-count elided})

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(defn- with-raw-gate! [enabled? body-fn]
  (let [prev (raw-state/allow-raw-state-enabled?)]
    (raw-state/set-allow-raw-state! enabled?)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (raw-state/set-allow-raw-state! prev))))))

;; ---------------------------------------------------------------------------
;; Arg parsing — the exhaustive event-parse matrix lives in `args_test`
;; against the shared `args/parse-event-arg` seam. Dispatch and
;; dispatch-dry-run route through the SAME parser, so here we keep only the
;; two narrow tool-level invariants: no-eval-on-error and the DISTINCT
;; dry-run missing-event hint.
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source-without-eval
  ;; Host-form source like `(println :pwn)` must NEVER reach the runtime —
  ;; it reads as a list, the vector-check fails, and we PROVE the no-eval by
  ;; capturing every form the tool would send and asserting none fired
  ;; (neither the configure-raw-state! signal nor the dispatch-dry-run eval).
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "(println :pwn)"})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :not-an-event-vector (:reason edn)))
                     (is (= :list (:parsed-type edn))))
                   (is (empty? @forms)
                       "no-eval-on-error — the runtime was never contacted; no signal / dispatch form fired")
                   (done)))))))

(deftest rejects-missing-event-with-dry-run-hint
  ;; The DISTINCT-hint invariant: a missing event surfaces :missing-event
  ;; carrying DISPATCH-DRY-RUN's own usage string (its opt set — frame /
  ;; fx-overrides / cofx), NOT dispatch's. Same shared parser, distinct
  ;; caller data. Also confirms no-eval on the missing path.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :missing-event (:reason edn)))
                     (is (str/includes? (:hint edn) "dispatch-dry-run {")
                         "the missing-event hint is dispatch-dry-run's own usage string")
                     (is (str/includes? (:hint edn) "cofx")
                         "carries the dry-run-specific opts — distinct from dispatch's hint"))
                   (is (empty? @forms) "no eval on the missing-event path")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Runtime call shape — emits `(re-frame2-pair.runtime/dispatch-dry-run
;; <event> <opts>)` with the event as a data literal, wrapped in the
;; elision form.
;; ---------------------------------------------------------------------------

(deftest threads-frame-arg
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:state/transition :paying]"
                                                  :frame ":checkout"})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":frame :checkout")
                         ":frame threaded into runtime opts"))
                   (done)))))))

(deftest rejects-caller-fx-overrides
  ;; Dry-run does not accept :fx-overrides. The runtime's
  ;; effect sink records+skips every fx BEFORE override resolution, so an
  ;; override cannot influence the simulation without executing a body. A
  ;; supplied :fx-overrides is REJECTED as an :isError envelope BEFORE the
  ;; eval, never threaded — so it can never defeat the no-fx-execute
  ;; guarantee. (A valid-looking `:stub-http` target and a bare-string
  ;; target are both refused the same way — the opt is refused whatever
  ;; its value.)
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {":http" ":stub-http"}})))
          (.then (fn [r]
                   (is (err? r)
                       "a caller :fx-overrides ⇒ :isError (rejected, not threaded)")
                   (is (nil? (dispatch-form forms))
                       "the dispatch-dry-run eval never fired — rejected up front")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; EP-0017 — scripted recordable coeffects. Identical posture
;; to `dispatch`'s `cofx`: the agent supplies a `cofx "{:rf/time-ms …}"`
;; EDN map (the shared `args/parse-cofx` gate), threaded into the simulated
;; dispatch opts under the flat `:rf.cofx` key. The router preserves it
;; verbatim, so a time-dependent / provided-cofx event dry-runs against the
;; EXACT causal token rather than a fresh stamp or a missing-required-cofx
;; failure. A malformed value short-circuits before the eval.
;; ---------------------------------------------------------------------------

(deftest no-cofx-arg-omits-the-opts-key
  ;; Absent `cofx` ⇒ no `:rf.cofx` key in the emitted opts (the ordinary
  ;; live path — the runtime stamps :rf/time-ms itself). Guards against a
  ;; stray nil-valued slot that would defeat the router's stamp.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:counter/inc]"})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (not (str/includes? form ":rf.cofx"))
                         "no :rf.cofx opt when the arg is absent"))
                   (done)))))))

(deftest cofx-non-map-rejected
  ;; A vector / scalar is valid EDN but the wrong shape — reject with
  ;; :invalid-cofx before the eval rather than thread it into the opts.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:counter/inc]"
                                                  :cofx "[:not :a :map]"})))
          (.then (fn [r]
                   (is (err? r) "a non-map cofx ⇒ :isError")
                   (is (= :invalid-cofx (:reason (read-result-text r))))
                   (is (nil? (dispatch-form forms))
                       "the dispatch-dry-run eval never fired — rejected up front")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Privacy gate. Gate OFF (default published posture) forces sensitive
;; slots to redact; the walker ALWAYS runs. The `:elision false` size
;; override is honoured on every launch — it
;; overlays `:rf.egress/include-large? true` on the off-box-tool floor,
;; which cannot reveal a sensitive slot. The runtime envelope's egress
;; slots are walked server-side (the unit test stubs the runtime, so it
;; asserts on the EMITTED form, not on a live walker).
;; ---------------------------------------------------------------------------

(deftest gate-on-honours-include-sensitive
  ;; With --allow-sensitive-reads + :include-sensitive true, the door
  ;; still runs (elision default true) under the trusted-local boundary,
  ;; which passes sensitive slots through.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:auth/login]"
                                                      :include-sensitive true})))))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":rf.egress/profile :rf.egress/local-raw")
                         "gate ON honours the caller's :include-sensitive true by naming local-raw"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; :would-fire-effects[*].args fail closed. The recorded fx
;; args are RAW fx-handler arguments (HTTP bodies, dispatched event
;; vectors, payment maps) NOT rooted at app-db, so the schema-path
;; `project-egress` walk cannot prove them safe — the same leak class
;; as an epoch record's :effects[*].args, which project-egress fails
;; closed. The dry-run egress MUST fail closed too: the emitted form
;; assoc's :rf/redacted onto every :would-fire-effects row's :args BY
;; DEFAULT (independently of the size-elision walker), and the trusted-
;; local :include-fx-args true opt-in keeps the raw args — honoured ONLY
;; under --allow-sensitive-reads.
;; ---------------------------------------------------------------------------

(deftest gate-on-default-still-redacts-fx-args
  ;; Even under --allow-sensitive-reads, fx args fail closed UNLESS the
  ;; caller passes :include-fx-args true. Revealing app-db (gate ON) is a
  ;; different keyspace than revealing the unprovable fx args.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      ;; gate ON but no :include-fx-args opt-in
                                                      :include-sensitive true})))))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":args :rf/redacted")
                         "fx args fail closed by default even under the gate (orthogonal to :include-sensitive)"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Envelope unwrap — the runtime's structured envelope rides through the
;; wire boundary, unwrapped from the `{:value ... :elided-count ...}`
;; eval-form shape.
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through
  ;; Happy-path envelope shape, including the cascade-summary the
  ;; runtime projects from the would-be epoch. The would-fire-effects
  ;; vector + db-state-after-simulation surface (already walked
  ;; server-side; the stubbed canned value is post-walk).
  (async done
    (let [env {:ok?                       true
               :dry-run?                  true
               :rolled-back?              true
               :event                     [:cart/checkout]
               :frame                     :rf/default
               :before-epoch-id           41
               :cascade-summary           {:epoch-id 42
                                           :event-id :cart/checkout
                                           :event-vector [:cart/checkout]
                                           :frame :rf/default
                                           :outcome :ok
                                           :db-diff {:changed-paths [[:cart]]
                                                     :added-paths [] :removed-paths []}
                                           :fx-fired [:http :navigate]
                                           :subs-recomputed 2
                                           :renders 1}
               :would-fire-effects        [{:fx-id :http :args {:url "/checkout"}}
                                           {:fx-id :navigate :args [:order-confirmation]}]
               :db-state-after-simulation {:cart {:items []} :order {:id 1}}}]
      (-> (with-captured-eval! (atom []) (wrap env 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (true? (:dry-run? edn)))
                     (is (true? (:rolled-back? edn)))
                     (is (= (:cascade-summary env) (:cascade-summary edn))
                         "cascade-summary rides through verbatim")
                     (is (= (:would-fire-effects env) (:would-fire-effects edn))
                         "would-fire-effects vector unwrapped from the elision form")
                     (is (= (:db-state-after-simulation env)
                            (:db-state-after-simulation edn))
                         "db-state-after-simulation unwrapped from the elision form"))
                   (done)))))))

(deftest redacted-markers-ride-through
  ;; The server-side walker has already redacted a sensitive slot; the
  ;; tool unwraps the envelope and surfaces the marker, plus the
  ;; :elided-large indicator when the count is non-zero.
  (async done
    (let [env {:ok?                       true
               :dry-run?                  true
               :rolled-back?              true
               :would-fire-effects        [{:fx-id :http :args {:headers {:authorization :rf/redacted}}}]
               :db-state-after-simulation {:user {:token :rf/redacted}
                                           :blob {:rf.size/large-elided {:bytes 99999 :type "string"}}}}]
      (-> (with-captured-eval! (atom []) (wrap env 1)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:auth/login]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= :rf/redacted (get-in edn [:db-state-after-simulation :user :token]))
                         "sensitive slot stays redacted on the wire")
                     (is (= :rf/redacted (get-in edn [:would-fire-effects 0 :args :headers :authorization]))
                         "fx args sensitive slot stays redacted")
                     (is (contains? (:db-state-after-simulation edn) :blob)
                         "large slot collapsed to a marker")
                     (is (= 1 (:elided-large edn))
                         "the elided-large indicator surfaces the marker count"))
                   (done)))))))

(deftest rollback-failed-rides-as-iserror
  ;; SAFETY. The simulation LANDED but the rollback FAILED
  ;; (`replace-frame-state!` rejected the pre-call state), so the
  ;; simulated state can still be the LIVE state. The runtime reports this
  ;; as the documented `:ok? false :reason :rollback-failed :rolled-back?
  ;; false` shape — never an `:ok? true` that would read GREEN over a
  ;; mutated db. The tool's `(false? (:ok? result))` routing MUST surface
  ;; it as an isError envelope so a dry-run that silently mutated the live
  ;; app can never read as success. The structured :reason/:hint +
  ;; :before-epoch-id ride through verbatim for manual recovery.
  (async done
    (let [hint (str "replace-frame-state! rejected the rollback; simulated "
                    "state can still be live. Inspect the frame state and "
                    "replacement failure trace "
                    "before further writes.")
          env {:ok?             false
               :reason          :rollback-failed
               :dry-run?        true
               :rolled-back?    false
               :event           [:cart/checkout]
               :frame           :rf/default
               :before-epoch-id nil
               :hint            hint}]
      (-> (with-captured-eval! (atom []) (wrap env 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (err? r)
                       ":rollback-failed dry-run rides as isError, not a silent green over a mutated db")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :rollback-failed (:reason edn)))
                     (is (false? (:rolled-back? edn))
                         "the mutated-state signal (rolled-back? false) rides through")
                     (is (= hint (:hint edn))
                         "the recovery hint rides through verbatim for manual recovery"))
                   (done)))))))

(deftest rolled-back-false-still-iserror-even-if-runtime-claims-ok
  ;; Belt-and-braces (defence-in-depth AT THE MCP BOUNDARY). A DEGRADED
  ;; or OLDER runtime could return `:ok? true` alongside
  ;; `:rolled-back? false` (the would-be db IS the live db). The MCP tool is the safety boundary the host trusts, so it
  ;; must NOT green-light a non-rolled-back dry-run regardless of what the
  ;; runtime claims for `:ok?`. The `(false? (:rolled-back? result))`
  ;; guard catches exactly that — an `:ok? true :rolled-back? false`
  ;; envelope routes to isError.
  (async done
    (let [env {:ok?          true
               :dry-run?     true
               :rolled-back? false
               :event        [:cart/checkout]
               :frame        :rf/default}]
      (-> (with-captured-eval! (atom []) (wrap env 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (err? r)
                       "an :ok? true :rolled-back? false envelope must NOT read green — the boundary guard fires")
                   (let [edn (read-result-text r)]
                     (is (false? (:rolled-back? edn))
                         "the non-rolled-back signal rides through to the caller"))
                   (done)))))))

(deftest non-map-runtime-result-surfaced-as-unexpected-shape
  ;; A runtime without `dispatch-dry-run` returns something other than
  ;; the wrapped map. The tool surfaces that as a structured
  ;; `:unexpected-shape` rather than silently returning the raw value.
  (async done
    (-> (with-captured-eval! (atom []) "not-a-map"
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:cart/checkout]"})))
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (= :unexpected-shape (:reason edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; The parsed event reaches the runtime as DATA here too.
;;
;; `dispatch-dry-run` shares `dispatch`'s parser and its emitter, so it
;; shares the hazard: the vector check guards only the OUTER shape, and
;; `pr-str` would render what got past it as SOURCE. A nested list would
;; evaluate, a symbol would resolve, and a payload wearing the emitter's
;; own IR tag would be spliced in as raw source — all while the runtime
;; call is being built, ahead of the simulation, and all reachable with
;; `eval-cljs` disabled. Quoting the event closes it.
;;
;; Dry-run composes its call inside an `rt-let`, so the assertions below
;; read the inner runtime call out of the emitted form: `read-string`
;; consumes one balanced form, so slicing at the call's opening paren
;; yields exactly it.
;; ---------------------------------------------------------------------------

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted` for
  anything else — an unquoted list is a call and an unquoted symbol is a
  name lookup, so neither yields the datum it was printed from."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(defn- runtime-call
  "The `(re-frame2-pair.runtime/dispatch-dry-run <event> <opts>)` call read
  out of the emitted `rt-let` form."
  [forms*]
  (let [form (dispatch-form forms*)
        head "(re-frame2-pair.runtime/dispatch-dry-run "
        i    (str/index-of form head)]
    (when i (cljs.reader/read-string (subs form i)))))

(deftest dry-run-cofx-fact-lists-are-not-evaluated
  ;; Dry-run shares dispatch's opts composition, so the opts map rides
  ;; quoted here too: printed, a scripted fact containing a list would be
  ;; evaluated while the call is built, and the simulation would then run
  ;; on a different fact from the one scripted.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:review/event]"
                                                  :cofx "{:review/fact (inc 41)}"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [opts (quoted-datum (nth (runtime-call forms) 2))]
                     (is (= '(inc 41) (get-in opts [:rf.cofx :review/fact]))
                         "the simulated dispatch uses the fact the caller scripted"))
                   (done)))))))
