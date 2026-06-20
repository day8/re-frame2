(ns re-frame.routing-scroll-test
  "Scroll-restoration tests for re-frame.routing (scroll-position
  save/lookup, per-frame isolation, the LRU cap, the `:rf.nav/scroll` fx
  emission, and scroll-strategy resolution precedence). Split from
  routing_test.clj per rf2-u8qe7y finding 3."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.routing :as routing]
            [re-frame.routing.nav-fx :as nav-fx]
            [re-frame.routing.scroll :as scroll]
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
                  {:scroll :top} "/")
    (rf/reg-route :route/article
                  {:params [:map [:id :string]]
                   :scroll :restore} "/articles/:id")
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
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/articles {} "/articles")
    (rf/reg-route :route/article  {:params [:map [:id :string]]
                                   :scroll :restore} "/articles/:id")
    (rf/reg-route :route/profile  {:scroll false} "/profile")

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

;; ---- Spec 012 §Scroll restoration — pure helpers (rf2-1aqz / rf2-1hncp2) --
;;
;; Per Spec 012 §Scroll restoration, the scroll-restoration helpers are
;; pure: `lookup-scroll-position` reads a [x y] from a per-frame cache map
;; (`{:positions {url [x y]} :order [...]}`), `save-scroll-position`
;; returns the cache map with the position recorded + the LRU cap applied.
;;
;; rf2-1hncp2: scroll positions are a HOST-SIDE TRANSIENT cache, NOT
;; runtime-db state — they live in the module-level
;; `re-frame.routing.scroll/scroll-positions-cache` atom keyed by
;; frame-id (host-derived, ephemeral, off the epoch/SSR egress wire). The
;; pure helpers operate on the per-frame cache map so the nav-planning
;; seam can thread it in explicitly and stay pure; per-frame isolation is
;; the frame-keyed host atom, not a runtime-db slice.
;;
;; These tests pin the helper round-trip directly so a regression in
;; either fn surfaces without going through the navigate flow's scroll fx.

(deftest scroll-position-lookup-after-save
  (testing "save-scroll-position then lookup-scroll-position round-trips
            the saved [x y] for the same url"
    (let [c0 nil
          c1 (routing/save-scroll-position c0 "/articles"  [0 250])
          c2 (routing/save-scroll-position c1 "/dashboard" [10 800])]
      (is (= [0 250]  (routing/lookup-scroll-position c2 "/articles"))
          "saved position for /articles is retrievable")
      (is (= [10 800] (routing/lookup-scroll-position c2 "/dashboard"))
          "saved position for /dashboard is retrievable; URLs are isolated")
      (is (nil? (routing/lookup-scroll-position c2 "/unsaved"))
          "an unseen url returns nil — no false positives"))))

(deftest scroll-position-overwrites-on-resave
  (testing "save-scroll-position over an existing url replaces the saved value"
    (let [c1 (routing/save-scroll-position nil "/page" [0 100])
          c2 (routing/save-scroll-position c1  "/page" [0 999])]
      (is (= [0 999] (routing/lookup-scroll-position c2 "/page"))
          "second save overwrites the first under the same url"))))

(deftest scroll-position-per-frame-isolation
  (testing "save-scroll-position is per-frame — the helpers thread through
            each frame's own cache map, so a position saved in one frame's
            cache is invisible from another frame's cache value"
    ;; Two frames' independent caches: each is its own cache map. The
    ;; helpers operate on cache values, so isolation is achieved by
    ;; passing the right frame's cache.
    (let [frame-A (routing/save-scroll-position nil "/shared-url" [0 250])
          frame-B (routing/save-scroll-position nil "/shared-url" [0 999])]
      (is (= [0 250] (routing/lookup-scroll-position frame-A "/shared-url"))
          "frame A's cache carries A's saved position")
      (is (= [0 999] (routing/lookup-scroll-position frame-B "/shared-url"))
          "frame B's cache carries B's saved position — values are not shared")
      (is (nil? (routing/lookup-scroll-position nil "/shared-url"))
          "a fresh cache (third frame, never-saved) returns nil for the same url"))))

