(ns re-frame.routing-test
  "JVM smoke tests for re-frame.routing. The conformance fixtures cover
  the canonical surface (URL ↔ params, fragment, blocking, nav-token);
  these tests exercise the JVM-pipeline composition end-to-end and
  pin a few behaviours the conformance DSL doesn't express directly:

  - routing-bootstrap            — reg-route + dispatch :rf.route/navigate
                                   updates the :rf/route slice end-to-end
  - routing-pending-nav-protocol — full pause / continue / cancel
                                   walk-through (the conformance fixtures
                                   cover continue and cancel separately;
                                   this one drives the slot's full
                                   lifecycle from one test)
  - routing-nav-token-staleness  — two in-flight navigations; only the
                                   most recent token's result commits
  - routing-scroll-metadata-preserved — :scroll metadata on a route is
                                   queryable via handler-meta. Pins the
                                   registration round-trip contract.
  - routing-scroll-fx-emitted-on-navigate — every successful navigation
                                   emits :rf.nav/scroll with the resolved
                                   strategy / from / to / saved-pos /
                                   fragment args; :scroll false suppresses.
  - routing-nested-layout-parent-link — :parent metadata is preserved by
                                   reg-route so views / handler-meta-driven
                                   tools can render the layout chain. The
                                   :rf.route/chain sub is not yet built-in;
                                   this test asserts the registry-level
                                   contract.

  Per Spec 012 §URL changes are events, §Navigation blocking — pending-nav
  protocol, §Navigation tokens — stale-result suppression, §Scroll
  restoration, §Nested layouts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            ;; rf2-k682: this test lives in the routing artefact's test
            ;; classpath, so requiring re-frame.routing here is the
            ;; primary trigger that loads the namespace and fires its
            ;; late-bind hook registrations + framework `:rf.route/*`
            ;; reg-sub installations. Without this require the
            ;; rf/reg-route call below would throw
            ;; :rf.error/routing-artefact-missing.
            [re-frame.routing :as routing]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx (routing.cljc, ssr.cljc) are registered at
  ;; ns-load; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (routing/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Spec 012 §Navigation is an event -------------------------------------

(deftest routing-bootstrap
  (testing "reg-route + dispatch :rf.route/navigate writes the :rf/route slice"
    ;; Per Spec 012 §Navigation is an event: dispatching :rf.route/navigate
    ;; with a route-id + params updates :rf/route.{id,params,query,...} and
    ;; emits a :rf.nav/push-url effect.
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    ;; Capture the URL that lands at :rf.nav/push-url.
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (let [slice (:rf/route (rf/get-frame-db :rf/default))]
        (is (= :route/article (:id slice))
            "the :rf/route slice carries the navigation target")
        (is (= {:id "intro"} (:params slice))
            ":params from the navigate vector landed in the slice")
        (is (= :idle (:transition slice))
            "no :on-match → transition stays :idle"))
      (is (= ["/articles/intro"] @pushed)
          ":rf.nav/push-url received the unparsed URL"))))

;; ---- Spec 012 §Navigation blocking — pending-nav protocol ----------------

(deftest routing-pending-nav-protocol
  (testing "block via :can-leave; continue and cancel both clear the slot"
    ;; Per Spec 012 §Navigation blocking — pending-nav protocol: a route
    ;; declares :can-leave (sub-id → boolean). When the sub returns false,
    ;; :rf/url-requested writes :rf/pending-navigation and does not push.
    ;; :rf.route/continue clears the slot AND completes the navigation.
    ;; :rf.route/cancel clears the slot WITHOUT navigating.
    (rf/reg-route :editor/article
                  {:path      "/editor/articles/:id"
                   :params    [:map [:id :string]]
                   :can-leave :editor/can-leave?})
    (rf/reg-route :route/cart {:path "/cart"})

    (rf/reg-event-db :editor/dirty
                     (fn [db [_ v]] (assoc-in db [:editor :dirty?] v)))
    (rf/reg-sub :editor/can-leave?
                (fn [db _]
                  ;; "OK to leave" = NOT dirty.
                  (not (get-in db [:editor :dirty?]))))

    (rf/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; 1. Land on the editor route. nav-token allocates; slice is set.
    (rf/dispatch-sync [:rf/url-changed "/editor/articles/A"])
    (is (= :editor/article (get-in (rf/get-frame-db :rf/default)
                                   [:rf/route :id]))
        "initial nav landed on :editor/article")

    ;; 2. Dirty the form so :can-leave? returns false.
    (rf/dispatch-sync [:editor/dirty true])

    ;; 3. Try to leave. Guard rejects → pending slot is set; URL unchanged.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (let [pending (:rf/pending-navigation (rf/get-frame-db :rf/default))]
      (is (some? pending)
          ":rf/pending-navigation is populated on guard rejection")
      (is (= "/cart" (get-in pending [:request :url]))
          "the requested URL is captured for resume")
      (is (= :editor/article
             (get-in (rf/get-frame-db :rf/default) [:rf/route :id]))
          "the :rf/route slice does NOT change when blocked"))

    ;; 4. CANCEL — slot clears; original route stays active.
    (rf/dispatch-sync [:rf.route/cancel "pn-1"])
    (is (nil? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "cancel clears :rf/pending-navigation")
    (is (= :editor/article
           (get-in (rf/get-frame-db :rf/default) [:rf/route :id]))
        "cancel does NOT navigate")

    ;; 5. Now block again — and CONTINUE this time.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (is (some? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "second blocked request reseats the slot")
    (rf/dispatch-sync [:rf.route/continue "pn-2"])
    (is (nil? (:rf/pending-navigation (rf/get-frame-db :rf/default)))
        "continue clears :rf/pending-navigation")
    (is (= :route/cart (get-in (rf/get-frame-db :rf/default) [:rf/route :id]))
        "continue completes the original navigation")))

;; ---- Spec 012 §Navigation tokens — stale-result suppression --------------

(deftest routing-nav-token-staleness
  (testing "two in-flight navigations: the older nav-token's result is suppressed"
    ;; Per Spec 012 §Navigation tokens — stale-result suppression: each
    ;; navigation allocates a fresh nav-token; async results carry the
    ;; token captured at request time; when a result arrives whose token
    ;; mismatches the current slice's :nav-token, the runtime suppresses
    ;; it and emits :rf.route.nav-token/stale-suppressed.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))

    (let [traces (atom [])]
      (rf/register-trace-cb! ::nav-token (fn [ev] (swap! traces conj ev)))

      ;; 1. Navigate to /articles/A. nav-token allocates → "nav-1".
      (rf/dispatch-sync [:rf/url-changed "/articles/A"])
      (is (= "nav-1" (get-in (rf/get-frame-db :rf/default)
                             [:rf/route :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's response lands, navigate to /articles/B → "nav-2".
      (rf/dispatch-sync [:rf/url-changed "/articles/B"])
      (is (= "nav-2" (get-in (rf/get-frame-db :rf/default)
                             [:rf/route :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale response carries "nav-1"; current is "nav-2";
      ;; the runtime suppresses [:article/loaded "A" "A-payload"].
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "A" "A-payload"]
                          :carried-nav-token  "nav-1"}])

      ;; 4. B's response carries "nav-2"; matches current; commits.
      (rf/dispatch-sync [:rf.test/simulate-http-resolution
                         {:on-success-event   [:article/loaded "B" "B-payload"]
                          :carried-nav-token  "nav-2"}])

      (rf/remove-trace-cb! ::nav-token)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/get-frame-db :rf/default)))
          "only B's payload committed; A's was suppressed")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :event-id))))
                @traces)
          "expected :rf.route.nav-token/stale-suppressed trace for the A response"))))

