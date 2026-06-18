(ns re-frame.installed-app-read-seam-cljs-test
  "EP-0013 disposition 6 — the PUBLIC realm→installed-app READ seam
  (`realm/installed-app`, rf2-imquoq).

  This is the NAMED GRADUATION: the per-module provenance/ownership/capability
  facts an `install!`-seated app value carries (`:modules` + each module's
  `:rf.module/owns` / `:rf.module/requires` and the `:owner`-stamped
  descriptors) graduate from
  internal WHEN a tool demands them — which the Xray Module-view now does. Before
  this seam, `re-frame.realm/installed-app` was internal (no `rf/*` re-export) and
  nothing public yielded a RUNNING realm's installed app value to feed to the
  existing `av/app-registrations` / `av/app-owns` / `av/app-requires` inspectors
  (those operate on an app value you already HOLD).

  `realm/installed-app` closes that: it returns a running realm's installed app
  value. The contract this test pins:

    (1) the public facade var EXISTS and re-exports the internal seam unchanged;
    (2) a realm seated via `install!` returns the RICH constructed value — its
        `:modules` provenance is present, so the existing inspectors read which
        module owns a handler/sub/path WITHOUT installing anything;
    (3) a realm seated only through the `reg-*` sugar (load-order, no `install!`)
        returns the recomputable projection — registrations by kind, but an
        empty `:modules` / `:rf.app/requires` (load-order registrations declare
        no module). The Module-view shows the honest awaiting-seam caption there;
    (4) it is a STATIC read of the install-time value — `(realm/installed-app)`
        without a realm reads the default realm (absence = default realm).

  Dual-runtime `*_cljs_test.cljc` — both `npm run test:cljs` (node) and
  `clojure -M:test` (JVM) pick it up. Pure CLJC, no DOM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.app-value :as av]
            [re-frame.realm :as realm]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The realm registry (`realm/realms`) is process-global and the `:app` slot is
;; NOT registrar state, so the runtime-reset fixture (snapshot/restore of the
;; registrar) does not clear it. Clear the default realm's `:app` around each
;; test so a sibling test's stored value never leaks into a read.
(defn- clear-default-realm-app! []
  (swap! realm/realms update realm/default-realm-id dissoc :app))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (clear-default-realm-app!)
    (test-fn)
    (clear-default-realm-app!)))

(defn- cart-add   [db _] (update db :items (fnil conj []) :x))
(defn- cart-items [db _] (:items db))

;; ---------------------------------------------------------------------------
;; (1) the internal installed-app read seam exists and yields the projection
;;     (EP-0023: this is the retained-INTERNAL realm substrate — no longer a
;;     `rf/*` public facade re-export; tools reach it via `re-frame.realm`.)
;; ---------------------------------------------------------------------------

(deftest installed-app-read-seam-yields-the-default-realm-projection
  (testing "re-frame.realm/installed-app is a callable read seam that yields the
            default realm's live installed-app value — the retained-internal
            substrate read (EP-0023) tools consume, not a public facade var"
    (rf/reg-event :seam/probe (fn [{:keys [db]} _] {:db db}))
    (let [running (realm/installed-app)]
      (is (map? running)
          "the seam returns the realm's installed-app value")
      (is (contains? (av/app-registrations running :event) :seam/probe)
          "the projection reflects the live default-realm registrations"))))

;; ---------------------------------------------------------------------------
;; (2) an install!-seated realm returns the RICH value with :modules provenance,
;;     feedable to the existing public inspectors
;; ---------------------------------------------------------------------------

