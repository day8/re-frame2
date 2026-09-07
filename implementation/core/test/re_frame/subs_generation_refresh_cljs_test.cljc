(ns re-frame.subs-generation-refresh-cljs-test
  "rf2-4lp1 — a frame's cached subscriptions REFRESH when its resolved image
  generation changes.

  ## The defect

  `re-frame.live-frame/make-frame` against an EXISTING `:id` is the supported
  image hot-reload verb (EP-0023 §Hot Reload, rf2-lxwpob retired
  `reload-images!` in its favour): it seals a fresh generation and installs it
  via `rf.frame/upsert-frame!`'s surgical-update path, preserving durable frame
  state — the `:sub-cache` atom among it. The automatic `reg-*` reprojection
  path (`reproject-live-frame!` / `reproject-live-frames!`) swaps a generation
  in place through `rf.frame/set-generation!` for the same reason.

  Both preserved the sub-cache and neither invalidated ANY of it. A query that
  was already materialised stayed a cache HIT — `subscribe-in-frame`'s hit
  branch bumps the ref-count and returns the cached reaction without ever
  comparing that entry against the current generation — so it kept running the
  OLD generation's body. Image replacement (and Story behaviour replacement)
  appeared not to take effect until the caller knew to call `clear-sub-cache!`
  by hand, which is exactly the ceremony EP-0023 §Hot Reload says a reload must
  not require.

  The second face of the same gap is a LATE dependency: a parent sub declaring
  an input that is not registered yet resolves that input to a nil-yielding
  reaction, and the miss is deliberately not cached (rf2-l9u5). But the PARENT
  is cached, holding the nil-yielding input by closure — so first-registering
  the missing input, which reprojects the frame's generation, left the cached
  parent permanently nil while `compute-sub` inside the frame's resolution
  returned the real value.

  ## The repair under test

  `re-frame.frame` now fires a generation-change hook from the TWO (and only
  two) writers of the `:generation` slot — `set-generation!` and
  `upsert-frame!`'s re-registration branch — and `re-frame.subs.cache` installs
  `invalidate-subs-on-generation-change!` on it. That handler diffs the two
  generations with the already-public `re-frame.live-frame/generation-diff`,
  keeps the `:sub` `[kind id]` pairs that were `:added` / `:changed` /
  `:removed`, and evicts exactly those slots plus their transitive
  declared-input dependent closure from THAT frame's cache — the same
  `transitive-dependent-closure` + dispose machinery the `reg-sub` replacement
  hook already uses. `:retained` registrations (present in both generations
  with an `=` descriptor — an unchanged sub merely selected by a different
  image composition) are NOT evicted, so unchanged entries keep their identity
  and ref-counts.

  ## Posture split (rf2-d2841)

  Every assertion here is posture-independent: it holds in the ordinary
  `clojure -M:test` suite AND under the real production gate
  (`scripts/test-core-prod-gate.sh`, `-Dre-frame.debug=false`). Nothing here
  observes the `:trace` stream, and the invalidation seam itself is deliberately
  NOT behind `rf.interop/debug-enabled?` — a correctness fix hung off the trace
  surface would DCE out of release bundles.

  `.cljc` — runs under both `clojure -M:test` (JVM) and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- constant-sub-image
  "An image whose only registration is an inline layer-1 `:reg-sub` under `id`
  returning the constant `value`. The inline descriptor goes through the REAL
  normalize + lower path when the frame's generation is sealed."
  [image-id id value]
  (rf.image/image {:id            image-id
                 :registrations {:reg-sub [[id (fn [_db _q] value)]]}}))

(defn- install!
  "Create — or, on a repeat call with the same `frame-id`, SURGICALLY REPLACE the
  generation of — a runnable frame sealed from `image`. The empty descriptor
  pool keeps the generation to the image's OWN inline registrations, so the
  live source store cannot contaminate the reading."
  [frame-id image]
  (rf.live-frame/make-frame {:id frame-id :images [image]} []))

(defn- cache-keys
  [frame-id]
  (set (keys @(:sub-cache (rf.frame/frame frame-id)))))

(defn- ref-count
  [frame-id query-v]
  (get-in @(:sub-cache (rf.frame/frame frame-id)) [query-v :ref-count]))

;; ---- 1. same-id re-construction refreshes a changed sub -------------------