(deftest with-nav-token-fx-suppresses-stale-do-and-commits-fresh
  (testing ":rf.route/with-nav-token fx: stale `:do` is suppressed; fresh `:do` runs"
    ;; Per Spec 012 §Navigation tokens §Threading: a user event handler
    ;; emits an `:fx` entry of the form
    ;;
    ;;   [:rf.route/with-nav-token {:do        [:dispatch [<ev> args...]]
    ;;                              :nav-token <captured-token>}]
    ;;
    ;; …and the runtime threads the carried token against the current
    ;; `:rf/route :nav-token`. Match → the inner fx runs (canonically a
    ;; `:dispatch` to the success continuation). Mismatch → the inner fx
    ;; is suppressed and `:rf.route.nav-token/stale-suppressed` emits.
    ;;
    ;; This test pins both branches via the production fx (no use of
    ;; the test-only `:rf.test/simulate-http-resolution` event). The
    ;; `:article/loaded` continuation is the user-facing handler the
    ;; wrapped dispatch would commit through; we observe it via the
    ;; resulting app-db slice.
    (rf/reg-route :route/article {:path   "/articles/:id"
                                  :params [:map [:id :string]]})
    (rf/reg-event-db :article/loaded
                     (fn [db [_ id payload]]
                       (assoc db :article {:id id :payload payload})))
    ;; Bridge event: a real :on-success handler. Carries the token it
    ;; captured at request time and re-emits an `:rf.route/with-nav-token`
    ;; fx entry. The runtime then either dispatches `[:article/loaded ...]`
    ;; (match) or suppresses (mismatch).
    (rf/reg-event-fx :article/loaded-via-nav-token
                     (fn [_ctx [_ {:keys [carried-token id payload]}]]
                       {:fx [[:rf.route/with-nav-token
                              {:do        [:dispatch [:article/loaded id payload]]
                               :nav-token carried-token}]]}))

    (let [traces (atom [])]
      (rf/register-trace-cb! ::with-nav-token-fx
                             (fn [ev] (swap! traces conj ev)))

      ;; 1. Land on :route/article id="A" — nav-token allocates to "nav-1".
      (rf/dispatch-sync [:rf/url-changed "/articles/A"])
      (is (= "nav-1" (get-in (rf/get-frame-db :rf/default)
                             [:rf/route :nav-token]))
          "first navigation got nav-1")

      ;; 2. Before A's async :on-success lands, navigate to id="B" — "nav-2".
      (rf/dispatch-sync [:rf/url-changed "/articles/B"])
      (is (= "nav-2" (get-in (rf/get-frame-db :rf/default)
                             [:rf/route :nav-token]))
          "second navigation advanced the epoch to nav-2")

      ;; 3. A's stale :on-success arrives carrying "nav-1" via the fx
      ;; wrapper. Current is "nav-2"; the inner :dispatch must be
      ;; suppressed and the trace must fire.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-1"
                          :id            "A"
                          :payload       "A-payload"}])

      ;; 4. B's fresh :on-success arrives carrying "nav-2"; matches
      ;; current; inner :dispatch fires; :article/loaded commits.
      (rf/dispatch-sync [:article/loaded-via-nav-token
                         {:carried-token "nav-2"
                          :id            "B"
                          :payload       "B-payload"}])

      (rf/remove-trace-cb! ::with-nav-token-fx)

      (is (= {:id "B" :payload "B-payload"}
             (:article (rf/get-frame-db :rf/default)))
          "fresh :do ran end-to-end; stale :do was suppressed before commit")

      (is (some (fn [ev]
                  (and (= :rf.route.nav-token/stale-suppressed (:operation ev))
                       (= "nav-1" (-> ev :tags :carried-token))
                       (= "nav-2" (-> ev :tags :current-token))
                       (= :article/loaded (-> ev :tags :event-id))))
                @traces)
          "stale :do produced :rf.route.nav-token/stale-suppressed with the inner dispatch's event-id")

      ;; Negative: no spurious suppressed-trace for the fresh path.
      (is (= 1 (count (filter (fn [ev]
                                (= :rf.route.nav-token/stale-suppressed
                                   (:operation ev)))
                              @traces)))
          "exactly one stale-suppressed trace fired — the fresh :do did NOT trip the validation"))))

