(ns day8.re-frame2-xray.panels.trace-mount-instance-id-dom-cljs-test
  "TWO STANDALONE `mount-trace!` MOUNTS IN ONE FRAME, WITH MATCHING ROWS
  EXPANDED, read off a real React commit (rf2-pua3).

  ## The claim, and why it needs a DOM

  rf2-fcy5 migrated this panel's mount to a Fresco boundary and swapped its
  inline payload head to `ei/edn-inspector-view` with a PER-ROW
  `:mount-id`, which fixed the collisions WITHIN one panel — two rows
  expanded at once each keep their own lifecycle. It left one ACROSS
  panels: `mount-trace!` delegated with no props, `Panel-bridge` always
  passed `{}`, the boundary ignored props, and `render-payload` derived the
  `:mount-id` from the row id ALONE. So the same focused epoch mounted into
  two containers under one frame emitted IDENTICAL mount-ids for matching
  expanded rows.

  A row asserting the opts key was THREADED would pass while both mounts
  still collided, which is the failure one level up. So nothing here reads
  the opts map, the captured tree, or the props. Two mounts are made
  through the public facade into two real containers, and every assertion
  reads `container.querySelector` — the DOM React committed on its own —
  the widget's own per-mount store, or the frame's app-db width slot.

  ## The two observables, and the SECOND is the one the defect breaks

  `data-rf-mount-id` is stamped by the edn-inspector widget on every
  committed container, carrying the BARE `:mount-id` this panel composed.
  It is not decoration: `edn-inspector/container-ref-for` memoises the ref
  callback on `[frame-id mount-id]`, `measure-and-dispatch!` writes the
  measured width to `:rf.xray.edn-inspector/widths` under that same bare
  string, and `release-mount!` clears BOTH when a mount detaches.

  [[two-named-mounts-each-keep-their-own-observer-and-width]] is the half a
  distinctness row would miss. Under the shared identity the SECOND mount
  never installs a ResizeObserver at all (`container-ref-for` hands back
  the memoised callback, whose mount arm is guarded on
  `(nil? (:observer entry))`), and `release-mount!` then tears the SHARED
  entry down — and clears the SHARED width slot — when EITHER mount
  detaches, leaving the survivor on screen with no observer and no width.
  So that row asserts both are held by each mount and STILL held by the
  survivor afterwards.

  ## WHAT THIS PANEL DELIBERATELY DOES NOT QUALIFY, and why it is ONE
  ## qualifier here rather than the two `app-db-diff` needs

  Trace passes a stable `:site-id` (`[:rf.xray.trace/row <id>]`, rf2-pvsxs)
  alongside its `:mount-id`, and the widget's `effective-id` is
  `(or site-id mount-id)` — so expansion and zoom are keyed
  `[panel-id site-id path]` and DO NOT READ THE MOUNT-ID AT ALL. The
  logical disclosure identity is therefore already separate from the
  physical one, and qualifying the mount-id moves the lifecycle key and the
  width slot while leaving expansion, zoom and the row's own testids
  byte-for-byte where they were. That is the whole of the fix, and
  [[two-named-mounts-share-their-disclosure-identity]] pins the half that
  must NOT move: two lists of the same rows open and close together, and
  what they no longer share is a ResizeObserver.

  (`app-db-diff` needs two qualifiers because `value-body` composes its
  `:mount-id` and its `:site-id` separately; `managed-fx` needs one because
  `edn-widget/inspect-view` builds the `:mount-id` AND the `:panel-id` from
  one node-key and passes no `:site-id`. This panel is a third shape.)

  ## The negative control IS the defect, and it is a row rather than a note

  [[two-unnamed-mounts-still-collide]] mounts the same two panels with NO
  `:instance-id` and asserts the id sets are IDENTICAL. It carries two
  claims at once: the instrument can see a collision (so the disjointness
  above is separation and not silence), and omitting the opt leaves every
  id byte-for-byte what it always was — which is every call site in this
  tree today.

  ## Substrate: the Reagent adapter, and the mount is the PUBLIC one

  `mount-trace!` delegates to `rf.substrate.adapter/render`, so the
  installed adapter is what renders. Nothing here reaches past the facade
  to build a tree of its own — the thing under test is the mount fn.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium) per
  `implementation/shadow-cljs.edn`. The `:node-test` build's `cljs-test$`
  regex also matches, so it LOADS under Node — where every row
  short-circuits through [[browser?]] and reports the skip rather than
  passing silently. A green node lane is therefore NOT evidence about this
  file; `npm run test:browser` is. The node lane cannot see a
  ResizeObserver at all."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
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
            ;; surface (`lifecycle-key`, `mount-state-held`) and the public
            ;; width-slot key. The widget's own lifecycle rows live in
            ;; `views/edn_inspector_mount_state_cljs_test`; what W2 below
            ;; asserts is that two STANDALONE MOUNTS hand that store two
            ;; distinct keys, each with its own observer and its own width.
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about, and
                      ;; `trace/Panel` is a Fresco boundary — a neighbour's
                      ;; entry left in the cache would make these rows read a
                      ;; residue that is not this panel's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the seeded epoch ----------------------------------------------------

