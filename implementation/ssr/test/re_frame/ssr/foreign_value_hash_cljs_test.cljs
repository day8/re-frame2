(ns re-frame.ssr.foreign-value-hash-cljs-test
  "A foreign JS value in the render tree — a plain object or a JS array —
  hashes as one fixed token instead of being printed. `cljs.core`'s printer
  descends into exactly those two with no seen-set, so a cyclic graph such as
  React 19's context (its `Provider` key points back at the context) would
  overflow the stack, and a printed object would leak `:advanced`-munged
  function names into the hash."
  (:require ["react" :as react]
            [cljs.test :refer-macros [deftest is testing]]
            [re-frame.ssr.hash :as rf.ssr.hash]))

(def ^:private provider
  "A real React 19 context provider: `ctx.Provider` IS the context, so it is
  cyclic."
  (.-Provider (react/createContext "unset")))

(deftest a-foreign-value-serialises-to-one-fixed-token
  (testing "an object (even one holding a fn) and an array each collapse to an
            identity-free token"
    (is (= "#js{}" (rf.ssr.hash/canonical-edn #js {"f" (fn [] 1)})))
    (is (= "#js[]" (rf.ssr.hash/canonical-edn #js [1 2 3]))))
  (testing "a cyclic provider in head position hashes; its props and children
            still serialise"
    (is (= "[#js{} {:value \"dark\"} [:p \"x\"]]"
           (rf.ssr.hash/canonical-edn [provider {:value "dark"} [:p "x"]])))))

(deftest only-the-two-descending-printer-branches-are-intercepted
  (testing "a foreign value whose print form is bounded keeps it, so a Date
            hashes as the #inst the JVM prints for it"
    (is (= "#inst \"1970-01-01T00:00:00.000-00:00\""
           (rf.ssr.hash/canonical-edn (js/Date. 0))))))
