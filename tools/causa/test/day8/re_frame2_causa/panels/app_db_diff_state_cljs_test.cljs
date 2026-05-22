(ns day8.re-frame2-causa.panels.app-db-diff-state-cljs-test
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
            [day8.re-frame2-causa.panels.app-db-diff-downstream
             :as downstream]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-causa.panels.app-db-diff-state :as state]))

;; The EDN widget's copy-affordance dispatches a registered event; a
;; plain-atom runtime keeps `edn/inspect` happy when it walks a value.
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

(defn- testids [tree]
  (->> (hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))
       (remove nil?)
       set))

;; ---- fn-component-expanding walker (for the downstream-subs trigger) -----
;;
;; `app-db-diff-state` mounts the downstream-subs hover trigger as a
;; Reagent fn-component vector `[downstream/hover-trigger path]`. The
;; plain `hiccup-seq` above doesn't render fn-components, so these
;; helpers expand them (calling the fn) so the trigger's own testid is
;; reachable. Mirrors `app_db_diff_cljs_test.cljs`.

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- expanded-hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- expanded-testids [tree]
  (->> (expanded-hiccup-seq tree)
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))
       (remove nil?)
       set))

;; ---- TOP (user-domain) section ------------------------------------------

(deftest top-section-renders-user-domain-value
  (testing "the TOP section renders the app-db-minus-reserved value"
    (let [model (h/current-state-sections {:counter 5 :user {:name "ada"}
                                           :rf/route {:id :home}})
          tree  (state/state-body model)]
      (is (some? (find-by-testid tree "rf-causa-app-db-state"))
          "panel state container present")
      (is (some? (find-by-testid tree "rf-causa-app-db-state-top"))
          "TOP user-domain section present"))))

(deftest top-section-empty-when-no-user-domain-keys
  (testing "a reserved-keys-only db → TOP section still renders, with the
            empty-state body (not omitted)"
    (let [model (h/current-state-sections {:rf/route {:id :home}})
          tree  (state/state-body model)
          top   (find-by-testid tree "rf-causa-app-db-state-top")]
      (is (some? top) "TOP section is always present")
      (is (re-find #"no user-domain keys" (pr-str top))
          "empty-state copy renders when user-domain app-db is empty"))))

;; ---- machines fan-out ---------------------------------------------------

(deftest machines-fan-out-one-section-per-id
  (testing "each machine renders its own section titled by the machine id"
    (let [model (h/current-state-sections
                  {:rf/machines {:title/flow {:state :playing}
                                 :auth       {:state :idle}}})
          tree  (state/state-body model)
          ids   (testids tree)]
      (is (contains? ids "rf-causa-app-db-state-instance-:rf/machines-:title/flow")
          "one section per machine id — :title/flow")
      (is (contains? ids "rf-causa-app-db-state-instance-:rf/machines-:auth")
          "one section per machine id — :auth")
      (let [flow-section (find-by-testid
                           tree "rf-causa-app-db-state-instance-:rf/machines-:title/flow")]
        (is (re-find #":title/flow" (pr-str flow-section))
            "section title carries the machine id")))))

(deftest machines-empty-renders-single-empty-state-section
  (testing "absent / empty :rf/machines → one empty-state area section,
            NOT one-section-per-id (there are no ids)"
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)
          area  (find-by-testid tree "rf-causa-app-db-state-area-:rf/machines")]
      (is (some? area) "machines area section is present even when empty")
      (is (re-find #"No machines" (pr-str area))
          "empty-state copy for machines"))))

;; ---- route singleton ----------------------------------------------------

(deftest route-singleton-renders-one-section
  (testing ":rf/route renders as ONE singleton section titled `route`"
    (let [model (h/current-state-sections
                  {:rf/route {:id :app/article :params {:id "A"}
                              :query {} :fragment nil :transition :idle
                              :error nil :nav-token "nav-1"}})
          tree  (state/state-body model)]
      (is (some? (find-by-testid tree "rf-causa-app-db-state-area-:rf/route"))
          "route singleton section present"))))

(deftest route-absent-renders-empty-section
  (testing "absent :rf/route → singleton section in the empty-state
            (blank, not omitted)"
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)
          area  (find-by-testid tree "rf-causa-app-db-state-area-:rf/route")]
      (is (some? area) "route section present even when no active route")
      (is (re-find #"No active route" (pr-str area))
          "route empty-state copy"))))

;; ---- full reserved inventory always present -----------------------------

(deftest every-reserved-area-section-renders
  (testing "the inventory is complete — every reserved :rf/* area has a
            section even on an empty db (none omitted)"
    (let [model (h/current-state-sections {})
          tree  (state/state-body model)
          ids   (testids tree)]
      ;; Singleton areas surface as `…-area-<key>`; machines/spawned do
      ;; too when empty (single empty-state section).
      (doseq [area h/reserved-app-db-keys]
        (is (contains? ids (str "rf-causa-app-db-state-area-" (pr-str area)))
            (str "section present for reserved area " area))))))

;; ---- nil-safety ---------------------------------------------------------

(deftest state-body-nil-safe
  (testing "nil / empty db model renders without throwing — TOP empty +
            full reserved inventory empty-state"
    (doseq [db [nil {}]]
      (let [tree (state/state-body (h/current-state-sections db))]
        (is (some? (find-by-testid tree "rf-causa-app-db-state-top")))
        (is (some? (find-by-testid tree "rf-causa-app-db-state-area-:rf/route")))))))

