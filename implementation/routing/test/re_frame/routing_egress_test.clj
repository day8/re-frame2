(ns re-frame.routing-egress-test
  "Routing's egress projections of raw route carriers (Spec 015
  §Registration-owned transient classification):

    - the `:rf.nav/scroll` fx args carry route params / query / fragment, and
      `:sensitive` marks on the fx registration redact the `:rf.fx/handled`
      trace copy;
    - the route-miss diagnostic (`:rf.error/no-such-handler`) and the
      `:rf.route/navigation-blocked` trace carry the requested URL under a
      custom slot, scrubbed at the emit site by
      `re-frame.privacy.url/redact-url-tag`;
    - the `:rf.route/navigation-blocked` / `:rf.route/entry-denied` event
      payloads carry `:requested-url` / `:destination` / `:target`, which the
      framework marks `:sensitive`, and that declaration survives an app's
      behaviour override in either namespace load order.

  The in-process value stays raw (handlers, the durable pending-nav slot);
  only the egress copy is projected. Route sub egress lives in
  `re-frame.routing-sub-egress-production-test` and
  `re-frame.routing-sub-prev-value-classification-test`.

  ## Posture split

  Assertions on registrar state (`rf/handler-meta`) and in-process values
  carry no posture guard, so they also run in
  `scripts/test-routing-prod-gate.sh` (`-Dre-frame.debug=false`). Every read
  off the trace bus sits inside a `(when rf.interop/debug-enabled? …)` arm,
  because `trace/emit!` is dev-gated; the `(not (re-find …))` census in the
  inverse-load-order case would otherwise pass vacuously with no trace."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing :as rf.routing]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.routing.nav-fx :as rf.routing.nav-fx]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private sentinel rf.privacy/redacted-sentinel)

;; ---- the scroll fx marks ------------------------------------------------------

(defn- reg-jvm-fx!
  "Re-register `fx-id` for the JVM drain (#{:server :client}) on its production
  meta, so the `:sensitive` marks under test stay in place. The fn form (no
  source-coord capture) replaces the framework's source-store slot instead of
  colliding as a cross-ns duplicate at default-image assembly."
  [fx-id prod-meta handler]
  (rf.fx/reg-fx fx-id (assoc prod-meta :platforms #{:server :client}) handler))

(defn- handled-trace-args
  "Dispatch `event` and return the `:rf.fx/args` of the last `:rf.fx/handled`
  trace for `fx-id`: the projected egress copy."
  [fx-id event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::egress (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::egress)
    (->> @traces
         (filter (fn [ev] (and (= :rf.fx/handled (:operation ev))
                               (= fx-id (-> ev :tags :rf.fx/id)))))
         last
         :tags
         :rf.fx/args)))

(deftest scroll-fx-handled-trace-redacts-route-descriptor-carriers
  (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
  (reg-jvm-fx! :rf.nav/scroll   rf.routing.scroll/scroll-fx-meta (fn [_ _] nil))
  (reg-jvm-fx! :rf.nav/push-url rf.routing.nav-fx/push-url-meta  (fn [_ _] nil))
  ;; Land on a route with params so the next navigation's :from carries them.
  (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "secret-doc-id"}}])
  (is (= [[:from :params] [:from :query] [:to :params] [:to :query] [:fragment]]
         (:sensitive (rf/handler-meta {:source :store :kind :fx :id :rf.nav/scroll})))
      "the trace redaction rides on this registrar declaration, which the production gate can see")
  ;; Dev-instrumentation arm (see ns docstring).
  (when rf.interop/debug-enabled?
    (let [args (handled-trace-args
                 :rf.nav/scroll
                 [:rf.route/navigate {:to :route/article :params {:id "another-secret"} :fragment "tok-in-fragment"}])]
      (is (= [:route/article sentinel sentinel sentinel]
             [(get-in args [:to :id]) (get-in args [:to :params]) (get-in args [:from :params]) (:fragment args)])
          "the route id rides; the carrier slots redact"))))

;; ---- emit-site URL scrubs -------------------------------------------------------

(deftest route-miss-no-such-handler-redacts-url-carriers
  (rf/reg-route :rf.route/not-found {} "/404")
  (let [traces (atom [])]
    (rf/register-listener! :trace ::miss (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.route/handle-url-change
                       "/oauth/callback?code=topsecret&state=xyz#access_token=leak"
                       {:rf.route/cause :link}])
    (rf/unregister-listener! :trace ::miss)
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (= {:url  "/oauth/callback?code=rf/redacted&state=rf/redacted#rf/redacted"
              :kind :route}
             (-> (filter #(= :rf.error/no-such-handler (:operation %)) @traces)
                 first
                 :tags
                 (select-keys [:url :kind])))
          "the path and :kind ride; the query and fragment values redact"))))