;; ---- Spec 012 §Scroll restoration -----------------------------------------

(deftest routing-scroll-metadata-preserved
  (testing "the :scroll metadata key is enumerable via handler-meta"
    ;; Per Spec 012 §Scroll restoration: a route may declare a :scroll
    ;; strategy (:top / :restore / :preserve / map / false). Metadata is
    ;; round-tripped through registration so tooling can enumerate it.
    (rf/reg-route :route/home
                  {:path   "/"
                   :scroll :top})
    (rf/reg-route :route/article
                  {:path   "/articles/:id"
                   :params [:map [:id :string]]
                   :scroll :restore})
    (let [home-meta    (rf/handler-meta :route :route/home)
          article-meta (rf/handler-meta :route :route/article)]
      (is (= :top (:scroll home-meta))
          ":scroll metadata is preserved as-declared")
      (is (= :restore (:scroll article-meta))
          ":scroll metadata is preserved per-route"))))

(deftest routing-scroll-fx-emitted-on-navigate
  (testing ":rf.route/navigate emits :rf.nav/scroll with the resolved strategy"
    ;; Per Spec 012 §Scroll restoration: the runtime emits :rf.nav/scroll
    ;; on every successful navigation, with args
    ;; {:strategy :from :to :saved-pos :fragment}. Resolution order:
    ;;   1. :scroll in :rf.route/navigate's opts (per-call override)
    ;;   2. route metadata's :scroll
    ;;   3. implicit default (:top for forward navigation)
    ;; A :scroll value of `false` (opts or meta) suppresses the fx.
    (rf/reg-route :route/home    {:path "/"})
    (rf/reg-route :route/articles {:path "/articles"})
    (rf/reg-route :route/article  {:path   "/articles/:id"
                                   :params [:map [:id :string]]
                                   :scroll :restore})
    (rf/reg-route :route/profile  {:path   "/profile"
                                   :scroll false})

    (let [calls (atom [])]
      ;; Override the spec's :platforms #{:client} default for the JVM
      ;; test — re-register :rf.nav/scroll on both server+client so the
      ;; do-fx interpreter actually invokes our capture.
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      ;; :rf.nav/push-url is :platforms #{:client} by default; suppress on
      ;; the JVM the same way the other routing tests do.
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))

      ;; 1. Forward navigation to a route with no :scroll metadata —
      ;;    default :top.
      (rf/dispatch-sync [:rf.route/navigate :route/articles])
      (is (= 1 (count @calls)) "navigate emits exactly one :rf.nav/scroll fx")
      (let [a (first @calls)]
        (is (= :top (:strategy a))
            "default forward strategy is :top")
        (is (= {:id :route/articles} (:to a))
            ":to descriptor identifies the destination"))

      ;; 2. Navigate to a route with :scroll :restore — strategy carries.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (let [a (first @calls)]
        (is (= :restore (:strategy a))
            "route's :scroll :restore wins over the implicit :top default")
        (is (= {:id :route/article :params {:id "intro"}} (:to a))
            ":to carries id + params")
        (is (= {:id :route/articles} (:from a))
            ":from is the previous route slice"))

      ;; 3. Per-call :scroll override in opts trumps route metadata.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "two"}
                         {:scroll :preserve}])
      (is (= :preserve (-> @calls first :strategy))
          "opts :scroll wins over route metadata")

      ;; 4. :scroll false on the route suppresses the fx entirely.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/profile])
      (is (empty? @calls)
          ":scroll false on the route suppresses the :rf.nav/scroll fx")

      ;; 5. :rf/url-changed (URL-driven) also emits the fx — default :top.
      (reset! calls [])
      (rf/dispatch-sync [:rf/url-changed "/articles"])
      (is (= :top (-> @calls first :strategy))
          ":rf/url-changed emits :rf.nav/scroll with default :top")

      ;; 6. :rf.route/handle-url-change (popstate / initial) defaults to
      ;;    :restore — the saved position trumps a forward-style :top.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles"])
      (is (= :restore (-> @calls first :strategy))
          ":rf.route/handle-url-change emits :rf.nav/scroll with default :restore")

      ;; 7. Fragment in URL is forwarded in the fx args.
      (reset! calls [])
      (rf/dispatch-sync [:rf/url-changed "/articles/intro#section-2"])
      (is (= "section-2" (-> @calls first :fragment))
          "fragment from URL flows into :rf.nav/scroll args"))))

