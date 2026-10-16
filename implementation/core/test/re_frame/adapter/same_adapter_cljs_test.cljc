(ns re-frame.adapter.same-adapter-cljs-test
  "`same-adapter?` matches by the canonical `:rf.adapter/*` `:kind`, a token
  that survives a copy, rather than by object identity: re-calling `init!`
  with a copied or wrapped canonical adapter map keeps the seated generation
  (Spec 006 §Installation). An adapter with no canonical kind falls back to
  identity."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(deftest same-adapter-matches-by-canonical-kind-else-identity
  (let [canonical rf.substrate.plain-atom/adapter
        copied    (assoc canonical :instrumentation-wrapper true)
        custom-a  {:kind :custom :make-state-container identity}
        custom-b  {:kind :custom :make-state-container identity}
        kindless  (dissoc canonical :kind)]
    (doseq [[expected a b why]
            [[true  canonical copied                "a copy keeps the canonical :kind token"]
             [true  custom-a  custom-a              "a non-canonical adapter is the same as itself"]
             [false custom-a  custom-b              "a shared non-canonical :kind does not conflate two adapters"]
             [false kindless  (assoc kindless :x 1) "a kindless copy has no token to match by"]
             [false canonical nil                   "nothing installed"]
             [false nil       canonical             "a nil adapter is never the installed one"]]]
      (is (= expected (rf.substrate.adapter/same-adapter? a b)) why))))