(deftest scroll-position-storage-shape
  (testing "save-scroll-position records [x y] under :positions and tracks
            recency under :order (the per-frame transient cache shape, rf2-1hncp2)"
    ;; Pin the cache-map shape. The cache is host-side transient state
    ;; (NOT runtime-db) — nothing reads a [:rf.runtime/routing :scroll-positions]
    ;; path anymore, so the contract is the {:positions :order} cache map.
    (let [c1 (routing/save-scroll-position nil "/x" [5 50])]
      (is (= [5 50] (get-in c1 [:positions "/x"]))
          "the saved [x y] lives under :positions in the cache map")
      (is (= ["/x"] (:order c1))
          ":order tracks the url as the most-recent entry"))))

;; ---- rf2-z2k4k: LRU cap on the scroll-position cache ----------------------
;;
;; Per audit A12: long sessions deep-linking through `/articles/:id`-style
;; routes can grow the per-frame scroll cache unboundedly. It is LRU-bounded
;; at `routing/scroll-positions-cap` (50). Re-saving a known url promotes it
;; to most-recent; saves past the cap evict the LRU entry.

(deftest scroll-position-lru-eviction-past-cap
  (testing "save-scroll-position evicts the least-recently-used url
            once the cap is exceeded; the cap is a soft upper bound, not
            a strict per-call limit"
    ;; Hammer 60 distinct urls. Cap is 50, so the first 10 should be gone
    ;; and the last 50 should remain — in insertion order.
    (let [cache (reduce (fn [c i] (routing/save-scroll-position c (str "/u" i) [i i]))
                        nil
                        (range 60))
          positions (:positions cache)]
      (is (= 50 (count positions))
          "exactly 50 entries remain — the cap holds")
      (is (every? nil? (map #(routing/lookup-scroll-position cache (str "/u" %))
                            (range 10)))
          "the first 10 (LRU) urls are evicted")
      (is (every? some? (map #(routing/lookup-scroll-position cache (str "/u" %))
                             (range 10 60)))
          "the most-recently-saved 50 urls all survive")))

  (testing "re-saving an existing url promotes it to most-recent — it survives
            an eviction wave that would otherwise drop it"
    ;; Insert 50 urls (fills cap). Promote /u0 by re-saving. Insert one more.
    ;; /u1 (now the LRU) should evict; /u0 should survive.
    (let [c0 (reduce (fn [c i] (routing/save-scroll-position c (str "/u" i) [i i]))
                     nil
                     (range 50))
          c1 (routing/save-scroll-position c0 "/u0" [999 999])  ;; promote
          c2 (routing/save-scroll-position c1 "/u50" [50 50])]  ;; force evict
      (is (= [999 999] (routing/lookup-scroll-position c2 "/u0"))
          "the re-saved url survives and carries its new value")
      (is (nil? (routing/lookup-scroll-position c2 "/u1"))
          "/u1 — the new LRU after the promotion — was evicted instead")
      (is (= 50 (count (:positions c2)))
          "cap is still 50"))))

;; ---- rf2-1hncp2: host-side transient cache (frame-keyed) ------------------
;;
;; The cache is held off runtime-db in the module-level
;; `scroll-positions-cache` atom, keyed by frame-id. These tests pin the
;; host-cache wrappers: save! writes the frame slot, frame-scroll-cache
;; reads it back, the value lives OUTSIDE runtime-db (so it never egresses
;; to trace/epoch/SSR), and release-frame! (the frame-destroy teardown
;; hook) drops the frame's entry.

(deftest scroll-cache-host-side-roundtrip
  (testing "save-scroll-position! writes the frame's host cache and
            frame-scroll-cache reads it back; lookups are per-frame isolated"
    (routing/reset-scroll-cache!)
    (routing/save-scroll-position! :frame/a "/articles" [0 250])
    (routing/save-scroll-position! :frame/b "/articles" [0 999])
    (is (= [0 250] (routing/lookup-scroll-position
                     (routing/frame-scroll-cache :frame/a) "/articles"))
        "frame :frame/a's host cache carries A's saved position")
    (is (= [0 999] (routing/lookup-scroll-position
                     (routing/frame-scroll-cache :frame/b) "/articles"))
        "frame :frame/b's host cache is isolated from A")
    (is (nil? (routing/lookup-scroll-position
                (routing/frame-scroll-cache :frame/never) "/articles"))
        "a never-saved frame returns nil — no cross-frame leakage")))

