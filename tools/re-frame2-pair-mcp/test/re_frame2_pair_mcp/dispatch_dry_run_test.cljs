(ns re-frame2-pair-mcp.dispatch-dry-run-test
  "Unit tests for the dispatch-dry-run tool (rf2-17hvp + rf2-z7roa).

  Simulate a re-frame2 cascade without committing — the framework's
  existing `:fx-overrides` + `restore-epoch` primitives compose into a
  true dry-run on the runtime side. The MCP tool is a thin wrapper
  that parses the event-vector arg (same EDN-data posture as
  `dispatch`, rf2-vflrg) and surfaces the structured runtime envelope.

  rf2-z7roa — dry-run is an AI-facing READ surface: its
  `:db-state-after-simulation` (would-be app-db) and
  `:would-fire-effects[*].args` (fx-derived) slots are off-box egress.
  The tool runs them through the elision walker server-side BY DEFAULT
  (gate OFF forces `:elision true` + `:include-sensitive false`); the
  per-call knobs win only under `--allow-sensitive-reads`. It also
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

;; The tool now issues TWO evals on the happy path: the
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
;; Arg parsing — mirrors dispatch's rf2-vflrg gate exactly.
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source
  ;; Host-form source like `(println :pwn)` must NEVER reach the
  ;; runtime — the parser rejects non-vector EDN before the eval
  ;; round-trip.
  (async done
    (-> (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "(println :pwn)"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-an-event-vector (:reason edn)))
                   (is (= :list (:parsed-type edn))))
                 (done))))))

(deftest rejects-missing-event
  (async done
    (-> (dry-run/dispatch-dry-run-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-event (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-unreadable-edn
  (async done
    (-> (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:foo"})
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-event-edn (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Runtime call shape — emits `(re-frame2-pair.runtime/dispatch-dry-run
;; <event> <opts>)` with the event as a data literal, wrapped in the
;; elision form.
;; ---------------------------------------------------------------------------

(deftest emits-runtime-dispatch-dry-run-call
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form "re-frame2-pair.runtime/dispatch-dry-run")
                         "routes through the runtime dispatch-dry-run fn")
                     (is (str/includes? form "[:cart/checkout]")
                         "event vector rides as DATA, not source"))
                   (done)))))))

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

(deftest threads-fx-overrides-arg
  ;; User-supplied :fx-overrides ride into the runtime opts; the
  ;; runtime merges them on top of the dry-run override set. rf2-hf7m9j —
  ;; the documented colon-prefixed string target `":stub-http"` MUST
  ;; coerce to the KEYWORD `:stub-http` in the emitted opts, never the
  ;; raw string (which core silently falls through to the original fx,
  ;; defeating the no-fx-execute guarantee).
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {":http" ":stub-http"}})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":fx-overrides")
                         ":fx-overrides threaded into runtime opts")
                     (is (str/includes? form ":stub-http")
                         "the stub target rides as a keyword")
                     (is (not (str/includes? form "\"stub-http\""))
                         "NOT a string target — a string would fall through to the real fx"))
                   (done)))))))

