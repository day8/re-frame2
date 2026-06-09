(ns re-frame.routing-scroll-test
  "Scroll-restoration tests for re-frame.routing (scroll-position
  save/lookup, per-frame isolation, the LRU cap, the `:rf.nav/scroll` fx
  emission, and scroll-strategy resolution precedence). Split from
  routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

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

      ;; 5. :rf.route/transitioned (URL-driven) also emits the fx — default :top.
      ;; Land on "/" (route/home, no :scroll meta) so the NEXT step's
      ;; handle-url-change to "/articles" is a genuine navigation, not a
      ;; rule-3 identical no-op (Spec 012 §Per-route data loading rule 3).
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/transitioned "/"])
      (is (= :top (-> @calls first :strategy))
          ":rf.route/transitioned emits :rf.nav/scroll with default :top")

      ;; 6. :rf.route/handle-url-change (popstate / initial) defaults to
      ;;    :restore — the saved position trumps a forward-style :top.
      ;;    "/articles" differs from the current "/" slice → real nav.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles"])
      (is (= :restore (-> @calls first :strategy))
          ":rf.route/handle-url-change emits :rf.nav/scroll with default :restore")

      ;; 7. Fragment in URL is forwarded in the fx args.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/transitioned "/articles/intro#section-2"])
      (is (= "section-2" (-> @calls first :fragment))
          "fragment from URL flows into :rf.nav/scroll args"))))

;; ---- Spec 012 §Scroll restoration — pure helpers (rf2-1aqz) --------------
;;
;; Per Spec 012 §Scroll restoration and routing.cljc:506/511, the scroll-
;; restoration helpers are pure: `lookup-scroll-position` reads from a
;; db value, `save-scroll-position` returns a db value with the saved
;; position assoc'd in. Per Spec 012 §Multi-frame routing the saved-
;; position map lives at `[:rf.runtime/routing :scroll-positions]` INSIDE each
;; frame's app-db (rf2-3ib8h + rf2-eguy4: a sibling key under
;; `[:rf.runtime/routing ...]`, not nested under the route slice) — so
;; per-frame isolation is achieved by routing the helpers through the
;; appropriate frame's db value.
;;
;; These tests pin the helper round-trip directly so a regression in
;; either fn surfaces without going through the navigate flow's scroll fx.

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
  (testing "save-scroll-position assoc's into [:rf.runtime/routing :scroll-positions <url>]"
    ;; Pin the storage shape. Tools and migrations inspect this path
    ;; directly; pinning here keeps the contract stable.
    (let [db1 (routing/save-scroll-position {} "/x" [5 50])]
      (is (= [5 50] (get-in db1 [:rf.runtime/routing :scroll-positions "/x"]))
          "the saved [x y] lives at [:rf.runtime/routing :scroll-positions <url>] in the db"))))

;; ---- rf2-z2k4k: LRU cap on scroll-positions -------------------------------
;;
;; Per audit A12: long sessions deep-linking through `/articles/:id`-style
;; routes can grow [:rf.runtime/routing :scroll-positions] unboundedly. The map is
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
          positions (get-in db [:rf.runtime/routing :scroll-positions])]
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
      (is (= 50 (count (get-in db2 [:rf.runtime/routing :scroll-positions])))
          "cap is still 50"))))

;; ---- scroll-strategy resolution precedence (resolve-scroll-strategy) -----
;;
;; `routing-scroll-fx-emitted-on-navigate` covers opts `:scroll :preserve`
;; winning over meta `:restore`, and meta `:scroll false` suppressing. The
;; UNcovered precedence edge is the asymmetric pair: an opts `:scroll` value
;; must win over a route whose meta declares `:scroll false`
;; (`(some? from-opts)` short-circuits BEFORE the `(false? from-meta)`
;; suppression branch), AND an opts `:scroll false` must suppress even when
;; the route's meta declares a concrete strategy. Map-form (host-extensible)
;; strategies must pass through verbatim.

(deftest scroll-strategy-opts-override-precedence
  (testing "opts :scroll value WINS over a route's :scroll false (the
            per-call override short-circuits before the meta-false
            suppression branch)"
    ;; Two distinct :scroll false routes so each assertion navigates to a
    ;; FRESH target — a second navigate to the same id/params would be a
    ;; Spec 012 rule-3 no-op (no scroll fx) and mask the precedence result.
    (rf/reg-route :route/silent  {:path   "/silent"
                                  :scroll false})
    (rf/reg-route :route/silent2 {:path   "/silent2"
                                  :scroll false})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Without an opts override the route's :scroll false suppresses.
      (rf/dispatch-sync [:rf.route/navigate :route/silent])
      (is (empty? @calls)
          "baseline: route :scroll false suppresses the fx")
      ;; A per-call :scroll :top opt resurrects the fx on a fresh target
      ;; (override wins over the route's :scroll false).
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/silent2 {} {:scroll :top}])
      (is (= :top (-> @calls first :strategy))
          "opts :scroll :top overrides the route's :scroll false → fx emits")))

  (testing "opts :scroll false suppresses even when the route declares a
            concrete :scroll strategy"
    (rf/reg-route :route/loud {:path   "/loud"
                               :scroll :restore})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/loud {} {:scroll false}])
      (is (empty? @calls)
          "opts :scroll false suppresses despite the route's :scroll :restore")))

  (testing "map-form (host-extensible) scroll strategies pass through to the
            fx args verbatim — the resolver does not coerce or drop them"
    (rf/reg-route :route/custom {:path   "/custom"
                                 :scroll {:behavior :smooth :block :center}})
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate :route/custom])
      (is (= {:behavior :smooth :block :center} (-> @calls first :strategy))
          "a map-form :scroll strategy flows into :rf.nav/scroll's :strategy arg unchanged"))))