(deftest same-id-remake-refreshes-a-changed-inline-sub
  (testing "a same-id make-frame that changes an inline sub body is observed by
            the next subscribe, with NO clear-sub-cache!"
    (install! :gen/frame (constant-sub-image :gen/v1 :gen/value 1))
    (let [r1 (rf.subs/subscribe [:gen/value] {:frame :gen/frame})]
      (is (= 1 @r1) "generation 1 yields the first body's value")

      ;; The supported image hot-reload verb: re-call make-frame on the SAME id
      ;; with a new image. Durable frame state (the sub-cache atom) is preserved
      ;; by design; the ENTRY for the changed sub must not be.
      (install! :gen/frame (constant-sub-image :gen/v2 :gen/value 2))

      (let [r2 (rf.subs/subscribe [:gen/value] {:frame :gen/frame})]
        (is (= 2 @r2)
            "THE BUG (rf2-4lp1): the next subscribe must resolve the NEW
             generation's body — pre-fix this read 1, because the hit branch
             returned the cached reaction built against generation 1")
        (is (not (identical? r1 r2))
            "a sub whose definition CHANGED does not keep its reaction identity
             (the bead explicitly does not require preserving it)")))

    ;; `subscribe-once` reads through the same seam.
    (is (= 2 (rf.subs/subscribe-once [:gen/value] {:frame :gen/frame}))
        "subscribe-once sees the new generation too")))

(deftest same-id-remake-refreshes-a-changed-declared-input-parent
  (testing "a cached PARENT rebuilds when the child it declares as an input changes"
    (let [image-1 (rf.image/image
                    {:id            :gen/p1
                     :registrations {:reg-sub [[:gen/child (fn [_db _q] 1)]
                                               [:gen/parent
                                                {:inputs [[:gen/child]]}
                                                (fn [[c] _q] (* 10 c))]]}})
          image-2 (rf.image/image
                    {:id            :gen/p2
                     :registrations {:reg-sub [[:gen/child (fn [_db _q] 5)]
                                               [:gen/parent
                                                {:inputs [[:gen/child]]}
                                                (fn [[c] _q] (* 10 c))]]}})]
      (install! :gen/pframe image-1)
      (is (= 10 @(rf.subs/subscribe [:gen/parent] {:frame :gen/pframe}))
          "generation 1: parent derives from the first child body")

      (install! :gen/pframe image-2)
      (is (= 50 @(rf.subs/subscribe [:gen/parent] {:frame :gen/pframe}))
          "THE BUG (rf2-4lp1): the parent's TRANSITIVE dependent closure must be
           evicted too — pre-fix the cached parent kept the generation-1 child
           reaction by closure and stayed 10"))))

;; ---- 2. a first-registered late input reaches a cached parent -------------

(deftest first-registered-late-input-reaches-a-cached-parent
  (testing "first-registering a previously-missing declared input refreshes the
            already-cached parent that resolved it to nil"
    ;; A DEFAULT-image frame: its generation reprojects off the live source
    ;; store, so a later `reg-sub` moves it.
    (rf/reg-sub :gen/parent-late {:inputs [[:gen/late]]} (fn [[x] _q] x))
    (rf.live-frame/make-frame {:id :gen/lframe})

    (let [r1 (rf.subs/subscribe [:gen/parent-late] {:frame :gen/lframe})]
      (is (nil? @r1)
          "the missing input resolves to a nil-yielding reaction (rf2-l9u5); the
           MISS is not cached but the PARENT is")
      (is (contains? (cache-keys :gen/lframe) [:gen/parent-late])
          "the parent IS cached, holding the nil-yielding input by closure"))

    ;; First registration of the missing input. This fires no registrar
    ;; REPLACEMENT hook (there is nothing to replace) — but it DOES dirty the
    ;; live-frame projection, so the frame's generation moves on the next read.
    (rf/reg-sub :gen/late (fn [_db _q] 7))

    (is (= 7 @(rf.subs/subscribe [:gen/parent-late] {:frame :gen/lframe}))
        "THE BUG (rf2-4lp1), second face: the :added registration must evict the
         cached parent that declares it as an input — pre-fix the parent stayed
         nil while compute-sub inside the frame's resolution returned 7")))

;; ---- 3. unaffected entries and other frames are UNTOUCHED -----------------

(deftest unchanged-entries-keep-identity-and-ref-counts
  (testing "a generation change evicts ONLY affected entries: an unchanged sub
            keeps its reaction identity and its ref-count"
    ;; `:gen/stable` lives in a SHARED image value carried into BOTH
    ;; compositions, so its resolved descriptor is byte-identical across the
    ;; swap and `generation-diff` classes it `:retained`. (Re-declaring the
    ;; same body inline in two SEPARATE images would not be retained, and
    ;; correctly so: the lowered descriptors differ on
    ;; `:rf.provenance/image`, which is a real difference in where the
    ;; registration came from — measured, not assumed.)
    (let [shared  (rf.image/image
                    {:id            :gen/shared
                     :registrations {:reg-sub [[:gen/stable (fn [_db _q] :stable)]]}})
          image-1 (rf.image/image
                    {:id            :gen/s1
                     :registrations {:reg-sub [[:gen/moving (fn [_db _q] :before)]]}})
          image-2 (rf.image/image
                    {:id            :gen/s2
                     :registrations {:reg-sub [[:gen/moving (fn [_db _q] :after)]]}})
          install-pair! (fn [moving-image]
                          (rf.live-frame/make-frame
                            {:id :gen/sframe :images [shared moving-image]} []))]
      (install-pair! image-1)
      (let [moving-1 (rf.subs/subscribe [:gen/moving] {:frame :gen/sframe})
            stable-1 (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe})]
        ;; A second holder, so the ref-count is observably 2 rather than 1.
        (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe})
        (is (= :before @moving-1))
        (is (= :stable @stable-1))
        (is (= 2 (ref-count :gen/sframe [:gen/stable]))
            "two holders → ref-count 2 before the swap")

        (install-pair! image-2)

        (is (= 2 (ref-count :gen/sframe [:gen/stable]))
            "the RETAINED entry's ref-count survives the generation change
             untouched — the invalidation is targeted, not a cache clear")
        (is (identical? stable-1
                        (rf.subs/subscribe [:gen/stable] {:frame :gen/sframe}))
            "the RETAINED entry keeps its reaction IDENTITY")
        (is (not (contains? (cache-keys :gen/sframe) [:gen/moving]))
            "the CHANGED entry was evicted")
        (is (= :after @(rf.subs/subscribe [:gen/moving] {:frame :gen/sframe}))
            "and rebuilds against the new generation")))))

(deftest other-frames-are-untouched-by-a-generation-change
  (testing "swapping frame A's generation does not disturb frame B's cache"
    (install! :gen/a (constant-sub-image :gen/a1 :gen/value 1))
    (install! :gen/b (constant-sub-image :gen/b1 :gen/value 100))
    (let [a1 (rf.subs/subscribe [:gen/value] {:frame :gen/a})
          b1 (rf.subs/subscribe [:gen/value] {:frame :gen/b})]
      (is (= 1 @a1))
      (is (= 100 @b1))

      (install! :gen/a (constant-sub-image :gen/a2 :gen/value 2))

      (is (identical? b1 (rf.subs/subscribe [:gen/value] {:frame :gen/b}))
          "frame B's entry — same query-v, different frame — keeps its identity")
      (is (= 100 @b1) "and its value")
      (is (= 2 @(rf.subs/subscribe [:gen/value] {:frame :gen/a}))
          "while frame A refreshed"))))

;; ---- 4. durable frame state survives -------------------------------------

(deftest durable-frame-state-survives-the-refresh
  (testing "the app-db a frame accumulated is preserved across the generation
            change the invalidation rides on"
    (let [image-of (fn [image-id n]
                     (rf.image/image
                       {:id            image-id
                        :registrations {:reg-event [[:gen/bump
                                                     (fn [{:keys [db]} _]
                                                       {:db (assoc db :bumped true)})]]
                                        :reg-sub   [[:gen/db-read
                                                     (fn [db _q] [n (:bumped db)])]]}}))]
      (install! :gen/dframe (image-of :gen/d1 1))
      (rf/dispatch-sync [:gen/bump] {:frame :gen/dframe})
      (is (= [1 true] @(rf.subs/subscribe [:gen/db-read] {:frame :gen/dframe})))

      (install! :gen/dframe (image-of :gen/d2 2))
      (is (= [2 true] @(rf.subs/subscribe [:gen/db-read] {:frame :gen/dframe}))
          "the sub body is the NEW generation's; the app-db is the SAME frame's
           durable state (`:bumped` survived)"))))

;; ---- 5. a removed registration releases exactly its own refs -------------

(deftest a-removed-sub-is-evicted-and-recovers-as-a-miss
  (testing "a registration REMOVED by the new generation is evicted, and the
            next subscribe takes the ordinary no-such-sub recovery"
    (let [image-1 (rf.image/image
                    {:id            :gen/r1
                     :registrations {:reg-sub [[:gen/kept  (fn [_db _q] :kept)]
                                               [:gen/gone  (fn [_db _q] :here)]]}})
          image-2 (rf.image/image
                    {:id            :gen/r2
                     :registrations {:reg-sub [[:gen/kept  (fn [_db _q] :kept)]]}})]
      (install! :gen/rframe image-1)
      (is (= :here @(rf.subs/subscribe [:gen/gone] {:frame :gen/rframe})))
      (is (contains? (cache-keys :gen/rframe) [:gen/gone]))

      (install! :gen/rframe image-2)

      (is (not (contains? (cache-keys :gen/rframe) [:gen/gone]))
          "the REMOVED registration's slot is evicted")
      (is (nil? @(rf.subs/subscribe [:gen/gone] {:frame :gen/rframe}))
          "and the next subscribe is an honest no-such-sub miss, not a stale hit"))))