(def ^:private dispatch-id 11)

(def ^:private expanded-row-ids
  "The two rows both mounts expand. TWO rather than one so the id SETS
  below are sets rather than singletons — a splice that dropped the row id
  would still pass with one."
  [101 202])

(defn- mk-trace [id operation time]
  {:id        id
   :time      time
   :op-type   :rf.event
   :operation operation
   :tags      {:rf.trace/dispatch-id dispatch-id}})

(def ^:private two-row-epoch
  "A minimal `:rf/epoch-record` carrying two `trace-events`, shaped exactly
  as `trace_fresco_boundary_dom_cljs_test` seeds one."
  {:epoch-id      1
   :dispatch-id   dispatch-id
   :event-id      :test/event
   :trigger-event [:test/event]
   :db-before     {}
   :db-after      {}
   :renders       []
   :sub-runs      []
   :committed-at  1000
   :trace-events  [(mk-trace 101 :rf.event/dispatched 100)
                   (mk-trace 202 :rf.event/handler-ran 200)]})

(defn- setup!
  "Seed the epoch ring, pin focus at its cascade, and EXPAND both rows
  BEFORE anything mounts, so the payload inspectors are in the very first
  commit of each container.

  The expansion slot lives in the frame's app-db and both mounts share it
  DELIBERATELY: this bead qualifies the inspector's PHYSICAL identity and
  leaves the logical disclosure identity exactly where it was. Two panels
  showing the same epoch open and close together — which is also what makes
  the rows MATCHING, and therefore what makes the collision reachable."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame! :rf/xray)
  (rf/dispatch-sync [:rf.xray/sync-epoch-history [two-row-epoch]]
                    {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/focus-event dispatch-id nil] {:frame :rf/xray})
  (doseq [id expanded-row-ids]
    (rf/dispatch-sync [:rf.xray/toggle-trace-row-expand id] {:frame :rf/xray}))
  nil)

;; ---- mounting, and reading the DOM back ----------------------------------

