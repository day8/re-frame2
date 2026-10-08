(ns re-frame.resources-revalidation-cljs-test
  "Focus / reconnect revalidation (Spec 016 §Stale and GC scheduling).
  :rf.resource/window-focused and :rf.resource/network-reconnected refetch only
  entries that are both actively owned and stale, through the ordinary
  refetch path (a :focus/:reconnect cause, never an owner; a new generation),
  coalescing with a refetch already in flight. The frame's :revalidate-on
  config owns the host listeners: registration installs exactly the declared
  subset, replacing rather than stacking, and frame destroy removes them."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   [re-frame.frame :as rf.frame]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.resources]
   [re-frame.resources.revalidate-listeners :as rf.resources.revalidate-listeners]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.test-support]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private aborts (atom []))

(defn- capturing-fixture
  "Capture aborts; the fetch and timer arming are no-ops."
  [f]
  (reset! aborts [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf.fx/reg-fx :rf.http/managed-abort (fn [_ctx work-id] (swap! aborts conj work-id) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ctx _args] nil))
  ;; ensures here pass an explicit :scope; the resolver's slot stays unwritten
  (rf/reg-resource-scope :t/caller-scope
    {:inputs {:scope [:db [:t/scope]]}}
    (fn [{:keys [scope]} _ctx] scope))
  (f))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  capturing-fixture)

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- entry [scoped-key] (get-in (runtime-db) (rf.resources.state/entry-path scoped-key)))

(defn- article-spec
  ([] (article-spec {}))
  ([overrides]
   (merge {:scope         {:from-db :t/caller-scope}
           :params-schema [:map [:slug :string]]
           :tags          (fn [{:keys [slug]} _data] #{[:article slug]})}
          overrides)))

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(def ^:private scope {:user "u"})

(defn- ensure! [resource slug owner]
  (rf/dispatch-sync [:rf.resource/ensure
                     {:resource resource :scope scope :params {:slug slug}
                      :owner owner}]))

(defn- succeed! [scoped-key data]
  (let [e (entry scoped-key)]
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key scoped-key :work/id (:current-work e)
                        :generation (:generation e) :data data}])))

(defn- owned-stale!
  "Register `resource` as stale the instant it loads, and load slug \"w\"
  under a route owner; return its scoped key."
  [resource]
  (rf/reg-resource resource (article-spec {:stale-after-ms 0}) article-spec-request)
  (let [k (rf.resources.state/scoped-resource-key scope resource {:slug "w"})]
    (ensure! resource "w" [:route :r 1])
    (succeed! k {:title "W"})
    k))

(deftest window-focused-scans-active-stale-only
  (rf/reg-resource :rv/sw   (article-spec {:stale-after-ms 0}) article-spec-request)
  (rf/reg-resource :rv/fresh (article-spec) article-spec-request)
  (let [k-as (rf.resources.state/scoped-resource-key scope :rv/sw    {:slug "active-stale"})
        k-af (rf.resources.state/scoped-resource-key scope :rv/fresh {:slug "active-fresh"})
        k-is (rf.resources.state/scoped-resource-key scope :rv/sw    {:slug "inactive-stale"})]
    (ensure! :rv/sw "active-stale" [:route :r 1])
    (succeed! k-as {:title "AS"})
    (ensure! :rv/fresh "active-fresh" [:route :r 1])
    (succeed! k-af {:title "AF"})
    (ensure! :rv/sw "inactive-stale" [:app :x 1])
    (succeed! k-is {:title "IS"})
    (rf/dispatch-sync [:rf.resource/release-owner {:owner [:app :x 1]}])
    (rf/dispatch-sync [:rf.resource/window-focused])
    (is (= [:fetching {:title "AS"}] ((juxt :status :data) (entry k-as)))
        "the owned stale entry refetches in the background")
    (is (= [:loaded :loaded] [(:status (entry k-af)) (:status (entry k-is))])
        "the owned fresh entry and the ownerless stale entry are left alone")))

(deftest network-reconnected-refetches-active-stale
  (let [k (owned-stale! :rc/sw)]
    (rf/dispatch-sync [:rf.resource/network-reconnected])
    (is (= [:fetching {:title "W"}] ((juxt :status :data) (entry k))))))

