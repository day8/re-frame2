(ns re-frame.reserved-install-frame-state-cljs-test
  "`:rf/install-frame-state` is a RESERVED framework-standard event id: a public
  `reg-event` over it is refused with `:rf.error/reserved-event-id`, exactly as
  one over `:rf/set-db` is, while core's own registration of it — through the
  private registrar path — stands.

  The event carries framework-write authority over runtime-db and classifies
  its whole payload `:sensitive`; an application registration under the id
  would silently shadow both. The guard fires before any registrar write, so a
  refused attempt leaves the framework's handler in place.

  `.cljc` ending `-cljs-test`, so it runs under `clojure -M:test` and the
  shadow-cljs `:node-test` build alike."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.events               :as rf.events]
            [re-frame.registrar            :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- thrown-error-id
  "Run `thunk`; return the `:rf.error/id` of the ex-info it throws, or
  `:no-throw`."
  [thunk]
  (try (thunk) :no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest install-frame-state-is-a-reserved-event-id
  (testing "`:rf/install-frame-state` is a member of the reserved set"
    (is (contains? rf.events/reserved-event-ids :rf/install-frame-state))))

(deftest a-public-reg-event-over-install-frame-state-is-refused
  (testing "an application `rf/reg-event` of `:rf/install-frame-state` fails
            loud with `:rf.error/reserved-event-id`, and registers nothing"
    (is (= :rf.error/reserved-event-id
           (thrown-error-id #(rf/reg-event :rf/install-frame-state
                                           (fn [_ _] {:db {:hijacked true}}))))
        "the public macro form is refused")
    (is (= :rf.error/reserved-event-id
           (thrown-error-id #(rf.events/reg-event :rf/install-frame-state
                                                  (fn [_ _] {:db {:hijacked true}}))))
        "the fn form is refused too")
    (let [meta (rf.registrar/handler-meta :event :rf/install-frame-state)]
      (is (= rf.events/install-frame-state-handler (:handler-fn meta))
          "core's own handler is still the registered one")
      (is (true? (:rf/framework-authority? meta))
          "…with its framework-write authority intact"))))

(deftest core-seeds-install-frame-state-past-the-guard
  (testing "the guard sits on the public entry only: core's own seeding goes
            through the private registrar path, so re-seeding the standard
            succeeds"
    (is (= :rf/install-frame-state
           (rf.events/register-install-frame-state-standard!)))
    (is (= rf.events/install-frame-state-handler
           (:handler-fn (rf.registrar/handler-meta :event :rf/install-frame-state))))))

(deftest a-non-reserved-id-still-registers
  (testing "control: the guard refuses the reserved ids and nothing else"
    (is (= :no-throw
           (thrown-error-id #(rf/reg-event :probe/ordinary-event (fn [_ _] {})))))))
