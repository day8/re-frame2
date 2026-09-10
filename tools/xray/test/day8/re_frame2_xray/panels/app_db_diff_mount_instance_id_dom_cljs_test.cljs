(ns day8.re-frame2-xray.panels.app-db-diff-mount-instance-id-dom-cljs-test
  "TWO STANDALONE `mount-app-db-diff!` MOUNTS IN ONE FRAME, read off a real
  React commit (rf2-2n8q).

  ## The claim, and why it needs a DOM

  rf2-t3fz gave `app-db-diff/Panel` an optional `:instance-id` PROP, so a
  caller that RENDERS two panels under one `frame-provider` can name them.
  `panels/mount-app-db-diff!` is the caller that cannot: it takes OPTS, and
  `render-panel!` mounted `[panel-view]` with no props at all — so two
  standalone mounts sharing the default `:frame` had no way to be told
  apart. rf2-2n8q is the opts key that opens that door.

  A row asserting the opts key was THREADED would pass while both mounts
  still collided, which is the failure one level up. So nothing here reads
  the opts map, the captured tree, or the props. Two mounts are made
  through the public facade into two real containers, and every assertion
  below reads `container.querySelector` — the DOM React committed on its
  own — or the widget's own per-mount store.

  ## The two observables, and both are the bug rather than a proxy

  The edn-inspector publishes both ids it composes as DOM attributes on
  each widget's container (`edn_inspector.cljs`, both the chromed and the
  flat branch): `data-rf-mount-id` and `data-rf-site-id`. They are not
  decoration — they are the two keys the defect collides on, and they are
  keyed differently, which is why BOTH are asserted:

    * `mount-id` keys the widget's per-mount store (qualified by frame, as
      `[frame-id mount-id]` — rf2-d2aj) AND, unqualified, the measured
      width slot inside the frame's app-db. Two mounts in ONE frame
      therefore share one store entry, one ResizeObserver and one width
      slot: qualifying the store key alone would be half a repair.
    * `site-id` keys expansion and zoom, so two colliding mounts expand and
      zoom in lockstep.

  ## The negative control IS the defect, and it is a row rather than a note

  [[two-unnamed-standalone-mounts-still-collide]] mounts the same two
  panels with NO `:instance-id` and asserts the id sets are IDENTICAL. It
  carries two claims at once: the instrument can see a collision (so the
  disjointness above is separation and not silence), and omitting the opt
  leaves every id byte-for-byte what it always was — which is every call
  site in this tree today.

  ## Substrate: the Reagent adapter, and the mount is the PUBLIC one

  `mount-app-db-diff!` delegates to `rf.substrate.adapter/render`, so the
  installed adapter is what renders. Nothing here reaches past the facade
  to build a tree of its own — the thing under test is the mount fn.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex also matches, so it LOADS under Node — where every row
  short-circuits through [[browser?]] and reports the skip rather than
  passing silently."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; READ-ONLY, and only for the per-mount store's public test
            ;; surface (`lifecycle-key`, `mount-state-held`). The widget's own
            ;; lifecycle rows live in `views/edn_inspector_mount_state_cljs_test`;
            ;; what W2 below asserts is that two STANDALONE MOUNTS hand that
            ;; store two distinct keys.
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(def ^:private host-frame
  "The ordinary application frame the panel OBSERVES. EP-0002 (rf2-bd4div)
  removed the default, so without selecting one the panel renders its TOP
  section with the empty-state body and no widget mounts at all — which
  would leave every disjointness assertion below comparing two empty sets.
  The `(seq …)` control in each row is what separates that silence from
  real separation, and it is why this frame is seeded with real data."
  :rf/default)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about, and
                      ;; `app-db-diff/Panel` is a Fresco boundary — a
                      ;; neighbour's entry left in the cache would make these
                      ;; rows read a residue that is not this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- setup ---------------------------------------------------------------

(defn- setup!
  "Seed the host frame with real app-db content and point Xray at it.

  `ensure-xray-frame!` is called HERE, before the target selection, and the
  ordering is load-bearing rather than tidy: the first call for a frame-id
  runs the first-mount seed hooks — `::seed-trace-and-target-frame` among
  them — and its run-once guard then makes every later call (each `mount!`
  below) skip the fan-out. Selecting the target BEFORE those hooks had run
  would leave the selection at the mercy of a seed pass firing inside the
  first mount."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id host-frame})
  (rf/replace-frame-state! host-frame
                           {:rf.db/app {:counter 5 :user {:name "ada"}}})
  (mount/ensure-xray-frame! :rf/xray)
  (rf/dispatch-sync [:rf.xray/set-target-frame host-frame] {:frame :rf/xray})
  nil)

;; ---- mounting, and reading the DOM back ----------------------------------

(defn- mount!
  "Mount the app-db panel through the PUBLIC facade with `opts`, committed
  synchronously.

  `react-dom/flushSync` rather than a Reagent queue drain: the panel's view
  is a Fresco boundary, which is not in Reagent's render queue at all, so
  draining that queue commits nothing of this panel's and a row written
  that way reads a container that never moved."
  [opts]
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    (let [unmount (react-dom/flushSync
                    (fn [] (panels/mount-app-db-diff! container opts)))]
      {:container container :unmount unmount})))

