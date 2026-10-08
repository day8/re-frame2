(ns re-frame.story.ui.url-state-test
  "Pure tests for the URL-state engine: projection, URL composition, the
  watcher's slot diff and hydration back into shell state."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.url-state :as rf.story.ui.url-state]))

;; ---- params-from-state ---------------------------------------------------

;; Exact `=` on the whole projection, so a nil-valued extra key fails a row.

(deftest params-from-state-projects-exactly-the-focused-slots
  (are [shell expected] (= expected (rf.story.ui.url-state/params-from-state shell))
    {:selected-variant :story.foo/bar}
    {:variant-id :story.foo/bar}

    {:selected-workspace :story.foo/grid}
    {:workspace-id :story.foo/grid}

    ;; mode-tab is projected from the focused variant's slot
    {:selected-variant :story.foo/bar
     :active-mode-tab  {:story.foo/bar :docs
                        :story.other/x :test}}
    {:variant-id :story.foo/bar
     :mode-tab   :docs}

    ;; cell-overrides only project for the focused variant
    {:selected-variant :story.foo/bar
     :cell-overrides   {:story.foo/bar  {:label "Hi"}
                        :story.other/x  {:label "Hidden"}}}
    {:variant-id     :story.foo/bar
     :cell-overrides {:label "Hi"}}))

;; ---- url-from-state ------------------------------------------------------

(deftest url-from-state-includes-every-populated-slot
  (is (= (str "/foo/?variant=foo%2Fbar&mode-tab=docs&modes=m%2Fdark"
              "&viewport=tablet&background=dark&tag-filter=tag%2Fa"
              "&substrate=uix#/stories")
         (rf.story.ui.url-state/url-from-state
           {:selected-variant   :foo/bar
            :active-mode-tab    {:foo/bar :docs}
            :active-modes       [:m/dark]
            :viewport           :tablet
            :background         :dark
            :tag-filter         #{:tag/a}
            :substrate          :uix}
           {:pathname "/foo/" :hash "#/stories"}))))

(deftest url-from-state-no-bare-question-mark-when-query-empties
  (testing "a bare `?` differs from `location.search`'s empty string, so
            the watcher would push a history entry it can never match"
    (is (= "/p/#/stories"
           (rf.story.ui.url-state/url-from-state {} {:pathname "/p/"
                                  :search   "?variant=story.old%2Fa"
                                  :hash     "#/stories"})))))

;; The state-watcher pushes this on every URL-relevant change, so it must
;; keep the params Story does not own (`embed=1`, a referrer `from=`) and
;; clear the ones it does — the boundary `rf.story.share/apply-story-params`
;; draws for the share builder too. Composing from `{:pathname :hash}`
;; alone would erase every unowned param on the first state change.

(deftest url-from-state-and-share-builder-agree-on-ownership
  (testing "given the same base and the same cell, the address-bar composer
            and the share-URL builder produce the same string"
    (let [pathname "/counter-with-stories/"
          search   "?from=index&embed=1&substrate=uix"
          hash     "#/stories"]
      (is (= (rf.story.share/variant-share-url
               :story.new/b
               (str pathname search hash)
               {:substrate :reagent})
             (rf.story.ui.url-state/url-from-state
               {:selected-variant :story.new/b :substrate :reagent}
               {:pathname pathname :search search :hash hash}))))))

;; ---- url-relevant-slots-changed? ----------------------------------------

(deftest url-relevant-slots-changed-diffs-only-url-slots
  (testing "a change to any URL-owned slot triggers a push"
    (are [old new] (rf.story.ui.url-state/url-relevant-slots-changed? old new)
      {:selected-variant :foo/a}       {:selected-variant :foo/b}
      {:selected-workspace :foo/a}     {:selected-workspace :foo/b}
      {:active-mode-tab {:foo/a :dev}} {:active-mode-tab {:foo/a :docs}}
      {:viewport :full}                {:viewport :tablet}
      {:background :light}             {:background :dark}
      {:tag-filter #{}}                {:tag-filter #{:tag/a}}
      {:active-modes []}               {:active-modes [:m/dark]}
      {:substrate :reagent}            {:substrate :uix}

      ;; a controls edit on the FOCUSED variant's overrides pushes, so the
      ;; live address bar round-trips the override
      {:selected-variant :foo/a :cell-overrides {}}
      {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}}))
  (testing "changes off the URL do NOT trigger a push"
    (are [old new] (not (rf.story.ui.url-state/url-relevant-slots-changed? old new))
      {:selected-variant :foo/a :hot-reload-tick 0}
      {:selected-variant :foo/a :hot-reload-tick 99}

      ;; a NON-focused variant's overrides stay off the URL
      {:selected-variant :foo/a :cell-overrides {:foo/b {:x 1}}}
      {:selected-variant :foo/a :cell-overrides {:foo/b {:x 2}}}

      ;; with no focused variant, an overrides edit never pushes
      {:selected-variant nil :cell-overrides {}}
      {:selected-variant nil :cell-overrides {:foo/a {:x 1}}})))

;; ---- apply-parsed-to-state ----------------------------------------------

(deftest apply-parsed-workspace-only
  (is (= [:foo/grid nil]
         ((juxt :selected-workspace :selected-variant)
          (rf.story.ui.url-state/apply-parsed-to-state
            {} {:workspace-id :foo/grid} {})))))

(deftest apply-parsed-variant-wins-over-workspace
  (testing "a URL carrying both (a crafted URL, a stale bookmark) selects the variant"
    (is (= [:foo/bar nil]
           ((juxt :selected-variant :selected-workspace)
            (rf.story.ui.url-state/apply-parsed-to-state
              {:selected-workspace :foo/old}
              {:variant-id   :foo/bar
               :workspace-id :foo/grid}
              {}))))))

(deftest apply-parsed-validators-drop-unknown-workspace
  (testing "unknown workspace id is dropped"
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:workspace-id :ghost/grid}
                {:workspace? (fn [_] false)})]
      (is (nil? (:selected-workspace out))))))

