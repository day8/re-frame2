(ns re-frame.db-noop-commit-test
  "Commit-level `:db` semantics (Spec 009 §Canonical per-event trace sequence):

    1. `commit-frame-transition!` SKIPS the container write when the next
       app-db is `identical?` to the current one (the `(if cond (assoc db …) db)`
       else-arm); a different-object-but-`=` value still writes.
    2. Change DETECTION is `=`: a `:db` commit that left app-db `=` emits
       `:rf.event/db-noop`, otherwise `:rf.event/db-changed` — exactly one.
    3. `{:db nil}` commits `{}` (app-db is never nil) and emits the dev-only
       `:rf.warning/db-nil-coerced`; a deliberate `{:db {}}` emits nothing.

  Whether the write was skipped is read off frame-state OBJECT IDENTITY, which
  needs no channel, so it runs under the production gate too. The trace
  assertions are dev-only and sit inside `(when rf.interop/debug-enabled? …)`
  arms, negatives included: over the empty production ring they would pass
  for free."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing  :reload)
  (require 're-frame.ssr      :reload)
  (require 're-frame.machines :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- dispatch-and-observe
  "Dispatch `event` and return `{:wrote? <frame-state object replaced?>
  :ops <set of trace operations emitted>}`."
  [event]
  (let [fs-before (rf.frame/frame-state-value :rf/default)
        acc       (atom [])]
    (rf/register-listener! :trace ::ops (fn [ev] (swap! acc conj ev)))
    (try
      (rf/dispatch-sync event)
      {:wrote? (not (identical? fs-before (rf.frame/frame-state-value :rf/default)))
       :ops    (set (map :operation @acc))}
      (finally
        (rf/unregister-listener! :trace ::ops)))))

(deftest identical-noop-skips-write-and-emits-db-noop
  (rf/reg-event :noop/seed (fn [_ _] {:db {:counter 1}}))
  (rf/reg-event :noop/maybe-inc
    (fn [{:keys [db]} [_ do-it?]] {:db (if do-it? (update db :counter inc) db)}))
  (rf/reg-event :noop/rebuild-equal (fn [_ _] {:db {:counter 1}}))
  (doseq [[label event wrote? signal]
          [["the identical db is not re-installed, and reports db-noop"
            [:noop/maybe-inc false] false :rf.event/db-noop]
           ["a changed db is installed, and reports db-changed"
            [:noop/maybe-inc true] true :rf.event/db-changed]
           ["an =-but-distinct db is still installed (only identical? skips), and reports db-noop"
            [:noop/rebuild-equal] true :rf.event/db-noop]]]
    (testing label
      (rf/dispatch-sync [:noop/seed])
      (let [{:keys [ops] :as seen} (dispatch-and-observe event)]
        (is (= wrote? (:wrote? seen)))
        (when rf.interop/debug-enabled?
          (is (= #{signal} (set (filter #{:rf.event/db-noop :rf.event/db-changed} ops)))
              "exactly one of db-noop / db-changed fires"))))))

(deftest db-nil-coerced-to-empty-map-with-diagnostic
  (rf/reg-event :nil/seed (fn [_ _] {:db {:counter 7}}))
  (rf/reg-event :nil/return-nil (fn [_ _] {:db nil}))
  (rf/reg-event :nil/clear (fn [_ _] {:db {}}))
  (doseq [[label event warned?]
          [["{:db nil} commits {} and warns" [:nil/return-nil] true]
           ["a deliberate {:db {}} clear commits {} and does not warn" [:nil/clear] false]]]
    (testing label
      (rf/dispatch-sync [:nil/seed])
      (let [{:keys [ops]} (dispatch-and-observe event)]
        (is (= {} (rf.frame/frame-app-db-value :rf/default)) "app-db is {}, never nil")
        (when rf.interop/debug-enabled?
          (is (= warned? (contains? ops :rf.warning/db-nil-coerced))))))))
