(ns counter-with-stories.elision-demo-cljs-test
  "The elision demo preserves handler privacy and declared wire classification."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [counter-with-stories.elision-demo])
  (:require-macros [re-frame.core :refer [with-frame]]))

(def ^:private registrar-snapshot (atom nil))

;; Leave the source store intact for neighboring story images.
(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (rf.registrar/clear-kind! :app-schema)
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.event-emit/clear-event-listeners!)
  (rf/reg-app-schema [:user/avatar-pdf] [:maybe :string])
  (rf/reg-classification ::avatar {:large [[:user/avatar-pdf]]}))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! rf.frame/frames {})
  (rf.event-emit/clear-event-listeners!))

(use-fixtures :each
  (fn [test-fn]
    (before!)
    (try
      (with-frame :rf/default
        (test-fn))
      (finally (after!)))))

(deftest auth-sign-in-registration-redacts-password
  (is (= [:auth/sign-in {:email "u@example.com" :password :rf/redacted}]
         (rf.classification/redact-event-by-registration
           [:auth/sign-in {:email "u@example.com" :password "secret"}]))))

(deftest sign-in-handler-doesnt-leak-password-to-app-db
  (rf/dispatch-sync [:auth/sign-in
                     {:email "test@example.com" :password "real-password-1234"}])
  (is (= {:email "test@example.com" :submitted? true}
         (:auth/last-sign-in (rf/app-db-value :rf/default)))))

(deftest large-schema-slot-becomes-wire-marker
  (rf/dispatch-sync [:user.avatar-pdf/set {:bytes 5000}])
  (let [db (rf/app-db-value :rf/default)
        blob (:user/avatar-pdf db)
        marker (get-in (rf.elision/elide-wire-value db {:frame :rf/default})
                       [:user/avatar-pdf :rf.size/large-elided])]
    (is (string? blob))
    (is (>= (count blob) 5000))
    (is (number? (:bytes marker)))
    (is (= {:path [:user/avatar-pdf] :reason :classification}
           (select-keys marker [:path :reason])))))

(deftest event-emit-listener-leaves-unschema'd-inline-large-payload
  (let [seen (atom [])
        blob (str/join (repeat 20000 "B"))]
    (rf.event-emit/register-event-listener! ::test-recorder #(swap! seen conj %))
    (rf/dispatch-sync [:user.avatar/upload {:blob blob}])
    (is (= 1 (count @seen)))
    (is (= {:event-id :user.avatar/upload
            :outcome :ok
            :event [:user.avatar/upload {:blob blob}]}
           (select-keys (first @seen) [:event-id :outcome :event])))))

(deftest event-emit-listener-fires-on-every-dispatch
  (let [seen (atom [])]
    (rf.event-emit/register-event-listener! ::test-recorder #(swap! seen conj %))
    (doseq [event [[:auth/sign-in {:email "u@example.com" :password "secret"}]
                  [:user.avatar-pdf/set {:bytes 1024}]
                  [:user.avatar-pdf/clear]]]
      (rf/dispatch-sync event))
    (is (= [[:auth/sign-in :ok]
            [:user.avatar-pdf/set :ok]
            [:user.avatar-pdf/clear :ok]]
           (mapv (juxt :event-id :outcome) @seen)))))