(defn- mount!
  "Mount the Trace panel through the PUBLIC facade with `opts`, committed
  synchronously.

  `react-dom/flushSync` rather than a Reagent queue drain: the panel's view
  is a Fresco boundary, which is not in Reagent's render queue at all, so
  draining that queue commits nothing of this panel's and a row written
  that way reads a container that never moved."
  [opts]
  (let [container (.createElement js/document "div")]
    (.appendChild (.-body js/document) container)
    (let [unmount (react-dom/flushSync
                    (fn [] (panels/mount-trace! container opts)))]
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

(defn- site-ids
  "Every `data-rf-site-id` in `container`'s committed DOM. The LOGICAL
  identity, which this bead must leave alone."
  [container]
  (->> (.querySelectorAll container "[data-rf-site-id]")
       (js/Array.from)
       (array-seq)
       (mapv #(.getAttribute % "data-rf-site-id"))))

(defn- widths
  "The frame's measured-width slot — a map of bare `mount-id` → px."
  []
  (or (rf/subscribe-once [ei/widths-slot] {:frame :rf/xray}) {}))

(def ^:private id-prefix
  "What `render-payload` puts in front of every payload mount-id. Named so
  W3's splice assertion states the qualifier's POSITION rather than a
  literal."
  "rf-xray-trace-row-")

;; ===========================================================================
;; W1 — two NAMED standalone mounts compose two disjoint sets of mount-ids
;; ===========================================================================

(deftest two-named-mounts-compose-disjoint-inspector-mount-ids
  (testing "rf2-pua3 — `mount-trace!` given two different `:instance-id`s
            mounts two panels whose committed DOM carries two disjoint sets
            of `data-rf-mount-id`, in the ONE `:rf/xray` frame they both
            default to. Each id composes the widget's lifecycle key AND its
            measured-width slot key, so disjointness here is disjointness of
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
            (is (some? (.querySelector (:container left)
                                       "[data-testid=\"rf-xray-trace-row-101-payload\"]"))
                "control: the left mount committed an EXPANDED row payload, so
                 an empty intersection below means separation and not a panel
                 that rendered its rows collapsed and mounted no inspector")
            (is (= 2 (count ids-l))
                (str "control: both expanded rows committed an inspector — "
                     "so the sets below are sets. left=" (pr-str ids-l)))
            (is (= (count ids-l) (count ids-r))
                "naming an instance changes the ids, never the rows — two
                 mounts of the same focused epoch over the same rows")

            ;; ---- the claim ---------------------------------------------
            (is (nil? (some (set ids-l) ids-r))
                (str "no mount-id survives from one standalone mount to the "
                     "other — the store's lifecycle key and the measured "
                     "width slot are both derived from this string. left="
                     (pr-str ids-l) " right=" (pr-str ids-r)))
            (is (every? #(string/starts-with? % (str id-prefix "left/")) ids-l)
                (str "each id carries the name THIS mount was given, rather "
                     "than a per-render nonce or a shared string: "
                     (pr-str ids-l))))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W2 — the lifecycle half: each mount owns an observer AND a width, and keeps
;;      both when its sibling goes
;; ===========================================================================

(deftest two-named-mounts-each-keep-their-own-observer-and-width
  (testing "rf2-pua3 — two named standalone mounts hold two entries in the
            widget's per-mount store, each with its OWN ResizeObserver and
            its OWN entry in the frame's measured-width slot, and unmounting
            one releases ONLY its own. Under the shared identity the second
            mount never installs an observer at all, and `release-mount!`
            tears the shared entry down AND clears the shared width when
            either detaches — so the survivor is left on screen unobserved
            and unmeasured, which a distinctness row alone cannot see."
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

          ;; ---- the observer half -------------------------------------
          ;; These two are LIVENESS controls rather than the discriminating
          ;; rows, and saying so matters: under the shared identity both
          ;; lookups resolve to the SAME entry, so both pass while the defect
          ;; is fully present (measured — they were the two rows in this
          ;; deftest that stayed green on the unrepaired tree). What bites
          ;; before the unmount is `not=` above; what bites after it is the
          ;; survivor row below.
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "the left mount installed its own ResizeObserver")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r))
                         :observer)
              "and so did the right — two live mounts, two observers. Under
               the shared identity the second element's ref callback finds an
               observer already on the entry and installs none")

          ;; ---- the width half ----------------------------------------
          ;; DRIVEN through the slot's own public event rather than read off
          ;; a real measurement, and that is a finding rather than a
          ;; shortcut: in the headless browser the committed payload
          ;; container measures `clientWidth` 0, so `measure-and-dispatch!`'s
          ;; `(pos? w)` guard never fires and the slot stays EMPTY on a
          ;; perfectly healthy mount (measured on the pre-fix run: `slot={}`
          ;; for both mounts). A row asserting a measured width would
          ;; therefore be red for a reason that is not this defect. The slot
          ;; is public for exactly this — its docstring says tests may drive
          ;; measurements deterministically — and `:rf.xray.edn-inspector/
          ;; set-width` is the very event the observer dispatches, under the
          ;; very id the widget composes. So what these rows exercise is the
          ;; half the defect actually breaks: WHICH KEY `release-mount!`
          ;; clears when a sibling detaches.
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-l 640]
                            {:frame :rf/xray})
          (rf/dispatch-sync [:rf.xray.edn-inspector/set-width id-r 480]
                            {:frame :rf/xray})
          (is (= 640 (get (widths) id-l))
              (str "two live mounts hold TWO width slots — under the shared "
                   "identity the right mount's write lands on the left's key "
                   "and overwrites it. slot=" (pr-str (widths))))

          (unmount! right)

          ;; ---- the survivor keeps both -------------------------------
          (is (nil? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-r)))
              "the detached mount is gone")
          (is (contains? (ei/mount-state-held (ei/lifecycle-key :rf/xray id-l))
                         :observer)
              "and the mount STILL ON SCREEN is still observed — releasing
               the survivor's entry is what the shared key did, and it left a
               live node with no observer and no width updates")
          (is (= 640 (get (widths) id-l))
              (str "and the survivor's width is STILL in the frame's slot — "
                   "`release-mount!` clears the width under the DETACHING "
                   "mount's id, which under the shared identity is the "
                   "survivor's own. slot=" (pr-str (widths))))
          (finally
            (unmount! left)))))))