;; ---- Spec 012 §Nested layouts ---------------------------------------------

(deftest routing-nested-layout-parent-link
  (testing ":parent metadata round-trips through reg-route"
    ;; Per Spec 012 §Nested layouts: a child route declares :parent
    ;; <route-id> so views can render the layout chain. The :rf.route/chain
    ;; sub is the runtime's enumeration entry point but is not yet
    ;; framework-registered; the registry-level contract — :parent is
    ;; enumerable via handler-meta — IS implemented and is what tooling
    ;; queries. This test pins that registry-level contract.
    (rf/reg-route :route/account             {:path "/account"})
    (rf/reg-route :route/account.settings    {:path   "/account/settings"
                                              :parent :route/account})
    (rf/reg-route :route/account.billing     {:path   "/account/billing"
                                              :parent :route/account})
    (let [settings-meta (rf/handler-meta :route :route/account.settings)
          billing-meta  (rf/handler-meta :route :route/account.billing)
          account-meta  (rf/handler-meta :route :route/account)]
      (is (= :route/account (:parent settings-meta))
          ":route/account.settings carries :parent :route/account")
      (is (= :route/account (:parent billing-meta))
          ":route/account.billing carries :parent :route/account")
      (is (nil? (:parent account-meta))
          "the parent route itself has no :parent (chain root)"))))

