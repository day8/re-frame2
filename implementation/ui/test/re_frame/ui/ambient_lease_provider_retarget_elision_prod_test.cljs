(ns re-frame.ui.ambient-lease-provider-retarget-elision-prod-test
  "rf2-vxgfnd.253 — the PROD (`:advanced`, goog.DEBUG=false) wrapper-selection
  twin for the LEASE wrappers, alongside the sub twin
  `frame-ops-provider-retarget-elision-prod-test`.

  A lease site implicitly captures the AMBIENT frame/incarnation as its resource
  owner, so a lease-bearing view MUST consume the frame context: an ancestor
  `frame-provider` RETARGET (A→B) with `rf=`-equal props must re-render it, ENSURE
  B's owner, and RELEASE A's — otherwise `React.memo` bails the non-consumer,
  the ViewCell keeps A's owner, and B is never ensured.

  Here the emitter's per-view wrapper selection is LIVE (dev collapses every view
  onto `render-dev`, which always consumes context, so a dev fixture cannot
  exercise it):

    - a LEASE-ONLY view (a `lease`, no sub, no `(frame)`) compiles to
      `viewcell/render-leases`;
    - a SUB+LEASE view compiles to `viewcell/render-subs-and-leases`;

  both now consume the frame context. This is the mutation-teeth fixture: removing
  the leading `use-frame-context!` from either wrapper memo-bails the retarget, so
  the re-render / ensure-B / release-A assertions go red.

  Runs ONLY in `:browser-test-prod-elision`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom" :as ReactDOM]
            [re-frame.core :as rf]
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(def ^:private this-ns
  "re-frame.ui.ambient-lease-provider-retarget-elision-prod-test")

(defonce ^:private resource-events (atom []))
(defonce ^:private lease-renders (atom 0))
(defonce ^:private sub-lease-renders (atom 0))

(defview prod-lease-only
  "A `lease`, NO sub, NO `(frame)` → render-leases."
  []
  (ui/lease {:resource ::feed :params {}})
  (let [_ (swap! lease-renders inc)]
    [:output {:data-role "prod-lease-only"} "leased"]))

(defview prod-sub-and-lease
  "A `sub` AND a `lease`, no `(frame)` → render-subs-and-leases."
  []
  (ui/lease {:resource ::feed :params {}})
  (let [n (ui/sub [:retarget/n])
        _ (swap! sub-lease-renders inc)]
    [:output {:data-role "prod-sub-and-lease"} (str n)]))

(defn- install! []
  (reset! resource-events [])
  (rf/reg-sub :retarget/n (fn [db _] (:n db)))
  (rf/reg-resource ::feed
                   {:scope :rf.scope/global
                    :params-schema [:map]}
                   (fn [_ _] {:request {:method :get :url "/ui-lease-retarget"}}))
  ;; Deterministic ensure/release handlers that LOG the owner — the resource
  ;; artefact registers these in the base image; this is a later-image override.
  (rf/reg-event :rf.resource/ensure
                (fn [{:keys [db]} [_ payload]]
                  (swap! resource-events conj [:ensure (:owner payload)])
                  {:db db}))
  (rf/reg-event :rf.resource/release-owner
                (fn [{:keys [db]} [_ payload]]
                  (swap! resource-events conj [:release (:owner payload)])
                  {:db db})))

(defn- make-frame* [id n]
  (rf/make-frame
   {:id id
    :images [(rf/image {:id ::base
                        :select-ns {:include ["**"] :exclude [this-ns]}})
             (rf/image {:id ::overrides
                        :select-ns {:include [this-ns]}})]
    :initial-events [[:rf/set-db {:n n}]]}))

(defn- macrotask []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- settle!
  "Drain the passive resource reconcile effect + the re-frame router's async
  ensure/release dispatches. `ui.test/flush!` is unavailable here — it rides
  React `act`, which is elided from the `:advanced` production React — so this
  awaits real macrotasks (React flushes passive effects, then the router next-tick
  drains) instead."
  []
  (-> (macrotask) (.then macrotask) (.then macrotask)
      (.then macrotask) (.then macrotask) (.then macrotask)))

(defn- lease-cell []
  (some #(when (seq (reactive/resource-reservations %)) %)
        (reactive/current-live-cells)))

(defn- reservation-owner [cell]
  (-> (reactive/resource-reservations cell) vals first :owner))

