(ns re-frame.routing-scroll-test
  "Scroll-restoration tests for re-frame.routing (scroll-position
  save/lookup, per-frame isolation, the LRU cap, the `:rf.nav/scroll` fx
  emission, and scroll-strategy resolution precedence). Split from
  routing_test.clj per rf2-u8qe7y finding 3.

  ## Posture split (rf2-o5dbf)

  Scroll restoration proper is production-real and carries no posture guard:
  the save / lookup round-trip, per-frame isolation, the LRU cap and eviction
  order, cache teardown with the frame, the `:rf.nav/scroll` fx EMISSION (what
  the plan puts on `:fx`), and strategy-resolution precedence all run in the
  ordinary `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh`
  (the `-Dre-frame.debug=false` lane).

  The one exception is `nav-fx-skip-traces-use-canonical-rf-fx-id-tag`, which
  is about a TRACE TAG SPELLING — that the four `:rf.nav/*` JVM skip paths
  stamp the canonical `:rf.fx/id` rather than a bare `:fx-id`. Tag spelling is
  a property of `:rf.fx/skipped-on-platform` events, emitted through
  `trace/emit!` behind `rf.interop/debug-enabled?`, so under the real gate there
  are no events to spell anything. Its assertions are kept VERBATIM inside
  `(when rf.interop/debug-enabled? …)` arms marked `rf2-o5dbf`.

  Note the five `(is (not (contains? (first tags) :fx-id)))` legs in
  particular: with no traces `(first tags)` is nil and `(contains? nil :fx-id)`
  is false, so each would pass VACUOUSLY — reporting that the drift was
  removed from a tag map that was never built. Outside the arm each block now
  asserts the always-on CAUSE of the skip instead: the fx's
  `:platforms #{:client}` declaration (registrar state), and for the
  frame-not-url-bound path, that the frame really is not the URL owner."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.nav-fx :as rf.routing.nav-fx]
            [re-frame.routing.nav-fx-schemas :as rf.routing.nav-fx-schemas]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- Spec 012 §Scroll restoration -----------------------------------------

(deftest routing-scroll-metadata-preserved
  (testing "the :scroll metadata key is enumerable via handler-meta"
    ;; Per Spec 012 §Scroll restoration: a route may declare a :scroll
    ;; strategy (:top / :restore / :preserve / false — a CLOSED vocabulary
    ;; since rf2-px26m). Metadata is round-tripped through registration so
    ;; tooling can enumerate it.
    (rf/reg-route :route/home
                  {:scroll :top} "/")
    (rf/reg-route :route/article
                  {:params [:map [:id :string]]
                   :scroll :restore} "/articles/:id")
    (let [home-meta    (rf/handler-meta {:source :store :kind :route :id :route/home})
          article-meta (rf/handler-meta {:source :store :kind :route :id :route/article})]
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
      (rf.fx/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      ;; :rf.nav/push-url is :platforms #{:client} by default; suppress on
      ;; the JVM the same way the other routing tests do.
      (rf.fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))

      ;; 1. Forward navigation to a route with no :scroll metadata —
      ;;    default :top.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/articles}])
      (is (= 1 (count @calls)) "navigate emits exactly one :rf.nav/scroll fx")
      (let [a (first @calls)]
        (is (= :top (:strategy a))
            "default forward strategy is :top")
        (is (= {:id :route/articles} (:to a))
            ":to descriptor identifies the destination"))

      ;; 2. Navigate to a route with :scroll :restore — strategy carries.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "intro"}}])
      (let [a (first @calls)]
        (is (= :restore (:strategy a))
            "route's :scroll :restore wins over the implicit :top default")
        (is (= {:id :route/article :params {:id "intro"}} (:to a))
            ":to carries id + params")
        (is (= {:id :route/articles} (:from a))
            ":from is the previous route slice"))

      ;; 3. Per-call :scroll override in opts trumps route metadata.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "two"} :scroll :preserve}])
      (is (= :preserve (-> @calls first :strategy))
          "opts :scroll wins over route metadata")

      ;; 4. :scroll false on the route suppresses the fx entirely.
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/profile}])
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
          c1 (rf.routing/save-scroll-position c0 "/articles"  [0 250])
          c2 (rf.routing/save-scroll-position c1 "/dashboard" [10 800])]
      (is (= [0 250]  (rf.routing/lookup-scroll-position c2 "/articles"))
          "saved position for /articles is retrievable")
      (is (= [10 800] (rf.routing/lookup-scroll-position c2 "/dashboard"))
          "saved position for /dashboard is retrievable; URLs are isolated")
      (is (nil? (rf.routing/lookup-scroll-position c2 "/unsaved"))
          "an unseen url returns nil — no false positives"))))

