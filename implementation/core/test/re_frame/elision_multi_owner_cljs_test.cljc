(ns re-frame.elision-multi-owner-cljs-test
  "The per-frame elision registry is a multi-owner claim registry: each axis
  slot maps a path to the set of owners claiming it, a path redacts while any
  owner claims it, and it is pruned only when its last owner drops. A registry
  holding one owner per path would let a subsystem teardown un-redact a path an
  event still classifies.

  This suite pins the effect path, the pure multi-owner operations and the
  rejected-candidate case; the cross-family proofs (route / flow / machine /
  resource on one path) live in each family's own suite."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            ;; reg-app-schema and the validator hooks the rejected-candidate case needs.
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private sentinel rf.privacy/redacted-sentinel)

;; Every family lowers its claims through the same core ops under its own owner
;; identity, so one `{:source :route}` owner stands in for any subsystem.
(def ^:private subsystem-owner {:source :route})

(defn- claim-subsystem! [paths]
  (rf.elision/swap-elision-slot! :rf/default
    (fn [reg] (rf.elision/add-claims (or reg {}) :sensitive-declarations subsystem-owner paths))))

(defn- drop-subsystem! []
  (rf.elision/swap-elision-slot! :rf/default
    (fn [reg] (rf.elision/remove-owner (or reg {}) :sensitive-declarations subsystem-owner))))

(defn- wire []
  (rf.elision/elide-wire-value (rf.frame/frame-app-db-value :rf/default)))

(deftest effect-then-subsystem-independent-survival-sensitive
  (rf/reg-event :classify-token
    (fn [{:keys [db]} _]
      {:db (assoc-in db [:user :token] "Bearer secret") :sensitive [[:user :token]]}))
  (rf/reg-event :clear-token
    (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
  (rf/dispatch-sync [:classify-token])
  (claim-subsystem! [[:user :token]])
  (is (= #{{:source :effect} subsystem-owner}
         (get (rf.elision/sensitive-declarations) [:user :token]))
      "independent owners union on one path")
  (drop-subsystem!)
  (is (= sentinel (get-in (wire) [:user :token]))
      "the subsystem's teardown leaves the effect's claim, so the path still redacts")
  (rf/dispatch-sync [:clear-token])
  (is (= "Bearer secret" (get-in (wire) [:user :token]))
      "clearing the last owner un-classifies the path"))

(deftest rejected-candidate-classification-never-installs
  ;; Validate-before-install discards a schema-rejected candidate whole, so a
  ;; classification riding it never reaches the registry.
  (rf/reg-app-schema [:n] [:int])
  (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-event :classify-and-break
    (fn [{:keys [db]} _]
      {:db        (assoc db :n "boom")
       :sensitive [[:user :token]]}))
  (rf/dispatch-sync [:classify-and-break])
  (is (not (contains? (rf.elision/sensitive-declarations) [:user :token]))))

(deftest core-ops-union-and-scoped-removal
  (let [e  {:source :effect}
        r  {:source :route}
        r2 (-> {}
               (rf.elision/add-claims :sensitive-declarations e [[:a]])
               (rf.elision/add-claims :sensitive-declarations r [[:a]]))]
    (is (= {:sensitive-declarations {[:a] #{e r}}} r2)
        "add-claims unions owners on one path")
    (is (= {:sensitive-declarations {[:a] #{r}}}
           (rf.elision/remove-claims r2 :sensitive-declarations e [[:a]]))
        "remove-claims strips one owner; the other survives")
    (is (= {} (-> r2
                  (rf.elision/remove-owner :sensitive-declarations e)
                  (rf.elision/remove-owner :sensitive-declarations r)))
        "removing the last owner prunes the path and the slot")
    (is (= {:sensitive-declarations {[:a] #{e} [:b] #{r}}}
           (rf.elision/replace-owner-claims r2 :sensitive-declarations r [[:b]]))
        "replace-owner-claims reconciles one owner's paths, leaving foreign owners")))
