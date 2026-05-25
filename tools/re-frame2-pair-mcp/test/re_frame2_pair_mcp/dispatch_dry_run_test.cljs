(ns re-frame2-pair-mcp.dispatch-dry-run-test
  "Unit tests for the dispatch-dry-run tool (rf2-17hvp).

  Simulate a re-frame2 cascade without committing — the framework's
  existing `:fx-overrides` + `restore-epoch` primitives compose into a
  true dry-run on the runtime side. The MCP tool is a thin wrapper
  that parses the event-vector arg (same EDN-data posture as
  `dispatch`, rf2-vflrg) and surfaces the structured runtime envelope.

  These tests pin the wire boundary: the arg parser, the runtime call
  shape, and the envelope passthrough — NOT the runtime semantics
  themselves (those are exercised in the runtime tests at
  skills/re-frame2-pair/tests/runtime/, which run against a live
  shadow-cljs build with the preload installed)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.dispatch-dry-run :as dry-run]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-eval!
  [captured* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form-str]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value))
               ([_conn _build-id form-str _opts]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

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
;; <event> <opts>)` with the event as a data literal.
;; ---------------------------------------------------------------------------

(deftest emits-runtime-dispatch-dry-run-call
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :dry-run? true :rolled-back? true}
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"})))
          (.then (fn [_]
                   (let [form    @captured
                         parsed  (cljs.reader/read-string form)]
                     (is (= 're-frame2-pair.runtime/dispatch-dry-run (first parsed))
                         "routes through the runtime dispatch-dry-run fn")
                     (is (= [:cart/checkout] (second parsed))
                         "event vector rides as DATA, not source")
                     (is (= {} (nth parsed 2))
                         "default opts is empty map"))
                   (done)))))))

(deftest threads-frame-arg
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :dry-run? true :rolled-back? true}
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:state/transition :paying]"
                                                  :frame ":checkout"})))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= {:frame :checkout} (nth parsed 2))
                         ":frame threaded into runtime opts"))
                   (done)))))))

(deftest threads-fx-overrides-arg
  ;; User-supplied :fx-overrides ride into the runtime opts; the
  ;; runtime merges them on top of the dry-run override set.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :dry-run? true :rolled-back? true}
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {:http "stub-http"}})))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)
                         opts   (nth parsed 2)]
                     (is (= {:http "stub-http"} (:fx-overrides opts))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Envelope passthrough — the runtime's structured envelope rides through
;; the wire boundary unchanged.
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through
  ;; Happy-path envelope shape, including the cascade-summary the
  ;; runtime projects from the would-be epoch. The would-fire-effects
  ;; vector + db-state-after-simulation surface verbatim.
  (async done
    (let [canned {:ok?                       true
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
      (-> (with-captured-eval! (atom nil) canned
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (true? (:dry-run? edn)))
                     (is (true? (:rolled-back? edn)))
                     (is (= (:cascade-summary canned) (:cascade-summary edn))
                         "cascade-summary rides through verbatim")
                     (is (= (:would-fire-effects canned) (:would-fire-effects edn))
                         "would-fire-effects vector rides through verbatim")
                     (is (= (:db-state-after-simulation canned)
                            (:db-state-after-simulation edn))
                         "db-state-after-simulation rides through verbatim"))
                   (done)))))))

(deftest no-new-epoch-failure-passes-through
  ;; The reducer rejected the event or an interceptor early-returned.
  ;; The runtime envelope's :reason :no-new-epoch passes through; no
  ;; rollback was needed and none was performed.
  (async done
    (let [canned {:ok?    false
                  :reason :no-new-epoch
                  :event  [:noop]
                  :frame  :rf/default
                  :hint   "..."}]
      (-> (with-captured-eval! (atom nil) canned
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
  ;; eval would return something other than a map. The tool surfaces
  ;; that as a structured `:unexpected-shape` rather than silently
  ;; returning the raw value.
  (async done
    (-> (with-captured-eval! (atom nil) "not-a-map"
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:cart/checkout]"})))
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (= :unexpected-shape (:reason edn))))
                 (done))))))
