(ns re-frame.story.ui.url-state-test
  "Pure tests for the URL-state engine (rf2-o4u18).

  The CLJS browser surface (pushState / popstate / window.location) is
  exercised in `re-frame.story.ui.url-state-cljs-test` and in the
  Playwright browser scenario. This ns pins the pure pieces:

  - `params-from-state`     — project shell-state slots onto build-params shape.
  - `query-string-from-state` — round-trip ?key=val&... composition.
  - `url-from-state`        — full path + query + hash composition.
  - `url-relevant-slots-changed?` — diff for the state watcher.
  - `apply-parsed-to-state` — fold parsed slots back into shell-state."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.ui.url-state :as us]))

;; ---- params-from-state ---------------------------------------------------

(deftest params-from-state-empty
  (testing "an empty shell state projects to {}"
    (is (= {} (us/params-from-state {})))))

(deftest params-from-state-variant-only
  (testing "a focused variant projects to {:variant-id ...} only"
    (is (= {:variant-id :story.foo/bar}
           (us/params-from-state
             {:selected-variant :story.foo/bar})))))

(deftest params-from-state-workspace-only
  (testing "a focused workspace projects to {:workspace-id ...} only"
    (is (= {:workspace-id :story.foo/grid}
           (us/params-from-state
             {:selected-workspace :story.foo/grid})))))

(deftest params-from-state-mode-tab-keyed-on-variant
  (testing "mode-tab is projected from the focused variant's slot"
    (is (= {:variant-id :story.foo/bar
            :mode-tab   :docs}
           (us/params-from-state
             {:selected-variant :story.foo/bar
              :active-mode-tab  {:story.foo/bar :docs
                                 :story.other/x :test}})))))

(deftest params-from-state-cell-overrides-scoped-to-variant
  (testing "cell-overrides only project for the focused variant"
    (is (= {:variant-id     :story.foo/bar
            :cell-overrides {:label "Hi"}}
           (us/params-from-state
             {:selected-variant :story.foo/bar
              :cell-overrides   {:story.foo/bar  {:label "Hi"}
                                 :story.other/x  {:label "Hidden"}}})))))

(deftest params-from-state-full
  (testing "every URL-relevant slot projects"
    (let [out (us/params-from-state
                {:selected-variant   :story.foo/bar
                 :selected-workspace nil
                 :active-mode-tab    {:story.foo/bar :test}
                 :active-modes       [:m/dark]
                 :viewport           :tablet
                 :background         :dark
                 :tag-filter         #{:tag/a}
                 :cell-overrides     {:story.foo/bar {:n 5}}
                 :substrate          :uix})]
      (is (= :story.foo/bar (:variant-id out)))
      (is (= :test          (:mode-tab   out)))
      (is (= [:m/dark]      (:active-modes out)))
      (is (= :tablet        (:viewport out)))
      (is (= :dark          (:background out)))
      (is (= #{:tag/a}      (:tag-filter out)))
      (is (= {:n 5}         (:cell-overrides out)))
      (is (= :uix           (:substrate out))))))

;; ---- query-string-from-state --------------------------------------------

(deftest query-string-from-empty-state-is-blank
  (is (= "" (us/query-string-from-state {}))))

(deftest query-string-from-state-prepends-question-mark
  (let [qs (us/query-string-from-state {:selected-variant :foo/bar})]
    (is (re-find #"^\?variant=" qs))))

(deftest query-string-from-state-includes-all-slots
  (let [qs (us/query-string-from-state
             {:selected-variant   :foo/bar
              :active-mode-tab    {:foo/bar :docs}
              :active-modes       [:m/dark]
              :viewport           :tablet
              :background         :dark
              :tag-filter         #{:tag/a}})]
    (is (re-find #"variant="    qs))
    (is (re-find #"mode-tab="   qs))
    (is (re-find #"modes="      qs))
    (is (re-find #"viewport="   qs))
    (is (re-find #"background=" qs))
    (is (re-find #"tag-filter=" qs))))

;; ---- url-from-state ------------------------------------------------------

(deftest url-from-state-composes-pathname-query-hash
  (let [url (us/url-from-state
              {:selected-variant :foo/bar}
              {:pathname "/foo/"
               :hash     "#/stories"})]
    (is (= "/foo/?variant=foo%2Fbar#/stories" url))))

(deftest url-from-state-no-query-when-empty
  (let [url (us/url-from-state {} {:pathname "/foo/" :hash "#/stories"})]
    (is (= "/foo/#/stories" url))))

;; ---- url-relevant-slots-changed? ----------------------------------------

(deftest url-relevant-slots-changed-detects-variant-change
  (is (us/url-relevant-slots-changed?
        {:selected-variant :foo/a}
        {:selected-variant :foo/b})))

(deftest url-relevant-slots-changed-detects-workspace-change
  (is (us/url-relevant-slots-changed?
        {:selected-workspace :foo/a}
        {:selected-workspace :foo/b})))

(deftest url-relevant-slots-changed-detects-mode-tab-change
  (is (us/url-relevant-slots-changed?
        {:active-mode-tab {:foo/a :dev}}
        {:active-mode-tab {:foo/a :docs}})))

(deftest url-relevant-slots-changed-detects-viewport-change
  (is (us/url-relevant-slots-changed?
        {:viewport :full}
        {:viewport :tablet})))

(deftest url-relevant-slots-changed-detects-background-change
  (is (us/url-relevant-slots-changed?
        {:background :light}
        {:background :dark})))

(deftest url-relevant-slots-changed-detects-tag-filter-change
  (is (us/url-relevant-slots-changed?
        {:tag-filter #{}}
        {:tag-filter #{:tag/a}})))

(deftest url-relevant-slots-changed-detects-active-modes-change
  (is (us/url-relevant-slots-changed?
        {:active-modes []}
        {:active-modes [:m/dark]})))

(deftest url-relevant-slots-changed-ignores-non-url-slots
  (testing "changes to non-URL slots (hot-reload-tick, fingerprints,
            panel-visibility) do NOT trigger a push"
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :hot-reload-tick 0}
               {:selected-variant :foo/a :hot-reload-tick 99})))
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :fingerprints {}}
               {:selected-variant :foo/a :fingerprints {:foo/a {:dec :h}}})))
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a
                :panel-visibility {:trace true}}
               {:selected-variant :foo/a
                :panel-visibility {:trace false}})))))

(deftest url-relevant-slots-changed-detects-focused-overrides-change
  (testing "rf2-5fyo3 — a controls edit on the FOCUSED variant changes
            its cell-overrides slice and MUST trigger a push so the
            live address bar round-trips the override (the share popover
            that used to own override serialisation was retired)"
    (is (us/url-relevant-slots-changed?
          {:selected-variant :foo/a :cell-overrides {}}
          {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}}))
    (is (us/url-relevant-slots-changed?
          {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}}
          {:selected-variant :foo/a :cell-overrides {:foo/a {:x 2}}}))))

(deftest url-relevant-slots-changed-ignores-unfocused-overrides-change
  (testing "rf2-5fyo3 — overrides on a NON-focused variant stay off the
            URL (the URL carries only the focused variant's overrides)"
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :cell-overrides {:foo/b {:x 1}}}
               {:selected-variant :foo/a :cell-overrides {:foo/b {:x 2}}})))
    (testing "no focused variant — overrides edits never push"
      (is (not (us/url-relevant-slots-changed?
                 {:selected-variant nil :cell-overrides {}}
                 {:selected-variant nil :cell-overrides {:foo/a {:x 1}}}))))))

;; ---- apply-parsed-to-state ----------------------------------------------

(deftest apply-parsed-variant-only
  (let [out (us/apply-parsed-to-state
              {} {:variant-id :foo/bar} {})]
    (is (= :foo/bar (:selected-variant out)))
    (is (nil? (:selected-workspace out)))))

(deftest apply-parsed-workspace-only
  (let [out (us/apply-parsed-to-state
              {} {:workspace-id :foo/grid} {})]
    (is (= :foo/grid (:selected-workspace out)))
    (is (nil? (:selected-variant out)))))

(deftest apply-parsed-variant-wins-over-workspace
  (testing "rf2-hscut — variant click clears :selected-workspace. When the
            URL carries BOTH (a teammate crafted a URL or a stale
            bookmark), variant wins."
    (let [out (us/apply-parsed-to-state
                {} {:variant-id   :foo/bar
                    :workspace-id :foo/grid}
                {})]
      (is (= :foo/bar (:selected-variant out)))
      (is (nil? (:selected-workspace out))))))

(deftest apply-parsed-validators-drop-unknown-variant
  (testing "unknown variant id is dropped (stale URL degrades, not crashes)"
    (let [out (us/apply-parsed-to-state
                {:selected-variant nil}
                {:variant-id :ghost/x}
                {:variant? (fn [vid] (= vid :foo/bar))})]
      (is (nil? (:selected-variant out))))))

(deftest apply-parsed-validators-drop-unknown-workspace
  (testing "unknown workspace id is dropped"
    (let [out (us/apply-parsed-to-state
                {} {:workspace-id :ghost/grid}
                {:workspace? (fn [_] false)})]
      (is (nil? (:selected-workspace out))))))

(deftest apply-parsed-validators-drop-unknown-viewport
  (testing "unknown viewport preset is dropped — chrome falls back to default"
    (let [out (us/apply-parsed-to-state
                {} {:viewport :nonsense}
                {:viewport? (fn [v] (= v :tablet))})]
      (is (nil? (:viewport out))))))

(deftest apply-parsed-mode-tab-keyed-on-variant
  (testing "mode-tab only applies when a valid variant is also present"
    (let [out (us/apply-parsed-to-state
                {} {:variant-id :foo/bar :mode-tab :docs} {})]
      (is (= :docs (get-in out [:active-mode-tab :foo/bar]))))))

(deftest apply-parsed-tag-filter-set
  (let [out (us/apply-parsed-to-state
              {} {:tag-filter #{:tag/a :tag/b}} {})]
    (is (= #{:tag/a :tag/b} (:tag-filter out)))))

(deftest apply-parsed-substrate
  (let [out (us/apply-parsed-to-state
              {} {:substrate :uix} {})]
    (is (= :uix (:substrate out)))))

;; ---- rf2-dxz4sg: substrate authoritative-clear --------------------------
;;
;; The build side omits `substrate=` to encode the `:reagent` default
;; (`share/build-params` emits it only for a non-default substrate). So
;; an omitted/nil parsed substrate MUST hydrate as `:reagent`, not preserve
;; the recipient's stale in-memory `:uix`/`:helix`. This mirrors the
;; authoritative-clear discipline the other URL-owned chrome slots already
;; get (rf2-fkmnh). The same single fn drives mount-time hydration AND
;; no-query popstate (via `parse-current-url-or-empty`), so both paths heal.

(deftest apply-parsed-empty-url-clears-stale-substrate-to-reagent
  (testing "rf2-dxz4sg — an all-nil parsed URL (no-query popstate / a share
            URL that omits everything) clears a stale `:substrate :uix` back
            to the `:reagent` default rather than preserving it. Before the
            fix `:substrate` only wrote when truthy, so the stale value leaked."
    (let [stale        {:substrate :uix}
          empty-parsed {:variant-id nil :workspace-id nil :mode-tab nil
                        :active-modes nil :viewport nil :background nil
                        :tag-filter nil :cell-overrides nil :substrate nil}
          out          (us/apply-parsed-to-state stale empty-parsed {})]
      (is (= :reagent (:substrate out))
          "omitted substrate= hydrates as the :reagent default, not stale :uix"))))

(deftest apply-parsed-default-share-link-clears-stale-substrate
  (testing "rf2-dxz4sg — the bead's concrete scenario: a recipient with stale
            in-memory `:substrate :helix` opens a DEFAULT share link
            `?variant=story.counter/loaded` that carries NO substrate= (the
            sender omitted it precisely to mean `:reagent`). The recipient
            must render the default `:reagent`, not their stale `:helix`."
    (let [recipient-local {:substrate :helix}
          ;; share/parse-params of just ?variant=story.counter/loaded —
          ;; substrate parses absent as nil.
          shared          {:variant-id :story.counter/loaded :substrate nil}
          out             (us/apply-parsed-to-state recipient-local shared {})]
      (is (= :story.counter/loaded (:selected-variant out)))
      (is (= :reagent (:substrate out))
          "recipient's stale :helix is cleared to the :reagent default"))))

(deftest apply-parsed-explicit-non-default-substrate-round-trips
  (testing "rf2-dxz4sg — an explicit non-default substrate still hydrates to
            that substrate (the fix only changes omitted/invalid handling).
            With a :substrate? validator that registers :uix it is kept; with
            no validator any present id is accepted (registry unreachable)."
    (let [with-validator (us/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :uix}
                           {:substrate? #{:reagent :uix :helix}})
          no-validator   (us/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :helix} {})]
      (is (= :uix (:substrate with-validator))
          "registered non-default substrate is kept")
      (is (= :helix (:substrate no-validator))
          "with no validator a present substrate is accepted as-is"))))

(deftest apply-parsed-invalid-substrate-degrades-to-reagent
  (testing "rf2-dxz4sg — a present-but-UNREGISTERED substrate (rejected by the
            :substrate? validator) degrades to the :reagent default rather than
            preserving the prior local substrate, so a stale URL can't pin a
            substrate the host app never registered."
    (let [stale {:substrate :uix}
          out   (us/apply-parsed-to-state
                  stale {:substrate :ghost-substrate}
                  {:substrate? #{:reagent :uix :helix}})]
      (is (= :reagent (:substrate out))
          "unregistered substrate= clears to :reagent, not the stale :uix"))))

;; ---- rf2-j0hwf: cell-overrides hydration --------------------------------

(deftest apply-parsed-installs-cell-overrides-under-focused-variant
  (testing "rf2-j0hwf — parsed :cell-overrides are written under
            [:cell-overrides variant-id] so a shared URL / popstate
            restores the same effective args, not just the selection.
            (Reproduces the bead's verification: previously this
            destructure dropped :cell-overrides entirely.)"
    (let [out (us/apply-parsed-to-state
                {} {:variant-id     :story.foo/bar
                    :cell-overrides {:label "Hi"}} {})]
      (is (= :story.foo/bar (:selected-variant out)))
      (is (= {:label "Hi"} (get-in out [:cell-overrides :story.foo/bar]))
          "overrides land under the focused variant"))))

(deftest apply-parsed-overrides-dropped-when-variant-invalid
  (testing "rf2-j0hwf — overrides are variant-scoped: when the variant id
            is rejected by the validator (stale URL) the overrides are
            not installed (no orphan slice under an unfocused variant)"
    (let [out (us/apply-parsed-to-state
                {} {:variant-id     :ghost/x
                    :cell-overrides {:label "Hi"}}
                {:variant? (fn [vid] (= vid :foo/bar))})]
      (is (nil? (:selected-variant out)))
      (is (nil? (get-in out [:cell-overrides :ghost/x]))
          "no overrides installed for a rejected variant"))))

(deftest apply-parsed-overrides-ignored-without-variant
  (testing "rf2-j0hwf — overrides without a variant id never install
            (the URL carries only the focused variant's slice)"
    (let [out (us/apply-parsed-to-state
                {} {:cell-overrides {:label "Hi"}} {})]
      (is (nil? (:selected-variant out)))
      (is (empty? (:cell-overrides out))))))

(deftest apply-parsed-overrides-round-trip-through-state
  (testing "rf2-j0hwf — state → params-from-state → apply-parsed-to-state
            restores the focused variant's overrides slice (the URL
            carries the focused variant only, so the projected
            :cell-overrides is the bare slice, not the side-table)"
    (let [shell  {:selected-variant :foo/bar
                  :cell-overrides   {:foo/bar {:label "Save, continue" :n 3}}}
          proj   (us/params-from-state shell)
          out    (us/apply-parsed-to-state {} proj {})]
      (is (= {:label "Save, continue" :n 3}
             (get-in out [:cell-overrides :foo/bar]))))))

;; ---- rf2-2cpoo: URL is authoritative — clear stale overrides on hydrate ---

(deftest apply-parsed-clears-stale-overrides-when-url-omits-them
  (testing "rf2-2cpoo — hydrating a URL that KEEPS the focused variant but
            carries NO overrides CLEARS the stale in-memory overrides for it.
            Previously the write-only branch left them intact, so back/forward,
            a bookmark, or a share link that no longer encoded overrides still
            rendered the prior control edits — the address bar stopped being the
            source of truth."
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar {:label "stale edit"}}}
          ;; parsed URL: same variant, NO :cell-overrides slice.
          out   (us/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (= :story.foo/bar (:selected-variant out)))
      (is (nil? (get-in out [:cell-overrides :story.foo/bar]))
          "the focused variant's stale overrides are cleared — URL wins"))))

(deftest apply-parsed-overwrites-stale-overrides-when-url-has-new
  (testing "rf2-2cpoo — a URL that carries DIFFERENT overrides for the kept
            variant overwrites the stale slice (not a deep-merge); the slice
            equals the URL payload exactly"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar {:label "stale" :keep "old"}}}
          out   (us/apply-parsed-to-state
                  stale {:variant-id     :story.foo/bar
                         :cell-overrides {:label "fresh"}} {})]
      (is (= {:label "fresh"} (get-in out [:cell-overrides :story.foo/bar]))
          "the slice is REPLACED by the URL payload, dropping the stale :keep"))))

(deftest apply-parsed-clear-leaves-other-variants-overrides-intact
  (testing "rf2-2cpoo — clearing the focused variant's overrides touches ONLY
            its slice; another variant's overrides survive (the URL speaks for
            the focused variant alone)"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar   {:label "stale"}
                                    :story.other/baz {:n 7}}}
          out   (us/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (nil? (get-in out [:cell-overrides :story.foo/bar]))
          "the focused variant's slice is cleared")
      (is (= {:n 7} (get-in out [:cell-overrides :story.other/baz]))
          "an unfocused variant's overrides are left intact"))))

(deftest apply-parsed-share-back-forward-scenario
  (testing "rf2-2cpoo — the back/forward + share scenario end-to-end: an
            override edit, then navigating to an override-free URL for the same
            variant (a bookmark / share link from before the edit) renders
            WITHOUT the edit. state → params (with edit) → params (without) →
            hydrate the override-free one ⇒ no stale slice."
    (let [;; 1. user edits a control — overrides live in state + would push to URL.
          edited      {:selected-variant :foo/bar
                       :cell-overrides   {:foo/bar {:label "edited"}}}
          ;; 2. a share link / bookmark captured BEFORE the edit: same variant,
          ;;    no overrides. params-from-state of a no-override shell yields no slice.
          shared-proj (us/params-from-state {:selected-variant :foo/bar})
          ;; 3. navigating to that URL must clear the in-memory edit.
          out         (us/apply-parsed-to-state edited shared-proj {})]
      (is (= :foo/bar (:selected-variant out)))
      (is (nil? (get-in out [:cell-overrides :foo/bar]))
          "navigating to the override-free shared URL clears the stale edit —
           the address bar is authoritative"))))

;; ---- rf2-gchydo: mode-tab is URL-authoritative too (per-variant) -------
;;
;; `:active-mode-tab` gets the SAME authoritative-clear treatment as
;; `:cell-overrides` (rf2-2cpoo) above — it is per-variant, so the clear
;; is scoped to the focused variant via `dissoc`, NOT an unconditional
;; `:always` write like the global rf2-fkmnh slots below.

(deftest apply-parsed-clears-stale-mode-tab-when-url-omits-it
  (testing "rf2-gchydo — hydrating a URL that KEEPS the focused variant but
            carries NO mode-tab= clears the stale [:active-mode-tab
            variant-id] entry so the reader's :dev default applies.
            Reproduces the bead's repro: select variant A (no mode-tab=),
            Test tab (mode-tab=test), Docs (mode-tab=docs), Back twice to
            the first entry (no mode-tab=) — the stale :docs must not
            survive."
    (let [stale {:selected-variant :story.foo/bar
                 :active-mode-tab  {:story.foo/bar :docs}}
          out   (us/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (= :story.foo/bar (:selected-variant out)))
      (is (nil? (get-in out [:active-mode-tab :story.foo/bar]))
          "the stale entry is dissoc'd, not left at :docs — the reader's
           default-mode-tab (:dev) fallback now applies"))))

(deftest apply-parsed-sets-mode-tab-when-url-carries-it
  (testing "rf2-gchydo — a URL that DOES carry mode-tab= overwrites a stale
            entry (authoritative, not a merge) — the sibling of the clear
            test above"
    (let [stale {:selected-variant :story.foo/bar
                 :active-mode-tab  {:story.foo/bar :test}}
          out   (us/apply-parsed-to-state
                  stale {:variant-id :story.foo/bar :mode-tab :docs} {})]
      (is (= :docs (get-in out [:active-mode-tab :story.foo/bar]))))))

(deftest apply-parsed-clears-mode-tab-leaves-other-variants-intact
  (testing "rf2-gchydo — clearing the focused variant's mode-tab touches
            ONLY its entry; another variant's mode-tab survives (mode-tab
            is per-variant, the URL speaks for the focused variant alone)"
    (let [stale {:selected-variant :story.foo/bar
                 :active-mode-tab  {:story.foo/bar   :docs
                                    :story.other/baz :test}}
          out   (us/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (nil? (get-in out [:active-mode-tab :story.foo/bar]))
          "the focused variant's stale mode-tab is cleared")
      (is (= :test (get-in out [:active-mode-tab :story.other/baz]))
          "an unfocused variant's mode-tab is left intact"))))

(deftest apply-parsed-mode-tab-untouched-when-variant-not-kept
  (testing "rf2-gchydo — mode-tab is per-variant: when NO variant is kept
            (e.g. an invalid/rejected variant id, or none at all) there is
            no focused variant's entry to clear, so OTHER variants'
            mode-tab entries are left alone entirely (unlike the global
            rf2-fkmnh slots below, this is not an unconditional :always
            write)"
    (let [stale {:active-mode-tab {:story.other/baz :test}}
          out   (us/apply-parsed-to-state stale {} {})]
      (is (nil? (:selected-variant out)))
      (is (= :test (get-in out [:active-mode-tab :story.other/baz]))
          "no variant kept -> the mode-tab map is untouched"))))

(deftest apply-parsed-mode-tab-back-forward-scenario
  (testing "rf2-gchydo — the bead's concrete Back/Back scenario end-to-end:
            select variant (no mode-tab), Test tab (mode-tab=test), Docs
            tab (mode-tab=docs), Back twice to the first (variant-only)
            history entry — the address bar has reverted to no mode-tab=,
            so hydrating it must revert the canvas's active tab too"
    (let [;; History entry 1: variant selected, default (:dev) tab — no
          ;; mode-tab= param, matching share/parse-params' shape for it.
          entry-1 {:variant-id :story.foo/bar}
          ;; The Test/Docs clicks pushed mode-tab=test then mode-tab=docs;
          ;; simulate the resulting stale in-memory state just before
          ;; Back/Back lands on entry-1 again.
          stale   {:selected-variant :story.foo/bar
                   :active-mode-tab  {:story.foo/bar :docs}}
          out     (us/apply-parsed-to-state stale entry-1 {})]
      (is (= :story.foo/bar (:selected-variant out)))
      (is (nil? (get-in out [:active-mode-tab :story.foo/bar]))
          "Back/Back to the mode-tab-less entry reverts the stale :docs —
           address bar and rendered UI agree again"))))

;; ---- rf2-fkmnh: URL authoritative for ALL URL-owned chrome slots --------

(deftest apply-parsed-clears-active-modes-when-url-omits-modes
  (testing "rf2-fkmnh — hydrating a URL that omits `modes=` clears stale
            `:active-modes` to []. A share link like ?variant=foo/bar must
            restore the DEFAULT (no modes) view for the recipient, not keep
            their localStorage-seeded modes."
    (let [stale {:active-modes [:m/dark :m/grid]}
          out   (us/apply-parsed-to-state stale {:variant-id :foo/bar} {})]
      (is (= [] (:active-modes out))
          "omitted modes= clears active-modes to the empty default"))))

(deftest apply-parsed-sets-active-modes-when-url-carries-them
  (testing "rf2-fkmnh — a URL that DOES carry modes= overwrites stale modes
            (authoritative, not a merge)"
    (let [stale {:active-modes [:m/light]}
          out   (us/apply-parsed-to-state
                  stale {:variant-id :foo/bar :active-modes [:m/dark]} {})]
      (is (= [:m/dark] (:active-modes out))))))

(deftest apply-parsed-clears-tag-filter-when-url-omits-it
  (testing "rf2-fkmnh — omitted tag-filter= clears `:tag-filter` to #{}"
    (let [stale {:tag-filter #{:tag/a :tag/b}}
          out   (us/apply-parsed-to-state stale {:variant-id :foo/bar} {})]
      (is (= #{} (:tag-filter out))
          "omitted tag-filter= clears to the empty set default"))))

(deftest apply-parsed-clears-viewport-when-url-omits-it
  (testing "rf2-fkmnh — omitted viewport= clears `:viewport` to nil so the
            chrome falls back to the :full default"
    (let [stale {:viewport :tablet}
          out   (us/apply-parsed-to-state stale {:variant-id :foo/bar} {})]
      (is (nil? (:viewport out))
          "omitted viewport= clears to nil (chrome resolves to :full)"))))

(deftest apply-parsed-clears-background-when-url-omits-it
  (testing "rf2-fkmnh — omitted background= clears `:background` to nil"
    (let [stale {:background :dark}
          out   (us/apply-parsed-to-state stale {:variant-id :foo/bar} {})]
      (is (nil? (:background out))
          "omitted background= clears to nil (chrome resolves to default)"))))

(deftest apply-parsed-clears-invalid-viewport-rather-than-keeping-stale
  (testing "rf2-fkmnh — a present-but-INVALID viewport (rejected by the
            validator) clears the slot rather than preserving the stale
            in-memory value, so a poisoned URL still degrades to default"
    (let [stale {:viewport :tablet}
          out   (us/apply-parsed-to-state
                  stale {:viewport :nonsense}
                  {:viewport? (fn [v] (= v :phone))})]
      (is (nil? (:viewport out))
          "invalid viewport= clears the stale value, not preserves it"))))

(deftest apply-parsed-clears-invalid-background-rather-than-keeping-stale
  (testing "rf2-fkmnh — a present-but-INVALID background clears the slot"
    (let [stale {:background :dark}
          out   (us/apply-parsed-to-state
                  stale {:background :nonsense}
                  {:background? (fn [b] (= b :light))})]
      (is (nil? (:background out))))))

(deftest apply-parsed-empty-url-clears-every-url-owned-slot
  (testing "rf2-fkmnh — applying the all-nil parsed shape (a no-query
            popstate / share URL that omits everything) clears ALL
            URL-owned slots back to their defaults — selection, modes,
            viewport, background, tag-filter — so the address bar is the
            source of truth even when it carries no params."
    (let [stale {:selected-variant :foo/bar
                 :active-modes     [:m/dark]
                 :viewport         :tablet
                 :background       :dark
                 :tag-filter       #{:tag/a}
                 :substrate        :uix}
          ;; the shape share/parse-params produces for an empty getter:
          empty-parsed {:variant-id nil :workspace-id nil :mode-tab nil
                        :active-modes nil :viewport nil :background nil
                        :tag-filter nil :cell-overrides nil :substrate nil}
          out   (us/apply-parsed-to-state stale empty-parsed {})]
      (is (nil? (:selected-variant out)))
      (is (nil? (:selected-workspace out)))
      (is (= [] (:active-modes out)))
      (is (nil? (:viewport out)))
      (is (nil? (:background out)))
      (is (= #{} (:tag-filter out)))
      ;; rf2-dxz4sg — substrate is a URL-owned slot too: a bare URL clears it
      ;; to the :reagent default rather than leaking the stale :uix.
      (is (= :reagent (:substrate out))))))

(deftest apply-parsed-share-counter-link-restores-default-chrome
  (testing "rf2-fkmnh — the bead's concrete scenario: a recipient with prior
            localStorage-seeded chrome (modes/viewport/background/tag-filter)
            opens a share link `?variant=story.counter/loaded` that carries
            ONLY the variant. The recipient lands on the variant with the
            DEFAULT chrome, not their stale local framing."
    (let [recipient-local {:active-modes [:m/dark]
                           :viewport     :phone
                           :background   :light
                           :tag-filter   #{:tag/legacy}
                           :substrate    :helix}
          ;; share/parse-params of just ?variant=story.counter/loaded
          shared {:variant-id :story.counter/loaded}
          out    (us/apply-parsed-to-state recipient-local shared {})]
      (is (= :story.counter/loaded (:selected-variant out)))
      (is (= [] (:active-modes out)) "recipient's local modes cleared")
      (is (nil? (:viewport out))     "recipient's local viewport cleared")
      (is (nil? (:background out))   "recipient's local background cleared")
      (is (= #{} (:tag-filter out))  "recipient's local tag-filter cleared")
      ;; rf2-dxz4sg — the omitted substrate= means :reagent, so the
      ;; recipient's stale :helix is cleared to the default.
      (is (= :reagent (:substrate out)) "recipient's local substrate cleared"))))

(deftest apply-parsed-full-round-trip-through-state
  (testing "URL → parsed → state, then state → URL produces equivalent
            params (allowing for set vs vec re-ordering)"
    (let [parsed   {:variant-id   :foo/bar
                    :mode-tab     :docs
                    :active-modes [:m/dark]
                    :viewport     :tablet
                    :background   :dark
                    :tag-filter   #{:tag/a :tag/b}
                    :substrate    :uix}
          state    (us/apply-parsed-to-state {} parsed {})
          re-proj  (us/params-from-state state)]
      (is (= :foo/bar       (:variant-id   re-proj)))
      (is (= :docs          (:mode-tab     re-proj)))
      (is (= [:m/dark]      (:active-modes re-proj)))
      (is (= :tablet        (:viewport     re-proj)))
      (is (= :dark          (:background   re-proj)))
      (is (= #{:tag/a :tag/b} (:tag-filter  re-proj)))
      (is (= :uix           (:substrate    re-proj))))))
