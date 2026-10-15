(ns re-frame.migration.fresco.shared-rule-test
  "The shared slot rule and printed callback contracts must agree with the shipped door."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.fresco.impl.slot :as rf.fresco.impl.slot]
            [re-frame.migration.fresco.dest :as rf.migration.fresco.dest]
            [re-frame.migration.fresco.donor :as rf.migration.fresco.donor]
            [re-frame.migration.fresco.rewrite :as rf.migration.fresco.rewrite]
            [rewrite-clj.parser :as p]))

(deftest the-slot-rule-is-the-shared-one
  (testing "call the shared resolver"
    (is (identical? rf.migration.fresco.dest/canonical-slot rf.fresco.impl.slot/prop-name)))

  (testing "load it from the shipped package"
    (let [path (some-> (io/resource "re_frame/fresco/impl/slot.cljc") .getPath (str/replace "\\" "/"))]
      (is (str/includes? path "implementation/fresco/src/re_frame/fresco/impl/slot.cljc")
          (str "the slot rule must come from the shipped package's shared file; it came from "
               path)))))

(def ^:private door-file
  (io/file ".." ".." ".." "implementation" "fresco" "src" "re_frame"
           "fresco" "impl" "codec.cljs"))

(defn- door-contracts
  []
  (some-> (re-find #"\(def \^:private callback-contracts[\s\S]*?(#\{[^}]*\})" (slurp door-file))
          second
          edn/read-string))

(deftest the-callback-contracts-are-the-doors
  (testing "print the contracts the door accepts"
    (let [door (door-contracts)]
      (is (= door (set rf.migration.fresco.dest/callback-contracts))
          (str "the door accepts " (pr-str door) " and this tool prints "
               (pr-str rf.migration.fresco.dest/callback-contracts))))))

(deftest the-donor-key-rule
  (testing "shared key conversion and donor deltas"
    (is (= ["className" "class" nil]
           (mapv rf.migration.fresco.donor/key-name
                 [:x/class "class" :--brand-color])))))

(deftest the-destination-vocabulary
  (testing "event spellings and key types"
    (is (= [true true true false false]
           (mapv rf.migration.fresco.dest/event-prop? [:on-click :onClick "on-click" 'on-click :once]))))

  (testing "nested keys follow clj->js"
    (is (= ["page-size" "x/page-size" "first-name"]
           (mapv rf.migration.fresco.dest/nested-key-name [:page-size 'x/page-size "first-name"]))))

  (testing "dangerous HTML spellings"
    (is (= [true true true false]
           (mapv rf.migration.fresco.dest/dangerous-html-key?
                 [:dangerouslySetInnerHTML :dangerously-set-inner-html "dangerouslySetInnerHTML" :dangerous])))))

(defn- collisions [src] (rf.migration.fresco.rewrite/injective-keys (p/parse-string src)))

(deftest amendment-b-injectivity
  (testing "report every source in a collision"
    (is (= [{:slot "fooBar" :keys ["\"fooBar\"" ":foo-bar" ":fooBar"]}]
           (collisions "{:foo-bar 1 :fooBar 2 \"fooBar\" 3}"))))

  (testing "string keys stay verbatim"
    (is (nil? (collisions "{\"first-name\" 1 :first-name 2}"))
        "the donor takes the string verbatim, so these are two DIFFERENT
         slots — `first-name` and `firstName` — and neither collides")))