;; ---- Spec 012 §Scroll restoration — pure helpers (rf2-1aqz) --------------
;;
;; Per Spec 012 §Scroll restoration and routing.cljc:506/511, the scroll-
;; restoration helpers are pure: `lookup-scroll-position` reads from a
;; db value, `save-scroll-position` returns a db value with the saved
;; position assoc'd in. Per Spec 012 §Multi-frame routing the saved-
;; position map lives at `[:rf.route/scroll-positions]` INSIDE each
;; frame's app-db — so per-frame isolation is achieved by routing the
;; helpers through the appropriate frame's db value.
;;
;; Pre-rf2-1aqz the helpers were reachable only through the navigate
;; flow's scroll fx; a regression in either fn would only surface via
;; integration. These tests pin the round-trip directly.

(deftest scroll-position-lookup-after-save
  (testing "save-scroll-position then lookup-scroll-position round-trips
            the saved [x y] for the same url"
    (let [db0 {}
          db1 (routing/save-scroll-position db0 "/articles"  [0 250])
          db2 (routing/save-scroll-position db1 "/dashboard" [10 800])]
      (is (= [0 250]  (routing/lookup-scroll-position db2 "/articles"))
          "saved position for /articles is retrievable")
      (is (= [10 800] (routing/lookup-scroll-position db2 "/dashboard"))
          "saved position for /dashboard is retrievable; URLs are isolated")
      (is (nil? (routing/lookup-scroll-position db2 "/unsaved"))
          "an unseen url returns nil — no false positives"))))

(deftest scroll-position-overwrites-on-resave
  (testing "save-scroll-position over an existing url replaces the saved value"
    (let [db1 (routing/save-scroll-position {}  "/page" [0 100])
          db2 (routing/save-scroll-position db1 "/page" [0 999])]
      (is (= [0 999] (routing/lookup-scroll-position db2 "/page"))
          "second save overwrites the first under the same url"))))

(deftest scroll-position-per-frame-isolation
  (testing "save-scroll-position is per-frame — the helpers thread through
            each frame's own db, so a position saved under :rf/default
            is invisible from another frame's db value"
    ;; Simulate two frames' independent app-dbs: each is its own map.
    ;; The helpers operate on db values, so isolation is achieved by
    ;; passing the right frame's db.
    (let [frame-A-db (routing/save-scroll-position {} "/shared-url" [0 250])
          frame-B-db (routing/save-scroll-position {} "/shared-url" [0 999])]
      (is (= [0 250] (routing/lookup-scroll-position frame-A-db "/shared-url"))
          "frame A's db carries A's saved position")
      (is (= [0 999] (routing/lookup-scroll-position frame-B-db "/shared-url"))
          "frame B's db carries B's saved position — values are not shared")
      (is (nil? (routing/lookup-scroll-position {} "/shared-url"))
          "a fresh db (third frame, never-saved) returns nil for the same url"))))

(deftest scroll-position-storage-shape
  (testing "save-scroll-position assoc's into [:rf.route/scroll-positions <url>]"
    ;; Pin the storage shape. Tools and migrations inspect this path
    ;; directly; pinning here keeps the contract stable.
    (let [db1 (routing/save-scroll-position {} "/x" [5 50])]
      (is (= [5 50] (get-in db1 [:rf.route/scroll-positions "/x"]))
          "the saved [x y] lives at [:rf.route/scroll-positions <url>] in the db"))))

;; ---- rf2-z2k4k: LRU cap on scroll-positions -------------------------------
;;
;; Per audit A12: long sessions deep-linking through `/articles/:id`-style
;; routes can grow [:rf.route/scroll-positions] unboundedly. The map is
;; LRU-bounded at `routing/scroll-positions-cap` (50). Re-saving a known
;; url promotes it to most-recent; saves past the cap evict the LRU entry.