(defn- unmount!
  "Tear one mount down inside `flushSync` so React's ref-detach has RUN by
  the time the next line reads the per-mount store. A bare unmount
  schedules it."
  [{:keys [container unmount]}]
  (react-dom/flushSync (fn [] (unmount)))
  (.remove container))

(defn- attr-values
  "Every value of `attr` in `container`'s committed DOM, in document order.
  Reads the DOM React wrote — nothing here reproduces the panel."
  [container attr]
  (->> (.querySelectorAll container (str "[" attr "]"))
       (js/Array.from)
       (array-seq)
       (mapv #(.getAttribute % attr))))

(defn- mount-ids  [container] (attr-values container "data-rf-mount-id"))
(defn- site-ids   [container] (attr-values container "data-rf-site-id"))

;; ===========================================================================
;; W1 — two NAMED standalone mounts compose two disjoint sets of ids
;; ===========================================================================

(deftest two-named-standalone-mounts-compose-disjoint-ids
  (testing "rf2-2n8q — `mount-app-db-diff!` given two different
            `:instance-id`s mounts two panels whose committed DOM carries
            two disjoint sets of `data-rf-mount-id` AND `data-rf-site-id`,
            in the ONE `:rf/xray` frame they both default to."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount! {:instance-id "left"})
            right (mount! {:instance-id "right"})]
        (try
          (let [ids-l  (mount-ids (:container left))
                ids-r  (mount-ids (:container right))
                sites-l (site-ids (:container left))
                sites-r (site-ids (:container right))]
            ;; ---- controls, taken from the target ------------------------
            (is (seq ids-l)
                "control: the left mount committed at least one edn-inspector
                 widget, so an empty intersection below means separation and
                 not an empty-state panel that rendered no widget at all")
            (is (= (count ids-l) (count ids-r))
                "naming an instance changes the ids, never the sections —
                 two mounts of the same panel over the same app-db")
            (is (seq sites-l)
                "control: and the widgets carry a site-id, so the second
                 disjointness assertion is about a populated set too")

            ;; ---- the claim ---------------------------------------------
            (is (nil? (some (set ids-l) ids-r))
                (str "no mount-id survives from one standalone mount to the "
                     "other — the store's lifecycle key and the measured "
                     "width slot are both derived from this string. left="
                     (pr-str ids-l) " right=" (pr-str ids-r)))
            (is (nil? (some (set sites-l) sites-r))
                (str "and no site-id either, so the two expand and zoom "
                     "independently rather than in lockstep. left="
                     (pr-str sites-l) " right=" (pr-str sites-r)))
            (is (every? #(re-find #"/left/" %) ids-l)
                (str "each id carries the name THIS mount was given, rather "
                     "than a per-render nonce or a shared string: "
                     (pr-str ids-l))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W2 — the lifecycle half: detaching one leaves the other whole
;; ===========================================================================

(deftest detaching-one-named-mount-leaves-the-other-whole
  (testing "rf2-2n8q — two named standalone mounts hold two entries in the
            widget's per-mount store, and unmounting one releases ONLY its
            own. Under the shared identity both mounts share one entry, so
            the survivor's read is nil and it is left with a disconnected
            observer on a node still in the document."
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
                         :ref)
              "the left mount holds its own store entry")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r))
                         :ref)
              "and so does the right — two live mounts, two entries, not one")

          (unmount! right)

          (is (nil? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r)))
              "the detached mount is gone")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :ref)
              "and the mount still on screen is untouched — releasing the
               survivor is what the shared key did, and it disconnected an
               observer of a node still in the document")
          (finally
            (unmount! left)))))))

;; ===========================================================================
;; W3 — the negative control: unnamed mounts collide, and are unchanged
;; ===========================================================================

(deftest two-unnamed-standalone-mounts-still-collide
  (testing "rf2-2n8q — the defect verbatim, kept as a row. Two standalone
            mounts with NO `:instance-id` present the SAME ids, which is
            what makes W1's disjointness a measurement rather than a
            coincidence; and the ids they present carry no instance segment
            at all, so every existing single-mount call site composes
            exactly what it always did."
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
                   (mapv #(str "app-db-state/left/"
                               (subs % (count "app-db-state/")))
                         ids-a))
                (str "and a named mount's ids are the UNNAMED ids with the "
                     "caller's name spliced in after the surface prefix — "
                     "which says both halves at once: naming qualifies the id "
                     "without disturbing the surface name inside it, and an "
                     "unnamed mount composes byte-for-byte what it always did. "
                     "unnamed=" (pr-str ids-a) " named=" (pr-str ids-n)))
            (is (= ids-a (mount-ids (:container a)))
                "re-reading the same container is stable — these are
                 identities, not per-render nonces"))
          (finally
            (unmount! named)
            (unmount! b)
            (unmount! a)))))))