;; ---- substrate authoritative-clear ---------------------------------------
;;
;; The build side omits `substrate=` to encode the `:reagent` default, so an
;; omitted or invalid parsed substrate MUST hydrate as `:reagent` rather
;; than keep the recipient's stale in-memory `:uix`.

(deftest apply-parsed-explicit-non-default-substrate-round-trips
  (testing "a registered non-default substrate is kept; with no
            :substrate? validator any present id is accepted"
    (let [with-validator (rf.story.ui.url-state/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :uix}
                           {:substrate? #{:reagent :uix}})
          no-validator   (rf.story.ui.url-state/apply-parsed-to-state
                           {:substrate :reagent} {:substrate :custom} {})]
      (is (= :uix (:substrate with-validator)))
      (is (= :custom (:substrate no-validator))))))

(deftest apply-parsed-invalid-substrate-degrades-to-reagent
  (testing "an UNREGISTERED substrate degrades to :reagent rather than
            keeping the prior local substrate"
    (let [stale {:substrate :uix}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:substrate :ghost-substrate}
                  {:substrate? #{:reagent :uix}})]
      (is (= :reagent (:substrate out))))))

;; ---- cell-overrides hydration --------------------------------------------

(deftest apply-parsed-installs-cell-overrides-under-focused-variant
  (testing "a shared URL / popstate restores the effective args, not just
            the selection"
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:variant-id     :story.foo/bar
                    :cell-overrides {:label "Hi"}} {})]
      (is (= {:label "Hi"} (get-in out [:cell-overrides :story.foo/bar]))))))

(deftest apply-parsed-overrides-dropped-when-variant-invalid
  (testing "overrides are variant-scoped: a rejected variant installs none"
    (let [out (rf.story.ui.url-state/apply-parsed-to-state
                {} {:variant-id     :ghost/x
                    :cell-overrides {:label "Hi"}}
                {:variant? (fn [vid] (= vid :foo/bar))})]
      (is (nil? (get-in out [:cell-overrides :ghost/x]))))))

;; ---- URL is authoritative — clear stale overrides on hydrate -------------