(defn- ensures  [] (filterv #(= :ensure (first %)) @resource-events))
(defn- releases [] (filterv #(= :release (first %)) @resource-events))

(defn- render-provider! [root frame component]
  ;; Mount the memo component through `ui/raw` + `React/createElement` (the
  ;; proven prod-elision pattern from resource-lease-hook-matrix), scoped by a
  ;; root-template frame-provider whose frame we retarget A→B.
  (ReactDOM/flushSync
   #(ui/render! root [ui/frame-provider {:frame frame}
                      (ui/raw (React/createElement component nil))])))

(deftest lease-only-consumes-context-ensures-b-and-releases-a-on-retarget
  (install!)
  (reset! lease-renders 0)
  (frames/reset-frame-ops-cache!)
  (let [fa (make-frame* :retarget/lease-a 1)
        fb (make-frame* :retarget/lease-b 2)
        container (js/document.createElement "div")
        owner-a* (atom nil)
        renders* (atom 0)]
    (.appendChild js/document.body container)
    (let [root (ui/create-root container {:root-id :retarget/lease-root})]
      (async done
        (-> (js/Promise.resolve)
            ;; Mount under provider A.
            (.then (fn [] (render-provider! root fa prod-lease-only) (settle!)))
            (.then
             (fn []
               (let [cell (lease-cell)
                     owner-a (reservation-owner cell)]
                 (reset! owner-a* owner-a)
                 (reset! renders* @lease-renders)
                 (testing "mount under A ensured exactly A's owner"
                   (is (some? cell) "the lease-only view minted a resource cell")
                   (is (some? owner-a))
                   (is (= 1 (count (ensures))) "one ensure at mount")
                   (is (= owner-a (second (last (ensures)))) "…for A's owner")
                   (is (empty? (releases)) "nothing released yet")))
               ;; Retarget A→B; the child keeps rf=-equal (empty) props.
               (render-provider! root fb prod-lease-only)
               (settle!)))
            (.then
             (fn []
               (let [owner-a @owner-a*
                     owner-b (reservation-owner (lease-cell))]
                 (testing "render-leases re-rendered on the retarget"
                   (is (> @lease-renders @renders*)
                       "the lease-only view reran despite rf=-equal props"))
                 (testing "ownership moved A→B — ensure B, release A, once each"
                   (is (not= owner-a owner-b) "a fresh owner was minted for B")
                   (is (= 2 (count (ensures))) "one further ensure — for B")
                   (is (= owner-b (second (last (ensures)))) "…B's owner")
                   (is (= 1 (count (releases))) "A's owner released exactly once")
                   (is (= owner-a (second (last (releases)))) "…A's owner"))
                 (testing "ensure B is queued BEFORE release A (correct order)"
                   (let [events @resource-events
                         idx-of (fn [pair] (first (keep-indexed
                                                   (fn [i e] (when (= e pair) i)) events)))
                         bi (idx-of [:ensure owner-b])
                         ai (idx-of [:release owner-a])]
                     (is (and (some? bi) (some? ai) (< bi ai))
                         "the fresh ensure precedes the old release"))))))
            (.catch (fn [e] (is false (str "settle failed: " e))))
            (.finally
             (fn []
               (ReactDOM/flushSync #(ui/unmount! root))
               (.remove container)
               (rf/destroy-frame! fa)
               (rf/destroy-frame! fb)
               (done))))))))

(deftest sub-and-lease-consumes-context-repaints-b-and-moves-ownership
  (install!)
  (reset! sub-lease-renders 0)
  (frames/reset-frame-ops-cache!)
  (let [fa (make-frame* :retarget/sl-a 10)
        fb (make-frame* :retarget/sl-b 20)
        container (js/document.createElement "div")
        owner-a* (atom nil)
        renders* (atom 0)
        text (fn [] (some-> (.querySelector container "[data-role='prod-sub-and-lease']")
                            (.-textContent)))]
    (.appendChild js/document.body container)
    (let [root (ui/create-root container {:root-id :retarget/sl-root})]
      (async done
        (-> (js/Promise.resolve)
            (.then (fn [] (render-provider! root fa prod-sub-and-lease) (settle!)))
            (.then
             (fn []
               (reset! owner-a* (reservation-owner (lease-cell)))
               (reset! renders* @sub-lease-renders)
               (testing "mount under A: sub resolves A, lease ensures A"
                 (is (= "10" (text)) "sub resolved A's :retarget/n")
                 (is (= 1 (count (ensures)))))
               (render-provider! root fb prod-sub-and-lease)
               (settle!)))
            (.then
             (fn []
               (let [owner-a @owner-a*
                     owner-b (reservation-owner (lease-cell))]
                 (testing "render-subs-and-leases re-rendered, repainted B, moved ownership"
                   (is (> @sub-lease-renders @renders*) "the view reran")
                   (is (= "20" (text)) "sub repainted B's value, not stale A")
                   (is (not= owner-a owner-b) "lease owner moved A→B")
                   (is (= 2 (count (ensures))) "B ensured")
                   (is (= 1 (count (releases))) "A released once")
                   (is (= owner-a (second (last (releases)))) "…A's owner")))))
            (.catch (fn [e] (is false (str "settle failed: " e))))
            (.finally
             (fn []
               (ReactDOM/flushSync #(ui/unmount! root))
               (.remove container)
               (rf/destroy-frame! fa)
               (rf/destroy-frame! fb)
               (done))))))))