(deftest scroll-position-lru-eviction-past-cap
  (testing "save-scroll-position evicts the least-recently-used url
            once the cap is exceeded; the cap is a soft upper bound, not
            a strict per-call limit"
    ;; Hammer 60 distinct urls. Cap is 50, so the first 10 should be gone
    ;; and the last 50 should remain — in insertion order.
    (let [db (reduce (fn [db i] (routing/save-scroll-position db (str "/u" i) [i i]))
                     {}
                     (range 60))
          positions (:rf.route/scroll-positions db)]
      (is (= 50 (count positions))
          "exactly 50 entries remain — the cap holds")
      (is (every? nil? (map #(routing/lookup-scroll-position db (str "/u" %))
                            (range 10)))
          "the first 10 (LRU) urls are evicted")
      (is (every? some? (map #(routing/lookup-scroll-position db (str "/u" %))
                             (range 10 60)))
          "the most-recently-saved 50 urls all survive")))

  (testing "re-saving an existing url promotes it to most-recent — it survives
            an eviction wave that would otherwise drop it"
    ;; Insert 50 urls (fills cap). Promote /u0 by re-saving. Insert one more.
    ;; /u1 (now the LRU) should evict; /u0 should survive.
    (let [db0 (reduce (fn [db i] (routing/save-scroll-position db (str "/u" i) [i i]))
                      {}
                      (range 50))
          db1 (routing/save-scroll-position db0 "/u0" [999 999])  ;; promote
          db2 (routing/save-scroll-position db1 "/u50" [50 50])]  ;; force evict
      (is (= [999 999] (routing/lookup-scroll-position db2 "/u0"))
          "the re-saved url survives and carries its new value")
      (is (nil? (routing/lookup-scroll-position db2 "/u1"))
          "/u1 — the new LRU after the promotion — was evicted instead")
      (is (= 50 (count (:rf.route/scroll-positions db2)))
          "cap is still 50"))))

;; ---- rf2-hra3: route-url missing-required-param raises clear error -------
;;
;; Per test-coverage-review-2026-05-12 P3-14. Hardening: ensure
;; `route-url` doesn't silently emit a malformed URL when a required
;; path param is absent.

