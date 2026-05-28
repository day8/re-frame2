(ns day8.re-frame2-xray.panels.app-db-diff-state-cljs-test
  "View-shape tests for the app-db tab's current-state inspector
  sections (rf2-okvit).

  Walks the hiccup tree `app-db-diff-state` renders by `data-testid` —
  no DOM mount, no Reagent runtime. Asserts the sectioning contract:
  TOP user-domain section, per-instance machine fan-out, singleton
  route, and the empty-state for absent / empty reserved areas.

  The section VALUE bodies render through the canonical EDN widget's
  cljs-devtools `inspect` path; these tests assert section structure +
  testids, not the inner cljs-devtools markup (that engine is covered
  by `views.edn-widget.*` tests)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [cljs.test :refer-macros [use-fixtures]]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]))

;; `state-body` renders values through the EDN widget's pure `inspect`
;; path (cljs-devtools). A plain-atom runtime keeps any reactive read in
;; that path resolvable across substrate adapters.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
  "Stitch a single area-id's value into a `:rf/runtime` shape per the
  `runtime-areas` table (`area-id` → sub-path). Pure data → data."
  [db area-id v]
  (let [path (get h/runtime-areas area-id)]
    (if path (assoc-in db path v) db)))

(defn- runtime-db
  "Build an app-db whose `:rf/runtime` container carries the values in
  `areas` keyed by their logical area-id. Convenience for tests that
  used to mint the old direct-root shape (`{:rf/machines …}`); now the
  runtime container is nested per rf2-eguy4 phase-A."
  ([areas] (runtime-db {} areas))
  ([base areas]
   (reduce-kv merge-area base areas)))

(defn- testids [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))
       (remove nil?)
       set))

;; ---- TOP (user-domain) section ------------------------------------------

(deftest top-section-renders-user-domain-value
  (testing "the TOP section renders the app-db-minus-reserved value"
    (let [model (h/current-state-sections (runtime-db {:counter 5 :user {:name "ada"}}
                                                      {:rf/route {:id :home}}))
          tree  (state/state-body model)]
      (is (some? (find-by-testid tree "rf-xray-app-db-state"))
          "panel state container present")
      (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
          "TOP user-domain section present"))))

