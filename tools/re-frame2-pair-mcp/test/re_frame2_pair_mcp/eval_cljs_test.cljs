(ns re-frame2-pair-mcp.eval-cljs-test
  "Unit tests for the eval-cljs tool's fail-loud + auto-detect build
  resolution (rf2-ivlb3).

  THE BUG: `eval-cljs` against a build with no live re-frame2-pair
  runtime returned `{:ok? true :value nil}` for EVERY form — including
  `(count ...)`, which can never be nil — because shadow's `cljs-eval`
  against a non-running build yields a blank value that
  `cljs-eval-value` reads as nil, indistinguishable from a genuine nil.
  ~30 min of dead-end debugging in the wild.

  THE FIX (shared with the bash shim via the same `probe` logic):
    1. Fail loud — preflight the runtime sentinel; a runtime-absent
       build returns `{:ok? false :reason :no-runtime-for-build ...}`
       enumerating the running builds, NEVER `:ok? true :value nil`.
    2. Auto-detect — when no `:build` arg is passed, detect the single
       running shadow build instead of blindly defaulting to `:app`.
       Exactly one running ⇒ use it; zero/many ⇒ fail loud listing them.

  The runtime calls these tests intercept:
    - `nrepl/jvm-eval`         — the `active-builds` enumeration (JVM-side).
    - `nrepl/cljs-eval-value`  — the sentinel probe + the actual eval."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]))

;; eval-cljs is gated behind --allow-eval; flip it ON for the suite and
;; restore the default OFF afterwards so we test the resolution path,
;; not the gate (the gate has its own coverage in conformance_test).
(use-fixtures :each
  {:before (fn [] (eval-cljs/set-allow-eval! true))
   :after  (fn [] (eval-cljs/set-allow-eval! false))})

(def ^:private read-edn tu/extract-edn)
(def ^:private err? tu/error?)

(defn- fresh-conn []
  (nrepl/make-conn 0 "127.0.0.1"))

(defn- sentinel-probe? [form-str]
  (and (string? form-str)
       (re-find #"__re_frame2_pair_runtime" form-str)))

(defn- with-stubbed-runtime!
  "Install stubs over the two nREPL entry points eval-cljs touches:

    - `nrepl/jvm-eval`         resolves `{:value <running-builds-edn>}`
      where `<running-builds-edn>` is the pr-str of `running-vec`
      (mimics shadow's `active-builds` JVM result).
    - `nrepl/cljs-eval-value`  resolves `runtime?` for the sentinel
      probe form, and `eval-value` for any other (the actual eval).

  Restores both in `.finally` so cleanup outlives async resolution."
  [{:keys [running-vec runtime? eval-value]} body-fn]
  (let [orig-jvm  nrepl/jvm-eval
        orig-cljs nrepl/cljs-eval-value
        ;; jvm-eval returns a CLJS map {:value "..."} in production; the
        ;; stub mirrors that shape so `running-builds`' `(:value resp)`
        ;; read works.
        jvm-stub  (fn
                    ([_conn _form] (js/Promise.resolve {:value (pr-str running-vec)}))
                    ([_conn _form _opts] (js/Promise.resolve {:value (pr-str running-vec)})))
        cljs-stub (fn
                    ([_conn _build form-str]
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value)))
                    ([_conn _build form-str _opts]
                     (js/Promise.resolve
                       (if (sentinel-probe? form-str) runtime? eval-value))))]
    (set! nrepl/jvm-eval jvm-stub)
    (set! nrepl/cljs-eval-value cljs-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (set! nrepl/jvm-eval orig-jvm)
                    (set! nrepl/cljs-eval-value orig-cljs))))))

;; ---------------------------------------------------------------------------
;; Fail-loud — the headline regression (rf2-ivlb3).
;; ---------------------------------------------------------------------------

(deftest no-runtime-for-build-fails-loud
  ;; A build IS running but has no live runtime sentinel. The eval MUST
  ;; NOT return `:ok? true :value nil` — it must fail loud with
  ;; `:no-runtime-for-build` enumerating the running builds.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:app] :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)) "must NOT report success")
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [:app] (:running-builds edn)) "enumerates running builds")
                   (is (string? (:hint edn)))
                   (is (not (contains? edn :value))
                       "no :value slot — the eval did not run"))
                 (done))))))

(deftest no-running-build-fails-loud
  ;; Zero shadow builds running, no :build passed → can't auto-detect.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [] :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(count [1 2 3])"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [] (:running-builds edn))))
                 (done))))))

(deftest multiple-builds-ambiguous-fails-loud
  ;; Two builds running, no :build passed → can't pick one. Error lists
  ;; both so the operator sees the right --build.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:app :examples/step-deck]
                                :runtime? true :eval-value 42}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= [:app :examples/step-deck] (:running-builds edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Auto-detect — the single running build is used without a :build arg.
;; ---------------------------------------------------------------------------

(deftest auto-detects-single-running-build
  ;; No :build arg, exactly one running build with a live runtime →
  ;; eval runs against it; the resolved build is echoed back.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:examples/step-deck]
                                :runtime? true :eval-value 3}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= 3 (:value edn)))
                   (is (= :examples/step-deck (:build edn))
                       "auto-detected build echoed back"))
                 (done))))))

(deftest explicit-build-honoured-when-runtime-present
  ;; An explicit :build with a live runtime → used verbatim; no
  ;; auto-detect (running-vec deliberately differs from the requested
  ;; build to prove it isn't consulted on the happy path).
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:other]
                                :runtime? true :eval-value 99}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(+ 90 9)" :build "app"})))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-edn r)]
                   (is (true? (:ok? edn)))
                   (is (= 99 (:value edn)))
                   (is (= :app (:build edn)) "explicit build used verbatim"))
                 (done))))))

(deftest explicit-build-with-no-runtime-fails-loud
  ;; An explicit :build that has no runtime sentinel → fail loud,
  ;; enumerating the builds that ARE running so the operator can switch.
  (async done
    (-> (with-stubbed-runtime! {:running-vec [:examples/step-deck]
                                :runtime? false :eval-value nil}
          (fn []
            (eval-cljs/eval-cljs-tool (fresh-conn)
                                      #js {:form "(count [1 2 3])" :build "app"})))
        (.then (fn [r]
                 (let [edn (read-edn r)]
                   (is (false? (:ok? edn)))
                   (is (= :no-runtime-for-build (:reason edn)))
                   (is (= :app (:build edn)) "echoes the requested build")
                   (is (= [:examples/step-deck] (:running-builds edn))
                       "lists the builds that ARE running"))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Gate + arg validation still hold.
;; ---------------------------------------------------------------------------

(deftest gate-closed-rejects-before-touching-nrepl
  (async done
    (eval-cljs/set-allow-eval! false)
    (-> (eval-cljs/eval-cljs-tool (fresh-conn) #js {:form "(+ 1 2)"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (= :rf.error/eval-cljs-disabled (:reason edn))))
                 (eval-cljs/set-allow-eval! true)
                 (done))))))

(deftest missing-form-rejected
  (async done
    (-> (eval-cljs/eval-cljs-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-edn r)]
                   (is (= :missing-form (:reason edn))))
                 (done))))))