(deftest focus-refetch-is-cause-not-owner
  (let [k (owned-stale! :ca/sw)]
    (rf/dispatch-sync [:rf.resource/window-focused])
    (let [e (entry k)]
      (is (= [#{[:route :r 1]} true]
             [(:active-owners e)
              (boolean (some #{:focus} (:causes (rf.resources.work-ledger/get-record (runtime-db) (:current-work e)))))])
          "the refetch records a :focus cause and attaches no owner"))))

(deftest focus-refetch-bumps-generation-and-suppresses-stale-reply
  (let [k          (owned-stale! :gn/sw)
        gen-before (:generation (entry k))]
    (rf/dispatch-sync [:rf.resource/window-focused])
    (is (= [(inc gen-before) :fetching] ((juxt :generation :status) (entry k))))
    (rf/dispatch-sync [:rf.resource.internal/succeeded
                       {:resource/key k
                        :work/id (rf.resources.work-ledger/resource-work-id k gen-before)
                        :generation gen-before :data {:title "Zombie"}}])
    (is (not= {:title "Zombie"} (:data (entry k))) "a pre-focus-generation reply is suppressed")))

(deftest back-to-back-focus-coalesces-to-one-refetch
  ;; a tab return fires both window focus and document visibilitychange, and
  ;; both dispatch :rf.resource/window-focused
  (let [k          (owned-stale! :co/sw)
        gen-before (:generation (entry k))]
    (reset! aborts [])
    (rf/dispatch-sync [:rf.resource/window-focused])
    (let [gen-after-1 (:generation (entry k))
          wid-after-1 (:current-work (entry k))]
      (is (= [(inc gen-before) :fetching] [gen-after-1 (:status (entry k))]) "the first focus refetches")
      (rf/dispatch-sync [:rf.resource/window-focused])
      (is (= [gen-after-1 wid-after-1 :fetching []]
             [(:generation (entry k)) (:current-work (entry k)) (:status (entry k)) @aborts])
          "the second focus joins the same work with no abort churn"))))

(deftest coalescing-does-not-block-a-fresh-revalidation
  ;; coalescing is per in-flight attempt, not a latch
  (let [k (owned-stale! :cf/sw)]
    (rf/dispatch-sync [:rf.resource/window-focused])
    (let [gen-mid (:generation (entry k))]
      (succeed! k {:title "W2"})
      (is (nil? (:current-work (entry k))) "precondition: the refetch settled")
      (rf/dispatch-sync [:rf.resource/window-focused])
      (is (= [(inc gen-mid) :fetching] ((juxt :generation :status) (entry k)))
          "a focus after the settle starts a fresh refetch"))))

(deftest frame-destroy-cancels-revalidation-listeners
  (let [fa :rv/frame-a]
    (rf/make-frame {:id fa :doc "frame-destroy revalidation-listener frame"})
    ;; the side-table slot is the platform-neutral half; the DOM arm is CLJS-only
    (swap! rf.resources.revalidate-listeners/listener-table assoc fa {:focus :h :visibility :h :online :h})
    (is (contains? @rf.resources.revalidate-listeners/listener-table fa) "precondition: slot recorded")
    (rf.frame/destroy-frame! fa)
    (is (not (contains? @rf.resources.revalidate-listeners/listener-table fa))
        "frame destroy drops the frame's listener slot")))

(deftest revalidate-on-config-is-inert-without-a-dom
  (let [fa :rv/no-dom]
    (rf/make-frame {:id fa :doc "no-DOM :revalidate-on frame"
                    :revalidate-on #{:focus :reconnect}})
    (is (= #{:focus :reconnect} (:revalidate-on (rf.frame/frame-meta fa)))
        "a :revalidate-on frame registers cleanly with no DOM, keeping the key")
    #?(:clj
       (is (not (contains? @rf.resources.revalidate-listeners/listener-table fa))
           "the JVM arm installs nothing"))
    (rf.frame/destroy-frame! fa)))

(deftest revalidate-on-without-the-resources-artefact-fails-loud
  ;; the check is the presence of the :resources/on-frame-registered! hook
  (let [original (rf.late-bind/get-fn :resources/on-frame-registered!)]
    (try
      (rf.late-bind/set-fn! :resources/on-frame-registered! nil)
      (let [thrown (try (rf/make-frame {:id :rv/no-artefact :revalidate-on #{:focus}})
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is (= {:rf.error/id :rf.error/resources-artefact-missing :where 'rf/make-frame
                :frame-id :rv/no-artefact}
               (select-keys (ex-data thrown) [:rf.error/id :where :frame-id])))
        (is (nil? (rf.frame/frame-meta :rv/no-artefact)) "no frame was registered"))
      (finally
        (rf.late-bind/set-fn! :resources/on-frame-registered! original)))))

;; ---- host listener wiring (CLJS, against a DOM stub) --------------------------
;; visibilitychange is a document event, never a window one; the node runtime
;; has no DOM, so a capturing window/document stub is installed on
;; js/globalThis.

#?(:cljs
   (do
     ;; A capturing window / document stub: each records its
     ;; addEventListener / removeEventListener calls in `state` and can
     ;; `dispatchEvent` to the registered listeners (keyed by target + type).
     (defn- install-dom-stub! []
       (let [state    (atom {:window {} :document {:visibilityState "visible"}})
             mk-target (fn [target-key]
                         #js {:addEventListener
                              (fn [type listener]
                                (swap! state update-in [target-key :listeners type]
                                       (fnil conj []) listener))
                              :removeEventListener
                              (fn [type listener]
                                (swap! state update-in [target-key :listeners type]
                                       (fnil (fn [xs] (vec (remove #(= % listener) xs))) [])))
                              :dispatchEvent
                              (fn [event]
                                (doseq [l (get-in @state [target-key :listeners (.-type event)] [])]
                                  (l event)))})
             window   (mk-target :window)
             document (mk-target :document)]
         ;; the visibility handler reads `(.-visibilityState js/document)` —
         ;; expose a mutable field the test flips between "visible"/"hidden".
         (set! (.-visibilityState document) "visible")
         (set! (.-window js/globalThis) window)
         (set! (.-document js/globalThis) document)
         (swap! state assoc :window-obj window :document-obj document)
         state))

     (defn- uninstall-dom-stub! []
       (js-delete js/globalThis "window")
       (js-delete js/globalThis "document"))

     (def ^:private real-browser?
       (and (exists? js/window)
            (identical? js/window js/globalThis)))

     (def ^:dynamic *dom-state* nil)

     (defn- listener-count
       "How many listeners the stub currently records for `target-key`
       (`:window` / `:document`) and event `type`. The count — not mere
       presence — is what proves reconciliation REPLACES rather than stacks."
       [state target-key type]
       (count (get-in @state [target-key :listeners type] [])))

     (defn- wiring
       "The stub's current wiring as a map of the three host listeners to
       their counts, so a whole subset assertion reads as one value."
       [state]
       {:focus      (listener-count state :window "focus")
        :visibility (listener-count state :document "visibilitychange")
        :online     (listener-count state :window "online")})))

#?(:cljs
   (deftest visibilitychange-attaches-to-document-not-window
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             ;; capture dispatches via the late-bind router hook the listener
             ;; rides (so we observe the EXACT event the host wiring produces)
             (let [dispatched (atom [])
                   prior      (rf.late-bind/get-fn :router/dispatch!)]
               (rf.late-bind/set-fn! :router/dispatch! (fn [ev opts] (swap! dispatched conj [ev opts])))
               (try
                 (rf/make-frame {:id :rv/dom :doc "DOM-stub revalidation frame"
                                 :revalidate-on #{:focus :reconnect}})
                 (testing "visibilitychange is attached to DOCUMENT, not window"
                   (is (seq (get-in @state [:document :listeners "visibilitychange"]))
                       "document carries the visibilitychange listener")
                   (is (empty? (get-in @state [:window :listeners "visibilitychange"]))
                       "window does NOT carry visibilitychange")
                   (is (seq (get-in @state [:window :listeners "focus"]))
                       "focus stays on window")
                   (is (seq (get-in @state [:window :listeners "online"]))
                       "online stays on window"))
                 (testing "a visibilitychange while VISIBLE dispatches window-focused"
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "visible")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (= [:rf.resource/window-focused] (ffirst @dispatched))
                       "visible visibilitychange dispatched :rf.resource/window-focused")
                   (is (= {:frame :rv/dom :source :revalidate} (second (first @dispatched)))
                       "dispatched at the frame the listener targets, with :revalidate source"))
                 (testing "a visibilitychange while HIDDEN does NOT dispatch"
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "hidden")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (empty? @dispatched) "hidden visibilitychange dispatched nothing"))
                 (testing "a re-registration that DROPS
                           :revalidate-on relinquishes the listeners (the
                           :url-bound? false relinquish rule)"
                   (rf/make-frame {:id :rv/dom :doc "DOM-stub revalidation frame"})
                   (is (= {:focus 0 :visibility 0 :online 0} (wiring state))
                       "every host listener detached when the key was dropped")
                   ;; a post-relinquish visible visibilitychange dispatches nothing
                   (reset! dispatched [])
                   (set! (.-visibilityState (:document-obj @state)) "visible")
                   (.dispatchEvent (:document-obj @state) #js {:type "visibilitychange"})
                   (is (empty? @dispatched) "detached listener fires nothing"))
                 (finally
                   (if prior
                     (rf.late-bind/set-fn! :router/dispatch! prior)
                     (rf.late-bind/set-fn! :router/dispatch! nil)))))
             (finally
               (uninstall-dom-stub!))))))))

#?(:cljs
   (deftest revalidate-on-installs-exactly-the-declared-subset
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             (testing "#{:focus} wires window focus AND document
                       visibilitychange (ONE setting), and NOT window online"
               (rf/make-frame {:id :rv/focus-only :doc "focus-only frame"
                               :revalidate-on #{:focus}})
               (is (= {:focus 1 :visibility 1 :online 0} (wiring state)))
               (rf.frame/destroy-frame! :rv/focus-only))
             (testing "#{:reconnect} wires window online only"
               (rf/make-frame {:id :rv/reconnect-only :doc "reconnect-only frame"
                               :revalidate-on #{:reconnect}})
               (is (= {:focus 0 :visibility 0 :online 1} (wiring state)))
               (rf.frame/destroy-frame! :rv/reconnect-only))
             (testing "an ABSENT key installs nothing"
               (rf/make-frame {:id :rv/no-key :doc "no revalidation frame"})
               (is (= {:focus 0 :visibility 0 :online 0} (wiring state)))
               (rf.frame/destroy-frame! :rv/no-key))
             (testing "an explicit EMPTY set is a legitimate
                       \"none\" and installs nothing"
               (rf/make-frame {:id :rv/empty-set :doc "explicit-none frame"
                               :revalidate-on #{}})
               (is (= {:focus 0 :visibility 0 :online 0} (wiring state)))
               (rf.frame/destroy-frame! :rv/empty-set))
             (finally
               (uninstall-dom-stub!))))))))

#?(:cljs
   (deftest repeated-registration-does-not-stack-listeners
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             (testing "N re-registrations with the SAME
                       :revalidate-on leave the listener counts constant:
                       replace-don't-stack, so nothing ever STACKS"
               (dotimes [_ 4]
                 (rf/make-frame {:id :rv/churn :doc "replace-don't-stack frame"
                                 :revalidate-on #{:focus :reconnect}}))
               (is (= {:focus 1 :visibility 1 :online 1} (wiring state))
                   "four registrations, one listener each"))
             ;; The counts above prove
             ;; NO STACKING. They do NOT prove "no churn", and the reconcile
             ;; deliberately does not offer it — it is a REPLACE, not a diff.
             ;; Pin that directly, so the contract is falsifiable in BOTH
             ;; directions: a reconcile that short-circuited on identical
             ;; triggers (routing's `reconcile-url-listener!` shape) turns
             ;; this red, and the prose must change with it.
             (testing "an identical-triggers re-registration
                       REPLACES: the handler instance is a fresh closure, not
                       the one already attached"
               (let [before (first (get-in @state [:window :listeners "focus"]))]
                 (is (some? before) "a focus handler is attached to start with")
                 (rf/make-frame {:id :rv/churn :doc "replace-don't-stack frame"
                                 :revalidate-on #{:focus :reconnect}})
                 (let [after (first (get-in @state [:window :listeners "focus"]))]
                   (is (= 1 (:focus (wiring state)))
                       "still exactly one focus listener — no stacking")
                   (is (not (identical? before after))
                       "and it is a NEW handler: detach-then-reattach, every time"))))
             (testing "a re-registration that CHANGES the subset
                       reconciles rather than accumulating"
               (rf/make-frame {:id :rv/churn :doc "replace-don't-stack frame"
                               :revalidate-on #{:reconnect}})
               (is (= {:focus 0 :visibility 0 :online 1} (wiring state))
                   "the focus half was detached, online kept at one")
               (rf.frame/destroy-frame! :rv/churn))
             (finally
               (uninstall-dom-stub!))))))))

#?(:cljs
   (deftest two-frames-own-their-listeners-independently
     (when-not real-browser?
       (let [state (install-dom-stub!)]
         (binding [*dom-state* state]
           (try
             (testing "two frames each own their own listeners"
               (rf/make-frame {:id :rv/two-a :doc "frame A" :revalidate-on #{:focus}})
               (rf/make-frame {:id :rv/two-b :doc "frame B" :revalidate-on #{:reconnect}})
               (is (= {:focus 1 :visibility 1 :online 1} (wiring state))
                   "A's focus pair and B's online listener coexist")
               (testing "destroying ONE frame leaves the other's listeners alone"
                 (rf.frame/destroy-frame! :rv/two-a)
                 (is (= {:focus 0 :visibility 0 :online 1} (wiring state))
                     "only A's listeners were detached")))
             (testing "destroy-and-recreate under the SAME id
                       installs fresh listeners"
               (rf.frame/destroy-frame! :rv/two-b)
               (is (= {:focus 0 :visibility 0 :online 0} (wiring state)))
               (rf/make-frame {:id :rv/two-b :doc "frame B reborn"
                               :revalidate-on #{:focus :reconnect}})
               (is (= {:focus 1 :visibility 1 :online 1} (wiring state))
                   "the recreated frame is wired from scratch")
               (rf.frame/destroy-frame! :rv/two-b))
             (finally
               (uninstall-dom-stub!))))))))