(deftest installed-app-of-a-seated-realm-carries-module-provenance
  (testing "a realm seated via install! returns the rich constructed app value —
            its :modules provenance is present, so av/app-registrations /
            av/app-owns / av/app-requires read the per-module facts off the
            running realm WITHOUT installing anything (EP-0013 disposition 6)"
    (let [cart (av/module {:id                 :shop/cart
                           :rf.module/owns     {:app-db [[:cart]]}
                           :rf.module/requires #{:rf.capability/http}
                           :events   {:cart/add   {:doc "Add."   :handler cart-add}}
                           :subs     {:cart/items {:doc "Items." :handler cart-items}}})
          a    (av/app {:id :shop/app :modules [cart]})
          ;; The default realm's capability map must satisfy the app's :rf.app/requires
          ;; for install! to proceed; seat it, then dispose on the way out.
          _    (swap! realm/realms update realm/default-realm-id
                      assoc :capabilities {:rf.capability/http :stub})]
      (try
        (av/install! a)
        ;; The PUBLIC read yields the running realm's installed app value:
        ;; the LIVE registrar projection enriched with the seated app's
        ;; provenance (rf2-77ewnm). With NO coexisting sugar the registration
        ;; set is exactly the seated app's, so it round-trips to the seated id +
        ;; modules + requires, with registrations re-projected off the registrar.
        (let [running (realm/installed-app)]
          (is (= :shop/app (:rf.app/id running))
              "realm/installed-app reports the seated app's identity")
          (is (= {:shop/cart cart} (:modules running))
              "the rich constructed value's :modules provenance is present
               (NOT the module-less projection)")
          ;; The whole point of disposition 6: feed the running value to the
          ;; existing public inspectors and read per-module facts.
          (is (contains? (av/app-registrations running :event) :cart/add)
              "app-registrations reads the seated event descriptor")
          (is (= :shop/cart (:owner (get (av/app-registrations running :event) :cart/add)))
              "the descriptor carries its owning module (provenance)")
          (is (= :shop/cart (av/app-owns running [:cart]))
              "app-owns resolves the owning module of an app-db path")
          (is (= #{:rf.capability/http} (av/app-requires running))
              "app-requires reads the module's capability requirements"))
        (finally
          (swap! realm/realms update realm/default-realm-id dissoc :capabilities))))))

;; ---------------------------------------------------------------------------
;; (3) a sugar/load-order realm returns the projection — registrations present,
;;     but no module structure (the Module-view keeps its awaiting-seam caption)
;; ---------------------------------------------------------------------------

(deftest installed-app-of-a-sugar-only-realm-is-the-module-less-projection
  (testing "a realm seated only through the reg-* sugar path (no install!)
            returns the recomputable projection — its registrations are present
            by kind, but it carries NO :modules and an empty :rf.app/requires
            (load-order registrations declare no module). The honest
            no-provenance case."
    ;; reg-* sugar — writes the default realm's registrar in place, no install!.
    (rf/reg-event :sugar/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [running (realm/installed-app)]
      (is (contains? (av/app-registrations running :event) :sugar/inc)
          "the projection enumerates the sugar-registered event")
      (is (nil? (:modules running))
          "a projected (sugar/load-order) app carries NO :modules provenance")
      (is (= #{} (av/app-requires running))
          "a projected app declares no capability requirements")
      (is (nil? (av/app-owns running [:cart]))
          "app-owns is nil for a projected app (no ownership declarations)"))))

;; ---------------------------------------------------------------------------
;; (4) static read of the default realm; an explicit unknown id projects empty
;; ---------------------------------------------------------------------------

(deftest installed-app-no-arg-reads-the-default-realm
  (testing "the no-arg form reads the default realm (absence = default realm)"
    (rf/reg-event :default/e (fn [{:keys [db]} _] {:db db}))
    (is (= (realm/installed-app)
           (realm/installed-app realm/default-realm-id))
        "(installed-app) and (installed-app default-realm-id) agree")
    (is (contains? (av/app-registrations (realm/installed-app) :event) :default/e)
        "the no-arg read is the default realm's program")))

;; ---------------------------------------------------------------------------
;; (5) MIXED MODE — reg-* sugar coexisting with an install!-seated app
;;     (rf2-77ewnm). THE contract this bead reconciles: once a realm has a
;;     stored :app, the public read seam must STILL reflect coexisting sugar
;;     (the registrar is the single source of truth, EP-0013:138/:838), while
;;     preserving the seated value's per-module provenance. Before the fix,
;;     `realm/installed-app` returned the frozen install-time snapshot, so a sugar
;;     registration live in the registrar (and visible to `av/app-value` /
;;     dispatch) was INVISIBLE here. These are the adversarial regressions.
;; ---------------------------------------------------------------------------

(defn- with-default-realm-http! [thunk]
  ;; install! gates on the realm satisfying the app's :rf.app/requires; seed a stub
  ;; :rf.capability/http, run, then clear (the :app slot is cleared by the
  ;; suite fixture).
  (swap! realm/realms update realm/default-realm-id
         assoc :capabilities {:rf.capability/http :stub})
  (try (thunk)
       (finally
         (swap! realm/realms update realm/default-realm-id dissoc :capabilities))))

(deftest installed-app-mixed-sugar-BEFORE-install-shows-both
  (testing "rf2-77ewnm: a reg-* sugar registration made BEFORE install! is
            visible in realm/installed-app alongside the installed app's events —
            the public read is the LIVE registrar, not the frozen snapshot. The
            seated app's :modules / :rf.app/requires provenance is preserved."
    ;; Sugar FIRST — writes the default realm's registrar in place, no module.
    (rf/reg-event :sugar/before (fn [{:keys [db]} _] {:db db}))
    (with-default-realm-http!
      (fn []
        (let [cart (av/module {:id                 :shop/cart
                               :rf.module/owns     {:app-db [[:cart]]}
                               :rf.module/requires #{:rf.capability/http}
                               :events   {:cart/add {:doc "Add." :handler cart-add}}
                               :subs     {:cart/items {:doc "Items." :handler cart-items}}})
              a    (av/app {:id :shop/app :modules [cart]})]
          (av/install! a)
          (let [running (realm/installed-app)
                events  (av/app-registrations running :event)]
            ;; BOTH the coexisting sugar AND the installed event are visible.
            (is (contains? events :sugar/before)
                "the pre-install sugar event is visible in the public read seam
                 (NOT swept by, NOT hidden behind, the stored :app)")
            (is (contains? events :cart/add)
                "the installed event is visible too")
            ;; The reconciled read agrees with the live projection's registrar
            ;; view — no desync between realm/installed-app and av/app-value.
            (is (= (set (keys events))
                   (set (keys (av/app-registrations (realm/installed-app realm/default-realm-id) :event))))
                "the public read enumerates exactly the live registrar's events")
            ;; Provenance survives: the installed event carries its owner; the
            ;; sugar event has none (load-order declares no module).
            (is (= :shop/cart (:owner (get events :cart/add)))
                "the installed event keeps its owning-module provenance")
            (is (nil? (:owner (get events :sugar/before)))
                "the sugar event declares no owner (no module)")
            ;; Seated-value provenance is overlaid onto the live projection.
            (is (= :shop/app (:rf.app/id running))
                "the read reports the seated app's identity")
            (is (= {:shop/cart cart} (:modules running))
                ":modules provenance from the seated app is preserved")
            (is (= #{:rf.capability/http} (av/app-requires running))
                ":rf.app/requires from the seated app is preserved")
            (is (= :shop/cart (av/app-owns running [:cart]))
                "app-owns resolves through the overlaid :modules")))))))

(deftest installed-app-mixed-sugar-AFTER-install-shows-both
  (testing "rf2-77ewnm: a reg-* sugar registration made AFTER install! is ALSO
            visible — sugar updates the default realm's app value in place at any
            point (EP-0013:138), and the public read recomputes over the live
            registrar every call, so a post-install sugar registration appears
            without any re-install or invalidation step."
    (with-default-realm-http!
      (fn []
        (let [a (av/app {:id :shop/app
                         :modules [(av/module {:id                 :shop/cart
                                               :rf.module/requires #{:rf.capability/http}
                                               :events   {:cart/add {:handler cart-add}}})]})]
          (av/install! a)
          ;; Sugar AFTER the install — must still surface in the public read.
          (rf/reg-event :sugar/after (fn [{:keys [db]} _] {:db db}))
          (let [events (av/app-registrations (realm/installed-app) :event)]
            (is (contains? events :sugar/after)
                "a post-install sugar registration is visible (live recompute,
                 no frozen snapshot)")
            (is (contains? events :cart/add)
                "the installed event remains visible alongside it")
            (is (= :shop/app (:rf.app/id (realm/installed-app)))
                "the seated identity/provenance is still overlaid")))))))

(deftest installed-app-repeated-install-public-view-keeps-coexisting-sugar
  (testing "rf2-77ewnm + rf2-c6armm.7 #1: a repeated install! clears the prior
            installed app's dropped registrations but PRESERVES coexisting sugar
            — and the PUBLIC read surface (realm/installed-app), not just the
            registrar, reflects that. AC3: assert how the chosen read exposes
            the coexisting sugar through a replacement."
    (rf/reg-event :coexist/sugar (fn [{:keys [db]} _] {:db db}))
    (with-default-realm-http!
      (fn []
        ;; install app1 (its own event), then install app2 dropping app1's event.
        (av/install! (av/app {:id :coexist/app1
                              :modules [(av/module {:id :m :events {:coexist/a1 {:handler cart-add}}})]}))
        (av/install! (av/app {:id :coexist/app2
                              :modules [(av/module {:id :m :events {:coexist/a2 {:handler cart-add}}})]}))
        (let [running (realm/installed-app)
              events  (av/app-registrations running :event)]
          (is (not (contains? events :coexist/a1))
              "app1's dropped event is GONE from the public read (replacement)")
          (is (contains? events :coexist/a2)
              "app2's event is present")
          (is (contains? events :coexist/sugar)
              "the coexisting sugar SURVIVES the replacement and is visible in
               the public read seam (NOT swept, NOT hidden by the stored :app)")
          (is (= :coexist/app2 (:rf.app/id running))
              "the read reports the most-recently-installed app's identity"))))))
