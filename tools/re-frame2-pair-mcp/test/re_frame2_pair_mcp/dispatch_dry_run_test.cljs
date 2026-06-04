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
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

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
  ;; runtime merges them on top of the dry-run override set.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {:http "stub-http"}})))
          (.then (fn [_]
                   (let [form (dispatch-form forms)]
                     (is (str/includes? form ":fx-overrides")
                         ":fx-overrides threaded into runtime opts")
                     (is (str/includes? form "\"stub-http\"")))
                   (done)))))))

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

(deftest gate-on-honours-elision-false
  ;; With --allow-sensitive-reads, the caller's :elision false wins —
  ;; the form ships the bare runtime call with NO walker wrap.
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
                     (is (not (str/includes? form "elide-wire-value"))
                         "gate ON + :elision false ships raw — no walker wrap"))
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

(deftest no-new-epoch-failure-passes-through
  ;; The reducer rejected the event or an interceptor early-returned.
  ;; The runtime envelope's :reason :no-new-epoch passes through; no
  ;; rollback was needed and none was performed. (Soft-failure
  ;; envelopes carry no egress slots, so the walker is a no-op.)
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
                   (is (not (err? r)) "soft-failure rides as ok-text, not isError")
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
