(ns re-frame.machine-regions-placement-cljs-test
  "`reg-machine` refuses `:regions` on any node that is not `:type :parallel`.

  The runtime runs `:regions` only on a `:type :parallel` machine root, so
  anywhere else they would register and be silently ignored. On a flat or
  compound root they were also walked by the `:timeout` validator, where a
  malformed region body threw a host exception. The likeliest cause is a
  missing `:type :parallel`, and each refusal names it.

  - A flat or compound root: `:rf.error/machine-root-slot-not-supported`, the
    category for every key the runtime never reads on a root, before any other
    check reads the root.
  - A state: `:rf.error/machine-unknown-node-key`, the category a leaf's
    `:on-done` is refused under.
  - A parallel region body keeps its own refusal:
    `:rf.error/machine-root-slot-not-supported` naming the region.

  A `:type :parallel` root registers as before, and so does a machine with no
  `:regions` anywhere.

  Cross-platform (`*_cljs_test.cljc`): the host exception a malformed flat-root
  region body raised differs per host, so both hosts are pinned."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; Load the machines facade so `rf/reg-machine` routes through its
   ;; late-bind hook (`:machines/reg-machine`).
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(defn- registration
  "Register `machine` under a fresh id. Returns `:registered`, the refusal's
  ex-data, or `{:host-throw <message>}` when the throw carries no
  `:rf.error/id`."
  [machine]
  (try (rf/reg-machine (keyword "regions-placement" (str (gensym))) machine) :registered
       (catch #?(:clj Throwable :cljs :default) t
         (let [data (ex-data t)]
           (if (:rf.error/id data)
             data
             {:host-throw #?(:clj (.getMessage ^Throwable t) :cljs (str t))})))))

(def ^:private body {:initial :x :states {:x {}}})

(def ^:private regions-values
  "`:regions` values a node that is not `:type :parallel` might carry: a
  well-formed map, malformed region bodies the `:timeout` validator would walk,
  a timeout inside a body, an empty map and nil."
  [{:r body}
   {:r body :s {:initial :y :states {:y {}}}}
   {:r 42}
   {:r {:initial :x :states 42}}
   {:r {:initial :x :states {:x 42}}}
   {:r {:initial :x :states {:x {:timeout 1000 :on-timeout :y} :y {}}}}
   {}
   nil])

(defn- names-type-parallel? [refusal]
  (str/includes? (str (:reason refusal)) ":type :parallel"))

(deftest a-flat-or-compound-root-refuses-regions
  (doseq [[kind make] {:flat     (fn [r] {:initial :a :regions r :states {:a {}}})
                       :compound (fn [r] {:initial :o :regions r
                                          :states  {:o {:initial :a :states {:a {}}}}})}
          r           regions-values
          :let [refusal (registration (make r))]]
    (testing (str "a " kind " root with :regions " (pr-str r))
      (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id refusal))
          (pr-str refusal))
      (is (= [:regions] (:offending-keys refusal)))
      (is (names-type-parallel? refusal) "the refusal points at :type :parallel"))))

(deftest a-flat-root-names-regions-beside-its-other-unread-keys
  (let [refusal (registration {:initial :a :regions {:r body} :on-done :a :states {:a {}}})]
    (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id refusal)))
    (is (= [:on-done :regions] (:offending-keys refusal)))))

(deftest a-non-map-regions-on-a-flat-root-is-refused-for-its-shape
  (testing "the shape check reads the definition first"
    (is (= :rf.error/machine-bad-structure
           (:rf.error/id (registration {:initial :a :regions 42 :states {:a {}}}))))))

(def ^:private state-positions
  "Position → [a machine whose state `:s` carries `:regions` `r`]."
  {:leaf         (fn [r] {:initial :s :states {:s {:regions r}}})
   :compound     (fn [r] {:initial :s :states {:s {:initial :a :regions r :states {:a {}}}}})
   :nested       (fn [r] {:initial :o :states {:o {:initial :s :states {:s {:regions r}}}}})
   :region-state (fn [r] {:type :parallel :regions {:q {:initial :s :states {:s {:regions r}}}}})})

(deftest a-state-that-is-not-parallel-refuses-regions
  (doseq [[position make] state-positions
          r               regions-values
          :let [refusal (registration (make r))]]
    (testing (str "the " position " state with :regions " (pr-str r))
      (is (= :rf.error/machine-unknown-node-key (:rf.error/id refusal))
          (pr-str refusal))
      (is (= :s (:state refusal)))
      (is (= [:regions] (:offending-keys refusal)))
      (is (names-type-parallel? refusal) "the refusal points at :type :parallel"))))

(deftest a-region-body-keeps-its-own-refusal
  (let [refusal (registration {:type :parallel :regions {:q {:initial :a :regions {:r body} :states {:a {}}}}})]
    (is (= :rf.error/machine-root-slot-not-supported (:rf.error/id refusal)))
    (is (= [:regions :q] (:path refusal)))
    (is (= [:regions] (:offending-keys refusal)))))

(deftest parallel-roots-and-machines-without-regions-register
  (doseq [machine [{:type :parallel :regions {:r body}}
                   {:type :parallel :regions {:r body :s {:initial :y :states {:y {}}}}}
                   {:type :parallel :region-order [:s :r]
                    :regions {:r body :s {:initial :y :states {:y {:initial :z :states {:z {}}}}}}}
                   {:initial :a :states {:a {}}}
                   {:initial :o :states {:o {:initial :a :states {:a {}}}}}
                   ;; A state marked `:type :parallel` registers with its
                   ;; `:regions` exactly as it did.
                   {:initial :a :states {:a {:type :parallel :regions {:r body}}}}]]
    (is (= :registered (registration machine)) (pr-str machine))))
