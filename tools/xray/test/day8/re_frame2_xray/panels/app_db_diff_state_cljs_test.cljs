(ns day8.re-frame2-xray.panels.app-db-diff-state-cljs-test
  "View-shape tests for the app-db tab's current-state inspector
  sections.

  Walks the hiccup tree `app-db-diff-state` renders by `data-testid` —
  no DOM mount, no Reagent runtime. Asserts the sectioning contract:
  TOP user-domain section, per-instance machine fan-out, singleton
  route, and the empty-state for absent / empty reserved areas.

  The section VALUE bodies render through the first-class edn-inspector
  widget; these tests assert section structure + testids, not the
  widget's inner markup (that is covered by the `views/edn_inspector_*`
  tests).

  ## Two panel instances under ONE frame

  The last block in this file is the exception to \"no DOM mount\": it
  drives the edn-inspector's per-mount store directly, with the mount-ids
  READ OUT OF the hiccup the sections above render. That is the case the
  widget's `[frame-id mount-id]` lifecycle key cannot cover — it
  separates two panels under two `frame-provider`s and cannot separate two
  under one — and the block carries the collision as an explicit negative
  control. See the banner comment there."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [cljs.test :refer-macros [use-fixtures]]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
            ;; READ-ONLY here, and only for the per-mount store's
            ;; public test surface (`lifecycle-key`, `container-ref-for`,
            ;; `mount-state-count`, `mount-state-held`). The widget's own
            ;; rows live in `views/edn_inspector_mount_state_cljs_test`; what
            ;; these assert is that the PANEL hands that store two distinct
            ;; keys when the caller names two instances.
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; `state-body` renders values through the edn-inspector widget. A
;; plain-atom runtime keeps any reactive read in that path resolvable
;; across substrate adapters.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- merge-area
  "Stitch a single area-id's value into the runtime-db PARTITION shape per
  the `runtime-areas` table (`area-id` → `[:rf.runtime/…]` sub-path).
  Pure data → data."
  [rt area-id v]
  (let [path (get h/runtime-areas area-id)]
    (if path (assoc-in rt path v) rt)))

(defn- runtime-db
  "Build a RUNTIME-DB partition value carrying the `areas` keyed by their
  logical area-id (`:rf/machines` / `:rf/route` / …) at the `:rf.runtime/*`
  paths the `runtime-areas` table names (EP-0001 — the runtime
  subsystems live in the runtime-db partition)."
  [areas]
  (reduce-kv merge-area {} areas))

(defn- sections
  "Build the section model the renderer consumes from a TOP user-domain
  app-db + a map of runtime areas (stitched into the runtime-db partition
  via `runtime-db`). Convenience wrapper over the two-partition
  `current-state-sections` so the view-shape tests read cleanly."
  ([areas] (sections {} areas))
  ([app-db areas]
   (h/current-state-sections app-db (runtime-db areas))))

(defn- testids [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))
       (remove nil?)
       set))

;; ---- TOP (user-domain) section ------------------------------------------