;; ===========================================================================
;; W3 — the LOGICAL identity must NOT move
;; ===========================================================================

(deftest two-named-mounts-share-their-disclosure-identity
  (testing "rf2-pua3 — the bound on this repair, as a row. Naming two mounts
            qualifies the PHYSICAL identity only: the `:site-id` that keys
            expansion and zoom (rf2-pvsxs) and the row's own testid are
            IDENTICAL across the two panels, so two views of the same epoch
            still open and close together and an operator's expansion choices
            still survive a tab leave-and-return. A repair that qualified the
            site-id too would pass W1 and W2 and silently change this."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_     (setup!)
            left  (mount! {:instance-id "left"})
            right (mount! {:instance-id "right"})]
        (try
          (let [sites-l (site-ids (:container left))
                sites-r (site-ids (:container right))]
            (is (seq sites-l)
                (str "control: the left mount committed a site-id at all — "
                     "this panel passes one, so an equality below is a "
                     "measurement rather than two empty vectors agreeing. "
                     "left=" (pr-str sites-l)))
            (is (= sites-l sites-r)
                (str "the LOGICAL identity is shared across the two mounts, "
                     "deliberately. left=" (pr-str sites-l)
                     " right=" (pr-str sites-r)))
            (is (some? (.querySelector (:container right)
                                       "[data-testid=\"rf-xray-trace-row-202-payload\"]"))
                "and the row's own testid is untouched by the qualifier — it
                 is the ROW's identity, not the inspector's"))
          (finally
            (unmount! right)
            (unmount! left)))))))

;; ===========================================================================
;; W4 — the negative control: unnamed mounts collide, and are unchanged
;; ===========================================================================

(deftest two-unnamed-mounts-still-collide
  (testing "rf2-pua3 — the defect verbatim, kept as a row. Two standalone
            mounts with NO `:instance-id` present the SAME mount-ids, which is
            what makes W1's disjointness a measurement rather than a
            coincidence; and the ids they present carry no instance segment at
            all, so every existing single-mount call site composes exactly what
            it always did."
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
            (is (= ids-a [(str id-prefix "101") (str id-prefix "202")])
                (str "and they are byte-for-byte the ids this panel composed "
                     "before rf2-pua3 — the row id and nothing else. a="
                     (pr-str ids-a)))
            (is (= ids-n
                   (mapv #(str id-prefix "left/" (subs % (count id-prefix)))
                         ids-a))
                (str "a named mount's ids are the UNNAMED ids with the "
                     "caller's name spliced in after the panel's own prefix "
                     "— which says both halves at once: naming qualifies the "
                     "id without disturbing the row id inside it, and an "
                     "unnamed mount composes byte-for-byte what it always "
                     "did. unnamed=" (pr-str ids-a) " named=" (pr-str ids-n)))
            (is (= ids-a (mount-ids (:container a)))
                "re-reading the same container is stable — these are
                 identities, not per-render nonces"))
          (finally
            (unmount! named)
            (unmount! b)
            (unmount! a)))))))
