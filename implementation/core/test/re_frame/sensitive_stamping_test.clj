(ns re-frame.sensitive-stamping-test
  "Runtime sensitivity stamping and schema-auto redaction tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (privacy/clear-suppression-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::rec)))
    @seen))

(defn- events-of [evs op]
  (filterv #(= op (:operation %)) evs))

(deftest plain-handler-no-sensitive-flag
  (rf/reg-event-db :sensitive/plain
                   (fn [db _] db))
  (let [evs (record-traces #(rf/dispatch-sync [:sensitive/plain]))]
    (doseq [ev evs]
      (is (not (contains? ev :sensitive?))))))

(deftest handler-meta-sensitive-no-longer-stamps-events
  (testing "The handler-meta `:sensitive?` annotation has been removed.
            The `:sensitive?` stamp is now driven exclusively by the
            schema-derived overlap (see `schema-auto-redaction-*` tests
            below). A handler-meta `:sensitive?` value is preserved on
            the registrar's stored meta (registry is opaque) but is no
            longer consulted by the trace surface."
  (rf/reg-event-fx :sensitive/cross-cutting
                   {:sensitive? true}   ;; stored, not consulted
                   (fn [{:keys [db]} _]
                     {:db (assoc db :ran? true)
                      :fx [[:sensitive/noop nil]]}))
  (rf/reg-fx :sensitive/noop (fn [_ _] nil))
  (let [evs (record-traces #(rf/dispatch-sync
                               [:sensitive/cross-cutting {:token "secret"}]))]
    (doseq [op #{:event/dispatched :event :event/db-changed :event/do-fx}]
      (let [matches (filterv #(= op (:operation %)) evs)]
        (is (seq matches) (str op " was emitted"))
        (doseq [ev matches]
          (is (not (true? (:sensitive? ev)))
              (str op " is NOT stamped sensitive (handler-meta annotation removed)"))))))))

(deftest schema-auto-redaction-for-path-scoped-handler
  (testing "A sensitive schema slot installs redaction without user-written
            redaction interceptors; the handler still sees the raw payload."
    (rf/reg-app-schema [:auth]
                       [:map
                        [:username :string]
                        [:password {:sensitive? true} :string]])
    (let [seen (atom nil)]
      (rf/reg-event-db :auth/login
                       [(rf/path :auth)]
                       (fn [auth [_ payload]]
                         (reset! seen payload)
                         (assoc auth :last-login payload)))
      (let [evs (record-traces
                  #(rf/dispatch-sync
                     [:auth/login {:username "ada" :password "shh"}]))
            [run-start]  (filterv #(and (= :event (:operation %))
                                        (= :run-start
                                           (get-in % [:tags :phase])))
                                  evs)
            [db-changed] (events-of evs :event/db-changed)]
        (is (= {:username "ada" :password "shh"} @seen)
            "handler body receives the unredacted event payload")
        (is (true? (:sensitive? run-start))
            "schema-sensitive handler scope is stamped")
        (is (= :rf/redacted
               (get-in run-start [:tags :event 1 :password])))
        (is (= :rf/redacted
               (get-in db-changed [:tags :event 1 :password])))
        (is (= "ada" (get-in db-changed [:tags :event 1 :username])))))))

(deftest schema-auto-redaction-stamps-handler-exception
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]])
  (rf/reg-event-db :auth/throws
                   [(rf/path :auth)]
                   (fn [_ _] (throw (ex-info "boom" {}))))
  (let [evs (record-traces
              #(rf/dispatch-sync
                 [:auth/throws {:password "shh"}]))
        [err] (events-of evs :rf.error/handler-exception)]
    (is (true? (:sensitive? err)))
    (is (= :rf/redacted
           (get-in err [:tags :event 1 :password])))))

(deftest schema-auto-redaction-does-not-affect-unrelated-paths
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]])
  (rf/reg-event-db :profile/save
                   [(rf/path :profile)]
                   (fn [profile [_ payload]]
                     (assoc profile :saved payload)))
  (let [evs (record-traces
              #(rf/dispatch-sync
                 [:profile/save {:password "not-auth"}]))
        [db-changed] (events-of evs :event/db-changed)]
    (is (not (true? (:sensitive? db-changed))))
    (is (= "not-auth"
           (get-in db-changed [:tags :event 1 :password])))))

(deftest trace-buffer-sensitive-filter
  (testing "Trace-buffer `:sensitive?` filter operates on the trace event's
            top-level `:sensitive?` field. Now that the handler-meta
            annotation has been removed, the stamp is schema-derived
            only: a sensitive app-schema slot drives it."
  (rf/clear-trace-buffer!)
  (rf/configure :trace-buffer {:depth 100})
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]])
  (rf/reg-event-db :sensitive/buf
                   [(rf/path :auth)]
                   (fn [auth _] auth))
  (rf/reg-event-db :plain/buf
                   (fn [db _] db))
  (rf/dispatch-sync [:sensitive/buf {:password "x"}])
  (rf/dispatch-sync [:plain/buf])
  (let [all   (rf/trace-buffer)
        sens  (rf/trace-buffer {:sensitive? true})
        plain (rf/trace-buffer {:sensitive? false})]
    (is (pos? (count sens)) "schema-driven sensitive events present in the buffer")
    (is (pos? (count plain)))
    (is (= (count all) (+ (count sens) (count plain))))
    (doseq [ev sens]  (is (true? (:sensitive? ev))))
    (doseq [ev plain] (is (not (true? (:sensitive? ev))))))))

(deftest sensitive-predicate
  (is (true? (rf/sensitive? {:sensitive? true})))
  (is (false? (rf/sensitive? {:sensitive? false})))
  (is (false? (rf/sensitive? {})))
  (is (false? (rf/sensitive? nil)))
  (is (identical? rf/sensitive? privacy/sensitive?)))
