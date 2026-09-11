(ns day8.re-frame2-xray.panels.managed-fx-mount-dom-cljs-test
  "`mount-managed-fx!` COMMITS THE PANEL TO A REAL DOM (rf2-fcy5).

  ## Why this needs a DOM, and why nothing else could stand in

  rf2-fcy5 migrated `panels/ManagedFxList` from `rf/reg-view` to an
  `rf.fresco/defview` boundary, mounted through the public facade behind
  `panels/ManagedFxList-bridge`. Three things moved at once and NONE of
  them is observable from a hiccup assertion:

    1. The bridge — `rf.fresco/as-component` interoped into the Reagent
       tree `panels/render-panel!` builds. The node lane cannot see it:
       every `panels_mount_cljs_test` row stubs
       `rf.substrate.adapter/render`, so the captured tree is READ and
       never rendered, and the view's body never runs at all.
    2. The boundary's own reads. `rf.fresco/sub` resolves its frame from
       React context, which exists only inside a real render window.
    3. The four `edn/inspect-view` heads inside the template. A plain fn
       or a `reg-view` head in a Fresco body raises
       `:rf.error/fresco-bad-head`, and on this render path there is no
       error boundary above it — React unmounts the whole root, which
       presents as A PANEL THAT NEVER APPEARS rather than as an error.
       That is the rf2-qhoj shape, and rf2-90kv is this very panel
       throwing before painting with no red row anywhere in the tree.

  So the claim under test is the plain one every tier so far has had to
  assume: mounting this panel puts its records on the screen.

  ## And the Xray feature-matrix gate is NOT this row's substitute

  Measured at this slice's base: `rf-xray-managed-fx` appears in no
  testbed, example or scenario file in the repository — `mount-managed-fx!`
  has zero call sites outside `tools/xray` — so no browser scenario mounts
  this panel and none can go red on it. `016-Auxiliary-Panels.md` says as
  much in its own row: \"standalone mount — no current panel embeds it\".
  This file is the browser-lane coverage for the surface, not a second
  opinion about it.

  ## The two observables are the two things that moved

  `data-testid=\"rf-xray-managed-fx-record-…\"` — one per record, written
  by `record-panel`, so its presence says the boundary rendered and the
  composite's `:records` vector reached the renderer.

  `data-rf-mount-id` — stamped by the edn-inspector widget itself on every
  committed container. Its presence says the `edn-inspector-view`
  BOUNDARIES rendered rather than raising, and its DISTINCTNESS across the
  two records is the per-record node-key claim measured rather than
  argued: `edn-widget/inspect-view` hands the node-key straight to the
  boundary as `:mount-id`, and `edn-inspector/container-ref-for` memoises
  the ref callback on it, so two records sharing one would share one
  ResizeObserver entry and one width slot.

  ## Substrate and fixture

  The installed adapter renders, and nothing here builds a tree of its
  own — the thing under test is the public mount fn. The fixture is the
  one `app_db_diff_mount_instance_id_dom_cljs_test` uses, for the same
  reason: `:ambient-frame nil` leaves the Xray reset in sole charge of the
  frame, and Fresco's collector tables are process-global `defonce`s the
  core fixture knows nothing about.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex also matches, so it LOADS under Node — where every row
  short-circuits through [[browser?]] and reports the skip rather than
  passing silently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about, and
                      ;; `ManagedFxList` is a Fresco boundary — a neighbour's
                      ;; entry left in the cache would make these rows read a
                      ;; residue that is not this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the seeded cascade --------------------------------------------------

(def ^:private dispatch-id 600)

(defn- cascade-evs
  "One cascade carrying TWO `:rf.http/managed` invocations.

  Two is what makes the distinctness assertions discriminating, and both
  carry the SAME fx-id deliberately: that is the case the pre-rf2-fcy5
  node-keys could not tell apart, since they were composed from the fx-id
  alone. `record-key` separates them on `:origin-event-id`, which is the
  trace-event id of the fx event and so differs per record."
  []
  [{:id 1 :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:user/load]}}
   {:id 2 :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id 3 :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/1"}
                        :request-id :req-a
                        :on-success [:user/loaded]}}}
   {:id 4 :op-type :rf.fx :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request    {:method :get :url "/api/users/2"}
                        :request-id :req-b
                        :on-success [:user/loaded]}}}])

(defn- records
  "The composite sub's records vector, read the way the boundary reads it."
  []
  (rf/with-frame :rf/xray
    (:records @(rf/subscribe [:rf.xray/managed-fx-for-focused-event]))))

(defn- setup!
  "Seed the trace buffer, focus the cascade, and open every section.

  The sections are opened BEFORE the mount, deliberately: the boundary
  reads the disclosure slot on its FIRST render, so the payload-bearing
  sections are in the very first commit and no row below depends on
  driving a React update from a dispatch."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame! :rf/xray)
  (doseq [ev (cascade-evs)]
    (trace-collector/seed-trace-for-test! ev))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/focus-event dispatch-id :rf/default])
    (doseq [rec  (records)
            sect [:request :response :handler]]
      (rf/dispatch-sync [:rf.xray/managed-fx-toggle-section
                         (h/record-key rec) sect])))
  nil)