(deftest rejects-string-fx-overrides-target
  ;; rf2-hf7m9j — a bare (non-colon) string override target can't redirect
  ;; over the wire (core would silently fall it through to the real fx).
  ;; For dry-run that defeats the no-fx-execute guarantee, so reject it as
  ;; an :isError envelope BEFORE the eval rather than ship a broken stub.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {":http" "stub-http"}})))
          (.then (fn [r]
                   (is (err? r)
                       "a bare-string override target ⇒ :isError, never a silent fall-through")
                   (is (nil? (dispatch-form forms))
                       "the dispatch-dry-run eval never fired — rejected up front")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-3q7gep · EP-0017 — scripted recordable coeffects. Identical posture
;; to `dispatch`'s `cofx`: the agent supplies a `cofx "{:rf/time-ms …}"`
;; EDN map (the shared `args/parse-cofx` gate), threaded into the simulated
;; dispatch opts under the flat `:rf.cofx` key. The router preserves it
;; verbatim, so a time-dependent / provided-cofx event dry-runs against the
;; EXACT causal token rather than a fresh stamp or a missing-required-cofx
;; failure. A malformed value short-circuits before the eval.
;; ---------------------------------------------------------------------------

(deftest cofx-threaded-into-opts-under-flat-key
  ;; The headline rf2-3q7gep case: `cofx "{:rf/time-ms 1781078400123}"`
  ;; must appear in the emitted runtime opts under the flat `:rf.cofx`
  ;; key, as DATA (the exact integer time), so the router preserves it
  ;; verbatim and the simulation is reproducible — NOT a freshly stamped
  ;; :rf/time-ms.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:todo/add {:text \"buy milk\"}]"
                                                  :cofx "{:rf/time-ms 1781078400123}"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":rf.cofx")
                         "cofx is threaded under the :rf.cofx opts key the router reads")
                     (is (str/includes? form ":rf/time-ms 1781078400123")
                         "the scripted :rf/time-ms rides as the EXACT integer — emitted under :rf.cofx, not omitted/freshly stamped"))
                   (done)))))))

(deftest cofx-supports-owner-qualified-recordable-facts
  ;; Owner-qualified recordable facts (the app's :counter/delta, a
  ;; subsystem's :rf.route/location) ride through verbatim so an agent can
  ;; script the recorded causal token for the simulation too.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:todo/add {:text \"x\"}]"
                                                  :cofx "{:rf/time-ms 1700000000000 :counter/delta 4 :rf.route/location {:path \"/todos\"}}"})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":rf.cofx"))
                     (is (str/includes? form ":rf/time-ms 1700000000000"))
                     (is (str/includes? form ":counter/delta 4")
                         "an app-owned recordable fact rides through verbatim")
                     (is (str/includes? form ":rf.route/location")
                         "a subsystem recordable fact rides through verbatim"))
                   (done)))))))

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

(deftest cofx-unreadable-rejected
  ;; Unreadable EDN (mismatched brackets) ⇒ :invalid-cofx.
  (async done
    (-> (with-captured-eval! (atom []) (wrap {:ok? true} 0)
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn)
                                           #js {:event "[:counter/inc]"
                                                :cofx "{:rf/time-ms 1"})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-cofx (:reason (read-result-text r))))
                 (done))))))

(deftest cofx-non-integer-time-ms-rejected
  ;; :rf/time-ms must be an integer (epoch ms). A string ⇒
  ;; :invalid-cofx-time-ms, short-circuited before the eval.
  (async done
    (-> (with-captured-eval! (atom []) (wrap {:ok? true} 0)
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn)
                                           #js {:event "[:counter/inc]"
                                                :cofx "{:rf/time-ms \"now\"}"})))
        (.then (fn [r]
                 (is (err? r) "a non-integer :rf/time-ms ⇒ :isError")
                 (is (= :invalid-cofx-time-ms (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; rf2-z7roa — privacy gate. Gate OFF (default published posture) forces
;; the elision walker on AND forces sensitive slots to redact; the form
;; must carry the walker call + the safe opts. The runtime envelope's
;; egress slots are walked server-side (the unit test stubs the runtime,
;; so it asserts on the EMITTED form, not on a live walker).
;; ---------------------------------------------------------------------------

(deftest gate-off-emits-elision-walker-with-safe-opts
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! false
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:auth/login]"
                                                      ;; caller tries to opt OUT / opt IN;
                                                      ;; gate OFF must ignore both.
                                                      :elision false
                                                      :include-sensitive true})))))
          (.then (fn [r]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form "re-frame.core/elide-wire-value")
                         "gate OFF forces the elision walker even when caller passed :elision false")
                     (is (str/includes? form ":db-state-after-simulation")
                         "the would-be db slot is walked")
                     (is (str/includes? form ":would-fire-effects")
                         "the fx args slot is walked")
                     (is (str/includes? form ":rf.size/include-sensitive? false")
                         "gate OFF forces sensitive slots to redact even when caller passed :include-sensitive true")
                     (is (str/includes? form ":rf.size/include-large? false")
                         "gate OFF emits markers (no large pass-through)"))
                   ;; the envelope echoes the effective elision state
                   (let [edn (read-result-text r)]
                     (is (true? (:elision edn)) "effective elision is true under gate OFF"))
                   (done)))))))