(deftest apply-parsed-overwrites-stale-overrides-when-url-has-new
  (testing "the URL payload REPLACES the kept variant's slice — not a deep-merge"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar {:label "stale" :keep "old"}}}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id     :story.foo/bar
                         :cell-overrides {:label "fresh"}} {})]
      (is (= {:label "fresh"} (get-in out [:cell-overrides :story.foo/bar]))))))

(deftest apply-parsed-clear-leaves-other-variants-overrides-intact
  (testing "a URL with no overrides clears the focused variant's slice only"
    (let [stale {:selected-variant :story.foo/bar
                 :cell-overrides   {:story.foo/bar   {:label "stale"}
                                    :story.other/baz {:n 7}}}
          out   (rf.story.ui.url-state/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
      (is (= {:story.other/baz {:n 7}} (:cell-overrides out))))))

;; ---- mode-tab is URL-authoritative too (per-variant) --------------------
;;
;; Per-variant like `:cell-overrides`, so its clear is a `dissoc` scoped to
;; the focused variant, not an unconditional write like the global slots.

(deftest apply-parsed-sets-mode-tab-when-url-carries-it
  (let [stale {:selected-variant :story.foo/bar
               :active-mode-tab  {:story.foo/bar :test}}
        out   (rf.story.ui.url-state/apply-parsed-to-state
                stale {:variant-id :story.foo/bar :mode-tab :docs} {})]
    (is (= :docs (get-in out [:active-mode-tab :story.foo/bar])))))

(deftest apply-parsed-clears-mode-tab-leaves-other-variants-intact
  (let [stale {:selected-variant :story.foo/bar
               :active-mode-tab  {:story.foo/bar   :docs
                                  :story.other/baz :test}}
        out   (rf.story.ui.url-state/apply-parsed-to-state stale {:variant-id :story.foo/bar} {})]
    (is (= {:story.other/baz :test} (:active-mode-tab out)))))

(deftest apply-parsed-mode-tab-untouched-when-variant-not-kept
  (testing "with no kept variant the URL's mode-tab is written nowhere"
    (let [stale {:active-mode-tab {:story.other/baz :test}}
          out   (rf.story.ui.url-state/apply-parsed-to-state
                  stale {:variant-id :ghost/x :mode-tab :docs}
                  {:variant? (fn [_] false)})]
      (is (nil? (:selected-variant out)))
      (is (= {:story.other/baz :test} (:active-mode-tab out))))))

;; ---- URL authoritative for ALL URL-owned chrome slots --------------------

(deftest apply-parsed-clears-invalid-chrome-rather-than-keeping-stale
  (testing "a present-but-INVALID viewport or background clears the slot,
            so a poisoned URL still degrades to default"
    (is (= [nil nil]
           ((juxt :viewport :background)
            (rf.story.ui.url-state/apply-parsed-to-state
              {:viewport :tablet :background :dark}
              {:viewport :nonsense :background :nonsense}
              {:viewport? #{:phone} :background? #{:light}}))))))

(deftest apply-parsed-empty-url-clears-every-url-owned-slot
  (testing "the all-nil parsed shape (a no-query popstate, a share URL
            that omits everything) clears every URL-owned slot to its default"
    (is (= {:selected-variant   nil
            :selected-workspace nil
            :active-modes       []
            :viewport           nil
            :background         nil
            :tag-filter         #{}
            :substrate          :reagent}
           (rf.story.ui.url-state/apply-parsed-to-state
             {:selected-variant :foo/bar
              :active-modes     [:m/dark]
              :viewport         :tablet
              :background       :dark
              :tag-filter       #{:tag/a}
              :substrate        :uix}
             (rf.story.share/parse-params {})
             {})))))

(deftest apply-parsed-full-round-trip-through-state
  (testing "URL → parsed → state → params reproduces the parsed slots"
    (let [parsed {:variant-id   :foo/bar
                  :mode-tab     :docs
                  :active-modes [:m/dark]
                  :viewport     :tablet
                  :background   :dark
                  :tag-filter   #{:tag/a :tag/b}
                  :substrate    :uix}]
      (is (= parsed
             (rf.story.ui.url-state/params-from-state
               (rf.story.ui.url-state/apply-parsed-to-state {} parsed {})))))))