(deftest top-section-empty-when-no-user-domain-keys
  (testing "a reserved-keys-only db → TOP section still renders, with the
            empty-state body (not omitted)"
    (let [model (sections {:rf/route {:id :home}})
          tree  (state/state-body model)
          top   (find-by-testid tree "rf-xray-app-db-state-top")]
      (is (some? top) "TOP section is always present")
      (is (re-find #"no user-domain keys" (pr-str top))
          "empty-state copy renders when user-domain app-db is empty"))))

;; ---- machines fan-out ---------------------------------------------------

(deftest machines-fan-out-one-section-per-id
  (testing "each machine renders its own section titled by the machine id"
    (let [model (sections {:rf/machines {:title/flow {:state :playing}
                                         :auth       {:state :idle}}})
          tree  (state/state-body model)
          ids   (testids tree)]
      (is (contains? ids "rf-xray-app-db-state-instance-:rf/machines-:title/flow")
          "one section per machine id — :title/flow")
      (is (contains? ids "rf-xray-app-db-state-instance-:rf/machines-:auth")
          "one section per machine id — :auth")
      (let [flow-section (find-by-testid
                           tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow")]
        (is (re-find #":title/flow" (pr-str flow-section))
            "section title carries the machine id")))))

(deftest machines-empty-is-omitted-entirely
  (testing "absent / empty :rf/machines is OMITTED from the
            rendered tree entirely (no placeholder card with 'No
            machines registered.' copy)"
    (let [model (sections {:counter 1} {})
          tree  (state/state-body model)]
      (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/machines"))
          "no machines area section in the tree")
      (is (not (re-find #"No machines" (pr-str tree)))
          "no 'No machines registered.' empty-state copy"))))

;; ---- route singleton ----------------------------------------------------

(deftest route-singleton-renders-one-section
  (testing ":rf/route renders as ONE singleton section titled `route`"
    (let [model (sections {:rf/route {:id :app/article :params {:id "A"}
                                      :query {} :fragment nil :transition :idle
                                      :error nil :nav-token "nav-1"}})
          tree  (state/state-body model)]
      (is (some? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
          "route singleton section present"))))

(deftest route-absent-is-omitted-entirely
  (testing "absent :rf/route is OMITTED from the rendered
            tree entirely (no placeholder card with 'No active route.'
            copy)"
    (let [model (sections {:counter 1} {})
          tree  (state/state-body model)]
      (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
          "no route area section in the tree")
      (is (not (re-find #"No active route" (pr-str tree)))
          "no 'No active route.' empty-state copy"))))

;; ---- an empty db renders only the TOP section ---------------------------

(deftest empty-db-renders-only-the-top-section
  (testing "an empty db renders ONLY the TOP user-domain
            section; every reserved-area card is omitted (data-driven
            visibility — sections appear as state accrues, not as
            persistent placeholders)"
    (let [model (sections {} {})
          tree  (state/state-body model)
          ids   (testids tree)]
      (is (contains? ids "rf-xray-app-db-state-top")
          "TOP is the panel's anchor — always renders")
      ;; Every logical reserved area is absent from an empty runtime-db, so
      ;; no area card renders (EP-0001 — areas read runtime-db).
      (doseq [area (keys h/runtime-areas)]
        (is (not (contains? ids (str "rf-xray-app-db-state-area-"
                                     (pr-str area))))
            (str "no placeholder card for empty reserved area " area))))))

;; ---- nil-safety ---------------------------------------------------------

(deftest state-body-nil-safe
  (testing "nil / empty db model renders without throwing —
            TOP empty + zero reserved-area cards (every reserved slot is
            empty so every entry is filtered out at projection time)"
    (doseq [app-db [nil {}]
            rt     [nil {}]]
      (let [tree (state/state-body (h/current-state-sections app-db rt))]
        (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
            "TOP is the panel's anchor — always renders")
        (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
            "absent route → no placeholder card")))))

;; ---- affordance strip ---------------------------------------------------
;;
;; There is no per-section "⤴ subs" downstream-subs hover trigger. The
;; current-state inspector is a clean sectioned view with no per-block
;; affordances. This test pins the negative: the trigger appears nowhere
;; in the rendered tree.

(defn- all-testids
  "Every `:data-testid` in the rendered tree (no fn-component expansion
  needed — the section renderers are plain hiccup)."
  [tree]
  (testids tree))

(deftest no-downstream-subs-trigger-anywhere
  (testing "there is no `⤴ subs` downstream-subs trigger in any section
            (TOP, machine fan-out, singleton areas, empty-state areas)"
    (let [model (sections {:counter 5 :user {:name "ada"}}
                          {:rf/route    {:id :home}
                           :rf/machines {:title/flow {:state :playing}}})
          tree  (state/state-body model)
          ids   (all-testids tree)]
      (is (not (contains? ids "rf-xray-app-db-state-top-triggers"))
          "no TOP-triggers container")
      (is (not-any? #(.startsWith % "rf-xray-app-db-downstream-trigger-")
                    ids)
          "no path-keyed downstream-subs trigger on any section"))))

;; There is no copy-button negative here. The EDN widget has no universal
;; copy affordance, so nothing in the tool can produce a `-copy` testid and
;; such an assertion would be vacuously green. App-DB carries no copy
;; control by construction.

;; ---- section-shell chrome ------------------------------------------------
;;
;; There is no inter-section hairline divider — each card's
;; own border (from the edn-inspector widget's `:card?` chrome) plus the
;; inter-card vertical gap is sufficient visual separation. The
;; section-shell wrapper draws padding only; no `border-top`, no
;; `border`, no `background`, no `border-radius`. The card chrome lives
;; inside the section body (the edn-inspector widget owns it).

(defn- section-styles
  "Collect the inline `:style` map of every `<section>` node in the tree."
  [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node)
                          (= :section (first node))
                          (map? (second node)))
                 (:style (second node)))))))

(deftest section-shells-draw-no-chrome
  (testing "no `<section>` wrapper carries any chrome
            (background / border / border-radius / border-top). The
            section is a transparent padded container; the card chrome
            lives inside the body via the edn-inspector widget's
            `:card? true` opt"
    (let [model  (sections {:counter 1}
                           {:rf/route    {:id :home}
                            :rf/machines {:title/flow {:state :idle}}})
          tree   (state/state-body model)
          styles (section-styles tree)]
      (is (seq styles) "sections render")
      (doseq [s styles]
        (is (nil? (:background s)) "no card background on section")
        (is (nil? (:border-radius s)) "no card radius on section")
        (is (nil? (:border s)) "no full border on section")
        (is (nil? (:border-top s))
            "no hairline border-top on any section")))))

;; ---- inline diff annotation (spec/021 §4.3) -----------------------------
;;
;; When a section's `:before` pre-image differs from its `:value`, the
;; value body routes through the edn-inspector widget's DIFF mode —
;; passing `:before` paints the inline
;; `← was X` annotation in place. With no pre-image (the
;; no-diff sentinel) the body stays in BROWSE mode (no annotation).
;;
;; The widget itself is exercised by
;; `tools/xray/test/day8/re_frame2_xray/views/edn_inspector_cljs_test.cljs`
;; (and the diff-mode tests below). These section-level tests assert
;; the section CALLS the widget in the right mode — i.e. with `:before`
;; threaded through when the pre-image differs, omitted when not.

(defn- find-edn-inspector-mounts
  "Walk the hiccup tree and collect every edn-inspector-widget mount.
  Returns a vec of `{:value :opts}` maps so tests can assert against the
  threaded opts (in particular `:before`).

  The mount is `[ei/edn-inspector-view {:mount-id … :value … :opts …}]`,
  the widget's FRESCO head, rather than `[ei/edn-inspector value opts]`,
  its Reagent one. Both render the same body over the same opts; the
  head takes ONE props map, as every `defview` boundary does, so the
  value and opts are read out of it rather than off positions 1 and 2.
  The head is detected by being
  a function — a Fresco boundary is a real React function component —
  and is never CALLED here: its body may only run inside a React render
  window."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [n]
              (cond
                (vector? n)
                (do (when (fn? (first n))
                      (let [props (when (>= (count n) 2) (nth n 1))]
                        ;; `:mount-id` is carried too. It is the
                        ;; id the per-mount store is keyed by (qualified by
                        ;; the frame), and the rows at the foot of this file
                        ;; read it off the render rather than rebuilding the
                        ;; panel's composition rule in the test.
                        (swap! out conj {:mount-id (:mount-id props)
                                         :value    (:value props)
                                         :opts     (:opts props)})))
                    (doseq [c (rest n)] (walk c)))
                (seq? n) (doseq [c n] (walk c))))]
      (walk tree))
    @out))

(deftest changed-value-carries-inline-changed-annotation
  (testing "a changed user-domain value renders in DIFF mode — the
            section threads `:before` into the edn-inspector widget so
            it paints the inline `← was <prior>` annotation"
    (let [model    (h/current-state-sections {:counter 2} {}
                                             {:app {:counter 1} :runtime {}})
          tree     (state/state-body model)
          top      (find-by-testid tree "rf-xray-app-db-state-top")
          mounts   (find-edn-inspector-mounts top)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq mounts) "top section mounts the edn-inspector widget")
      (is (seq diff-mts)
          "the mount carries a `:before` opt — i.e. the widget renders
           in DIFF mode for the changed value")
      (is (= {:counter 1} (-> diff-mts first :opts :before))
          "the threaded `:before` is the prior value"))))

(deftest changed-machine-snapshot-carries-annotation
  (testing "a changed machine snapshot renders in DIFF mode in its
            instance section — the section threads the prior instance
            map as `:before` so the widget annotates the change"
    (let [before   (runtime-db {:rf/machines {:title/flow {:state :idle}}})
          after    (runtime-db {:rf/machines {:title/flow {:state :loaded}}})
          model    (h/current-state-sections {} after {:app {} :runtime before})
          tree     (state/state-body model)
          flow     (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow")
          mounts   (find-edn-inspector-mounts flow)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "the instance section renders in DIFF mode")
      (is (= {:state :idle} (-> diff-mts first :opts :before))
          "the threaded `:before` is the prior instance snapshot"))))

;; ---- a wholly-new slice reads :added, not plain ---------------------------
;;
;; An instance / singleton present in `:value` but ABSENT in the focused
;; epoch's pre-image is tagged the `h/added` sentinel, and the view
;; translates that to the edn-inspector's `:added? true` first-run signal
;; (which washes the whole subtree green). Tagged `no-diff` it would render
;; identically to an unchanged slice — so the one thing that should make
;; each event visually distinct (the newly-created machine / spawn / route
;; appearing) would carry NO marker. These tests assert the view
;; mounts the inspector with `:added? true` (NOT a `:before` opt) for a
;; wholly-new instance / singleton.

(deftest newly-added-machine-instance-mounts-added-not-before
  (testing "a machine present in :value but absent in the
            focused epoch's :before renders :added (not plain): its
            instance section mounts the inspector with `:added? true`
            and NO `:before` opt"
    (let [before (runtime-db {:rf/machines {:door/main {:state :open}}})
          after  (runtime-db {:rf/machines {:door/main    {:state :open}
                                            :traffic/light {:state :red}}})
          model  (h/current-state-sections {} after {:app {} :runtime before})
          tree   (state/state-body model)
          new-mc (find-by-testid
                   tree "rf-xray-app-db-state-instance-:rf/machines-:traffic/light")
          mounts (find-edn-inspector-mounts new-mc)]
      (is (seq mounts) "the newly-added machine's instance section mounts")
      (is (some #(true? (:added? (:opts %))) mounts)
          "the new machine mounts with `:added? true` — the whole subtree
           washes :added (green) for the focused epoch")
      (is (not-any? #(contains? (:opts %) :before) mounts)
          "an :added slice carries NO `:before` opt (it has no pre-image —
           the first-run path synthesises the missing-sentinel)"))))

(deftest existing-machine-instance-still-diffs-not-added
  (testing "a machine present in BOTH :value and :before
            threads `:before` (diffs in place); it does NOT get
            the `:added?` first-run treatment"
    (let [before (runtime-db {:rf/machines {:door/main {:state :open}}})
          after  (runtime-db {:rf/machines {:door/main    {:state :closed}
                                            :traffic/light {:state :red}}})
          model  (h/current-state-sections {} after {:app {} :runtime before})
          tree   (state/state-body model)
          door   (find-by-testid
                   tree "rf-xray-app-db-state-instance-:rf/machines-:door/main")
          mounts (find-edn-inspector-mounts door)]
      (is (some #(contains? (:opts %) :before) mounts)
          "the pre-existing machine threads `:before` (a real diff)")
      (is (not-any? #(true? (:added? (:opts %))) mounts)
          "a pre-existing machine is NOT marked :added"))))

(deftest newly-added-singleton-slice-mounts-added-not-before
  (testing "a singleton reserved slice (e.g. :rf/route) that
            appeared this epoch (absent in :before) mounts the inspector
            with `:added? true` and no `:before`"
    (let [before (runtime-db {})
          after  (runtime-db {:rf/route {:id :home}})
          model  (h/current-state-sections {} after {:app {} :runtime before})
          tree   (state/state-body model)
          route  (find-by-testid tree "rf-xray-app-db-state-area-:rf/route")
          mounts (find-edn-inspector-mounts route)]
      (is (seq mounts) "the newly-added route slice mounts")
      (is (some #(true? (:added? (:opts %))) mounts)
          "the new route slice mounts with `:added? true`")
      (is (not-any? #(contains? (:opts %) :before) mounts)
          "an :added slice carries no `:before` opt"))))

;; ---- a whole-section removal renders struck-through ----------------------
;;
;; The mirror of the `:added` case above. A slice present in the focused
;; epoch's pre-image and gone now carries the `h/removed` sentinel as its
;; value; the view hands
;; the inspector its absent-value marker beside the real `:before`, and the
;; inspector draws the prior value in place as a removed ghost (spec/004
;; §Removed slots render in place). The last row feeds the panel's OWN mount
;; to the inspector's renderer, so the removed-root presentation is checked
;; rather than assumed.

(defn- removed-door-mount []
  (let [before (runtime-db {:rf/machines {:door/main {:state :open}
                                          :other     {:state :idle}}})
        after  (runtime-db {:rf/machines {:other {:state :idle}}})
        model  (h/current-state-sections {} after {:app {} :runtime before})
        tree   (state/state-body model)
        door   (find-by-testid
                 tree "rf-xray-app-db-state-instance-:rf/machines-:door/main")]
    (first (find-edn-inspector-mounts door))))

(deftest destroyed-machine-instance-mounts-removed-with-its-before
  (testing "a machine destroyed this epoch still has its
            instance section, mounting the inspector's absent-value marker
            as the value and the prior snapshot as `:before`"
    (let [mount (removed-door-mount)]
      (is (some? mount) "the destroyed machine's section still mounts")
      (is (= ei/missing-sentinel (:value mount))
          "the value is the inspector's absent-value marker, not the sentinel keyword")
      (is (= {:state :open} (-> mount :opts :before))
          "the prior snapshot rides `:before`")
      (is (not (true? (-> mount :opts :added?)))
          "a removal is not an addition"))))

(deftest destroyed-machine-instance-renders-a-removed-ghost
  (testing "the inspector draws that mount as a struck-through
            removed ghost carrying the prior value"
    (let [{:keys [value opts]} (removed-door-mount)
          node (ei/render-node {:value value :before (:before opts) :diff? true
                                :panel-id :rf.xray/app-db :mount-id "m"
                                :path [] :depth 0 :expansion-map {}
                                :opts {:default-expanded-depth 3}})
          s    (pr-str node)]
      (is (re-find #":data-rf-removed-ghost \"1\"" s)
          "the root renders through the removed-ghost path")
      (is (re-find #":open" s) "the prior value is drawn")
      (is (not (re-find #"edn-inspector/missing" s))
          "the absent-value marker never leaks into the render"))))

(deftest cleared-top-renders-its-removed-keys
  (testing "a user-domain db this epoch cleared to `{}`
            takes the value path with the prior map as `:before`, not the
            'no user-domain keys yet' placeholder"
    (let [model  (h/current-state-sections {} {}
                                           {:app {:user {:name "a"} :cart [1 2]}
                                            :runtime {}})
          top    (find-by-testid (state/state-body model) "rf-xray-app-db-state-top")
          mounts (find-edn-inspector-mounts top)]
      (is (not (re-find #"no user-domain keys" (pr-str top)))
          "no placeholder claiming the db was always empty")
      (is (= {:user {:name "a"} :cart [1 2]} (-> mounts first :opts :before))
          "the cleared keys ride `:before`, so they render struck-through")))
  (testing "control — empty before AND after still shows the placeholder"
    (let [model (h/current-state-sections {} {} {:app {} :runtime {}})
          top   (find-by-testid (state/state-body model) "rf-xray-app-db-state-top")]
      (is (re-find #"no user-domain keys" (pr-str top))))))

(deftest no-diff-model-renders-current-state-no-annotation
  (testing "the no-diff (2-arity, no pre-image) model renders plain
            current-state — every mount is BROWSE mode (no `:before` opt)
            so the widget renders no `← changed` annotation"
    (let [model  (sections {:counter 2} {:rf/route {:id :home}})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (every? #(not (contains? (:opts %) :before)) mounts)
          "no mount carries a `:before` opt — no diff annotation
           without a pre-image")
      (is (not-any? #(true? (:added? (:opts %))) mounts)
          "and no mount is marked :added in 1-arity / cold-
           boot mode: with no focused epoch there is no 'this epoch added
           it' claim, so every slot renders plain current-state"))))

;; ---- popup affordance ---------------------------------------------------
;;
;; App-DB does NOT use `:popup-affordance?`. The side panel has plenty of
;; horizontal room; the whole-tree inspector reads comfortably in place.
;; These tests pin the absence of the opt so a stray opt-in trips the gate.

(deftest edn-inspector-mounts-omit-popup-affordance-opt
  (testing "no `[ei/edn-inspector-view ...]` mount the App-DB
            panel produces carries `:popup-affordance? true`; the App-
            DB tree renders comfortably in-place and the affordance
            would be unnecessary noise"
    (let [model  (sections {:counter 2}
                           {:rf/route {:id :home}
                            :rf/machines {:auth {:state :idle}}})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (not-any? #(true? (:popup-affordance? (:opts %))) mounts)
          "no mount opts in to the popup affordance"))))

(deftest diff-mode-mounts-also-omit-popup-affordance-opt
  (testing "DIFF-mode mounts (when a pre-image is supplied)
            ALSO omit the popup affordance opt"
    (let [model  (h/current-state-sections {:counter 2} {}
                                           {:app {:counter 1} :runtime {}})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "diff-mode mounts present")
      (is (not-any? #(true? (:popup-affordance? (:opts %))) diff-mts)
          "diff-mode mounts also omit the popup affordance"))))

;; ---- card chrome --------------------------------------------------------
;;
;; The App-DB panel renders the user-domain TOP + every reserved `:rf/*`
;; area as top-level mounts in the same panel. Without card chrome the
;; mounts blend into one continuous block; `:card? true` gives each mount
;; a distinct inspector-card affordance.

(deftest browse-mode-mounts-carry-card-opt
  (testing "every `[ei/edn-inspector-view ...]` mount the App-DB
            panel produces (BROWSE mode, 1-arity / no-diff) carries
            `:card? true` so each top-level mount reads as a discrete
            inspector card"
    (let [model  (sections {:counter 2}
                           {:rf/route {:id :home}
                            :rf/machines {:auth {:state :idle}}})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (every? #(true? (:card? (:opts %))) mounts)
          "every browse-mode mount opts in to the card chrome"))))

(deftest diff-mode-mounts-also-carry-card-opt
  (testing "DIFF-mode mounts (when a pre-image is supplied)
            ALSO carry `:card? true`; card chrome is independent of
            diff mode and applies to every top-level App-DB mount"
    (let [model  (h/current-state-sections {:counter 2} {}
                                           {:app {:counter 1} :runtime {}})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "diff-mode mounts present")
      (is (every? #(true? (:card? (:opts %))) diff-mts)
          "diff-mode mounts also opt in to the card chrome"))))

;; ---- single render path: `:before` presence is the only diff signal -----
;;
;; The edn-inspector has ONE rendering path keyed on value (always) +
;; before (optional). There is no `:full-with-diff?` flag and no
;; distinction between a plain `:diff` lens and full+diff; with a single
;; path the R4 2px vertical rail + R3 chip paint whenever a `:before`
;; pre-image is present. The
;; App-DB call site threads `:before` ONLY when a real pre-image differs
;; and omits it otherwise (one `ei/edn-inspector-view` call, no browse/diff
;; branch). These tests pin that the diff signal rides EXCLUSIVELY on
;; `:before` presence and that no mount carries a `:full-with-diff?` opt.

(deftest no-mount-carries-the-removed-full-with-diff-flag
  (testing "there is no `:full-with-diff?` opt; no
            App-DB mount (diff or plain) may carry it. The R4 rail + R3
            chip paint whenever `:before` is present, with no
            separate flag."
    (let [diff-tree  (state/state-body
                       (h/current-state-sections {:counter 2} {}
                                                 {:app {:counter 1} :runtime {}}))
          plain-tree (state/state-body
                       (sections {:counter 2}
                                 {:rf/route {:id :home}
                                  :rf/machines {:auth {:state :idle}}}))
          mounts     (concat (find-edn-inspector-mounts diff-tree)
                             (find-edn-inspector-mounts plain-tree))]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (not-any? #(contains? (:opts %) :full-with-diff?) mounts)
          "no mount carries a `:full-with-diff?` opt"))))

;; ===========================================================================
;; TWO PANEL INSTANCES UNDER ONE FRAME PROVIDER
;; ===========================================================================
;;
;; Every id these sections compose names a logical SURFACE — the widget's
;; `:mount-id`, the expansion/zoom `:site-id` — and is deliberately the same
;; string from every instance of the panel, because that stability is what
;; survives a tab-switch round-trip. The widget's per-mount store has a
;; lifecycle key of `[frame-id mount-id]`, which separates two
;; panels under two `frame-provider`s. It does not, and cannot, separate two
;; panels under ONE: a Fresco boundary is a React function component with no
;; per-instance storage its body may use, so nothing inside the widget can
;; tell two structurally identical siblings apart. Naming that case is the
;; CALLER's job; `Panel`'s `:instance-id` prop is
;; the naming, and these rows are what says it works.
;;
;; They follow R9 in `views/edn_inspector_mount_state_cljs_test` — no DOM,
;; because the store is where the release actually happens and it is
;; assertable in milliseconds against a browser lane's seconds — with two
;; differences that carry the whole claim:
;;
;;   * ONE DISPATCHER, not two. Both instances render under one frame, so
;;     the widths they measure land in ONE app-db. Folding both dispatches
;;     into the slot they write is what exercises the SECOND half of the
;;     collision: qualifying the store key alone would leave both instances
;;     writing the same `mount-id`-keyed width slot, so the instance name
;;     has to reach both or it is only half a separation.
;;   * THE MOUNT-IDS ARE READ OFF THE PANEL'S OWN RENDER rather than written
;;     here. A row that spelled them out would keep passing if the panel
;;     stopped distinguishing two instances, which is precisely the claim.
;;
;; The negative control is the collision itself: the same two panels with no
;; instance named at all.

(def ^:private one-frame
  "The single frame both instances render under — the case the
  `[frame-id mount-id]` lifecycle key cannot separate. A test-only id, so these rows
  cannot collide with another namespace's entries in the module-global
  store."
  :rf.xray.test/samefrm)

(defn- section-mount-ids
  "The `:mount-id`s the panel composed for `tree`, in render order."
  [tree]
  (mapv :mount-id (find-edn-inspector-mounts tree)))

(defn- section-site-ids
  "The expansion/zoom `:site-id`s the panel composed for `tree`."
  [tree]
  (mapv #(get-in % [:opts :site-id]) (find-edn-inspector-mounts tree)))

(defn- fake-el
  "A stand-in container element. `measure-and-dispatch!` reads `clientWidth`
  and nothing else, and `js/ResizeObserver` does not exist under Node."
  [w]
  #js {:clientWidth w})

(defn- attach!
  "Mount one section the way `edn-inspector-view` does: compose the lifecycle
  key from the frame and the mount-id the PANEL rendered, take the memoised
  ref for it, and hand it an element of `width`. Returns the ref callback."
  [mount-id dispatch-fn width]
  (let [ref-fn (ei/container-ref-for (ei/lifecycle-key one-frame mount-id)
                                     mount-id
                                     dispatch-fn)]
    (ref-fn (fake-el width))
    ref-fn))

(defn- unmount!
  "What React does at unmount: call the container ref with nil."
  [ref-fn]
  (ref-fn nil))

(defn- widths-from
  "Fold the dispatched width events into the app-db slot they write, which is
  where the renderer reads a measured column back out of. Asserting on the
  SLOT rather than on the event list is the point: two events that name one
  key are one width, and that is the half of this defect a store-key-only
  fix leaves standing."
  [events]
  (reduce (fn [acc [ev mount-id w]]
            (case ev
              :rf.xray.edn-inspector/set-width   (assoc acc mount-id w)
              :rf.xray.edn-inspector/clear-width (dissoc acc mount-id)
              acc))
          {}
          events))

(deftest t3fz-named-instances-compose-distinct-ids
  (testing "two `:instance-id`s over the SAME section model
            compose two disjoint sets of `:mount-id`s and `:site-id`s, and
            naming no instance composes ids with no instance segment."
    (let [model  (sections {:counter 5} {:rf/route {:id :home}})
          plain  (state/state-body model)
          left   (state/state-body model "left")
          right  (state/state-body model "right")
          ids-p  (section-mount-ids plain)
          ids-l  (section-mount-ids left)
          ids-r  (section-mount-ids right)]
      (is (< 1 (count ids-p))
          "control: this model renders more than one widget mount, so the
           rows below are about a SET of ids and not a single string")
      (is (= (count ids-p) (count ids-l) (count ids-r))
          "naming an instance changes the ids, never the sections")
      (is (nil? (some (set ids-l) ids-r))
          "no mount-id is shared between two named instances — the store key
           and the width slot are both derived from this string, so a single
           shared id is the whole defect")
      (is (nil? (some (set (section-site-ids left)) (section-site-ids right)))
          "and no site-id either, so the two expand and zoom independently
           rather than moving in lockstep")
      (is (= ["app-db-state/top" "app-db-state/:rf/route"] ids-p)
          "UNNAMED CARRIES NO INSTANCE SEGMENT: the single-mount call sites
           (the L4 tab, the standalone embed) compose the plain surface ids")
      (is (= ["app-db-state/left/top" "app-db-state/left/:rf/route"] ids-l)
          "and a named instance qualifies them without disturbing the
           surface name inside")))

  (testing "a keyword instance-id is accepted, because a Reagent
            parent's `[:>]` crossing converts a prop value to its name while
            a Fresco body hands it over as a keyword; refusing one here would
            make one call site behave two ways."
    (let [model (sections {:counter 5} {})]
      (is (= (section-mount-ids (state/state-body model :left))
             (section-mount-ids (state/state-body model "left")))
          "`:left` and \"left\" name the same instance")))

  (testing "a NAMESPACED keyword keeps its namespace. The
            contract accepts keywords without excluding namespaces, so
            `:left/panel` and `:right/panel` are two names and must compose
            two sets of ids. `instance-token` reads a keyword with
            `(subs (str id) 1)`; the other door is the Reagent crossing,
            which would collapse both to \"panel\" if it named the
            keyword — see the bridge rows in `app_db_diff_cljs_test`,
            which pin it."
    (let [model (sections {:counter 5} {})
          left  (section-mount-ids (state/state-body model :left/panel))
          right (section-mount-ids (state/state-body model :right/panel))]
      (is (= (section-mount-ids (state/state-body model "left/panel")) left)
          "`:left/panel` and \"left/panel\" name the same instance, which is
           what lets the bridge normalise to the string form before the
           crossing without changing what anything composes")
      (is (nil? (some (set left) right))
          "and two namespaces that differ compose disjoint ids")
      (is (= ["app-db-state/left/panel/top"] left)
          "the token is the keyword MINUS its leading colon — readable, and
           stable across that instance's renders")))

  (testing "an instance-id that could not be stable across renders
            is REFUSED rather than `str`-ed into a fresh id every pass."
    (let [model (sections {:counter 5} {})]
      (is (thrown-with-msg? js/Error #":instance-id"
            (state/state-body model {:not "a name"}))
          "a map is refused")
      (is (= (section-mount-ids (state/state-body model))
             (section-mount-ids (state/state-body model "")))
          "and a blank string is simply no instance, not a `//` id"))))

(deftest t3fz-two-named-instances-in-one-frame-stay-independent
  (testing "two app-db Panels under ONE frame provider, each named
            by the caller, get two ref callbacks, two store entries and two
            width slots, and detaching one leaves the other whole."
    (let [model    (sections {:counter 5} {})
          left-id  (first (section-mount-ids (state/state-body model "left")))
          right-id (first (section-mount-ids (state/state-body model "right")))
          sink     (atom [])
          ;; ONE frame ⇒ ONE dispatcher. Both instances write into the same
          ;; app-db, which is why the width slot has to separate them.
          dispatch #(swap! sink conj %)
          before   (ei/mount-state-count)
          ref-l    (attach! left-id dispatch 100)
          ref-r    (attach! right-id dispatch 200)]
      (is (not= left-id right-id)
          "the panel composed two mount-ids for two named instances")
      (is (not (identical? ref-l ref-r))
          "two live mounts under one frame get two ref callbacks — sharing
           one is what leaves the second element unobserved and never
           measured")
      (is (= (+ before 2) (ei/mount-state-count))
          "and two store entries, not one")
      (is (= {left-id 100 right-id 200} (widths-from @sink))
          "each instance measured itself into its OWN width slot inside the
           one frame they share")

      (reset! sink [])
      (unmount! ref-r)
      (is (nil? (ei/mount-state-held (ei/lifecycle-key one-frame right-id)))
          "the detached instance is gone")
      (is (contains? (ei/mount-state-held (ei/lifecycle-key one-frame left-id))
                     :ref)
          "and the instance still on screen is untouched — a shared key
           would release the survivor too, disconnecting an observer of a
           node still in the document")
      (is (= [[:rf.xray.edn-inspector/clear-width right-id]] @sink)
          "the width clear named the LEAVING instance's slot and only it, so
           the survivor's measured column is still there")

      (unmount! ref-l)
      (is (= before (ei/mount-state-count))
          "both released, store back where it started"))))

(deftest t3fz-negative-control-unnamed-instances-collide
  (testing "THE COLLISION ITSELF, so this file cannot go green with only
            half the separation in place. The same two panels with no
            instance named compose
            ONE mount-id between them; under one frame that is one lifecycle
            key AND one width slot, so they share a ref, the second
            instance's measurement lands in the FIRST's slot — a wrong number
            rather than a missing one — and detaching the second releases the
            first."
    (let [model    (sections {:counter 5} {})
          id-a     (first (section-mount-ids (state/state-body model)))
          id-b     (first (section-mount-ids (state/state-body model)))
          sink     (atom [])
          dispatch #(swap! sink conj %)
          before   (ei/mount-state-count)]
      (is (= id-a id-b)
          "CONTROL BITES: two unnamed instances compose the SAME mount-id")
      (let [ref-a (attach! id-a dispatch 100)
            ref-b (attach! id-b dispatch 200)]
        (is (identical? ref-a ref-b)
            "one ref callback between two mounts")
        (is (= (inc before) (ei/mount-state-count))
            "and one store entry between them")
        (is (= {id-a 200} (widths-from @sink))
            "ONE width slot, holding the SECOND instance's measurement: the
             first panel now lays itself out against a column it does not
             have")
        (unmount! ref-b)
        (is (nil? (ei/mount-state-held (ei/lifecycle-key one-frame id-a)))
            "detaching the SECOND released the shared entry, so the first —
             still on screen — has lost its observer, its width debounce and
             its projection cache together")
        (is (= before (ei/mount-state-count))
            "store back where it started")))))