(deftest scroll-position-overwrites-on-resave
  (testing "save-scroll-position over an existing url replaces the saved value"
    (let [c1 (rf.routing/save-scroll-position nil "/page" [0 100])
          c2 (rf.routing/save-scroll-position c1  "/page" [0 999])]
      (is (= [0 999] (rf.routing/lookup-scroll-position c2 "/page"))
          "second save overwrites the first under the same url"))))

(deftest scroll-position-per-frame-isolation
  (testing "save-scroll-position is per-frame — the helpers thread through
            each frame's own cache map, so a position saved in one frame's
            cache is invisible from another frame's cache value"
    ;; Two frames' independent caches: each is its own cache map. The
    ;; helpers operate on cache values, so isolation is achieved by
    ;; passing the right frame's cache.
    (let [frame-A (rf.routing/save-scroll-position nil "/shared-url" [0 250])
          frame-B (rf.routing/save-scroll-position nil "/shared-url" [0 999])]
      (is (= [0 250] (rf.routing/lookup-scroll-position frame-A "/shared-url"))
          "frame A's cache carries A's saved position")
      (is (= [0 999] (rf.routing/lookup-scroll-position frame-B "/shared-url"))
          "frame B's cache carries B's saved position — values are not shared")
      (is (nil? (rf.routing/lookup-scroll-position nil "/shared-url"))
          "a fresh cache (third frame, never-saved) returns nil for the same url"))))

(deftest scroll-position-storage-shape
  (testing "save-scroll-position records [x y] under :positions and tracks
            recency under :order (the per-frame transient cache shape, rf2-1hncp2)"
    ;; Pin the cache-map shape. The cache is host-side transient state
    ;; (NOT runtime-db) — nothing reads a [:rf.runtime/routing :scroll-positions]
    ;; path anymore, so the contract is the {:positions :order} cache map.
    (let [c1 (rf.routing/save-scroll-position nil "/x" [5 50])]
      (is (= [5 50] (get-in c1 [:positions "/x"]))
          "the saved [x y] lives under :positions in the cache map")
      (is (= ["/x"] (:order c1))
          ":order tracks the url as the most-recent entry"))))

;; ---- rf2-z2k4k: LRU cap on the scroll-position cache ----------------------
;;
;; Per audit A12: long sessions deep-linking through `/articles/:id`-style
;; routes can grow the per-frame scroll cache unboundedly. It is LRU-bounded
;; at `rf.routing/scroll-positions-cap` (50). Re-saving a known url promotes it
;; to most-recent; saves past the cap evict the LRU entry.

