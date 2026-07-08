(ns re-frame.epoch-override-capture-test
  "rf2-yigokd — `:rf/epoch-record` captures the envelope's SERIALIZABLE
  `:fx-overrides` / `:interceptor-overrides` so a Tool-Pair strict replay can
  re-supply them beside `:rf.cofx` (Spec-Schemas §`:rf/epoch-record`,
  Tool-Pair §Replay). Proves:

    1. Absence on the override-free hot path — neither key rides the record.
    2. A keyword-valued (EDN) `:fx-overrides` entry is captured VERBATIM, and
       re-supplying the CAPTURED map on a fresh dispatch reproduces the same
       redirect — the 'strict replay re-supplies the captured override keys'
       contract, exercised here at the core+epoch layer. (The Tool-Pair
       pair-mcp dispatch-tool re-supply WIRING is a separate, already-tracked
       follow-up slice — Slice 3 of the ruling — not this dispatch's scope.)
    3. A fn-valued `:fx-overrides` entry is marker-ized to `:rf/fn-override`
       at capture — the fn itself NEVER rides the record.
    4. A per-call `:interceptor-overrides` entry is captured verbatim and
       EDN round-trips through the record; re-supplying it reproduces the
       removal.
    5. The per-frame override tier is explicitly OUT of scope — the record
       reflects only the envelope's own per-call + lexical keys, per the
       ruling's pinned scope."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(deftest override-free-dispatch-omits-both-keys
  (testing "no per-call overrides => the record carries neither key"
    (rf/reg-frame :test/main {})
    (rf/reg-event :probe/noop (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:probe/noop] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (not (contains? r :fx-overrides))
          "no :fx-overrides key on an override-free record")
      (is (not (contains? r :interceptor-overrides))
          "no :interceptor-overrides key on an override-free record"))))

(deftest keyword-valued-fx-override-is-captured-and-resuppliable
  (testing "an id-valued :fx-overrides entry rides the record verbatim, and
   re-supplying the captured map on a fresh dispatch reproduces the redirect"
    (rf/reg-frame :test/main {})
    (let [real-fired (atom false)
          stub-fired (atom false)]
      (rf/reg-fx :probe/real-fx (fn [_m _args] (reset! real-fired true)))
      (rf/reg-fx :probe/stub-fx (fn [_m _args] (reset! stub-fired true)))
      (rf/reg-event :probe/emit-fx (fn [_ _] {:fx [[:probe/real-fx nil]]}))

      (rf/dispatch-sync [:probe/emit-fx]
                        {:frame :test/main
                         :fx-overrides {:probe/real-fx :probe/stub-fx}})
      (is @stub-fired "the override redirected the call to the stub")
      (is (not @real-fired) "the real fx did not fire")

      (let [r (last-record :test/main)]
        (is (= {:probe/real-fx :probe/stub-fx} (:fx-overrides r))
            "the record captures the envelope's per-call :fx-overrides verbatim")

        ;; "strict replay re-supplies the captured override keys" — re-drive
        ;; the same event, forwarding ONLY the captured :fx-overrides (no
        ;; hand-written opt at this call site), and confirm the redirect
        ;; still applies.
        (reset! real-fired false)
        (reset! stub-fired false)
        (rf/dispatch-sync [:probe/emit-fx]
                          (merge {:frame :test/main}
                                 (select-keys r [:fx-overrides])))
        (is @stub-fired
            "re-supplying the captured :fx-overrides reproduces the redirect")
        (is (not @real-fired))))))

(deftest fn-valued-fx-override-is-marker-ized-never-a-fn
  (testing "a CLJS-reference fn-valued :fx-overrides entry is recorded as the
   opaque :rf/fn-override sentinel, never the fn itself"
    (rf/reg-frame :test/main {})
    (rf/reg-fx :probe/real-fx (fn [_m _args] nil))
    (rf/reg-event :probe/emit-fx (fn [_ _] {:fx [[:probe/real-fx nil]]}))

    (rf/dispatch-sync [:probe/emit-fx]
                      {:frame :test/main
                       :fx-overrides {:probe/real-fx (fn [_m _args] :ran)}})

    (let [r (last-record :test/main)]
      (is (= {:probe/real-fx :rf/fn-override} (:fx-overrides r))
          "the fn is replaced by the opaque sentinel — never rides the record"))))

(deftest interceptor-override-is-captured-verbatim-and-resuppliable
  (testing "a per-call :interceptor-overrides entry is captured verbatim,
   EDN round-trips through the record, and re-supplying it reproduces the
   removal"
    (rf/reg-frame :test/main {})
    (let [seen (atom 0)]
      (rf/reg-interceptor ::counting-icpt
        {:before (fn [ctx] (swap! seen inc) ctx)})
      (rf/reg-event :probe/counted
        {:interceptors [::counting-icpt]}
        (fn [{:keys [db]} _] {:db db}))

      (rf/dispatch-sync [:probe/counted] {:frame :test/main})
      (is (= 1 @seen) "the interceptor fires on a plain dispatch")

      (rf/dispatch-sync [:probe/counted]
                        {:frame :test/main
                         :interceptor-overrides {::counting-icpt nil}})
      (is (= 1 @seen)
          "the override removed the interceptor — no further increment")

      (let [r (last-record :test/main)]
        (is (= {::counting-icpt nil} (:interceptor-overrides r))
            "the record captures the envelope's per-call :interceptor-overrides verbatim")

        (rf/dispatch-sync [:probe/counted]
                          (merge {:frame :test/main}
                                 (select-keys r [:interceptor-overrides])))
        (is (= 1 @seen)
            "re-supplying the captured :interceptor-overrides reproduces the removal")))))

(deftest per-frame-override-tier-is-not-captured
  (testing "the per-frame :fx-overrides tier is explicitly OUT of scope — the
   record reflects only the envelope's own per-call + lexical keys"
    (rf/reg-fx :probe/frame-fx (fn [_m _args] nil))
    (rf/reg-fx :probe/frame-fx-stub (fn [_m _args] nil))
    (rf/reg-event :probe/emit-frame-fx (fn [_ _] {:fx [[:probe/frame-fx nil]]}))
    (rf/reg-frame :test/main {:fx-overrides {:probe/frame-fx :probe/frame-fx-stub}})

    (rf/dispatch-sync [:probe/emit-frame-fx] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (not (contains? r :fx-overrides))
          "a per-frame-only override is NOT captured on the record — only per-call + lexical rides it"))))