;; ---- mounting, and reading the DOM back ----------------------------------

(defn- mount!
  "Mount the managed-fx list through the PUBLIC facade, committed
  synchronously.

  `react-dom/flushSync` rather than a Reagent queue drain: the panel's
  view is a Fresco boundary, which is not in Reagent's render queue at
  all, so draining that queue commits nothing of this panel's and a row
  written that way reads a container that never moved."
  []
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    (let [unmount (react-dom/flushSync
                    (fn [] (panels/mount-managed-fx! container)))]
      {:container container :unmount unmount})))

(defn- unmount!
  [{:keys [container unmount]}]
  (react-dom/flushSync (fn [] (unmount)))
  (.remove container))

(defn- query-all
  "Every element matching `sel` in `container`'s committed DOM, in document
  order. Reads the DOM React wrote — nothing here reproduces the panel."
  [container sel]
  (->> (.querySelectorAll container sel)
       (js/Array.from)
       (array-seq)))

(defn- record-testids [container]
  (mapv #(.getAttribute % "data-testid")
        (query-all container "[data-testid^='rf-xray-managed-fx-record-']")))

(defn- mount-ids [container]
  (mapv #(.getAttribute % "data-rf-mount-id")
        (query-all container "[data-rf-mount-id]")))

;; ===========================================================================
;; W1 — the boundary paints, through the bridge, into a real container
;; ===========================================================================

(deftest mounting-commits-one-record-panel-per-record
  (testing "rf2-fcy5 — `mount-managed-fx!` wraps `ManagedFxList-bridge` in a
            frame-provider and hands it to the adapter; the bridge interops
            to the `as-component` React component; the boundary resolves
            `:rf/xray` from React context and renders. If ANY link in that
            chain is wrong the container is empty rather than wrong, which
            is why this row reads the committed DOM and not a tree."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)]
        ;; CONTROL FIRST, taken from the target: the composite really answers
        ;; two records, so an empty container below is the mount's answer and
        ;; not the seeding's. Without it the two readings are identical.
        (is (= 2 (count (records)))
            "control: the composite answers two records before anything mounts")
        (let [m (mount!)]
          (try
            (let [ids (record-testids (:container m))]
              (is (= 2 (count ids))
                  (str "one record panel per record reached the DOM — "
                       (pr-str ids)))
              (is (= 2 (count (distinct ids)))
                  "and the two are distinct, so this is not one record twice"))
            (finally (unmount! m))))))))

;; ===========================================================================
;; W2 — the edn-inspector BOUNDARIES render, and each record owns its own
;; ===========================================================================

(deftest each-record-owns-its-inspector-mount-ids
  (testing "rf2-fcy5 — with the payload sections open, the `edn/inspect-view`
            heads really rendered rather than raising
            `:rf.error/fresco-bad-head`, which is what a committed
            `data-rf-mount-id` says. A seeded `:rf.fx/handled` carries a
            request and an on-success handler but no response yet, so the
            widgets that commit are REQUEST and HANDLER per record; the row
            counts what is there rather than a fixed number.

            Their `:mount-id`s are derived from `record-key`, so two records
            carrying the SAME fx-id still own disjoint sets — the pre-rf2-fcy5
            keys were composed from the fx-id alone and the HANDLER key was
            the bare constant `\"managed-fx/handler\"`, so every record in
            every list shared one."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            m (mount!)]
        (try
          ;; The expected groups are DERIVED from `record-key` rather than
          ;; spelled out: that is the composer `edn-widget/inspect-view`'s
          ;; node-key is built from, so this row states the claim in the
          ;; renderer's own terms and cannot drift from the seeded trace ids.
          (let [rec-keys (mapv h/record-key (records))
                ids      (mount-ids (:container m))
                groups   (mapv (fn [rk] (filterv #(string/includes? % rk) ids))
                               rec-keys)]
            ;; Controls, taken from the target: widgets committed at all, and
            ;; the two records really do carry different keys. An empty set or
            ;; two equal keys would make the disjointness below vacuous.
            (is (seq ids)
                (str "control: at least one edn-inspector widget committed, so "
                     "the inspector heads are boundaries the codec accepted"))
            (is (= 2 (count (distinct rec-keys)))
                (str "control: the two records carry distinct record-keys — "
                     (pr-str rec-keys)))
            (is (= (count ids) (count (distinct ids)))
                (str "no two committed widgets share a mount-id — " (pr-str ids)))
            (is (every? seq groups)
                (str "each record owns at least one widget — keys "
                     (pr-str rec-keys) " ids " (pr-str ids)))
            (is (= (count ids) (reduce + (map count groups)))
                (str "every committed widget belongs to exactly one record — "
                     (pr-str ids))))
          (finally (unmount! m)))))))