(deftest scroll-position-lru-eviction-past-cap
  (testing "save-scroll-position evicts the least-recently-used url
            once the cap is exceeded; the cap is a soft upper bound, not
            a strict per-call limit"
    ;; Hammer 60 distinct urls. Cap is 50, so the first 10 should be gone
    ;; and the last 50 should remain — in insertion order.
    (let [cache (reduce (fn [c i] (rf.routing/save-scroll-position c (str "/u" i) [i i]))
                        nil
                        (range 60))
          positions (:positions cache)]
      (is (= 50 (count positions))
          "exactly 50 entries remain — the cap holds")
      (is (every? nil? (map #(rf.routing/lookup-scroll-position cache (str "/u" %))
                            (range 10)))
          "the first 10 (LRU) urls are evicted")
      (is (every? some? (map #(rf.routing/lookup-scroll-position cache (str "/u" %))
                             (range 10 60)))
          "the most-recently-saved 50 urls all survive")))

  (testing "re-saving an existing url promotes it to most-recent — it survives
            an eviction wave that would otherwise drop it"
    ;; Insert 50 urls (fills cap). Promote /u0 by re-saving. Insert one more.
    ;; /u1 (now the LRU) should evict; /u0 should survive.
    (let [c0 (reduce (fn [c i] (rf.routing/save-scroll-position c (str "/u" i) [i i]))
                     nil
                     (range 50))
          c1 (rf.routing/save-scroll-position c0 "/u0" [999 999])  ;; promote
          c2 (rf.routing/save-scroll-position c1 "/u50" [50 50])]  ;; force evict
      (is (= [999 999] (rf.routing/lookup-scroll-position c2 "/u0"))
          "the re-saved url survives and carries its new value")
      (is (nil? (rf.routing/lookup-scroll-position c2 "/u1"))
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
    (rf.routing/reset-scroll-cache!)
    (rf.routing/save-scroll-position! :frame/a "/articles" [0 250])
    (rf.routing/save-scroll-position! :frame/b "/articles" [0 999])
    (is (= [0 250] (rf.routing/lookup-scroll-position
                     (rf.routing/frame-scroll-cache :frame/a) "/articles"))
        "frame :frame/a's host cache carries A's saved position")
    (is (= [0 999] (rf.routing/lookup-scroll-position
                     (rf.routing/frame-scroll-cache :frame/b) "/articles"))
        "frame :frame/b's host cache is isolated from A")
    (is (nil? (rf.routing/lookup-scroll-position
                (rf.routing/frame-scroll-cache :frame/never) "/articles"))
        "a never-saved frame returns nil — no cross-frame leakage")))

(deftest scroll-cache-not-in-runtime-db
  (testing "the host cache lives outside runtime-db — a frame's runtime-db
            carries NO scroll-position keys after a capture (rf2-1hncp2)"
    ;; The acceptance point: scroll positions no longer sit under
    ;; [:rf.runtime/routing ...], so they cannot egress to trace/epoch/SSR.
    (rf.routing/reset-scroll-cache!)
    (rf/reg-route :route/home {} "/")
    (rf.fx/reg-fx :rf.nav/scroll        {:platforms #{:server :client}} (fn [_ _] nil))
    (rf.fx/reg-fx :rf.nav/push-url      {:platforms #{:server :client}} (fn [_ _] nil))
    ;; Capture a position for the :rf/default frame via the production fx.
    (rf.fx/reg-fx :rf.nav/capture-scroll {:platforms #{:server :client}}
               (fn [ctx args]
                 ;; mirror the handler but supply an explicit position (no
                 ;; window on the JVM) so the save path is exercised here.
                 (rf.routing/save-scroll-position! (:frame ctx) (:url args) [0 321])))
    (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
    (rf.routing/save-scroll-position! :rf/default "/" [0 321])
    (let [rdb (rf.frame/frame-runtime-db-value :rf/default)]
      (is (nil? (get-in rdb [:rf.runtime/routing :scroll-positions]))
          "runtime-db carries no :scroll-positions key")
      (is (nil? (get-in rdb [:rf.runtime/routing :scroll-positions-order]))
          "runtime-db carries no :scroll-positions-order key"))
    (is (= [0 321] (rf.routing/lookup-scroll-position
                     (rf.routing/frame-scroll-cache :rf/default) "/"))
        "the position is held in the host cache instead")))

(deftest scroll-cache-released-on-frame-destroy
  (testing "destroy-frame! releases the destroyed frame's host scroll cache
            entry via the :routing/on-frame-destroyed! teardown hook"
    (rf.routing/reset-scroll-cache!)
    (rf/make-frame {:id :frame/scrollee})
    (rf.routing/save-scroll-position! :frame/scrollee "/x" [0 100])
    (is (some? (rf.routing/frame-scroll-cache :frame/scrollee))
        "precondition: the frame has a host cache entry")
    (rf.frame/destroy-frame! :frame/scrollee)
    (is (nil? (rf.routing/frame-scroll-cache :frame/scrollee))
        "the frame's scroll cache entry is dropped on destroy — no leak")))

(deftest scroll-restore-end-to-end-across-navigation
  (testing "a captured position is restored on a later :restore navigation
            back to the same url — save/restore survives the storage move"
    ;; Acceptance point 1: no behavioral regression in scroll save/restore.
    (rf.routing/reset-scroll-cache!)
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]
                                  :scroll :restore} "/articles/:id")
    (let [calls (atom [])]
      (rf.fx/reg-fx :rf.nav/scroll {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Seed a saved position for "/articles/intro" in the host cache,
      ;; as a prior visit's capture would have.
      (rf.routing/save-scroll-position! :rf/default "/articles/intro" [0 640])
      ;; Land on home first, then navigate to the :restore article route.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/home}])
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "intro"}}])
      (let [a (first @calls)]
        (is (= :restore (:strategy a))
            "the article route resolves to :restore")
        (is (= [0 640] (:saved-pos a))
            ":saved-pos is read from the host cache and threaded into the fx")))))

