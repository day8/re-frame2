(ns re-frame.epoch-override-capture-test
  "`:rf/epoch-record` captures the envelope's SERIALIZABLE
  `:fx-overrides` / `:interceptor-overrides` so a Tool-Pair strict replay can
  re-supply them beside `:rf.cofx` (Spec-Schemas §`:rf/epoch-record`,
  Tool-Pair §Replay). Proves:

    1. Absence on the override-free hot path — neither key rides the record.
    2. The per-frame override tier is explicitly OUT of scope — the record
       reflects only the envelope's own per-call + lexical keys.

  The captured shapes themselves — an id-valued `:fx-overrides` entry and an
  `:interceptor-overrides` entry verbatim, a fn-valued entry marker-ized to
  `:rf/fn-override` — and their re-supply on replay are pinned on both hosts
  by `re-frame.epoch-replay-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(deftest override-free-dispatch-omits-both-keys
  (testing "no per-call overrides => the record carries neither key"
    (rf/make-frame {:id :test/main})
    (rf/reg-event :probe/noop (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:probe/noop] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (not (contains? r :fx-overrides))
          "no :fx-overrides key on an override-free record")
      (is (not (contains? r :interceptor-overrides))
          "no :interceptor-overrides key on an override-free record"))))

(deftest per-frame-override-tier-is-not-captured
  (testing "the per-frame :fx-overrides tier is explicitly OUT of scope — the
   record reflects only the envelope's own per-call + lexical keys"
    (rf/reg-fx :probe/frame-fx (fn [_m _args] nil))
    (rf/reg-fx :probe/frame-fx-stub (fn [_m _args] nil))
    (rf/reg-event :probe/emit-frame-fx (fn [_ _] {:fx [[:probe/frame-fx nil]]}))
    (rf/make-frame {:id :test/main :fx-overrides {:probe/frame-fx :probe/frame-fx-stub}})

    (rf/dispatch-sync [:probe/emit-frame-fx] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (not (contains? r :fx-overrides))
          "a per-frame-only override is NOT captured on the record — only per-call + lexical rides it"))))