(deftest gate-off-signals-configure-raw-state-before-dispatch
  ;; The raw-state tap posture (rf2-c2dtu) is signalled before the
  ;; dispatch eval, so the dry-run's internal restore-epoch tap is gated.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! false
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"})))))
          (.then (fn [_]
                   (let [all @forms
                         cfg-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         disp-idx (first (keep-indexed (fn [i f] (when (str/includes? f "dispatch-dry-run") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! is signalled")
                     (is (some? disp-idx) "dispatch-dry-run is evaluated")
                     (is (< cfg-idx disp-idx)
                         "configure-raw-state! is signalled BEFORE the dispatch eval")
                     (is (str/includes? (nth all cfg-idx) ":allow-raw-state? false")
                         "the gate-OFF posture is pushed to the runtime"))
                   (done)))))))

(deftest gate-on-bare-elision-false-still-walks
  ;; rf2-t55hxg.13 — fail-CLOSED. Pre-fix this asserted that gate-ON +
  ;; `:elision false` shipped the db slot raw with NO walker — which let a
  ;; declared-sensitive db slot leak off-box WITHOUT the per-call
  ;; `:include-sensitive true` opt-in (the EP-0015 two-key gate collapse).
  ;; A BARE `:elision false` now STILL walks: large content passes
  ;; (`include-large? true`) but a declared-sensitive db slot redacts.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      :elision false})))))
          (.then (fn [r]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form "re-frame.core/elide-wire-value")
                         "bare :elision false MUST still walk the db slot — no sensitive bypass")
                     (is (str/includes? form ":rf.size/include-sensitive? false")
                         "sensitive db slot redacts (caller didn't opt in)")
                     (is (str/includes? form ":rf.size/include-large? true")
                         ":elision false overlays include-large? true — large content passes"))
                   (let [edn (read-result-text r)]
                     (is (false? (:elision edn)) "the echo reports the caller's large-slot intent"))
                   (done)))))))

(deftest gate-on-full-raw-opt-in-skips-walker
  ;; rf2-t55hxg.13 — the deliberate full-raw local opt-in (`:elision
  ;; false` AND `:include-sensitive true`) is the ONLY combination that
  ;; ships the db slot raw with no walker wrap.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      :elision false
                                                      :include-sensitive true})))))
          (.then (fn [r]
                   (let [form (dispatch-form forms)]
                     (is (not (str/includes? form "elide-wire-value"))
                         "full-raw opt-in (elision false + include-sensitive true) ships raw — no walker wrap"))
                   (let [edn (read-result-text r)]
                     (is (false? (:elision edn)) "effective elision is false"))
                   (done)))))))

(deftest gate-on-honours-include-sensitive
  ;; With --allow-sensitive-reads + :include-sensitive true, the walker
  ;; still runs (elision default true) but passes sensitive slots through.
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
                     (is (str/includes? form ":rf.size/include-sensitive? true")
                         "gate ON honours the caller's :include-sensitive true"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-6to9xj — :would-fire-effects[*].args fail closed. The recorded fx
;; args are RAW fx-handler arguments (HTTP bodies, dispatched event
;; vectors, payment maps) NOT rooted at app-db, so the schema-path
;; `elide-wire-value` walker cannot prove them safe — the same leak class
;; as an epoch record's :effects[*].args, which projected-record fails
;; closed. The dry-run egress MUST fail closed too: the emitted form
;; assoc's :rf/redacted onto every :would-fire-effects row's :args BY
;; DEFAULT (independently of the size-elision walker), and the trusted-
;; local :include-fx-args true opt-in keeps the raw args — honoured ONLY
;; under --allow-sensitive-reads.
;; ---------------------------------------------------------------------------

(deftest default-redacts-fx-args
  ;; The published-build default (gate OFF). The emitted form fail-closes
  ;; the fx args (assoc :rf/redacted on each :would-fire-effects row),
  ;; while the app-db slot still rides the elide-wire-value walker — the
  ;; two egress slots egress under different policies.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! false
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      ;; caller tries to opt in; gate OFF ignores it.
                                                      :include-fx-args true})))))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":would-fire-effects")
                         "the fx-args fail-close touches :would-fire-effects")
                     (is (str/includes? form ":args :rf/redacted")
                         "gate OFF forces fx args to :rf/redacted even when caller passed :include-fx-args true")
                     (is (str/includes? form "re-frame.core/elide-wire-value")
                         "the app-db slot still rides the size-elision walker"))
                   (done)))))))

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