;; ---- rf2-g1i5m6: symmetric scroll-cache keys (canonicalise on restore) ----
;;
;; Capture keys the leaving position under the CANONICAL `route-url`
;; reconstruction of the slice (canonical query order, no trailing slash), but
;; restore used to look up under the RAW incoming popstate URL. A history entry
;; the browser holds in a non-canonical spelling (a `/cart/` deep link, a
;; `?b=2&a=1` reordered query) therefore never found its saved position. The
;; restore lookup now canonicalises the resolved target the same way capture
;; does, with a raw-URL fallback for a not-found popstate.

(deftest scroll-restore-canonicalises-non-canonical-popstate-url-rf2-g1i5m6
  (rf/reg-route :route/cart   {} "/cart")
  (rf/reg-route :route/search {:query [:map [:a {:optional true} :string]
                                       [:b {:optional true} :string]]} "/search")
  (rf/reg-route :route/other  {} "/other")
  (let [;; Cache keys built via route-url — exactly the CANONICAL keys capture
        ;; would write for the leaving slice.
        cart-key   (rf.routing/route-url {:to :route/cart :params {} :query {}})            ;; "/cart"
        search-key (rf.routing/route-url {:to :route/search :params {} :query {:a "1" :b "2"}}) ;; canonical order
        cache      (-> nil
                       (rf.routing.scroll/save-scroll-position cart-key   [0 640])
                       (rf.routing.scroll/save-scroll-position search-key [0 810]))
        rdb        {:rf.runtime/routing {:current {:route-id :route/home}}}
        ;; Drive the restore leg exactly as a popstate would: resolve the target
        ;; from the RAW incoming url via match-url, then plan the scroll.
        restore    (fn [raw-url]
                     (let [m (rf.routing/match-url raw-url)
                           {:keys [scroll-fx]}
                           (rf.routing.plan/scroll-plan {:rdb rdb :scroll-cache cache
                                              :route-meta nil :opts {:scroll :restore}
                                              :default-strategy :restore
                                              :route-id (:route-id m) :params (:params m)
                                              :query (:query m) :fragment (:fragment m)
                                              :url raw-url})]
                       (:saved-pos (second scroll-fx))))]
    (testing "a trailing-slash spelling of the same route restores its position"
      (is (= [0 640] (restore "/cart/"))
          "/cart/ (non-canonical) canonicalises to /cart and finds [0 640]"))
    (testing "a reordered-query spelling restores its position"
      (is (= [0 810] (restore "/search?b=2&a=1"))
          "?b=2&a=1 canonicalises to the canonical query order and finds [0 810]"))
    (testing "the canonical spelling still restores (no regression)"
      (is (= [0 640] (restore "/cart")))
      (is (= [0 810] (restore "/search?a=1&b=2"))))
    (testing "a route with no saved position misses on both canonical and raw keys"
      (is (nil? (restore "/other"))
          "/other has no saved position → nil saved-pos"))))

