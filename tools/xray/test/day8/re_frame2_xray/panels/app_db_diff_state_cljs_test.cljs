(ns day8.re-frame2-xray.panels.app-db-diff-state-cljs-test
  "View-shape tests for the app-db tab's current-state inspector sections,
  walking the hiccup `app-db-diff-state` renders by `data-testid` — no DOM
  mount. The section VALUE bodies are edn-inspector mounts; these rows assert
  how each section calls the widget, not the widget's own markup (the
  `views/edn_inspector_*` tests')."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [cljs.test :refer-macros [use-fixtures]]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.app-db-diff-state :as state]
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

;; ---- TOP (user-domain) section ------------------------------------------

(deftest top-section-empty-when-no-user-domain-keys
  (testing "a reserved-keys-only db → TOP section still renders, with the
            empty-state body (not omitted)"
    (let [model (sections {:rf/route {:id :home}})
          tree  (state/state-body model)
          top   (find-by-testid tree "rf-xray-app-db-state-top")]
      (is (re-find #"no user-domain keys" (pr-str top))
          "empty-state copy renders when user-domain app-db is empty"))))

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
      (is (= ei/missing-sentinel (:value mount))
          "the value is the inspector's absent-value marker, not the sentinel keyword")
      (is (= {:state :open} (-> mount :opts :before))
          "the prior snapshot rides `:before`"))))

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

;; ---- mount opts: card chrome --------------------------------------------
;;
;; The TOP and every reserved area are top-level mounts in one panel, so each
;; carries `:card? true` or they blend into one continuous block. The opts
;; map is one constant across browse and diff mode.

(deftest every-mount-is-a-card
  (let [mounts (find-edn-inspector-mounts
                 (state/state-body
                   (sections {:counter 2}
                             {:rf/route {:id :home}
                              :rf/machines {:auth {:state :idle}}})))]
    (is (seq mounts) "the panel mounts edn-inspector widget instances")
    (is (every? #(true? (:card? (:opts %))) mounts)
        "every mount opts in to the card chrome")))

;; ---- two panel instances under ONE frame provider ------------------------
;;
;; Every id a section composes names a logical SURFACE and is the same string
;; from every instance of the panel, which is what survives a tab-switch
;; round-trip. The widget's per-mount store keys on `[frame-id mount-id]`, so
;; it cannot separate two panels under ONE frame; `Panel`'s `:instance-id` is
;; the caller's naming, and what the store and width slot then do with two
;; names is `app_db_diff_mount_instance_id_dom_cljs_test`'s.

(defn- section-mount-ids
  "The `:mount-id`s the panel composed for `tree`, in render order."
  [tree]
  (mapv :mount-id (find-edn-inspector-mounts tree)))

(defn- section-site-ids
  "The expansion/zoom `:site-id`s the panel composed for `tree`."
  [tree]
  (mapv #(get-in % [:opts :site-id]) (find-edn-inspector-mounts tree)))

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
    (let [left (section-mount-ids
                 (state/state-body (sections {:counter 5} {}) :left/panel))]
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