(deftest opt-in-reveals-fx-args
  ;; --allow-sensitive-reads + :include-fx-args true is the trusted-local
  ;; opt-in: the emitted form does NOT redact the fx args (the redaction
  ;; walk collapses to identity), so the raw :args ride through.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      :include-fx-args true})))))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (not (str/includes? form ":args :rf/redacted"))
                         "gate ON + :include-fx-args true ships raw fx args — no fail-close"))
                   (done)))))))

(deftest fx-args-redacted-markers-ride-through
  ;; End-to-end envelope unwrap: the server-side form has already failed
  ;; closed (the stub canned value is post-redaction), so the wire result
  ;; carries :rf/redacted at each fx row's :args while :fx-id rides through.
  (async done
    (let [env {:ok?                true
               :dry-run?           true
               :rolled-back?       true
               :would-fire-effects [{:fx-id :http     :args :rf/redacted}
                                    {:fx-id :navigate :args :rf/redacted}]
               :db-state-after-simulation {:cart {:items []}}}]
      (-> (with-captured-eval! (atom []) (wrap env 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= :rf/redacted (get-in edn [:would-fire-effects 0 :args]))
                         "first fx row's args fail closed")
                     (is (= :rf/redacted (get-in edn [:would-fire-effects 1 :args]))
                         "second fx row's args fail closed")
                     (is (= :http (get-in edn [:would-fire-effects 0 :fx-id]))
                         "fx-id rides through — operator still sees WHICH effects fire")
                     (is (= :navigate (get-in edn [:would-fire-effects 1 :fx-id]))
                         "second fx-id rides through"))
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

(deftest no-new-epoch-failure-rides-as-iserror
  ;; rf2-wdxyx3 finding 2 — the reducer rejected the event or an
  ;; interceptor early-returned, so the dry-run did NOT land. A known-tool
  ;; runtime failure (`:ok? false`) MUST ride back as an isError envelope,
  ;; matching the dispatch/read-sub no-silent-success parity model: the old
  ;; ok-text shape let a non-landed dry-run read as green and be cache-
  ;; eligible. The structured :reason/:hint rides through verbatim.
  (async done
    (let [env {:ok?    false
               :reason :no-new-epoch
               :event  [:noop]
               :frame  :rf/default
               :hint   "..."}]
      (-> (with-captured-eval! (atom []) (wrap env 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:noop]"})))
          (.then (fn [r]
                   (is (err? r) ":ok? false dry-run rides as isError, not a silent ok-text")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :no-new-epoch (:reason edn))))
                   (done)))))))

(deftest non-map-runtime-result-surfaced-as-unexpected-shape
  ;; A pre-rf2-17hvp runtime would not have `dispatch-dry-run` and the
  ;; eval would return something other than the wrapped map. The tool
  ;; surfaces that as a structured `:unexpected-shape` rather than
  ;; silently returning the raw value.
  (async done
    (-> (with-captured-eval! (atom []) "not-a-map"
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:cart/checkout]"})))
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (= :unexpected-shape (:reason edn))))
                 (done))))))