;; ---- scroll-strategy resolution precedence (resolve-scroll-strategy) -----
;;
;; `routing-scroll-fx-emitted-on-navigate` covers opts `:scroll :preserve`
;; winning over meta `:restore`, and meta `:scroll false` suppressing. The
;; UNcovered precedence edge is the asymmetric pair: an opts `:scroll` value
;; must win over a route whose meta declares `:scroll false`
;; (`(some? from-opts)` short-circuits BEFORE the `(false? from-meta)`
;; suppression branch), AND an opts `:scroll false` must suppress even when
;; the route's meta declares a concrete strategy.

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
      (rf.fx/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf.fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      ;; Without an opts override the route's :scroll false suppresses.
      (rf/dispatch-sync [:rf.route/navigate {:to :route/silent}])
      (is (empty? @calls)
          "baseline: route :scroll false suppresses the fx")
      ;; A per-call :scroll :top opt resurrects the fx on a fresh target
      ;; (override wins over the route's :scroll false).
      (reset! calls [])
      (rf/dispatch-sync [:rf.route/navigate {:to :route/silent2 :scroll :top}])
      (is (= :top (-> @calls first :strategy))
          "opts :scroll :top overrides the route's :scroll false → fx emits")))

  (testing "opts :scroll false suppresses even when the route declares a
            concrete :scroll strategy"
    (rf/reg-route :route/loud {:scroll :restore} "/loud")
    (let [calls (atom [])]
      (rf.fx/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf.fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/loud :scroll false}])
      (is (empty? @calls)
          "opts :scroll false suppresses despite the route's :scroll :restore")))

  (testing "rf2-px26m: the planner does not INTERPRET strategies — it carries
            the resolved value to the fx, which owns adjudication (Spec 012:
            'the registered fx interprets the strategy'). An unsupported
            value therefore still reaches the fx args unchanged; what changed
            is that it is now REJECTED there instead of silently ignored.
            This test previously asserted the pass-through as if the map form
            were a supported host-extension — it was not: nothing downstream
            read it. The rejection legs live in routing_nav_fx_schemas_test
            (schema) and routing_nav_fx_schemas_cljs_test (handler + gate)"
    (rf/reg-route :route/custom {:scroll {:behavior :smooth :block :center}} "/custom")
    (let [calls (atom [])]
      (rf.fx/reg-fx :rf.nav/scroll
                 {:platforms #{:server :client}}
                 (fn [_ args] (swap! calls conj args)))
      (rf.fx/reg-fx :rf.nav/push-url
                 {:platforms #{:server :client}}
                 (fn [_ _] nil))
      (rf/dispatch-sync [:rf.route/navigate {:to :route/custom}])
      (is (= {:behavior :smooth :block :center} (-> @calls first :strategy))
          "the resolver neither coerces nor drops an unsupported strategy")
      (is (not (m/validate rf.routing.nav-fx-schemas/scroll-args (first @calls)))
          "…and the args it produced do NOT satisfy the fx's own :schema —
           the value is carried to the boundary that rejects it, not past it"))))

;; ---- rf2-ukv4ck: nav-fx identity trace tags use canonical :rf.fx/id -------
;;
;; The rf.routing/nav fx skip & failure traces stamp the fx identity under the
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
                 #(rf.routing.scroll/scroll-fx-handler nil {:strategy :top}))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the skip's always-on cause.
      (is (= #{:client} (:platforms (rf/handler-meta {:source :store :kind :fx :id :rf.nav/scroll})))
          ":rf.nav/scroll is declared :client-only — that is what makes the JVM skip")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count tags))
            "the JVM :rf.nav/scroll handler emits exactly one skip trace")
        (is (= :rf.nav/scroll (:rf.fx/id (first tags)))
            "the skip trace carries :rf.fx/id :rf.nav/scroll (canonical identity tag)")
        (is (not (contains? (first tags) :fx-id))
            "no bare :fx-id tag remains (drift removed)"))))

  (testing ":rf.nav/capture-scroll's JVM skip-on-platform trace stamps :rf.fx/id"
    (let [tags (capture-fx-traces
                 #(rf.routing.scroll/capture-scroll-handler {:frame :rf/default}
                                                 {:url "/articles/intro"}))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the skip's always-on cause.
      (is (= #{:client} (:platforms (rf/handler-meta {:source :store :kind :fx :id :rf.nav/capture-scroll})))
          ":rf.nav/capture-scroll is declared :client-only")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count tags))
            "the JVM :rf.nav/capture-scroll handler emits exactly one skip trace")
        (is (= :rf.nav/capture-scroll (:rf.fx/id (first tags)))
            "the skip trace carries :rf.fx/id :rf.nav/capture-scroll")
        (is (not (contains? (first tags) :fx-id))
            "no bare :fx-id tag remains"))))

  (testing ":rf.nav/push-url's JVM owner-skip trace stamps :rf.fx/id"
    ;; :rf/default is the URL owner in this suite (reset-runtime declares
    ;; {:url-bound? true}); the owner path hits the JVM :clj skip branch
    ;; (history.pushState is browser-only), emitting :rf.fx/skipped-on-platform.
    (let [tags (capture-fx-traces
                 #(rf.routing.nav-fx/push-url-handler {:frame :rf/default} "/articles"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the skip's always-on cause.
      (is (= #{:client} (:platforms (rf/handler-meta {:source :store :kind :fx :id :rf.nav/push-url})))
          ":rf.nav/push-url is declared :client-only")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count tags))
            "the JVM :rf.nav/push-url handler emits exactly one skip trace")
        (is (= :rf.nav/push-url (:rf.fx/id (first tags)))
            "the skip trace carries :rf.fx/id :rf.nav/push-url")
        (is (not (contains? (first tags) :fx-id))
            "no bare :fx-id tag remains"))))

  (testing ":rf.nav/replace-url's JVM owner-skip trace stamps :rf.fx/id"
    (let [tags (capture-fx-traces
                 #(rf.routing.nav-fx/replace-url-handler {:frame :rf/default} "/articles"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the skip's always-on cause.
      (is (= #{:client} (:platforms (rf/handler-meta {:source :store :kind :fx :id :rf.nav/replace-url})))
          ":rf.nav/replace-url is declared :client-only")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count tags))
            "the JVM :rf.nav/replace-url handler emits exactly one skip trace")
        (is (= :rf.nav/replace-url (:rf.fx/id (first tags)))
            "the skip trace carries :rf.fx/id :rf.nav/replace-url")
        (is (not (contains? (first tags) :fx-id))
            "no bare :fx-id tag remains"))))

  (testing ":rf.nav/push-url's frame-not-url-bound skip trace also stamps :rf.fx/id"
    ;; A non-URL-bound frame skips the history push with :reason
    ;; :frame-not-url-bound — the OTHER routing skip path. It must carry
    ;; the canonical identity tag too.
    (rf/make-frame {:id :story/variant})              ;; no :url-bound?
    (let [tags (capture-fx-traces
                 #(rf.routing.nav-fx/push-url-handler {:frame :story/variant} "/articles"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the OTHER skip path's
      ;; always-on cause — :story/variant is not the URL owner, so the push
      ;; has nothing to push to whatever the posture.
      (is (= :rf/default (rf.routing/url-owner-frame-id))
          ":story/variant is not the URL owner — the frame-not-url-bound skip path")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count tags))
            "a non-URL-bound frame's :rf.nav/push-url emits exactly one skip trace")
        (is (= :rf.nav/push-url (:rf.fx/id (first tags)))
            "the frame-not-url-bound skip trace carries :rf.fx/id :rf.nav/push-url")
        (is (= :frame-not-url-bound (:reason (first tags)))
            "it is the frame-not-url-bound skip path (not the platform skip)")
        (is (not (contains? (first tags) :fx-id))
            "no bare :fx-id tag remains")))))
