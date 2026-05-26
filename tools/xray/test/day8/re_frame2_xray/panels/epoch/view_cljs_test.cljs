(ns day8.re-frame2-xray.panels.epoch.view-cljs-test
  "View-layer tests for the Epoch panel cascade (rf2-sc3r1 follow-ons).

  Pure hiccup tests — each render-fn is exercised against a synthesised
  step row and walked via the framework's hiccup walker. No DOM mount;
  no substrate spin. Anchored on `data-testid`s the view stamps onto
  every step body."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
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
  (testing "rf2-9jvx1 — HANDLER header carries the flavour + event-id
            once; the body's pre-rf2-9jvx1 stand-alone flavour row is
            removed. Body's first slot is the source block (rf2-66wis),
            not a flavour pill."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :no-such/handler
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)
          header-text (text-of tree "rf-xray-epoch-handler-header")]
      (is (string/includes? header-text "reg-event-db"))
      (is (string/includes? header-text ":no-such/handler"))
      ;; The body's first slot is now the source block — never a
      ;; duplicate flavour pill.
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source"))
          "the body leads with the source block (rf2-66wis)")
      ;; Confirm the body never carries a redundant flavour-only span.
      ;; The body text for an unknown handler resolves to the
      ;; placeholder; it MUST NOT contain the bare flavour keyword.
      (is (not (string/includes?
                 (or (text-of tree "rf-xray-epoch-handler-source-placeholder")
                     "")
                 "reg-event-db"))
          "the source-placeholder slot MUST NOT echo the flavour pill"))))

;; ---- rf2-93a7s — DISPATCH body shows the event vector ----------------

(deftest dispatch-body-shows-event-vector-test
  (testing "rf2-93a7s — DISPATCH body renders the dispatched event vector"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event [:counter/inc 7] :source :ui :coord nil})
          body (th/find-by-testid tree "rf-xray-epoch-dispatch-event")]
      (is (some? body) "the body's event-vector slot is present")
      (is (string/includes? (th/text-content body) ":counter/inc")
          "event-id is visible in the body")
      (is (string/includes? (th/text-content body) "7")
          "the event args are visible too"))))

(deftest dispatch-body-omits-event-when-absent-test
  (testing "rf2-93a7s — empty event yields no event-vector slot
            (graceful-degrade rather than rendering `[]` noise)"
    (let [tree (view/render-dispatch-step
                 {:step :dispatch :badge :DISPATCH :step-number 1
                  :event nil :source :ui :coord nil})]
      (is (nil? (th/find-by-testid tree "rf-xray-epoch-dispatch-event"))
          "no body row when no event vector was captured"))))

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

;; ---- rf2-66wis — HANDLER source code block ---------------------------

(deftest handler-body-renders-source-placeholder-test
  (testing "rf2-66wis — HANDLER body carries a source-code slot.
            When no handler-meta has been stamped the slot renders
            a clear `<source not yet captured>` placeholder rather
            than collapsing silently — operator learns where to
            look and when the substrate hasn't captured."
    (let [step {:step :handler :badge :HANDLER :step-number 3
                :flavour :reg-event-db :event-id :no-such/handler
                :db-diff [] :fx [] :machine nil}
          tree (view/render-handler-step step)]
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source"))
          "the source slot is present even on graceful-degrade")
      (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source-placeholder"))
          "no-source state renders the placeholder")
      (is (string/includes?
            (or (text-of tree "rf-xray-epoch-handler-source-placeholder") "")
            "<source not yet captured>")))))

;; ---- rf2-6djth — VIEWS row carries view-id + subs ---------------------

(deftest views-row-shows-id-and-subs-test
  (testing "rf2-6djth — VIEWS table row shows the view-id + its
            consumed subs. Per-row testids are stable and the body
            text contains both halves."
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id :app.counter/Counter
                        :subs-read [[:counter/total] [:counter/threshold]]
                        :duration-ms 1.2}]}
          tree (view/render-views-step step)
          row  (th/find-by-testid tree "rf-xray-epoch-view-row-0")
          id   (text-of tree "rf-xray-epoch-view-row-id-0")
          subs (text-of tree "rf-xray-epoch-view-row-subs-0")]
      (is (some? row) "the view row renders")
      (is (string/includes? id ":app.counter/Counter")
          "view-id is the leading element")
      (is (string/includes? subs ":counter/total")
          "consumed subs appear in the subs cell")
      (is (string/includes? subs ":counter/threshold")
          "every consumed sub renders, one per line"))))

(deftest views-row-graceful-degrades-when-id-absent-test
  (testing "rf2-6djth — a row whose view-id is nil renders an
            anonymous-view placeholder rather than the bare keyword
            colon"
    (let [step {:step :views :badge :VIEWS :step-number 6
                :rows [{:view-id nil :subs-read [] :duration-ms nil}]}
          tree (view/render-views-step step)
          id   (text-of tree "rf-xray-epoch-view-row-id-0")]
      (is (string/includes? id "<anonymous view>")
          "missing view-id reads as `<anonymous view>` placeholder"))))

(deftest handler-body-renders-captured-source-test
  (testing "rf2-66wis — when the registrar carries an
            `:rf.handler/source`, the body renders it via the
            canonical `edn/code-block` widget"
    (rf/with-frame :rf/default
      (rf/reg-event-db :rf.test.epoch.view/srctest-handler
                       {:rf.handler/source "(reg-event-db :rf.test.epoch.view/srctest-handler\n  (fn [db _] (assoc db :ok true)))"}
                       (fn [db _] db))
      (let [step {:step :handler :badge :HANDLER :step-number 3
                  :flavour :reg-event-db
                  :event-id :rf.test.epoch.view/srctest-handler
                  :db-diff [] :fx [] :machine nil}
            tree (view/render-handler-step step)]
        (is (some? (th/find-by-testid tree "rf-xray-epoch-handler-source-body"))
            "the code-block widget mounts under the source slot")
        (is (nil? (th/find-by-testid tree "rf-xray-epoch-handler-source-placeholder"))
            "no placeholder when source IS captured")))))
