(ns re-frame.story.ui.test-mode.view-styles
  "Style map for `re-frame.story.ui.test-mode.view`. Pure data; no
  Reagent dependency. Extracted from `view.cljs` per rf2-gv5kq so the
  view ns drops below the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only — the JVM pure helpers don't need styles."
  (:require [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]))

(def styles
  {:wrap          {:flex             "1"
                   :overflow         "auto"
                   :padding          "20px 28px"
                   :background       (:bg-canvas colors/tokens)
                   :color            (:text-primary colors/tokens)
                   :font-family      sans-stack
                   :font-size        (:body typography/type-scale)
                   :line-height      "1.5"}
   :h1            {:font-family      mono-stack
                   :font-size        (:display typography/type-scale)
                   :font-weight      "bold"
                   :color            (:text-primary colors/tokens)
                   :margin           "0 0 4px 0"}
   :sub           {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :margin-bottom    "10px"}
   :header-row    {:display          "flex"
                   :justify-content  "space-between"
                   :align-items      "center"
                   :gap              "12px"
                   :margin           "12px 0 8px 0"}
   :rerun-btn     {:padding          "6px 14px"
                   :background       (:accent-amber colors/tokens)
                   :color            (:text-on-accent colors/tokens)
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :letter-spacing   "0.3px"}
   :rerun-running {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)
                   :cursor           "not-allowed"}
   :last-run      {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :section       {:margin-top       "16px"}
   :section-h     {:font-weight      "bold"
                   :color            (:text-secondary colors/tokens)
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"
                   :margin-bottom    "8px"
                   :border-bottom    (str "1px solid " (:border-default colors/tokens))
                   :padding-bottom   "4px"}
   :pill-row      {:display          "flex"
                   :align-items      "center"
                   :gap              "12px"
                   :margin-bottom    "8px"}
   :pill          {:padding          "4px 10px"
                   :border-radius    "10px"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :font-weight      "bold"
                   :text-transform   "uppercase"
                   :letter-spacing   "0.5px"}
   :pill-pass     {:background       (:success-bg colors/tokens)
                   :color            (:success colors/tokens)}
   :pill-fail     {:background       (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)}
   :pill-empty    {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)}
   ;; rf2-ba86n.11 — `:error` reads as a louder fail; `:cannot-run` is the
   ;; distinct THIRD state (spec/018 §12.6 — visually distinct from
   ;; pass/fail/error); `:pending` is the muted never-run/no-signal state.
   :pill-error    {:background       (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)
                   :border           (str "1px solid " (:danger colors/tokens))}
   :pill-cannot   {:background       (:warning-bg colors/tokens)
                   :color            (:warning colors/tokens)}
   :pill-pending  {:background       (:bg-3 colors/tokens)
                   :color            (:text-tertiary colors/tokens)}
   :counts        {:color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :count-pass    {:color            (:success colors/tokens)}
   :count-fail    {:color            (:danger colors/tokens)}
   :count-skip    {:color            (:text-tertiary colors/tokens)}
   :table         {:width            "100%"
                   :border-collapse  "collapse"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :th            {:text-align       "left"
                   :padding          "6px 8px"
                   :background       (:bg-2 colors/tokens)
                   :color            (:text-secondary colors/tokens)
                   :border-bottom    (str "1px solid " (:border-default colors/tokens))
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"}
   :td            {:padding          "6px 8px"
                   :border-bottom    (str "1px solid " (:border-subtle colors/tokens))
                   :color            (:text-primary colors/tokens)
                   :vertical-align   "top"}
   :td-status     {:width            "20px"
                   :text-align       "center"
                   :font-size        (:display typography/type-scale)
                   :line-height      "1"}
   :status-pass   {:color            (:success colors/tokens)}
   :status-fail   {:color            (:danger colors/tokens)}
   :status-skip   {:color            (:text-tertiary colors/tokens)}
   :row-fail      {:background       (:row-fail-bg colors/tokens)}
   :details-tog   {:cursor           "pointer"
                   :color            (:info colors/tokens)
                   :background       "none"
                   :border           "none"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :padding          "0"
                   :text-decoration  "underline"}
   :detail-box    {:background       (:bg-2 colors/tokens)
                   :border-left      (str "3px solid " (:danger colors/tokens))
                   :padding          "8px 12px"
                   :margin-top       "6px"
                   :color            (:text-primary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)
                   :white-space      "pre-wrap"}
   :detail-key    {:color            (:info colors/tokens)}
   :detail-source {:color            (:text-secondary colors/tokens)
                   :font-size        (:micro typography/type-scale)
                   :margin-top       "4px"}
   :empty         {:padding          "32px"
                   :color            (:text-tertiary colors/tokens)
                   :font-style       "italic"
                   :font-family      sans-stack
                   :text-align       "center"
                   :background       (:bg-canvas colors/tokens)
                   :flex             "1"}
   :empty-link    {:color            (:info colors/tokens)
                   :font-family      mono-stack
                   :margin-top       "8px"
                   :display          "block"}

   ;; ---- unified run-result surfaces (rf2-ba86n.11) ----------------
   ;; runner selected vs required badge (spec/021 §1)
   :runner-row    {:display          "flex"
                   :align-items      "center"
                   :gap              "8px"
                   :flex-wrap        "wrap"
                   :margin-top       "6px"
                   :color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :runner-badge  {:padding          "2px 7px"
                   :border-radius    "3px"
                   :background       (:bg-3 colors/tokens)
                   :color            (:text-primary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :runner-key    {:color            (:text-tertiary colors/tokens)
                   :text-transform   "uppercase"
                   :letter-spacing   "0.4px"}
   ;; failed-only filter toggle (spec/021 §1)
   :filter-row    {:display          "flex"
                   :align-items      "center"
                   :gap              "8px"
                   :margin-bottom    "8px"}
   :filter-toggle {:padding          "3px 10px"
                   :border           (str "1px solid " (:border-strong colors/tokens))
                   :border-radius    "3px"
                   :background       "none"
                   :color            (:text-secondary colors/tokens)
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :filter-on     {:background       (:accent-amber-soft colors/tokens)
                   :color            (:accent-amber colors/tokens)
                   :border           (str "1px solid " (:accent-amber-deep colors/tokens))}
   :filter-hint   {:color            (:text-tertiary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   ;; checks grouped by check id (spec/021 §1)
   :check-box     {:border           (str "1px solid " (:border-subtle colors/tokens))
                   :border-radius    "4px"
                   :margin-bottom    "6px"
                   :background       (:bg-2 colors/tokens)}
   :check-head    {:display          "flex"
                   :align-items      "center"
                   :gap              "8px"
                   :padding          "6px 10px"
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :check-id      {:color            (:text-primary colors/tokens)
                   :font-weight      "bold"}
   :check-counts  {:color            (:text-secondary colors/tokens)
                   :font-size        (:micro typography/type-scale)
                   :margin-left      "auto"}
   :check-body    {:padding          "0 10px 8px 10px"}
   ;; schema-violation rows (spec/021 §1)
   :schema-row    {:padding          "6px 10px"
                   :border-left      "3px solid"
                   :margin-bottom    "5px"
                   :background       (:bg-2 colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :schema-consumed {:border-left-color (:success colors/tokens)}
   :schema-unconsumed {:border-left-color (:danger colors/tokens)}
   :schema-tag    {:display          "inline-block"
                   :padding          "1px 6px"
                   :border-radius    "8px"
                   :font-size        (:micro typography/type-scale)
                   :text-transform   "uppercase"
                   :letter-spacing   "0.4px"
                   :margin-right     "8px"}
   :schema-tag-ok {:background       (:success-bg colors/tokens)
                   :color            (:success colors/tokens)}
   :schema-tag-bad {:background      (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)}
   :schema-sel    {:color            (:text-primary colors/tokens)}
   :schema-meta   {:color            (:text-tertiary colors/tokens)
                   :font-size        (:micro typography/type-scale)
                   :margin-top       "3px"}
   ;; cannot-run rows (spec/021 §1; spec/018 §12.6 — distinct THIRD state)
   :cannot-row    {:padding          "6px 10px"
                   :border-left      (str "3px solid " (:warning colors/tokens))
                   :margin-bottom    "5px"
                   :background       (:warning-bg colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:caption typography/type-scale)}
   :cannot-key    {:color            (:warning colors/tokens)
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.4px"
                   :margin-right     "6px"}
   :cannot-val    {:color            (:text-primary colors/tokens)}
   ;; result → evidence link (spec/021 §2)
   :evidence-row  {:margin-top       "10px"
                   :padding          "8px 12px"
                   :border           (str "1px dashed " (:border-strong colors/tokens))
                   :border-radius    "4px"
                   :background       (:bg-2 colors/tokens)
                   :color            (:text-secondary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)}
   :evidence-pending {:color         (:text-tertiary colors/tokens)
                      :font-style    "italic"}

   ;; ---- step-through scrubber (rf2-lc36w) -------------------------
   :scrub-wrap    {:margin           "8px 0 0 0"
                   :padding          "8px 10px"
                   :background       (:bg-2 colors/tokens)
                   :border           (str "1px solid " (:border-strong colors/tokens))
                   :border-radius    "4px"}
   :scrub-h       {:font-weight      "bold"
                   :color            (:text-secondary colors/tokens)
                   :text-transform   "uppercase"
                   :font-size        (:micro typography/type-scale)
                   :letter-spacing   "0.5px"
                   :margin-bottom    "6px"
                   :display          "flex"
                   :justify-content  "space-between"
                   :align-items      "center"}
   :scrub-ticks   {:display          "flex"
                   :gap              "3px"
                   :align-items      "center"
                   :flex-wrap        "wrap"
                   :margin-bottom    "6px"}
   :scrub-tick    {:display          "inline-block"
                   :min-width        "14px"
                   :height           "14px"
                   :line-height      "14px"
                   :text-align       "center"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)
                   :padding          "0 4px"
                   :user-select      "none"}
   :tick-pass     {:background       (:success-bg colors/tokens)
                   :color            (:success colors/tokens)}
   :tick-fail     {:background       (:danger-bg colors/tokens)
                   :color            (:danger colors/tokens)}
   :tick-event    {:background       (:bg-3 colors/tokens)
                   :color            (:text-secondary colors/tokens)}
   :tick-skip     {:background       (:bg-2 colors/tokens)
                   :color            (:text-tertiary colors/tokens)}
   :tick-selected {:outline          (str "2px solid " (:info colors/tokens))
                   :outline-offset   "1px"}
   :scrub-slider  {:width            "100%"
                   :margin           "4px 0"}
   :scrub-detail  {:color            (:text-tertiary colors/tokens)
                   :font-family      mono-stack
                   :font-size        (:micro typography/type-scale)
                   :margin-top       "4px"
                   :display          "flex"
                   :gap              "10px"
                   :flex-wrap        "wrap"}
   :scrub-release {:padding          "2px 8px"
                   :background       (:border-strong colors/tokens)
                   :color            (:text-primary colors/tokens)
                   :border           "none"
                   :border-radius    "3px"
                   :cursor           "pointer"
                   :font-size        (:micro typography/type-scale)
                   :font-family      mono-stack}})
