(ns day8.re-frame2-xray.panels.managed-fx-mount-instance-id-dom-cljs-test
  "TWO STANDALONE `mount-managed-fx!` MOUNTS IN ONE FRAME, read off a real
  React commit (rf2-5ykm).

  ## The claim, and why it needs a DOM

  rf2-fcy5 migrated this panel's mount to a Fresco boundary and gave each
  record's inspector a per-record node-key, which fixed the collisions
  WITHIN one list. It introduced one ACROSS lists: `mount-managed-fx!`
  delegated with no props, `ManagedFxList-bridge` always passed `{}`, the
  boundary ignored props, and `managed_fx_template`'s four
  `edn/inspect-view` sites derived every node-key from `record-key` and the
  section role alone. So the same focused event mounted into two containers
  under one frame emitted IDENTICAL mount-ids — the per-mount identity the
  Reagent head used to mint was what the migration dropped.

  A row asserting the opts key was THREADED would pass while both mounts
  still collided, which is the failure one level up. So nothing here reads
  the opts map, the captured tree, or the props. Two mounts are made
  through the public facade into two real containers, and every assertion
  below reads `container.querySelector` — the DOM React committed on its
  own — or the widget's own per-mount store.

  ## The two observables, and the SECOND is the one the defect breaks

  `data-rf-mount-id` is stamped by the edn-inspector widget on every
  committed container. It is not decoration: `edn-widget/inspect-view`
  hands the node-key straight to the boundary as `:mount-id`, and that one
  string composes BOTH ids the widget keys on —
  `edn-inspector/container-ref-for` memoises the ref callback on
  `[frame-id mount-id]`, and `inspect-opts` composes
  `:rf.xray.inspect/<node-key>` as the `:panel-id` that keys expansion and
  zoom. (This panel passes no `:site-id`, so the widget's
  `effective-id` falls back to the mount-id — qualifying the node-key
  qualifies both halves at once, which is why there is one qualifier here
  where `app-db-diff` needs two.)

  [[two-named-mounts-each-hold-their-own-observer]] is the half a
  distinctness row would miss. Under the shared identity the SECOND mount
  never installs a ResizeObserver at all (`container-ref-for` hands back
  the memoised callback, whose mount arm is guarded on
  `(nil? (:observer entry))`), and `release-mount!` then tears the SHARED
  entry down when EITHER mount detaches — leaving the survivor on screen
  with no observer and no width updates. So that row asserts the observer
  is held by each mount and STILL held by the survivor afterwards.

  ## The negative control IS the defect, and it is a row rather than a note

  [[two-unnamed-mounts-still-collide]] mounts the same two lists with NO
  `:instance-id` and asserts the id sets are IDENTICAL. It carries two
  claims at once: the instrument can see a collision (so the disjointness
  above is separation and not silence), and omitting the opt leaves every
  id byte-for-byte what it always was — which is every call site in this
  tree today.

  ## Substrate: the Reagent adapter, and the mount is the PUBLIC one

  `mount-managed-fx!` delegates to `rf.substrate.adapter/render`, so the
  installed adapter is what renders. Nothing here reaches past the facade
  to build a tree of its own — the thing under test is the mount fn.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex also matches, so it LOADS under Node — where every row
  short-circuits through [[browser?]] and reports the skip rather than
  passing silently. A green node lane is therefore NOT evidence about this
  file; `npm run test:browser` is."
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
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            ;; READ-ONLY, and only for the per-mount store's public test
            ;; surface (`lifecycle-key`, `mount-state-held`). The widget's own
            ;; lifecycle rows live in `views/edn_inspector_mount_state_cljs_test`;
            ;; what W2 below asserts is that two STANDALONE MOUNTS hand that
            ;; store two distinct keys, each with its own observer.
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

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

(def ^:private dispatch-id 620)

(defn- cascade-evs
  "One cascade carrying two `:rf.http/managed` invocations — the same shape
  `managed_fx_mount_dom_cljs_test` seeds, so the two files disagree about
  nothing but how many containers they mount it into."
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
  "Seed the trace buffer, focus the cascade, and open the payload-bearing
  sections BEFORE anything mounts, so the inspector widgets are in the very
  first commit of each container.

  The disclosure slot is keyed by `record-key` and lives in the frame's
  app-db, which both mounts share DELIBERATELY: this bead qualifies the
  inspector's own identities and leaves record identity and disclosure
  semantics exactly where they were. Two lists of the same records open and
  close together; what they no longer share is a ResizeObserver."
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
  "Mount the managed-fx list through the PUBLIC facade with `opts`,
  committed synchronously.

  `react-dom/flushSync` rather than a Reagent queue drain: the panel's view
  is a Fresco boundary, which is not in Reagent's render queue at all, so
  draining that queue commits nothing of this panel's and a row written
  that way reads a container that never moved."
  [opts]
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    (let [unmount (react-dom/flushSync
                    (fn [] (panels/mount-managed-fx! container opts)))]
      {:container container :unmount unmount})))

(defn- unmount!
  "Tear one mount down inside `flushSync` so React's ref-detach has RUN by
  the time the next line reads the per-mount store. A bare unmount
  schedules it."
  [{:keys [container unmount]}]
  (react-dom/flushSync (fn [] (unmount)))
  (.remove container))

