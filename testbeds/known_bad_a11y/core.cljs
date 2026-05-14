(ns known-bad-a11y.core
  "Shared framework-behavior testbed — a Story variant whose body
  carries deliberate axe-core a11y violations. A consumer (Story's
  a11y panel) verifies that:

    1. axe-core reports the violations against the user-authored
       variant's DOM, scoped to the `data-rf-story-variant-root`
       wrapper (per rf2-qgms1's fix — PR #1080).
    2. Story's OWN chrome (sidebar, toolbar, panels, title bar) is
       NOT flagged by the same scan — even if the chrome contains
       elements that would otherwise trip axe-core (e.g. an icon
       button without an aria-label, a colour-contrast warning on
       a faint label).

  This is NOT a tutorial. The variant body is deliberately minimal
  — five well-known a11y violations packed into the smallest DOM
  that triggers each axe-core rule. The bodies have no other
  business logic.

  Five violations on the bad variant's root:

    1. `img-alt` — `<img>` without `alt` attribute. Axe rule:
       `image-alt`, impact: critical.
    2. `button-name` — `<button>` with no accessible name (no text,
       no `aria-label`). Axe rule: `button-name`, impact: critical.
    3. `label` — `<input>` with no associated `<label>`. Axe rule:
       `label`, impact: critical.
    4. `link-name` — `<a>` with no accessible name. Axe rule:
       `link-name`, impact: serious.
    5. `color-contrast` — text with insufficient contrast against
       background. Axe rule: `color-contrast`, impact: serious.

  The good variant body has the same elements WITH proper a11y
  attributes — axe-core's scan must produce zero violations on it.
  The two variants share the same Story so a consumer asserts
  pairwise: bad has N≥5 violations, good has 0, both run against
  the same variant-root scope, neither flags Story chrome."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.story :as story]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ----------------------------------------------------------------------------
;; The bad-variant view — five deliberate a11y violations
;; ----------------------------------------------------------------------------
;;
;; HOT PATH — every violation site is one line; the elements have no
;; other purpose than to trigger their axe-core rule.

(reg-view bad-variant []
  [:section {:data-testid "known-bad-a11y-bad"}
   [:h2 "Known-bad a11y variant"]

   ;; Violation 1 — image without alt.
   [:img {:data-testid "bad-img"
          :src         "data:image/gif;base64,R0lGODlhAQABAAAAACw="
          :style       {:width 16 :height 16}}]

   ;; Violation 2 — button without accessible name.
   [:button {:data-testid "bad-button"
             :style       {:width 16 :height 16 :background "#888"}}]

   ;; Violation 3 — unlabeled input.
   [:input {:data-testid "bad-input"
            :type        "text"}]

   ;; Violation 4 — anchor without accessible name.
   [:a {:data-testid "bad-link" :href "#"} ""]

   ;; Violation 5 — insufficient colour contrast (light grey on white).
   [:p {:data-testid "bad-contrast"
        :style       {:color "#eaeaea" :background "#ffffff"}}
    "Very faint text that fails the WCAG AA contrast threshold."]])

;; ----------------------------------------------------------------------------
;; The good-variant view — same elements, WITH proper a11y attributes
;; ----------------------------------------------------------------------------

(reg-view good-variant []
  [:section {:data-testid "known-bad-a11y-good"}
   [:h2 "A11y-clean variant"]

   ;; Same image, WITH alt.
   [:img {:data-testid "good-img"
          :src         "data:image/gif;base64,R0lGODlhAQABAAAAACw="
          :alt         "A small placeholder pixel"
          :style       {:width 16 :height 16}}]

   ;; Same button, WITH text content (accessible name).
   [:button {:data-testid "good-button"
             :style       {:width 80 :height 24}}
    "Click me"]

   ;; Same input, WITH associated label.
   [:label {:html-for "good-input-id"} "Name: "
    [:input {:id          "good-input-id"
             :data-testid "good-input"
             :type        "text"}]]

   ;; Same anchor, WITH text content.
   [:a {:data-testid "good-link" :href "#"} "Home"]

   ;; Same paragraph, WITH high-contrast colours.
   [:p {:data-testid "good-contrast"
        :style       {:color "#000000" :background "#ffffff"}}
    "High-contrast text that meets the WCAG AA threshold."]])

;; ----------------------------------------------------------------------------
;; Story registrations — one parent story, two variants
;; ----------------------------------------------------------------------------

(defn register-all!
  []
  (story/install-canonical-vocabulary!)

  (story/reg-story :story.a11y
    {:doc        "Known-bad / known-good a11y variants — used to verify
                 the a11y panel scopes axe-core to the variant root
                 (rf2-qgms1, PR #1080) and reports violations only
                 from the user-authored variant, not Story chrome."
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.a11y/known-bad
    {:doc        "Five deliberate axe-core violations packed into the
                 smallest DOM that triggers each rule. The a11y panel
                 should surface every one of them."
     :component  :known-bad-a11y.core/bad-variant
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.a11y/known-good
    {:doc        "Same shape as :story.a11y/known-bad but with proper
                 a11y attributes. The a11y panel should surface zero
                 violations on this variant — proves the scan is
                 scoped to the variant root, not Story chrome."
     :component  :known-bad-a11y.core/good-variant
     :tags       #{:dev :test}
     :substrates #{:reagent}}))

;; Fire the registrations once at namespace load.
(register-all!)

;; ----------------------------------------------------------------------------
;; Mount — Story shell directly, no hash routing
;; ----------------------------------------------------------------------------
;;
;; This testbed boots straight into the Story shell. A consumer
;; (Story's a11y panel + a Playwright spec) drives variant selection
;; via the sidebar; the URL ergonomics live in the consuming Story
;; example (counter_with_stories), not here.

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (story/install-canonical-vocabulary!)
  (story/configure! {:global-args {:locale :en}})
  (story/mount-shell! (js/document.getElementById "app")))
