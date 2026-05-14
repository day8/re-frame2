(ns re-frame.story.ui.shell-styles
  "Style map for `re-frame.story.ui.shell`. Pure data; no Reagent
  dependency. Extracted from `shell.cljs` per rf2-gv5kq so the shell
  ns trends toward the 250-LoC leaf-size ceiling (rf2-zkca8).

  CLJS-only.")

(def styles
  {:root      {:display "flex"
               :flex-direction "column"
               :height "100vh"
               :font-family "system-ui, sans-serif"
               :background "#1e1e1e"
               :color "#ddd"}
   :body      {:display "flex"
               :flex-direction "row"
               :flex "1"
               :min-height "0"
               :overflow "hidden"}
   :main      {:display "flex"
               :flex-direction "column"
               :flex "1"
               :overflow "hidden"}
   :right     {:width "320px"
               :display "flex"
               :flex-direction "column"
               :border-left "1px solid #444"
               :background "#252526"
               :overflow "auto"}
   :tab-bar   {:display "flex"
               :background "#2d2d30"
               :border-bottom "1px solid #444"
               :font-family "monospace"
               :font-size "11px"}
   :tab       {:padding "6px 12px"
               :cursor "pointer"
               :color "#b0b0b0"
               :border-right "1px solid #444"}
   :tab-active {:color "white"
                :background "#1e1e1e"
                :border-bottom "1px solid #1e1e1e"
                :margin-bottom "-1px"}
   :help-slot {:position "fixed"
               :top      "8px"
               :right    "12px"
               :z-index  1500}})