(defn- block-fixture!
  "Land on an editor route guarded by a blocking :can-leave."
  []
  (rf/reg-route :editor/article
                {:params    [:map [:id :string]]
                 :can-leave :editor/can-leave?} "/editor/articles/:id")
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-event :editor/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
  (rf/reg-sub :editor/can-leave? (fn [db _] (not (get-in db [:editor :dirty?]))))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/editor/articles/A" {:rf.route/cause :link}])
  (rf/dispatch-sync [:editor/dirty true]))

(deftest navigation-blocked-trace-redacts-requested-url-carriers
  (block-fixture!)
  (let [traces (atom [])]
    (rf/register-listener! :trace ::blocked (fn [ev] (swap! traces conj ev)))
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100&ref=x"}])
    (rf/unregister-listener! :trace ::blocked)
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (= {:requested-url   "/cart?coupon=rf/redacted&ref=rf/redacted"
              :rejecting-guard :editor/can-leave?}
             (-> (filter #(= :rf.route/navigation-blocked (:operation %)) @traces)
                 first
                 :tags
                 (select-keys [:requested-url :rejecting-guard])))))))

(deftest navigation-blocked-pending-nav-slot-keeps-raw-in-process
  (block-fixture!)
  (rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100"}])
  (is (= {:requested-url "/cart?coupon=SECRET100"
          :destination   {:to :route/cart :query {"coupon" "SECRET100"}}}
         (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
             (get-in [:rf.runtime/routing :pending-navigation])
             (select-keys [:requested-url :destination])))
      "continue replays the durable slot, so it keeps the raw carriers"))

;; ---- the payload carriers survive a public behaviour override ------------------
;;
;; `:rf.route/entry-denied` / `:rf.route/navigation-blocked` are replaceable
;; framework defaults, and a bare `rf/reg-event` under the same id is the
;; documented auth recipe. Their payloads are framework-constructed, so the
;; carrier classification is the framework's own and must survive the override.

(defn- entry-fixture!
  "A `/account` route whose `:can-enter` says no, so any entry door ends in a
  terminal denial."
  []
  (rf/reg-route :route/account {:can-enter [:auth/signed-in?]} "/account")
  (rf/reg-route :route/home    {} "/home")
  (rf/reg-sub   :auth/signed-in? (fn [_ _] false))
  (rf.fx/reg-fx :rf.nav/push-url    {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/dispatch-sync [:rf.route/handle-url-change "/home" {:rf.route/cause :link}]))

(defn- traced-event-payload
  "Run `f` and return the argument map of the first traced dispatched-event
  vector whose id is `event-id`: the egress copy of the payload."
  [event-id f]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::carrier (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace ::carrier)))
    (->> @traces
         (keep (fn [ev]
                 (let [v (or (-> ev :tags :rf.event/v) (-> ev :tags :event))]
                   (when (and (vector? v) (= event-id (first v)))
                     (second v)))))
         first)))

(defn- deny-account! []
  (rf/dispatch-sync [:rf.route/handle-url-change "/account?invite=SECRET100"]))

(deftest public-entry-denied-override-still-redacts-carriers-on-egress
  (testing "the canonical auth recipe, a bare `rf/reg-event :rf.route/entry-denied`
            with no metadata, keeps the framework's carrier redaction while the
            handler receives the RAW payload"
    (entry-fixture!)
    (let [seen (atom [])]
      (rf/reg-event :rf.route/entry-denied
                    (fn [{:keys [db]} [_ {:keys [destination] :as denial}]]
                      (swap! seen conj denial)
                      {:db (assoc-in db [:auth :return-to] destination)}))
      (is (= [[:requested-url] [:destination] [:target]]
             (:sensitive (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied}))))
      (let [payload (traced-event-payload :rf.route/entry-denied deny-account!)]
        ;; Dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= {:requested-url sentinel :destination sentinel :target sentinel :guard :auth/signed-in?}
                 (select-keys payload [:requested-url :destination :target :guard])))))
      (is (= [["/account?invite=SECRET100" {"invite" "SECRET100"}]]
             (mapv (juxt :requested-url (comp :query :destination)) @seen))
          "the app handler runs once, on the raw replayable destination"))))

