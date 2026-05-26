(ns day8.re-frame2-xray.panels.epoch.view-cljs-test
  "View-layer tests for the Epoch panel cascade (rf2-sc3r1 follow-ons).

  Pure hiccup tests — each render-fn is exercised against a synthesised
  step row and walked via the framework's hiccup walker. No DOM mount;
  no substrate spin. Anchored on `data-testid`s the view stamps onto
  every step body."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.epoch.view :as view]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-test-support/reset-all!}))

;; ---- helpers -----------------------------------------------------------

(defn- text-of
  [tree testid]
  (some-> tree (th/find-by-testid testid) th/text-content))

;; ---- rf2-9jvx1 — text-duplication audit --------------------------------

(deftest dispatch-source-renders-once-test
  (testing "rf2-9jvx1 — DISPATCH header carries `from <source>` once;
            the body does NOT also render a `from` line"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc] :source :ui :coord nil})]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-dispatch-event"))
          "dispatch event vector renders in the body")
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-source"))
          "the body MUST NOT carry a duplicate `from <source>` row")
      (let [header-text (text-of tree "rf-xray-epoch-dispatch-header")]
        (is (string/includes? header-text "from"))
        (is (string/includes? header-text "ui"))))))

(deftest handler-flavour-renders-once-test
  (testing "rf2-9jvx1 — HANDLER header carries the flavour + event-id;
            the body MUST NOT also render the same flavour pill"
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :counter/inc
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)
          header-text (text-of tree "rf-xray-epoch-handler-header")
          body-text   (text-of tree "rf-xray-epoch-handler-body")]
      (is (string/includes? header-text "reg-event-db"))
      (is (string/includes? header-text ":counter/inc"))
      (is (not (string/includes? (or body-text "") "reg-event-db"))
          "body MUST NOT duplicate the flavour pill"))))

;; ---- rf2-cq0ch — COEFFECT body --------------------------------------

(deftest coeffect-body-renders-labelled-value-test
  (testing "rf2-cq0ch — each user-injected cofx renders the id +
            value via the canonical edn-inspector. No cryptic
            `+[]nil` line."
    (let [step {:step :coeffect :badge :COEFFECT :step-number 2
                :rows [{:id :session :value {:user-id 42}}
                       {:id :rf/now :value "2026-05-26T00:00:00"}]}
          tree (view/render-coeffect-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-coeffect-row-0")))
      (is (some? (th/find-by-testid tree "rf-xray-epoch-coeffect-row-1")))
      (let [r0-id    (text-of tree "rf-xray-epoch-coeffect-row-id-0")
            r0-value (text-of tree "rf-xray-epoch-coeffect-row-value-0")
            r1-id    (text-of tree "rf-xray-epoch-coeffect-row-id-1")]
        (is (string/includes? r0-id ":session"))
        (is (string/includes? r0-value "42"))
        (is (string/includes? r1-id ":rf/now"))))))
