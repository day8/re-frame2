(ns re-frame.redact-path-vector-parent-cljs-test
  "`re-frame.privacy/redact-event` writes only through a parent that can TAKE
  the leaf segment. The path-overlap arm derives payload paths from the DB's
  classification, not the payload's shape, and the router computes that
  redaction in `prepare-handler-ctx`, outside the chain's capture, so a throw
  from `assoc-in` (a keyword segment on a vector, say) would escape
  `dispatch-sync` and lose the event. Always-on: no posture tag."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [are deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest redact-event-takes-only-a-parent-that-can-take-the-segment
  (testing "a map parent redacts; a vector parent redacts only under an
            in-range integer segment; anything else is a no-op, never a throw;
            an empty path scrubs the whole payload"
    (are [payload path expected] (= [:e expected] (rf.privacy/redact-event [:e payload] [path]))
      {:cards {:number "4111"}}               [:cards :number] {:cards {:number :rf/redacted}}
      {:cards [{:number "4111"}]}             [:cards :number] {:cards [{:number "4111"}]}
      {:cards [{:number "1"} {:number "2"}]}  [:cards 0]       {:cards [:rf/redacted {:number "2"}]}
      {:cards [{:number "1"}]}                [:cards 1]       {:cards [{:number "1"}]}
      {:auth "tok"}                           [:auth :password] {:auth "tok"}
      {:any "thing"}                          []               :rf/redacted)))

(deftest path-focused-dispatch-with-a-vector-payload-commits
  (testing "a classified path under a path-focused handler's slice meets a
            vector in the payload: the event commits instead of throwing out
            of dispatch-sync"
    (rf/make-frame {:id :redact-vec/app})
    (rf/reg-event :redact-vec/classify
      (fn [{:keys [db]} _] {:db db :sensitive [[:user :cards :number]]}))
    (rf/reg-event :redact-vec/save-user
      {:interceptors [[:rf.interceptor/path [:user]]]}
      (fn [{:keys [db]} [_ payload]] {:db (merge db payload)}))
    (rf/dispatch-sync [:redact-vec/classify] {:frame :redact-vec/app})
    (rf/dispatch-sync [:redact-vec/save-user {:cards [{:number "4111-vec"}]}]
                      {:frame :redact-vec/app})
    (is (= {:cards [{:number "4111-vec"}]}
           (:user (rf.frame/frame-app-db-value :redact-vec/app))))))
