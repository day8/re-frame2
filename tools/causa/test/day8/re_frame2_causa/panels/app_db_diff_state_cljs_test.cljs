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
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-causa.panels.app-db-diff-state :as state]))

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
                  {:counter 5 :user {:name "ada"}
                   :rf/route    {:id :home}
                   :rf/machines {:title/flow {:state :playing}}})
          tree  (state/state-body model)
          ids   (all-testids tree)]
      (is (not (contains? ids "rf-causa-app-db-state-top-triggers"))
          "no TOP-triggers container")
      (is (not-any? #(.startsWith % "rf-causa-app-db-downstream-trigger-")
                    ids)
          "no path-keyed downstream-subs trigger on any section"))))

(deftest no-copy-button-on-app-db-blocks
  (testing "rf2-ilubp — the app-db inspect renders opt out of the EDN
            widget's universal ⎘ copy button (`:copy? false`); no
            `…-copy` testid appears on any value block"
    (let [model (h/current-state-sections
                  {:counter 5 :user {:name "ada"}
                   :rf/route    {:id :home}
                   :rf/machines {:title/flow {:state :playing}}})
          tree  (state/state-body model)
          ids   (all-testids tree)]
      (is (not-any? #(.endsWith % "-copy") ids)
          "no copy affordance on any app-db section value block"))))