(defn- mount-ids
  "Every `data-rf-mount-id` in `container`'s committed DOM, in document
  order. Reads the DOM React wrote — nothing here reproduces the panel."
  [container]
  (->> (.querySelectorAll container "[data-rf-mount-id]")
       (js/Array.from)
       (array-seq)
       (mapv #(.getAttribute % "data-rf-mount-id"))))

(def ^:private id-prefix
  "What `edn-widget/inspect-view` puts in front of every node-key. Named so
  W3's splice assertion states the qualifier's position rather than a
  literal."
  "rf-xray-inspect-managed-fx/")

;; ===========================================================================
;; W1 — two NAMED standalone mounts compose two disjoint sets of ids
;; ===========================================================================

(deftest two-named-mounts-compose-disjoint-inspector-ids
  (testing "rf2-5ykm — `mount-managed-fx!` given two different
            `:instance-id`s mounts two lists whose committed DOM carries two
            disjoint sets of `data-rf-mount-id`, in the ONE `:rf/xray` frame
            they both default to. Each id composes the widget's lifecycle
            key AND its `:panel-id`, so disjointness here is disjointness of
            both."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount! {:instance-id "left"})
            right (mount! {:instance-id "right"})]
        (try
          (let [ids-l (mount-ids (:container left))
                ids-r (mount-ids (:container right))]
            ;; ---- controls, taken from the target ------------------------
            (is (seq ids-l)
                "control: the left mount committed at least one edn-inspector
                 widget, so an empty intersection below means separation and
                 not an empty list that rendered no widget at all")
            (is (= (count ids-l) (count ids-r))
                "naming an instance changes the ids, never the records — two
                 mounts of the same focused event over the same records")

            ;; ---- the claim ---------------------------------------------
            (is (nil? (some (set ids-l) ids-r))
                (str "no mount-id survives from one standalone mount to the "
                     "other — the store's lifecycle key, the measured width "
                     "slot and the expansion/zoom panel-id are all derived "
                     "from this string. left=" (pr-str ids-l)
                     " right=" (pr-str ids-r)))
            (is (every? #(string/starts-with? % (str id-prefix "left/")) ids-l)
                (str "each id carries the name THIS mount was given, rather "
                     "than a per-render nonce or a shared string: "
                     (pr-str ids-l))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W2 — the lifecycle half: each mount owns an observer, and keeps it
;; ===========================================================================

(deftest two-named-mounts-each-hold-their-own-observer
  (testing "rf2-5ykm — two named standalone mounts hold two entries in the
            widget's per-mount store, each with its OWN ResizeObserver, and
            unmounting one releases ONLY its own. Under the shared identity
            the second mount never installs an observer at all and
            `release-mount!` tears the shared entry down when either
            detaches, so the survivor is left on screen unobserved — which a
            distinctness row alone cannot see."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount! {:instance-id "left"})
            right (mount! {:instance-id "right"})
            id-l  (first (mount-ids (:container left)))
            id-r  (first (mount-ids (:container right)))]
        (try
          (is (some? id-l)
              "control: the left mount committed a widget, so the store keys
               below name something that really mounted")
          (is (not= id-l id-r)
              "the two mounts composed two mount-ids")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "the left mount installed its own ResizeObserver")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r))
                         :observer)
              "and so did the right — two live mounts, two observers. Under
               the shared identity the second element's ref callback finds an
               observer already on the entry and installs none")

          (unmount! right)

          (is (nil? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r)))
              "the detached mount is gone")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "and the mount STILL ON SCREEN is still observed — releasing
               the survivor's entry is what the shared key did, and it left a
               live node with no observer and no width updates")
          (finally
            (unmount! left)))))))

;; ===========================================================================
;; W3 — the negative control: unnamed mounts collide, and are unchanged
;; ===========================================================================

(deftest two-unnamed-mounts-still-collide
  (testing "rf2-5ykm — the defect verbatim, kept as a row. Two standalone
            mounts with NO `:instance-id` present the SAME ids, which is what
            makes W1's disjointness a measurement rather than a coincidence;
            and the ids they present carry no instance segment at all, so
            every existing single-mount call site composes exactly what it
            always did."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            a     (mount! nil)
            b     (mount! {})
            named (mount! {:instance-id "left"})]
        (try
          (let [ids-a (mount-ids (:container a))
                ids-b (mount-ids (:container b))
                ids-n (mount-ids (:container named))]
            (is (seq ids-a)
                "control: both unnamed mounts committed widgets")
            (is (= ids-a ids-b)
                (str "two unnamed mounts present the SAME ids — so the "
                     "instrument W1 uses CAN see a collision, and its "
                     "disjointness is a measurement rather than silence. a="
                     (pr-str ids-a)))
            (is (= ids-n
                   (mapv #(str id-prefix "left/" (subs % (count id-prefix)))
                         ids-a))
                (str "and a named mount's ids are the UNNAMED ids with the "
                     "caller's name spliced in after the panel's own prefix "
                     "— which says both halves at once: naming qualifies the "
                     "id without disturbing the record key inside it, and an "
                     "unnamed mount composes byte-for-byte what it always "
                     "did. unnamed=" (pr-str ids-a) " named=" (pr-str ids-n)))
            (is (= ids-a (mount-ids (:container a)))
                "re-reading the same container is stable — these are
                 identities, not per-render nonces"))
          (finally
            (unmount! named)
            (unmount! b)
            (unmount! a)))))))