(deftest top-section-empty-when-no-user-domain-keys
  (testing "a reserved-keys-only db → TOP section still renders, with the
            empty-state body (not omitted)"
    (let [model (h/current-state-sections (runtime-db {:rf/route {:id :home}}))
          tree  (state/state-body model)
          top   (find-by-testid tree "rf-xray-app-db-state-top")]
      (is (some? top) "TOP section is always present")
      (is (re-find #"no user-domain keys" (pr-str top))
          "empty-state copy renders when user-domain app-db is empty"))))

;; ---- machines fan-out ---------------------------------------------------

(deftest machines-fan-out-one-section-per-id
  (testing "each machine renders its own section titled by the machine id"
    (let [model (h/current-state-sections
                  (runtime-db {:rf/machines {:title/flow {:state :playing}
                                             :auth       {:state :idle}}}))
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
  (testing "rf2-jcdvo — absent / empty :rf/machines is OMITTED from the
            rendered tree entirely (no placeholder card with 'No
            machines registered.' copy)"
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)]
      (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/machines"))
          "no machines area section in the tree")
      (is (not (re-find #"No machines" (pr-str tree)))
          "the dead 'No machines registered.' empty-state copy is gone"))))

;; ---- route singleton ----------------------------------------------------

(deftest route-singleton-renders-one-section
  (testing ":rf/route renders as ONE singleton section titled `route`"
    (let [model (h/current-state-sections
                  (runtime-db {:rf/route {:id :app/article :params {:id "A"}
                                          :query {} :fragment nil :transition :idle
                                          :error nil :nav-token "nav-1"}}))
          tree  (state/state-body model)]
      (is (some? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
          "route singleton section present"))))

(deftest route-absent-is-omitted-entirely
  (testing "rf2-jcdvo — absent :rf/route is OMITTED from the rendered
            tree entirely (no placeholder card with 'No active route.'
            copy)"
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)]
      (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
          "no route area section in the tree")
      (is (not (re-find #"No active route" (pr-str tree)))
          "the dead 'No active route.' empty-state copy is gone"))))

;; ---- full reserved inventory always present -----------------------------

(deftest empty-db-renders-only-the-top-section
  (testing "rf2-jcdvo — an empty db renders ONLY the TOP user-domain
            section; every reserved-area card is omitted (data-driven
            visibility — sections appear as state accrues, not as
            persistent placeholders)"
    (let [model (h/current-state-sections {})
          tree  (state/state-body model)
          ids   (testids tree)]
      (is (contains? ids "rf-xray-app-db-state-top")
          "TOP is the panel's anchor — always renders")
      (doseq [area h/reserved-app-db-keys]
        (is (not (contains? ids (str "rf-xray-app-db-state-area-"
                                     (pr-str area))))
            (str "no placeholder card for empty reserved area " area))))))

(deftest populated-reserved-areas-render
  (testing "rf2-jcdvo — populated reserved areas render exactly as before
            (only the empty-area filtering changed); each populated area
            still gets a card the operator can read"
    (let [model (h/current-state-sections
                  (runtime-db {:counter 1}
                              {:rf/route    {:id :home}
                               :rf/machines {:auth {:state :idle}}}))
          tree  (state/state-body model)
          ids   (testids tree)]
      (is (contains? ids "rf-xray-app-db-state-area-:rf/route")
          "populated route → area section present")
      (is (contains? ids "rf-xray-app-db-state-instance-:rf/machines-:auth")
          "populated machines → instance section present"))))

;; ---- nil-safety ---------------------------------------------------------

(deftest state-body-nil-safe
  (testing "rf2-jcdvo — nil / empty db model renders without throwing —
            TOP empty + zero reserved-area cards (every reserved slot is
            empty so every entry is filtered out at projection time)"
    (doseq [db [nil {}]]
      (let [tree (state/state-body (h/current-state-sections db))]
        (is (some? (find-by-testid tree "rf-xray-app-db-state-top"))
            "TOP is the panel's anchor — always renders")
        (is (nil? (find-by-testid tree "rf-xray-app-db-state-area-:rf/route"))
            "absent route → no placeholder card")))))

;; ---- affordance strip (rf2-kbxgj + rf2-ilubp) ---------------------------
;;
;; rf2-kbxgj removed the dead per-section "⤴ subs" downstream-subs hover
;; trigger; rf2-ilubp opted the app-db inspect renders out of the EDN
;; widget's universal ⎘ copy gesture (`:copy? false`). The current-state
;; inspector is now a clean sectioned view with no per-block affordances.
;; These tests pin the negative: neither affordance appears anywhere in
;; the rendered tree.

(defn- all-testids
  "Every `:data-testid` in the rendered tree (no fn-component expansion
  needed — the section renderers are plain hiccup now)."
  [tree]
  (testids tree))

(deftest no-downstream-subs-trigger-anywhere
  (testing "rf2-kbxgj — the dead `⤴ subs` downstream-subs trigger is gone
            from every section (TOP, machine fan-out, singleton areas,
            empty-state areas)"
    (let [model (h/current-state-sections
                  (runtime-db {:counter 5 :user {:name "ada"}}
                              {:rf/route    {:id :home}
                               :rf/machines {:title/flow {:state :playing}}}))
          tree  (state/state-body model)
          ids   (all-testids tree)]
      (is (not (contains? ids "rf-xray-app-db-state-top-triggers"))
          "no TOP-triggers container")
      (is (not-any? #(.startsWith % "rf-xray-app-db-downstream-trigger-")
                    ids)
          "no path-keyed downstream-subs trigger on any section"))))

(deftest no-copy-button-on-app-db-blocks
  (testing "rf2-ilubp — the app-db inspect renders opt out of the EDN
            widget's universal ⎘ copy button (`:copy? false`); no
            `…-copy` testid appears on any value block"
    (let [model (h/current-state-sections
                  (runtime-db {:counter 5 :user {:name "ada"}}
                              {:rf/route    {:id :home}
                               :rf/machines {:title/flow {:state :playing}}}))
          tree  (state/state-body model)
          ids   (all-testids tree)]
      (is (not-any? #(.endsWith % "-copy") ids)
          "no copy affordance on any app-db section value block"))))

;; ---- section-shell chrome (rf2-jcdvo) ------------------------------------
;;
;; rf2-jcdvo dropped the inter-section hairline divider — each card's
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
  (testing "rf2-jcdvo — no `<section>` wrapper carries any chrome
            (background / border / border-radius / border-top). The
            section is a transparent padded container; the card chrome
            lives inside the body via the edn-inspector widget's
            `:card? true` opt"
    (let [model  (h/current-state-sections
                   (runtime-db {:counter 1}
                               {:rf/route    {:id :home}
                                :rf/machines {:title/flow {:state :idle}}}))
          tree   (state/state-body model)
          styles (section-styles tree)]
      (is (seq styles) "sections render")
      (doseq [s styles]
        (is (nil? (:background s)) "no card background on section")
        (is (nil? (:border-radius s)) "no card radius on section")
        (is (nil? (:border s)) "no full border on section")
        (is (nil? (:border-top s))
            "rf2-jcdvo — no hairline border-top on any section")))))

(deftest top-section-draws-no-border-top
  (testing "rf2-jcdvo — the TOP section's wrapper carries no border-top
            (consistent with every other section now that the divider
            was dropped)"
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)
          top   (find-by-testid tree "rf-xray-app-db-state-top")]
      (is (some? top))
      (is (nil? (:border-top (:style (second top))))
          "TOP → no border-top divider"))))