(deftest scroll-cache-not-in-runtime-db
  (testing "the host cache lives outside runtime-db — a frame's runtime-db
            carries NO scroll-position keys after a capture (rf2-1hncp2)"
    ;; The acceptance point: scroll positions no longer sit under
    ;; [:rf.runtime/routing ...], so they cannot egress to trace/epoch/SSR.
    (routing/reset-scroll-cache!)
    (rf/reg-route :route/home {} "/")
    (rf/reg-fx :rf.nav/scroll        {:platforms #{:server :client}} (fn [_ _] nil))
    (rf/reg-fx :rf.nav/push-url      {:platforms #{:server :client}} (fn [_ _] nil))
    ;; Capture a position for the :rf/default frame via the production fx.
    (rf/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}}
               (fn [ctx args]
                 ;; mirror the handler but supply an explicit position (no
                 ;; window on the JVM) so the save path is exercised here.
                 (routing/save-scroll-position! (:frame ctx) (:url args) [0 321])))
    (rf/dispatch-sync [:rf.route/navigate :route/home])
    (routing/save-scroll-position! :rf/default "/" [0 321])
    (let [rdb (frame/frame-runtime-db-value :rf/default)]
      (is (nil? (get-in rdb [:rf.runtime/routing :scroll-positions]))
          "runtime-db carries no :scroll-positions key")
      (is (nil? (get-in rdb [:rf.runtime/routing :scroll-positions-order]))
          "runtime-db carries no :scroll-positions-order key"))
    (is (= [0 321] (routing/lookup-scroll-position
                     (routing/frame-scroll-cache :rf/default) "/"))
        "the position is held in the host cache instead")))

(deftest scroll-cache-released-on-frame-destroy
  (testing "destroy-frame! releases the destroyed frame's host scroll cache
            entry via the :routing/on-frame-destroyed! teardown hook"
    (routing/reset-scroll-cache!)
    (rf/reg-frame :frame/scrollee {})
    (routing/save-scroll-position! :frame/scrollee "/x" [0 100])
    (is (some? (routing/frame-scroll-cache :frame/scrollee))
        "precondition: the frame has a host cache entry")
    (frame/destroy-frame! :frame/scrollee)
    (is (nil? (routing/frame-scroll-cache :frame/scrollee))
        "the frame's scroll cache entry is dropped on destroy — no leak")))

(deftest scroll-restore-end-to-end-across-navigation
  (testing "a captured position is restored on a later :restore navigation
            back to the same url — save/restore survives the storage move"
    ;; Acceptance point 1: no behavioral regression in scroll save/restore.
    (routing/reset-scroll-cache!)
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]
                                  :scroll :restore} "/articles/:id")
    (let [calls (atom [])]
      (rf/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Seed a saved position for "/articles/intro" in the host cache,
      ;; as a prior visit's capture would have.
      (routing/save-scroll-position! :rf/default "/articles/intro" [0 640])
      ;; Land on home first, then navigate to the :restore article route.
      (rf/dispatch-sync [:rf.route/navigate :route/home])
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate :route/article {:id "intro"}])
      (let [a (first @calls)]
        (is (= :restore (:strategy a))
            "the article route resolves to :restore")
        (is (= [0 640] (:saved-pos a))
            ":saved-pos is read from the host cache and threaded into the fx")))))

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
    (rf/reg-route :route/silent  {:scroll false} "/silent")
    (rf/reg-route :route/silent2 {:scroll false} "/silent2")
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
    (rf/reg-route :route/loud {:scroll :restore} "/loud")
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
    (rf/reg-route :route/custom {:scroll {:behavior :smooth :block :center}} "/custom")
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

;; ---- rf2-ukv4ck: nav-fx identity trace tags use canonical :rf.fx/id -------
;;
;; The routing/nav fx skip & failure traces stamp the fx identity under the
;; CANONICAL `:rf.fx/id` tag — the same spelling core `re-frame.fx` uses for
;; `:rf.fx/skipped-on-platform` / `:rf.error/fx-handler-exception` and that
;; Spec 009's error catalogue + Spec-Schemas' `FxSkippedOnPlatformTags`
;; document. Previously these emitted a bare `:fx-id`, drifting from core,
;; the spec, and the epoch projection's `(:rf.fx/id tags)` read (which would
;; have seen nil). These tests pin the corrected tag on the JVM `:clj`
;; skip-on-platform branch of each of the four `:rf.nav/*` fxs (the CLJS
;; push/replace `-failed` traces are pinned in routing_history_cljs_test).
;; They invoke the production handlers directly so a regression in THIS emit
;; surfaces without routing around it through a re-registered test fx.