(deftest public-navigation-blocked-override-still-redacts-carriers-on-egress
  (block-fixture!)
  (let [seen (atom [])]
    (rf/reg-event :rf.route/navigation-blocked
                  (fn [_ [_ pending]] (swap! seen conj pending) {}))
    (is (= [[:requested-url] [:destination] [:target]]
           (:sensitive (rf/handler-meta {:source :store :kind :event :id :rf.route/navigation-blocked}))))
    (let [payload (traced-event-payload
                    :rf.route/navigation-blocked
                    #(rf/dispatch-sync [:rf.route/url-requested {:url "/cart?coupon=SECRET100"}]))]
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= {:requested-url sentinel :destination sentinel :target sentinel :cause :link}
               (select-keys payload [:requested-url :destination :target :cause])))))
    (is (= ["/cart?coupon=SECRET100"] (mapv :requested-url @seen))
        "the app handler runs once, on the raw requested URL")))

(deftest an-app-classification-is-additive-over-the-retained-carriers
  (entry-fixture!)
  (rf/reg-event :rf.route/entry-denied {:sensitive [[:guard]]} (fn [_ _] {}))
  (is (= [[:requested-url] [:destination] [:target] [:guard]]
         (:sensitive (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied})))
      "framework carriers first, then the app's own declaration")
  (let [payload (traced-event-payload :rf.route/entry-denied deny-account!)]
    ;; Dev-instrumentation arm (see ns docstring).
    (when rf.interop/debug-enabled?
      (is (= {:requested-url sentinel :guard sentinel}
             (select-keys payload [:requested-url :guard]))))))

(deftest the-retained-carriers-survive-a-hot-reload-re-registration
  (rf/reg-event :rf.route/entry-denied (fn [_ _] {}))
  (rf/reg-event :rf.route/entry-denied (fn [_ _] {}))
  (is (= [[:requested-url] [:destination] [:target]]
         (:sensitive (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied})))
      "a second registration neither duplicates nor drops the carriers"))

;; ---- the retention is order-independent -----------------------------------------
;;
;; `re-frame.core` does not load the routing artefact, so an app namespace that
;; registers `:rf.route/entry-denied` can load BEFORE `re-frame.routing` seeds
;; its defaults. There is then no framework descriptor to retain from at
;; registration, so the seeding reconciles the other way.

(defn- restage-app-registered-before-routing!
  "Re-stage the process in the inverse namespace-load order, leaving a live
  URL-owning `:rf/default`: the suite fixture's clear-and-reload sequence with
  `register-app!` moved ahead of the `re-frame.routing` reload."
  [register-app!]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (register-app!)
  (require 're-frame.routing :reload)
  (require 're-frame.routing.test-support :reload)
  (rf.routing/reset-counters!)
  (rf.routing/reset-scroll-cache!)
  (rf.routing/reset-nav-counters!)
  (rf.routing/reset-url-claims!)
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "Inverse-load-order default app frame (explicit URL owner)."}))

(deftest app-registered-before-routing-still-redacts-framework-carriers
  (let [seen (atom [])]
    (restage-app-registered-before-routing!
      (fn []
        (rf/reg-event :rf.route/entry-denied
                      {:sensitive [[:guard]]}
                      (fn [{:keys [db]} [_ {:keys [destination] :as denial}]]
                        (swap! seen conj denial)
                        {:db (assoc-in db [:auth :return-to] destination)}))))
    (entry-fixture!)
    (is (= [[:requested-url] [:destination] [:target] [:guard]]
           (:sensitive (rf/handler-meta {:frame :rf/default :kind :event :id :rf.route/entry-denied})))
        "the frame-targeted read, which dispatch and egress resolve through, carries the same union as the default-first order")
    ;; The positional read is the process resolver map, last-write-wins
    ;; (Spec 012); in this order the framework's own seeding writes last.
    (is (= {:sensitive [[:requested-url] [:destination] [:target]] :rf/framework-default? true}
           (select-keys (rf/handler-meta {:source :store :kind :event :id :rf.route/entry-denied})
                        [:sensitive :rf/framework-default?])))
    (let [payload (traced-event-payload :rf.route/entry-denied deny-account!)]
      ;; Dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= {:requested-url sentinel :destination sentinel :target sentinel :guard sentinel}
               (select-keys payload [:requested-url :destination :target :guard])))
        (is (not (re-find #"SECRET100" (pr-str payload)))
            "the query secret appears nowhere on the egress copy")))
    (is (= [["/account?invite=SECRET100" {"invite" "SECRET100"}]]
           (mapv (juxt :requested-url (comp :query :destination)) @seen))
        "the app handler wins the frame, runs once, and receives the raw payload")))
