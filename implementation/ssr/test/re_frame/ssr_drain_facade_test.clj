(ns re-frame.ssr-drain-facade-test
  "Per rf2-zplpsp — the `re-frame.ssr/drain-blocking-resources!` FAÇADE
  WRAPPER (ssr.cljc §196-264), tested IN THE SSR SLICE (where the resources
  artefact is absent).

  The drain LOOP + timeout POLICY live in the resources artefact
  (`re-frame.resources.ssr/drain-blocking-resources!`, published as the
  `:resources/drain-blocking-ssr!` late-bind hook and exercised by the
  resources slice's `resources_ssr_cljs_test`). This façade is the thin ssr-
  slice wrapper the Ring / streaming host render path actually calls: it
  resolves the late-bound hook and, when present, forwards a normalised opts
  map (`:deadline-ms` / `:pump!` / `:tick-ms`); when ABSENT it returns a
  fixed settled-shape no-op.

  The ssr slice never loads resources, so the hook is ALWAYS absent here — yet
  nothing pinned the ssr-slice-specific branches:

    1. HOOK-ABSENT no-op fast path — the DEFAULT path for every SSR app that
       does not load the optional resources artefact. It MUST return
       `{:settled? true :timed-out #{} :route-blocking-failure nil}` — the
       shape the Ring host consults for its pre-render `:settled?` /
       `:route-blocking-failure` decision. A regression returning nil / a
       wrong shape would break that consult with no ssr-slice test to catch it.
    2. `:pump!` RESOLUTION — `(if (contains? opts :pump!) (:pump! opts)
       default-blocking-pump!)`: an EXPLICIT nil `:pump!` must WIN (pass nil
       through), while an ABSENT `:pump!` defaults to the host-yield pump. The
       `contains?` gate (not an `or`) is what makes explicit-nil distinguishable
       from absent.
    3. DEFAULT timeout — `default-ssr-blocking-timeout-ms` (5000) applied as
       `:deadline-ms` when `:ssr-blocking-timeout-ms` is absent.

  These are pinned by (a) calling the façade with the hook removed and asserting
  the settled-shape, and (b) installing a RECORDING stub hook and asserting the
  exact opts map the façade forwards."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr :as ssr]))

(def ^:private drain-key :resources/drain-blocking-ssr!)

;; Private façade defaults, reached via the var AT CALL TIME (not captured at
;; ns-load): sibling ssr suites reload `re-frame.ssr` in their fixtures, minting
;; a fresh `default-blocking-pump!` fn object, so an `identical?` compare must
;; read the CURRENT var root the façade also resolves — a load-time snapshot
;; would go stale after any such reload.
(defn- default-pump [] @#'ssr/default-blocking-pump!)
(defn- default-timeout [] @#'ssr/default-ssr-blocking-timeout-ms)

(defn- restore-hook!
  "Restore the drain hook to its pre-test registration (or remove it when it
  was absent), keeping the late-bind resolution cache consistent."
  [prev]
  (if prev
    (late-bind/set-fn! drain-key prev)
    (do (swap! late-bind/hooks dissoc drain-key)
        (late-bind/invalidate-cache! drain-key)))
  nil)

;; ---- (1) hook-absent no-op fast path ---------------------------------------

(def ^:private settled-shape
  {:settled? true :timed-out #{} :route-blocking-failure nil})

(deftest hook-absent-returns-the-settled-fast-path-shape
  (testing "rf2-zplpsp — with NO resources artefact (the `:resources/drain-
  blocking-ssr!` hook unregistered — the default for every no-resources SSR
  app) the façade is a no-op returning the exact settled-shape the Ring host
  consults: {:settled? true :timed-out #{} :route-blocking-failure nil}"
    (let [prev (late-bind/get-fn drain-key)]
      (try
        (swap! late-bind/hooks dissoc drain-key)
        (late-bind/invalidate-cache! drain-key)
        (is (nil? (late-bind/get-fn drain-key))
            "precondition: the drain hook is absent in the ssr slice")
        (testing "1-arity (the host's default call — no opts)"
          (is (= settled-shape (ssr/drain-blocking-resources! :ssr/some-frame))
              "hook-absent 1-arity returns the settled-shape"))
        (testing "2-arity opts are IGNORED on the absent path — still settled-shape"
          (is (= settled-shape
                 (ssr/drain-blocking-resources!
                   :ssr/some-frame {:ssr-blocking-timeout-ms 123 :pump! nil :tick-ms 9}))
              "even with opts, the hook-absent path short-circuits to settled-shape"))
        (finally
          (restore-hook! prev))))))

;; ---- (2) hook-present opts resolution --------------------------------------

(deftest hook-present-forwards-resolved-opts-and-return
  (testing "rf2-zplpsp — with the drain hook PRESENT the façade forwards the
  frame-id and a normalised opts map, and returns the hook's result verbatim"
    (let [prev     (late-bind/get-fn drain-key)
          captured (atom nil)
          sentinel {:settled? false :timed-out #{[:x]} :route-blocking-failure {:route :r}}]
      (try
        (late-bind/set-fn! drain-key
          (fn [frame-id opts]
            (reset! captured {:frame-id frame-id :opts opts})
            sentinel))

        (testing "the hook's return rides back verbatim"
          (is (= sentinel (ssr/drain-blocking-resources! :ssr/f))
              "the façade does not reshape the resources drain result"))

        (testing "DEFAULTS (1-arity / no opts): :deadline-ms = default 5000,
                  :pump! = default host-yield pump, :tick-ms = 5"
          (reset! captured nil)
          (ssr/drain-blocking-resources! :ssr/f)
          (let [{:keys [frame-id opts]} @captured]
            (is (= :ssr/f frame-id) "frame-id is forwarded")
            (is (= (default-timeout) (:deadline-ms opts))
                "absent :ssr-blocking-timeout-ms → default-ssr-blocking-timeout-ms (5000) as :deadline-ms")
            (is (= 5000 (:deadline-ms opts)) "…which is 5000ms")
            (is (identical? (default-pump) (:pump! opts))
                "absent :pump! defaults to the host-yield default-blocking-pump!")
            (is (= 5 (:tick-ms opts)) "absent :tick-ms → 5")))

        (testing "EXPLICIT :pump! nil WINS over the default (the `contains?` gate,
                  not an `or`) — a sync test stub drives a never-settling resource
                  straight to the deadline"
          (reset! captured nil)
          (ssr/drain-blocking-resources! :ssr/f {:pump! nil})
          (let [opts (:opts @captured)]
            (is (contains? opts :pump!) ":pump! key is present in the forwarded opts")
            (is (nil? (:pump! opts))
                "an EXPLICIT nil :pump! is passed through, NOT replaced by the default")
            (is (not (identical? (default-pump) (:pump! opts)))
                "…confirming the default did not win")))

        (testing "EXPLICIT values thread through: :ssr-blocking-timeout-ms →
                  :deadline-ms, a custom :pump!, and :tick-ms"
          (reset! captured nil)
          (let [my-pump (fn [_tick] :pumped)]
            (ssr/drain-blocking-resources! :ssr/f
              {:ssr-blocking-timeout-ms 250 :pump! my-pump :tick-ms 7})
            (let [opts (:opts @captured)]
              (is (= 250 (:deadline-ms opts)) "explicit :ssr-blocking-timeout-ms → :deadline-ms")
              (is (identical? my-pump (:pump! opts)) "an explicit custom :pump! is threaded")
              (is (= 7 (:tick-ms opts)) "explicit :tick-ms is threaded"))))
        (finally
          (restore-hook! prev))))))