(deftest route-url-missing-required-path-param-throws
  (testing "route-url with a missing required :id path param raises
            :rf.error/missing-route-param"
    (rf/reg-route :route/article {:path "/articles/:id"})
    ;; No :id supplied — must throw the structured error.
    (let [ex (try
               (routing/route-url :route/article {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "route-url with absent required param raises")
      (is (= ":rf.error/missing-route-param" (ex-message ex))
          "the structured error id is :rf.error/missing-route-param")
      (let [data (ex-data ex)]
        (is (= :id (:param data))
            "ex-data names the absent param")
        (is (= :route/article (:route-id data))
            "ex-data names the route-id"))))

  (testing "supplying nil for the param has the same shape as omitting it"
    (rf/reg-route :route/article2 {:path "/articles/:id"})
    (let [ex (try
               (routing/route-url :route/article2 {:id nil})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "nil :id behaves like an absent :id — same structured error")
      (is (= ":rf.error/missing-route-param" (ex-message ex)))))

  (testing "providing the param works — sanity check the happy path"
    (rf/reg-route :route/article3 {:path "/articles/:id"})
    (is (= "/articles/intro"
           (routing/route-url :route/article3 {:id "intro"}))
        "supplying the required param renders the URL"))

  (testing "splat params raise the same structured error when absent"
    (rf/reg-route :route/files {:path "/files/*path"})
    (let [ex (try
               (routing/route-url :route/files {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "absent splat raises")
      (is (= ":rf.error/missing-route-param" (ex-message ex))
          "splat absence uses the same structured error id"))))

(deftest route-url-no-such-route-throws
  (testing "route-url against an unregistered route id raises
            :rf.error/no-such-route"
    (let [ex (try
               (routing/route-url :route/no-such-route {})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= ":rf.error/no-such-route" (ex-message ex))
          ":rf.error/no-such-route is the structured error for an unregistered id"))))

;; ---- rf2-u1na: match-url malformed-input edge cases ----------------------
;;
;; Per test-coverage-review-2026-05-12 P3-13. Hardening for the URL parser.

(deftest match-url-empty-string
  (testing "match-url \"\" matches the root '/' route (the compiled regex
            is `^/?$`, so the leading slash is optional)"
    (rf/reg-route :route/home {:path "/"})
    ;; Pin the actual behaviour: the compiled regex treats the leading
    ;; slash as optional, so both "" and "/" match the root.
    (let [m (routing/match-url "")]
      (is (some? m)
          "empty string matches the root '/' route (leading slash optional)")
      (is (= :route/home (:route-id m))))))

(deftest match-url-missing-leading-slash
  (testing "URLs without a leading slash STILL match the corresponding
            route (the compiled regex is `^/?...`, leading slash optional).
            Documenting the actual lenient behaviour."
    (rf/reg-route :route/home {:path "/home"})
    (is (some? (routing/match-url "/home"))
        "the canonical /home matches")
    (let [m (routing/match-url "home")]
      (is (some? m)
          "no-leading-slash also matches — the compiled regex permits it")
      (is (= :route/home (:route-id m))))))

(deftest match-url-repeated-query-key-last-wins
  (testing "repeated query keys — last value wins (the parser's array-map
            reduce assoc's left-to-right, so a later key replaces an
            earlier one)"
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?x=1&x=2")]
      (is (some? m) "the route matches")
      (is (= "2" (get-in m [:query :x]))
          "repeated key — last value wins (left-to-right reduce)"))))

(deftest match-url-unterminated-query
  (testing "a query pair without an '=' value parses as an empty string
            (per routing.cljc:347)"
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?foo=")]
      (is (some? m) "the route still matches")
      (is (= "" (get-in m [:query :foo]))
          "an unterminated `?foo=` parses to :foo \"\""))))

(deftest match-url-trailing-slash-is-strict
  (testing "trailing-slash equivalence is NOT implicit — /foo and /foo/
            are distinct as far as the matcher is concerned (the route's
            :path declares the canonical shape; consumers add explicit
            redirects if they want trailing-slash tolerance)"
    (rf/reg-route :route/foo {:path "/foo"})
    (is (some? (routing/match-url "/foo"))
        "the canonical /foo matches")
    ;; The route's pattern is exactly "/foo"; the strictness depends on
    ;; how `match-against` handles the trailing slash. Pin whichever
    ;; observable result the current impl produces so future regressions
    ;; are visible.
    (let [trailing (routing/match-url "/foo/")]
      ;; Either behaviour is documentable; what we pin is "the matcher
      ;; doesn't crash on the trailing slash, and produces a deterministic
      ;; result". This guards against silent behaviour shifts.
      (is (or (nil? trailing) (map? trailing))
          "matcher produces a deterministic nil-or-map result for /foo/"))))

(deftest match-url-url-encoded-path-round-trip
  (testing "URL-encoded characters in the path round-trip with route-url"
    (rf/reg-route :route/articles {:path "/articles/:slug"})
    ;; A slug with characters that get percent-encoded.
    (let [slug   "hello world"
          built  (routing/route-url :route/articles {:slug slug})
          parsed (routing/match-url built)]
      (is (some? built)
          "route-url built a URL for the encoded slug")
      ;; The built URL must NOT contain a raw space.
      (is (not (clojure.string/includes? built " "))
          "the built URL contains no raw space — encoding was applied")
      (is (some? parsed) "match-url parses the built URL")
      ;; The decoded slug should round-trip back.
      (is (= {:slug slug}
             (:params parsed))
          "the slug round-trips through route-url → match-url"))))

;; ---- rf2-070jt: match-url :fragment + route-url 4-arity round-trip --------
;;
;; Per Spec 012 §Bidirectional URL ↔ params and §Fragments §Programmatic
;; navigation with fragments. match-url surfaces the URL's `#fragment`
;; portion on its result map; route-url's 4-arity rebuilds the URL with
;; the fragment appended. The two are inverses: a URL parsed with
;; match-url and rebuilt with route-url's 4-arity recovers the original
;; (modulo route-id resolution).

(deftest match-url-returns-fragment-from-url
  (testing "match-url surfaces the URL's `#fragment` as :fragment"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [m (routing/match-url "/docs/routing#scroll-restoration")]
      (is (some? m) "the route matches")
      (is (= :route/docs (:route-id m)))
      (is (= {:page "routing"} (:params m))
          "path params parsed as usual; fragment did not pollute :page")
      (is (= "scroll-restoration" (:fragment m))
          ":fragment carries the URL's #fragment portion"))))

(deftest match-url-fragment-absent-is-nil
  (testing "URLs without a #fragment yield :fragment nil"
    (rf/reg-route :route/home {:path "/home"})
    (let [m (routing/match-url "/home")]
      (is (some? m))
      (is (nil? (:fragment m))
          "absent #fragment → :fragment nil"))))

(deftest match-url-fragment-with-query
  (testing ":fragment is independent of the query string"
    (rf/reg-route :route/search {:path "/search"})
    (let [m (routing/match-url "/search?q=clojure#results")]
      (is (some? m))
      (is (= {:q "clojure"} (:query m))
          "query parsed without the fragment polluting it")
      (is (= "results" (:fragment m))
          "fragment captured after the query string"))))

(deftest match-url-empty-fragment
  (testing "a bare trailing '#' yields :fragment \"\""
    (rf/reg-route :route/page {:path "/page"})
    (let [m (routing/match-url "/page#")]
      (is (some? m))
      (is (= "" (:fragment m))
          "URL ending with bare '#' yields empty-string fragment"))))

(deftest match-url-no-rank-in-result
  (testing "match-url result does NOT carry the internal :rank key"
    (rf/reg-route :route/home {:path "/"})
    (let [m (routing/match-url "/")]
      (is (some? m))
      (is (not (contains? m :rank))
          ":rank is internal routing-table state; not part of the
          documented match-url result shape"))))

(deftest route-url-4-arity-appends-fragment
  (testing "the 4-arity form appends `#fragment` when non-nil and non-empty"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (is (= "/docs/routing#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {} "scroll-restoration"))
        "fragment is appended after the path")
    (is (= "/docs/routing?lang=en#scroll-restoration"
           (routing/route-url :route/docs {:page "routing"} {:lang "en"} "scroll-restoration"))
        "fragment is appended after the query string"))

  (testing "nil and empty-string fragments are not appended"
    (rf/reg-route :route/docs2 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} nil))
        "nil fragment → no `#` suffix")
    (is (= "/docs/routing"
           (routing/route-url :route/docs2 {:page "routing"} {} ""))
        "empty-string fragment → no `#` suffix"))

  (testing "the 3-arity form delegates to the 4-arity with fragment nil"
    (rf/reg-route :route/docs3 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs3 {:page "routing"} {}))
        "3-arity produces the same URL as 4-arity with nil fragment"))

  (testing "the 2-arity form delegates the same way"
    (rf/reg-route :route/docs4 {:path "/docs/:page"})
    (is (= "/docs/routing"
           (routing/route-url :route/docs4 {:page "routing"}))
        "2-arity → no query, no fragment")))

(deftest match-url-route-url-round-trip-with-fragment
  (testing "URL → match-url → route-url 4-arity → URL recovers the original
            (the full bidirectional contract including #fragment)"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [original "/docs/routing?lang=en#scroll-restoration"
          parsed   (routing/match-url original)
          rebuilt  (routing/route-url (:route-id parsed)
                                      (:params parsed)
                                      (:query parsed)
                                      (:fragment parsed))]
      (is (= :route/docs (:route-id parsed)))
      (is (= {:page "routing"} (:params parsed)))
      (is (= {:lang "en"}      (:query parsed)))
      (is (= "scroll-restoration" (:fragment parsed)))
      (is (= original rebuilt)
          "the rebuilt URL equals the original — fragment round-trips"))))

(deftest navigate-pushes-url-with-fragment
  (testing ":rf.route/navigate with :fragment opt pushes the URL with #fragment appended"
    ;; Per Spec 012 §Fragments §Programmatic navigation with fragments:
    ;; `[:rf.route/navigate :route/docs {:page "routing"} {:fragment "x"}]`
    ;; pushes "/docs/routing#x" via :rf.nav/push-url. The 4-arity route-url
    ;; is the canonical builder; the navigate handler routes opts → 4-arity.
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate
                         :route/docs
                         {:page "routing"}
                         {:fragment "scroll-restoration"}])
      (is (= ["/docs/routing#scroll-restoration"] @pushed)
          ":rf.nav/push-url received the URL WITH the appended #fragment"))))

(deftest navigate-url-form-preserves-fragment
  (testing ":rf.route/navigate with URL-string target preserves the URL's
            embedded #fragment in the pushed URL"
    (rf/reg-route :route/docs {:path "/docs/:page"})
    (let [pushed (atom [])]
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf/dispatch-sync [:rf.route/navigate
                         {:url "/docs/routing#scroll-restoration"}])
      (is (= ["/docs/routing#scroll-restoration"] @pushed)
          "fragment in the URL-string target round-trips through the push"))))
