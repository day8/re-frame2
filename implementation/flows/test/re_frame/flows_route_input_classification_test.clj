(ns re-frame.flows-route-input-classification-test
  "A flow reading a route through a `[:rf.db/runtime …]` input traces it under
  the route's claims re-rooted at the input's path: a claim at or above the
  input redacts the whole value, one below it redacts that part, and one on
  another branch contributes nothing, whatever its length, and never fails the
  flow (Spec 013 §Flow tracing, Spec 012 §Route data classification).

  Real routing drives each navigation, so on the event that navigates onto the
  route the claims come from the pending runtime-db that event commits."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- visit-vault
  "Register `/home` and a `/vault/:pin` route claiming `sensitive`, and a flow
  deriving `[:out]` from the current route at the runtime `input` path. Visit
  `/home`, then `/vault/PIN-ONE`, and return the input values the arrival's
  `:rf.flow/computed` trace carried, every `:rf.error/*` operation raised on
  the way, and the flow's output."
  [sensitive input derive]
  (rf/reg-route :route/home {} "/home")
  (rf/reg-route :route/vault {:sensitive sensitive} "/vault/:pin")
  (rf/reg-flow :vault/out
               {:inputs      [(into [:rf.db/runtime :rf.runtime/routing :current] input)]
                :output-path [:out]}
               derive)
  (rf/make-frame {:id :rf/default :url-bound? true :flows [:vault/out]})
  (let [seen (atom {:computed [] :errors []})]
    (rf/register-listener! :trace ::watch
                           (fn [{:keys [operation tags]}]
                             (cond
                               (= :rf.flow/computed operation)
                               (swap! seen update :computed conj (:input-values tags))

                               (= "rf.error" (some-> operation namespace))
                               (swap! seen update :errors conj operation))))
    (try
      (doseq [url ["/home" "/vault/PIN-ONE"]]
        (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                          {:frame :rf/default}))
      (let [{:keys [computed errors]} @seen]
        {:arrival (last computed)
         :traces  (count computed)
         :errors  errors
         :out     (:out (rf/app-db-value :rf/default))})
      (finally (rf/unregister-listener! :trace ::watch)))))

(defn- arrival-traced
  "Assert the navigation onto the vault computed the flow from the raw param
  with no error, and traced `expected` as its input value; a production build
  traces no flow input at all."
  [{:keys [arrival traces errors out]} expected]
  (is (= [] errors))
  (is (= 7 out) "the flow derives from the raw param")
  (if rf.interop/debug-enabled?
    (is (= [expected] arrival))
    (is (zero? traces) "a production build traces no flow input")))

(def ^:private pin-length (comp count :pin))

(deftest a-shorter-claim-on-another-branch-contributes-nothing
  ;; The claim `[:rf.runtime/routing :current :query]` is one segment shorter
  ;; than the input and diverges from it, so it neither covers the input nor
  ;; sits below it.
  (arrival-traced (visit-vault [[:query]] [:params :pin] count) "PIN-ONE"))

(deftest a-longer-claim-on-another-branch-contributes-nothing
  (arrival-traced (visit-vault [[:query :token]] [:params] pin-length) {:pin "PIN-ONE"}))

(deftest an-ancestor-claim-redacts-the-whole-input
  (arrival-traced (visit-vault [[:params]] [:params :pin] count) :rf/redacted))

(deftest a-descendant-claim-redacts-only-its-part-of-the-input
  (arrival-traced (visit-vault [[:params :pin]] [:params] pin-length) {:pin :rf/redacted}))
