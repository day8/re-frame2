(ns re-frame.redact-path-vector-parent-cljs-test
  "rf2-3x7nj.4.4 — `re-frame.privacy/redact-path` guarded `assoc-in` only
  against a NON-associative parent, and a vector IS associative. The
  path-overlap arm derives payload-relative redaction paths from the DB's
  classification, not from the payload's shape, so a keyword segment landing on
  a vector in the payload made `assoc-in` throw (`Key must be integer` on the
  JVM, `Vector's key for assoc must be a number` on CLJS). The router computes
  that redaction in `prepare-handler-ctx`, outside the interceptor chain's
  capture, so the throw escaped `dispatch-sync` and the event was lost.

  Always-on: the router's redaction runs on every dispatch in every posture, so
  no posture tag. Dual-runtime `.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private r rf.privacy/redacted-sentinel)

(deftest redact-event-takes-only-a-parent-that-can-take-the-segment
  (testing "a map parent redacts; a vector parent redacts only under an
            in-range integer segment; anything else is a no-op, never a throw"
    (is (= [:e {:cards {:number r}}]
           (rf.privacy/redact-event [:e {:cards {:number "4111"}}] [[:cards :number]]))
        "control: a map parent redacts the leaf")
    (is (= [:e {:cards [{:number "4111"}]}]
           (rf.privacy/redact-event [:e {:cards [{:number "4111"}]}] [[:cards :number]]))
        "a keyword segment on a vector parent is a no-op")
    (is (= [:e {:cards [r {:number "2"}]}]
           (rf.privacy/redact-event [:e {:cards [{:number "1"} {:number "2"}]}] [[:cards 0]]))
        "an in-range integer segment on a vector parent redacts that element")
    (is (= [:e {:cards [{:number "1"}]}]
           (rf.privacy/redact-event [:e {:cards [{:number "1"}]}] [[:cards 1]]))
        "an out-of-range integer segment is a no-op — it never appends")
    (is (= [:e {:auth "tok"}]
           (rf.privacy/redact-event [:e {:auth "tok"}] [[:auth :password]]))
        "a scalar parent stays the no-op it was")))

(deftest path-focused-dispatch-with-a-vector-payload-commits
  (testing "a classified path under a path-focused handler's slice meets a
            vector in the payload: the event runs and commits instead of
            throwing out of dispatch-sync"
    (rf/make-frame {:id :redact-vec/app})
    (rf/reg-event :redact-vec/classify
      (fn [{:keys [db]} _] {:db db :sensitive [[:user :cards :number]]}))
    (rf/reg-event :redact-vec/save-user
      {:interceptors [[:rf.interceptor/path [:user]]]}
      (fn [{:keys [db]} [_ payload]] {:db (merge db payload)}))
    (rf/dispatch-sync [:redact-vec/classify] {:frame :redact-vec/app})
    (rf/dispatch-sync [:redact-vec/save-user {:cards {:number "4111-map"}}]
                      {:frame :redact-vec/app})
    (is (= {:cards {:number "4111-map"}}
           (:user (rf.frame/frame-app-db-value :redact-vec/app)))
        "control: the map-shaped payload commits")
    (rf/dispatch-sync [:redact-vec/save-user {:cards [{:number "4111-vec"}]}]
                      {:frame :redact-vec/app})
    (is (= {:cards [{:number "4111-vec"}]}
           (:user (rf.frame/frame-app-db-value :redact-vec/app)))
        "the vector-shaped payload commits too — the event is not lost")))