;; ---- downstream-subs hover trigger re-wire (spec/021 §4.4, rf2-2lb7z) ---
;;
;; After rf2-okvit redesigned this tab into a current-state inspector,
;; the §4.4 downstream-subs popover lost its host (the diff breadcrumbs).
;; rf2-2lb7z re-wires the trigger into the current-state section headers:
;; one trigger per top-level user-domain key in the TOP section, one per
;; reserved area `[:rf/area]`, and one per fan-out instance `[:rf/area
;; id]`. The popover subs/events/component are unchanged — these tests
;; assert the trigger is now INJECTED, threading the section's path.

(defn- trigger-testid
  "The downstream-subs trigger's path-keyed testid (mirrors
  `app-db-diff-downstream/hover-trigger`)."
  [path]
  (str "rf-causa-app-db-downstream-trigger-" (pr-str path)))

(defn- find-expanded-by-testid
  "Find the (fn-expanded) node carrying `testid`, walking through
  fn-components so containers whose children are fn-component vectors
  are reachable."
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (expanded-hiccup-seq tree)))

(deftest top-section-hosts-per-user-domain-key-trigger
  (testing "the TOP section fans a downstream-subs trigger out per
            top-level user-domain key (path `[:key]`) — the whole-db
            root `[]` is a path-filter no-op so we key per top-level key"
    (downstream/install!)
    (let [model (h/current-state-sections {:counter 5 :user {:name "ada"}
                                           :rf/route {:id :home}})
          tree  (state/state-body model)
          ids   (expanded-testids tree)
          ;; Scope the negative assertion to the TOP-triggers container
          ;; only — reserved-area sections DO host their own
          ;; `[:rf/route]` trigger elsewhere in the tree (correct), so
          ;; the whole-tree id set legitimately contains it.
          top-triggers (find-expanded-by-testid
                         tree "rf-causa-app-db-state-top-triggers")
          top-ids      (expanded-testids top-triggers)]
      (is (contains? ids "rf-causa-app-db-state-top-triggers")
          "TOP section carries the trigger group container")
      (is (contains? top-ids (trigger-testid [:counter]))
          "a trigger keyed to the user-domain key [:counter]")
      (is (contains? top-ids (trigger-testid [:user]))
          "a trigger keyed to the user-domain key [:user]")
      (is (not (contains? top-ids (trigger-testid [:rf/route])))
          "reserved keys are NOT in the user-domain TOP triggers"))))

(deftest top-section-no-triggers-when-no-user-domain-keys
  (testing "a reserved-keys-only db → TOP section renders no triggers
            (no user-domain keys to host one)"
    (downstream/install!)
    (let [model (h/current-state-sections {:rf/route {:id :home}})
          tree  (state/state-body model)
          ids   (expanded-testids tree)]
      (is (not (contains? ids "rf-causa-app-db-state-top-triggers"))
          "no trigger group when the user-domain app-db is empty"))))

(deftest machine-instance-section-hosts-path-trigger
  (testing "each machine fan-out section hosts a trigger keyed to its
            `[:rf/machines id]` path"
    (downstream/install!)
    (let [model (h/current-state-sections
                  {:rf/machines {:title/flow {:state :playing}
                                 :auth       {:state :idle}}})
          tree  (state/state-body model)
          ids   (expanded-testids tree)]
      (is (contains? ids (trigger-testid [:rf/machines :title/flow]))
          "trigger keyed to [:rf/machines :title/flow]")
      (is (contains? ids (trigger-testid [:rf/machines :auth]))
          "trigger keyed to [:rf/machines :auth]"))))

(deftest singleton-area-section-hosts-path-trigger
  (testing ":rf/route singleton section hosts a trigger keyed to
            `[:rf/route]`"
    (downstream/install!)
    (let [model (h/current-state-sections
                  {:rf/route {:id :app/article :params {:id "A"}}})
          tree  (state/state-body model)
          ids   (expanded-testids tree)]
      (is (contains? ids (trigger-testid [:rf/route]))
          "trigger keyed to the [:rf/route] area path"))))

(deftest empty-reserved-area-section-still-hosts-path-trigger
  (testing "an absent/empty reserved area's empty-state section STILL
            hosts its `[:rf/area]` trigger — the path is meaningful even
            with no live value (the popover degrades to its empty-state)"
    (downstream/install!)
    (let [model (h/current-state-sections {:counter 1})
          tree  (state/state-body model)
          ids   (expanded-testids tree)]
      (is (contains? ids (trigger-testid [:rf/machines]))
          "empty :rf/machines area still hosts a [:rf/machines] trigger")
      (is (contains? ids (trigger-testid [:rf/route]))
          "empty :rf/route area still hosts a [:rf/route] trigger"))))

(deftest trigger-rewire-nil-safe
  (testing "nil / empty db still renders the triggers (where applicable)
            without throwing"
    (downstream/install!)
    (doseq [db [nil {}]]
      (let [tree (state/state-body (h/current-state-sections db))
            ids  (expanded-testids tree)]
        ;; No user-domain keys → no TOP triggers; reserved areas still
        ;; host theirs.
        (is (not (contains? ids "rf-causa-app-db-state-top-triggers")))
        (is (contains? ids (trigger-testid [:rf/route])))))))