(deftest reserved-area-sections-draw-no-border-top
  (testing "rf2-jcdvo — no populated reserved-area section draws a
            hairline border-top divider above it (the card chrome alone
            self-separates adjacent cards). With every reserved slot
            populated, every section renders without a divider."
    (let [model (h/current-state-sections
                  (runtime-db {:rf/machines           {:title/flow {:state :idle}}
                               :rf/spawned            {:parent     {:invoke :child}}
                               :rf/route              {:id :home}
                               :rf/system-ids         #{:app}
                               :rf/pending-navigation {:to :next}
                               :rf/elision            {:declarations {}}}))
          tree   (state/state-body model)
          styles (section-styles tree)]
      (is (seq styles) "sections render")
      (doseq [s styles]
        (is (nil? (:border-top s))
            "no hairline divider on any section")))))

;; ---- inline diff annotation (spec/021 §4.3 · rf2-ad7zx.11) ---------------
;;
;; When a section's `:before` pre-image differs from its `:value`, the
;; value body routes through the edn-inspector widget's DIFF mode
;; (rf2-q3dzw phase 5) — passing `:before` paints the inline
;; `← was X` annotation in place. With no pre-image (the
;; no-diff sentinel) the body stays in BROWSE mode (no annotation).
;;
;; The widget itself is exercised by
;; `tools/xray/test/day8/re_frame2_xray/views/edn_inspector_cljs_test.cljs`
;; (and the diff-mode tests below). These section-level tests assert
;; the section CALLS the widget in the right mode — i.e. with `:before`
;; threaded through when the pre-image differs, omitted when not.

(defn- find-edn-inspector-mounts
  "Walk the hiccup tree and collect every `[ei/edn-inspector value opts]`
  mount. Returns a vec of `{:value :opts}` maps so tests can assert
  against the threaded opts (in particular `:before`)."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [n]
              (cond
                (vector? n)
                (do (when (and (fn? (first n)))
                      (let [a (when (>= (count n) 2) (nth n 1))
                            b (when (>= (count n) 3) (nth n 2))]
                        (swap! out conj {:value a :opts b})))
                    (doseq [c (rest n)] (walk c)))
                (seq? n) (doseq [c n] (walk c))))]
      (walk tree))
    @out))

