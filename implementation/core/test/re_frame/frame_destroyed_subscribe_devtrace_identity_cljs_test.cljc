(ns re-frame.frame-destroyed-subscribe-devtrace-identity-cljs-test
  "A `:rf.error/frame-destroyed` dev trace with `:op :subscribe` carries the
  attempted subscription query vector as its `:event` tag. That vector is public
  identity (Spec 015), so `project-trace-event` must not redact it, even when an
  event registered under the same keyword (legal: separate registries) declares
  a matching `:sensitive` path. The `:dispatch` realms carry a dispatched event
  payload and keep their registration redaction.

  The collision is real: the control shows the same registration does redact
  that vector, so a raw subscribe tag is the guard's doing."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private shared-id :audit6516/same-id)
(def ^:private query-v [shared-id {:token :RAW-IDENTITY}])
(def ^:private redacted [shared-id {:token rf.privacy/redacted-sentinel}])

(defn- register-colliding-event!
  "An event under the subscription's keyword, declaring `:token` sensitive."
  []
  (rf.registrar/register! :event shared-id {:sensitive [[:token]]}))

(defn- frame-destroyed-devtrace
  "The dev trace `router/emit-frame-destroyed!` fans."
  [op event]
  {:operation :rf.error/frame-destroyed
   :tags {:frame :some-frame :op op :event event :reason :frame-destroyed}})

(defn- project-event
  "The `:event` tag after the dev-trace projection."
  [op event]
  (get-in (rf.classification/project-trace-event (frame-destroyed-devtrace op event))
          [:tags :event]))

(deftest frame-destroyed-subscribe-devtrace-preserves-raw-query-identity
  (register-colliding-event!)
  (is (= redacted (rf.classification/redact-event-by-registration query-v))
      "control: the colliding registration would redact this vector")
  (is (= query-v (project-event :subscribe query-v))))

(deftest frame-destroyed-dispatch-devtrace-still-projects
  (register-colliding-event!)
  (is (= [redacted redacted]
         [(project-event :dispatch query-v) (project-event :dispatch-sync query-v)])))

(deftest guard-scoped-to-frame-destroyed-operation
  ;; another error operation with :op :subscribe still projects its :event tag
  (register-colliding-event!)
  (is (= redacted
         (get-in (rf.classification/project-trace-event
                   {:operation :rf.error/handler-exception
                    :tags {:frame :some-frame :op :subscribe :event query-v}})
                 [:tags :event]))))
