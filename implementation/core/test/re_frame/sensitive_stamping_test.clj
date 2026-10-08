(ns re-frame.sensitive-stamping-test
  "Trace-surface `:sensitive?` stamping and classification auto-redaction.
  Both are driven by the classified `:sensitive` app-db path overlapping a
  path-scoped handler's slice (the per-frame elision registry, EP-0025), not
  by schema slot props or a frame annotation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- install-sensitive! [frame-id paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- first-of [evs op]
  (first (filter #(= op (:operation %)) evs)))

(deftest frame-class-auto-redaction-for-path-scoped-handler
  (testing "a frame-sensitive app-db path installs redaction and the stamp
            without a user interceptor; the handler still sees the raw payload"
    (install-sensitive! :rf/default [[:auth :password]])
    (let [seen (atom nil)]
      (rf/reg-event :auth/login
        {:interceptors [[:rf.interceptor/path [:auth]]]}
        (fn [{:keys [db]} [_ payload]]
          (reset! seen payload)
          {:db (assoc db :last-login payload)}))
      (let [evs       (record-traces
                        #(rf/dispatch-sync [:auth/login {:username "ada" :password "shh"}]))
            run-start (first-of evs :rf.event/run-start)
            redacted  [:auth/login {:username "ada" :password :rf/redacted}]]
        (is (= {:username "ada" :password "shh"} @seen))
        (is (true? (:sensitive? run-start)))
        (is (= redacted (get-in run-start [:tags :rf.event/v])))
        (is (= redacted (get-in (first-of evs :rf.event/db-changed) [:tags :rf.event/v])))))))

(deftest frame-class-auto-redaction-stamps-handler-exception
  (install-sensitive! :rf/default [[:auth :password]])
  (rf/reg-event :auth/throws
    {:interceptors [[:rf.interceptor/path [:auth]]]}
    (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
  (let [err (first-of (record-traces #(rf/dispatch-sync [:auth/throws {:password "shh"}]))
                      :rf.error/handler-exception)]
    (is (true? (:sensitive? err)))
    (is (= [:auth/throws {:password :rf/redacted}] (get-in err [:tags :event])))))

(deftest frame-class-auto-redaction-does-not-affect-unrelated-paths
  (install-sensitive! :rf/default [[:auth :password]])
  (rf/reg-event :profile/save
    {:interceptors [[:rf.interceptor/path [:profile]]]}
    (fn [{:keys [db]} [_ payload]] {:db (assoc db :saved payload)}))
  (let [db-changed (first-of (record-traces #(rf/dispatch-sync [:profile/save {:password "not-auth"}]))
                             :rf.event/db-changed)]
    (is (not (true? (:sensitive? db-changed))))
    (is (= [:profile/save {:password "not-auth"}] (get-in db-changed [:tags :rf.event/v])))))

(deftest trace-buffer-sensitive-filter
  (testing "the trace buffer's `:sensitive?` filter partitions on the
            classification-derived top-level stamp"
    (rf/clear-trace-buffer! :rf/default)
    (rf/configure! {:trace-buffer {:events-retained 100}})
    (install-sensitive! :rf/default [[:auth :password]])
    (rf/reg-event :sensitive/buf
      {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{auth :db} _] {:db auth}))
    (rf/reg-event :plain/buf
      (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:sensitive/buf {:password "x"}])
    (rf/dispatch-sync [:plain/buf])
    (let [all   (rf/trace-buffer :rf/default {:flat true})
          sens  (rf/trace-buffer :rf/default {:flat true :sensitive? true})
          plain (rf/trace-buffer :rf/default {:flat true :sensitive? false})]
      (is (seq sens))
      (is (seq plain))
      (is (= (count all) (+ (count sens) (count plain))))
      (is (every? #(true? (:sensitive? %)) sens))
      (is (not-any? #(true? (:sensitive? %)) plain)))))

(deftest sensitive-predicate-fails-closed-on-a-malformed-stamp
  ;; `:sensitive?` is schema-typed boolean, so a non-boolean truthy stamp is a
  ;; contract violation and reads as SENSITIVE (a `true?` reading would forward
  ;; it). The result is always a boolean, never the stamp value.
  (doseq [[ev expected] [[{:sensitive? true}    true]
                         [{:sensitive? "false"} true]
                         [{:sensitive? false}   false]
                         [{:other :key}         false]
                         [nil                   false]]]
    (is (= expected (rf/sensitive? ev)) (pr-str ev))))