(deftest changed-value-carries-inline-changed-annotation
  (testing "a changed user-domain value renders in DIFF mode — the
            section threads `:before` into the edn-inspector widget so
            it paints the inline `← was <prior>` annotation"
    (let [model    (h/current-state-sections {:counter 2} {:counter 1})
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
          model    (h/current-state-sections after before)
          tree     (state/state-body model)
          flow     (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow")
          mounts   (find-edn-inspector-mounts flow)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "the instance section renders in DIFF mode")
      (is (= {:state :idle} (-> diff-mts first :opts :before))
          "the threaded `:before` is the prior instance snapshot"))))

(deftest no-diff-model-renders-current-state-no-annotation
  (testing "the 1-arity (no pre-image) model renders plain current-state
            — every mount is BROWSE mode (no `:before` opt) so the
            widget renders no `← changed` annotation"
    (let [model  (h/current-state-sections
                   (runtime-db {:counter 2} {:rf/route {:id :home}}))
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (every? #(not (contains? (:opts %) :before)) mounts)
          "no mount carries a `:before` opt — no diff annotation
           without a pre-image"))))

;; ---- popup affordance (rf2-7sdja) ---------------------------------------
;;
;; App-DB does NOT use `:popup-affordance?` (Mike's call 2026-05-26 from
;; live testing). The side panel has plenty of horizontal room; the
;; whole-tree inspector reads comfortably in place. These tests pin the
;; absence of the opt so a stray re-introduction trips the gate.

(deftest edn-inspector-mounts-omit-popup-affordance-opt
  (testing "rf2-7sdja — no `[ei/edn-inspector ...]` mount the App-DB
            panel produces carries `:popup-affordance? true`; the App-
            DB tree renders comfortably in-place and the affordance
            would be unnecessary noise (Mike's live-testing call
            2026-05-26)"
    (let [model  (h/current-state-sections
                   (runtime-db {:counter 2}
                               {:rf/route {:id :home}
                                :rf/machines {:auth {:state :idle}}}))
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (not-any? #(true? (:popup-affordance? (:opts %))) mounts)
          "no mount opts in to the popup affordance"))))

(deftest diff-mode-mounts-also-omit-popup-affordance-opt
  (testing "rf2-7sdja — DIFF-mode mounts (when a pre-image is supplied)
            ALSO omit the popup affordance opt"
    (let [model  (h/current-state-sections {:counter 2} {:counter 1})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "diff-mode mounts present")
      (is (not-any? #(true? (:popup-affordance? (:opts %))) diff-mts)
          "diff-mode mounts also omit the popup affordance"))))

;; ---- card chrome (rf2-63ie5) --------------------------------------------
;;
;; The App-DB panel renders the user-domain TOP + every reserved `:rf/*`
;; area as top-level mounts in the same panel. Without card chrome the
;; mounts blend into one continuous block; `:card? true` gives each mount
;; a distinct inspector-card affordance.

(deftest browse-mode-mounts-carry-card-opt
  (testing "rf2-63ie5 — every `[ei/edn-inspector ...]` mount the App-DB
            panel produces (BROWSE mode, 1-arity / no-diff) carries
            `:card? true` so each top-level mount reads as a discrete
            inspector card"
    (let [model  (h/current-state-sections
                   (runtime-db {:counter 2}
                               {:rf/route {:id :home}
                                :rf/machines {:auth {:state :idle}}}))
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (every? #(true? (:card? (:opts %))) mounts)
          "every browse-mode mount opts in to the card chrome"))))

(deftest diff-mode-mounts-also-carry-card-opt
  (testing "rf2-63ie5 — DIFF-mode mounts (when a pre-image is supplied)
            ALSO carry `:card? true`; card chrome is independent of
            diff mode and applies to every top-level App-DB mount"
    (let [model  (h/current-state-sections {:counter 2} {:counter 1})
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "diff-mode mounts present")
      (is (every? #(true? (:card? (:opts %))) diff-mts)
          "diff-mode mounts also opt in to the card chrome"))))

;; ---- mode-3 grammar opt (rf2-kkhss) -------------------------------------
;;
;; The edn-inspector widget gates its mode-3 R4 2px vertical rail behind
;; `(and full-with-diff? has-change?)` in `render-container`. The App-DB
;; call site MUST set `:full-with-diff? true` on the DIFF branch so the
;; rail paints on change-bearing subtrees — Stories at
;; `gallery_diff_mode_3.cljs` all set it, and sister surfaces (HANDLER
;; `:db`, Machine, SUBS) also set it; the App-DB BROWSE branch (no
;; `:before` present) MUST NOT set it (mode-3 chrome against a non-diff
;; tree mis-renders).
;;
;; Per rf2-ya3nj audit finding H1. Without the opt the R4 rail silently
;; never paints on App-DB — a load-bearing visual signal absent on the
;; real surface while Stories render it correctly.

(deftest diff-mode-mounts-carry-full-with-diff-opt
  (testing "rf2-kkhss — DIFF-mode mounts (when a pre-image is supplied)
            carry `:full-with-diff? true` so the edn-inspector widget
            paints the mode-3 R4 vertical rail on change-bearing
            subtrees (Stories at `gallery_diff_mode_3.cljs` all set
            this opt; live App-DB used to silently drop it)"
    (let [model    (h/current-state-sections {:counter 2} {:counter 1})
          tree     (state/state-body model)
          mounts   (find-edn-inspector-mounts tree)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "diff-mode mounts present")
      (is (every? #(true? (:full-with-diff? (:opts %))) diff-mts)
          "every diff-mode mount opts in to mode-3 chrome so the R4
           rail paints on change-bearing containers"))))

(deftest browse-mode-mounts-omit-full-with-diff-opt
  (testing "rf2-kkhss — BROWSE-mode mounts (1-arity / no pre-image,
            `:before` absent) MUST NOT carry `:full-with-diff? true`
            — mode-3 chrome against a non-diff tree mis-renders. The
            opt belongs exclusively on the DIFF branch."
    (let [model  (h/current-state-sections
                   (runtime-db {:counter 2}
                               {:rf/route {:id :home}
                                :rf/machines {:auth {:state :idle}}}))
          tree   (state/state-body model)
          mounts (find-edn-inspector-mounts tree)]
      (is (seq mounts) "the panel mounts edn-inspector widget instances")
      (is (every? #(not (contains? (:opts %) :before)) mounts)
          "no `:before` opt — every mount is in BROWSE mode")
      (is (not-any? #(true? (:full-with-diff? (:opts %))) mounts)
          "no browse-mode mount carries the mode-3 opt"))))

(deftest changed-machine-snapshot-diff-mount-carries-mode-3-opt
  (testing "rf2-kkhss — DIFF-mode mounts in the per-machine fan-out
            (instance sections) also carry `:full-with-diff? true` so
            the R4 rail paints uniformly across every reserved area"
    (let [before   (runtime-db {:rf/machines {:title/flow {:state :idle}}})
          after    (runtime-db {:rf/machines {:title/flow {:state :loaded}}})
          model    (h/current-state-sections after before)
          tree     (state/state-body model)
          flow     (find-by-testid
                     tree "rf-xray-app-db-state-instance-:rf/machines-:title/flow")
          mounts   (find-edn-inspector-mounts flow)
          diff-mts (filter #(contains? (:opts %) :before) mounts)]
      (is (seq diff-mts) "the instance section renders in DIFF mode")
      (is (every? #(true? (:full-with-diff? (:opts %))) diff-mts)
          "the changed-machine instance mount opts in to mode-3 chrome"))))
