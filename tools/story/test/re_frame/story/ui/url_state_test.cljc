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
  (testing "changes to non-URL slots (hot-reload-tick, cell-overrides,
            fingerprints, panel-visibility) do NOT trigger a push"
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :hot-reload-tick 0}
               {:selected-variant :foo/a :hot-reload-tick 99})))
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :cell-overrides {}}
               {:selected-variant :foo/a :cell-overrides {:foo/a {:x 1}}})))
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a :fingerprints {}}
               {:selected-variant :foo/a :fingerprints {:foo/a {:dec :h}}})))
    (is (not (us/url-relevant-slots-changed?
               {:selected-variant :foo/a
                :panel-visibility {:trace true}}
               {:selected-variant :foo/a
                :panel-visibility {:trace false}})))))

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