(defn- capture-fx-traces
  "Run `thunk` while collecting every `:rf.fx/skipped-on-platform` trace's
  tags (with the operation folded in). Returns the captured vector."
  [thunk]
  (let [captured (atom [])
        k        (keyword (gensym "fx-id-tag-"))]
    (rf/register-listener! :trace k
      (fn [ev]
        (when (= :rf.fx/skipped-on-platform (:operation ev))
          (swap! captured conj (:tags ev)))))
    (try (thunk) (finally (rf/unregister-listener! :trace k)))
    @captured))

(deftest nav-fx-skip-traces-use-canonical-rf-fx-id-tag
  (testing ":rf.nav/scroll's JVM skip-on-platform trace stamps :rf.fx/id, not bare :fx-id"
    (let [tags (capture-fx-traces
                 #(scroll/scroll-fx-handler nil {:strategy :top}))]
      (is (= 1 (count tags))
          "the JVM :rf.nav/scroll handler emits exactly one skip trace")
      (is (= :rf.nav/scroll (:rf.fx/id (first tags)))
          "the skip trace carries :rf.fx/id :rf.nav/scroll (canonical identity tag)")
      (is (not (contains? (first tags) :fx-id))
          "no bare :fx-id tag remains (drift removed)")))

  (testing ":rf.nav/capture-scroll's JVM skip-on-platform trace stamps :rf.fx/id"
    (let [tags (capture-fx-traces
                 #(scroll/capture-scroll-handler {:frame :rf/default}
                                                 {:url "/articles/intro"}))]
      (is (= 1 (count tags))
          "the JVM :rf.nav/capture-scroll handler emits exactly one skip trace")
      (is (= :rf.nav/capture-scroll (:rf.fx/id (first tags)))
          "the skip trace carries :rf.fx/id :rf.nav/capture-scroll")
      (is (not (contains? (first tags) :fx-id))
          "no bare :fx-id tag remains")))

  (testing ":rf.nav/push-url's JVM owner-skip trace stamps :rf.fx/id"
    ;; :rf/default is the URL owner in this suite (reset-runtime declares
    ;; {:url-bound? true}); the owner path hits the JVM :clj skip branch
    ;; (history.pushState is browser-only), emitting :rf.fx/skipped-on-platform.
    (let [tags (capture-fx-traces
                 #(nav-fx/push-url-handler {:frame :rf/default} "/articles"))]
      (is (= 1 (count tags))
          "the JVM :rf.nav/push-url handler emits exactly one skip trace")
      (is (= :rf.nav/push-url (:rf.fx/id (first tags)))
          "the skip trace carries :rf.fx/id :rf.nav/push-url")
      (is (not (contains? (first tags) :fx-id))
          "no bare :fx-id tag remains")))

  (testing ":rf.nav/replace-url's JVM owner-skip trace stamps :rf.fx/id"
    (let [tags (capture-fx-traces
                 #(nav-fx/replace-url-handler {:frame :rf/default} "/articles"))]
      (is (= 1 (count tags))
          "the JVM :rf.nav/replace-url handler emits exactly one skip trace")
      (is (= :rf.nav/replace-url (:rf.fx/id (first tags)))
          "the skip trace carries :rf.fx/id :rf.nav/replace-url")
      (is (not (contains? (first tags) :fx-id))
          "no bare :fx-id tag remains")))

  (testing ":rf.nav/push-url's frame-not-url-bound skip trace also stamps :rf.fx/id"
    ;; A non-URL-bound frame skips the history push with :reason
    ;; :frame-not-url-bound — the OTHER routing skip path. It must carry
    ;; the canonical identity tag too.
    (rf/reg-frame :story/variant {})              ;; no :url-bound?
    (let [tags (capture-fx-traces
                 #(nav-fx/push-url-handler {:frame :story/variant} "/articles"))]
      (is (= 1 (count tags))
          "a non-URL-bound frame's :rf.nav/push-url emits exactly one skip trace")
      (is (= :rf.nav/push-url (:rf.fx/id (first tags)))
          "the frame-not-url-bound skip trace carries :rf.fx/id :rf.nav/push-url")
      (is (= :frame-not-url-bound (:reason (first tags)))
          "it is the frame-not-url-bound skip path (not the platform skip)")
      (is (not (contains? (first tags) :fx-id))
          "no bare :fx-id tag remains"))))
