(ns re-frame.story.ui.shell-styles
  "Style map for `re-frame.story.ui.shell`. Pure data; no Reagent
  dependency. Extracted from `shell.cljs` per rf2-gv5kq so the shell
  ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only."
  (:require [re-frame.story.theme.typography :as typography :refer [sans-stack mono-stack]]
            [re-frame.story.theme.colors :as colors]
            [re-frame.story.theme.depth :as depth]))

(def styles
  {:root      {:display "flex"
               :flex-direction "column"
               :height "100vh"
               :font-family sans-stack
               ;; rf2-ypd6h: atmospheric backdrop — radial-gradient mesh
               ;; over the deepest slate ground, lifts the shell out of
               ;; the 'editor pane' flat-solid floor.
               :background (:shell-root depth/backdrops)
               :color (:text-primary colors/tokens)}
   :body      {:display "flex"
               :flex-direction "row"
               :flex "1"
               :min-height "0"
               :overflow "hidden"}
   :body-narrow {:flex-direction "column"
                 :overflow "auto"}
   :main      {:display "flex"
               :flex-direction "column"
               :flex "1"
               :min-width "0"
               :overflow "hidden"}
   :right     {:width "320px"
               :flex-shrink "0"
               :display "flex"
               :flex-direction "column"
               :border-left (str "1px solid " (:border-default colors/tokens))
               :background (:bg-1 colors/tokens)
               :box-shadow (:elev-1 depth/shadows)
               :overflow "auto"}
   :right-narrow {:width "auto"
                  :max-height "42vh"
                  :border-left "none"
                  :border-top "1px solid #444"}
   :splitter  {:width "10px"
               :flex "0 0 10px"
               :background (:bg-canvas colors/tokens)
               :border "0"
               :border-left "1px solid #333"
               :border-right "1px solid #333"
               :cursor "col-resize"
               :padding "0"
               :position "relative"}
   :splitter-grip {:position "absolute"
                   :top "50%"
                   :left "3px"
                   :width "2px"
                   :height "36px"
                   :transform "translateY(-50%)"
                   :background (:text-tertiary colors/tokens)
                   :box-shadow "3px 0 0 #6a6a6a"}
   :splitter-active {:background (:accent-amber-soft colors/tokens)}
   :tab-bar   {:display "flex"
               :background (:bg-2 colors/tokens)
               :border-bottom "1px solid #444"
               :font-family mono-stack
               :font-size (:caption typography/type-scale)}
   :tab       {:padding "6px 12px"
               :cursor "pointer"
               :color (:text-secondary colors/tokens)
               :border-right "1px solid #444"}
   :tab-active {:color "white"
                :background (:bg-canvas colors/tokens)
                :border-bottom "1px solid #1e1e1e"
                :margin-bottom "-1px"}
   ;; rf2-pxeko — `?` help-button chip lives top-LEFT of the viewport.
   ;; The top-RIGHT corner is reserved for the Test-Codegen REC chip
   ;; (`recorder/rec-chip` in the toolbar) plus the recording-overlay
   ;; banner (`recorder/recording-overlay` at top:44px right:12px); a
   ;; floating `?` on the right occluded the REC affordance. Top-left
   ;; sits over the toolbar's first axis-label only — no fixed-position
   ;; conflict with the sidebar (which is part of the flex layout, not
   ;; fixed-positioned) or any other chrome affordance.
   :help-slot {:position "fixed"
               :top      "8px"
               :left     "12px"
               :z-index  1500}})
