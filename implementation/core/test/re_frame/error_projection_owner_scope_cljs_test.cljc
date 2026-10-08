(ns re-frame.error-projection-owner-scope-cljs-test
  "`rf/project-egress` applies an error record's event REGISTRATION marks in
  the record OWNER's registration universe — an explicit `opts :frame`, else
  the record's own `:frame` — not whatever generation is ambient at projection
  time. An image-local event declaration is invisible outside its frame's
  resolution scope, so an ambient lookup would let a deferred projection (a
  recorder projecting a retained record after `dispatch` returned) ship a
  declared-sensitive password RAW off-box."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-login-frame!
  "Seal `frame-id`'s generation from an image whose only registration is an
  inline `:ifzi/login` event carrying `classification`. The empty descriptor
  pool keeps live source-store registrations out."
  [frame-id image-id classification]
  (rf.live-frame/make-frame
    {:id     frame-id
     :images [(rf.image/image {:id            image-id
                               :registrations {:reg-event [[:ifzi/login classification
                                                            (fn [_cofx _ev] {})]]}})]}
    []))

(defn- error-record [frame]
  {:kind  :rf.observe/error
   :frame frame
   :event [:ifzi/login {:password "secret" :user "ann"}]})

(defn- projected
  "The `:event` payload of `record` projected under the off-box profile."
  ([record] (projected record nil))
  ([record opts]
   (get-in (rf/project-egress
             record
             (merge {:rf.egress/profile :rf.egress/off-box-observability} opts))
           [:event 1])))

(deftest deferred-error-projection-uses-the-owners-registration-scope
  (testing "with no owning generation bound, the record's own :frame governs the
            registration pass, so the image-local :sensitive declaration redacts"
    (install-login-frame! :ifzi/owner :ifzi/image {:sensitive [[:password]]})
    (is (= {:password rf.privacy/redacted-sentinel :user "ann"}
           (projected (error-record :ifzi/owner))))))

(deftest explicit-target-frame-overrides-a-different-ambient-generation
  (testing "an explicit `opts :frame` names the registration universe in both
            directions — a conflicting same-id declaration in the ambient frame
            cannot answer for the target's"
    (install-login-frame! :ifzi/owner :ifzi/image-a {:sensitive [[:password]]})
    (install-login-frame! :ifzi/other :ifzi/image-b {})
    (is (= rf.privacy/redacted-sentinel
           (rf.live-frame/call-with-frame-resolution
             :ifzi/other
             #(:password (projected (dissoc (error-record :ifzi/owner) :frame)
                                    {:frame :ifzi/owner}))))
        "the classified target redacts under an unclassified ambient frame")
    (is (= "secret"
           (rf.live-frame/call-with-frame-resolution
             :ifzi/owner
             #(:password (projected (dissoc (error-record :ifzi/other) :frame)
                                    {:frame :ifzi/other}))))
        "the unclassified target rides raw under a classified ambient frame")))

(deftest global-registration-still-answers-for-a-frameless-projection
  (rf/reg-event :ifzi/global {:sensitive [[:password]]} (fn [_ _] {}))
  (is (= {:password rf.privacy/redacted-sentinel :user "ann"}
         (projected {:kind  :rf.observe/error
                     :frame :rf/default
                     :event [:ifzi/global {:password "secret" :user "ann"}]}))))
