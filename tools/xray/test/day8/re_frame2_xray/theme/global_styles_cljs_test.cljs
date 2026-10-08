(ns day8.re-frame2-xray.theme.global-styles-cljs-test
  "Tests for the Xray global-styles injection. Node has no `js/document`,
  so the stylesheet strings are read directly and `install-into!` runs
  against a stub document."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-xray.theme.global-styles :as gs]))

;; ---- font faces ----------------------------------------------------------

(deftest font-faces-css-is-local-only-no-third-party-egress
  (testing "the three brand faces ship `local()`-only: no `url()` candidate,
            so no third-party fetch; consumers layer their own `url()` rules"
    (let [css @#'gs/font-faces-css]
      (doseq [face ["Inter" "JetBrains Mono" "Fraunces"]]
        (is (str/includes? css (str "font-family:'" face "'")) face))
      (is (re-find #"src:local\(" css))
      (is (not (re-find #"url\(" css))))))

;; ---- motion css ---------------------------------------------------------

(deftest motion-css-declares-fade-in-keyframes
  (testing "the L4 tab cross-fade `shell.cljs` names: opacity 0 → 1,
            rising 2px into place"
    (let [css @#'gs/motion-css]
      (is (re-find #"@keyframes\s+rf-xray-fade-in" css))
      (is (re-find #"from\s*\{[^}]*opacity:\s*0" css))
      (is (re-find #"to\s*\{[^}]*opacity:\s*1" css))
      (is (re-find #"translateY\(2px\)" css)))))

(deftest motion-css-publishes-font-size-default-on-root
  (testing "`:root` publishes the `--rf-xray-font-size` knob every
            type-scale entry multiplies"
    (is (re-find #":root\s*\{[^}]*--rf-xray-font-size:\s*13px"
                 @#'gs/motion-css))))

(deftest motion-css-declares-prefers-reduced-motion-override
  (testing "`prefers-reduced-motion: reduce` drops the motion scale to a
            hair above zero, so every calc()'d duration collapses yet the
            keyframes still reach their end state (older Chrome never
            applies a 0s animation's fill)"
    (is (re-find #"@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{[^}]*--rf-xray-motion-scale:\s*0\.001"
                 @#'gs/motion-css))))

(deftest motion-css-focus-visible-uses-the-accent-token
  (testing "keyboard focus inside every Xray root paints a 2px ring in
            `--rf-xray-accent`, which resolves per theme; a fixed hex would
            fall under a focus indicator's contrast floor in one theme"
    (is (re-find #"\[data-testid=\"rf-xray-shell\"\] \*:focus-visible,\s*\[data-testid=\"rf-xray-static-shell\"\] \*:focus-visible,\s*\[data-testid=\"rf-xray-palette-backdrop\"\] \*:focus-visible\s*\{\s*outline:\s*2px\s+solid\s+var\(--rf-xray-accent\)"
                 @#'gs/motion-css))))

(deftest motion-css-forced-colors-distinguishes-status-accents
  (testing "each lifecycle status keeps a distinct system colour under both
            activators: the OS `@media (forced-colors: active)` block, whose
            `!important` beats the rows' inline styles, and the operator's
            `[data-rf-force-colors=\"active\"]` opt-in"
    (let [css   @#'gs/motion-css
          media (re-find #"@media\s*\(forced-colors:\s*active\)[\s\S]*?\n\}\n" css)]
      (doseq [[status token] [["settled-error" "Mark"]
                              ["in-flight" "Highlight"]
                              ["settled-success" "CanvasText"]
                              ["stale" "GrayText"]
                              ["paused-by-tool" "GrayText"]]
              :let [rule (str "\\[data-rf-xray-status=\"" status "\"\\][^{]*\\{[^}]*" token)]]
        (is (re-find (re-pattern (str rule "\\s*!important")) media)
            (str status " → " token " under OS forced-colors"))
        (is (re-find (re-pattern (str "\\[data-rf-force-colors=\"active\"\\] " rule)) css)
            (str status " → " token " under the opt-in attribute"))))))

(deftest motion-css-view-highlight-is-layout-safe
  (testing "the view hover-highlight paints a background image and nothing
            that takes space, so hovering shifts no pixel of the inspected app"
    (let [rule (re-find #"\.rf-xray-view-highlight\s*\{[\s\S]*?\}" @#'gs/motion-css)]
      (is (re-find #"background-image:" rule))
      (is (not (re-find #"(?i)\b(border|outline|box-shadow|margin|padding|width|height)\s*:" rule))))))

(deftest motion-css-declares-filters-collapse-animation
  (testing "the filters ribbon (rows) and the chrome `+ filter` button
            (`-h`, columns) collapse to 0fr and open to 1fr on `data-open`,
            through the reduced-motion scale"
    (let [css @#'gs/motion-css]
      (doseq [re [#"\.rf-xray-filters-collapse\s*\{[^}]*grid-template-rows:\s*0fr[^}]*transition:\s*grid-template-rows\s+calc\([^)]*var\(--rf-xray-motion-scale[^}]*opacity\s+calc\([^)]*var\(--rf-xray-motion-scale"
                  #"\.rf-xray-filters-collapse\[data-open=\"true\"\]\s*\{[^}]*grid-template-rows:\s*1fr"
                  ;; The closed track must beat the inner toolbar's inline
                  ;; 34px min-height, or the closed bar leaves a 34px gap.
                  #"\.rf-xray-filters-collapse\[data-open=\"false\"\]\s*>\s*\*\s*\{[^}]*min-height:\s*0\s*!important"
                  #"\.rf-xray-filters-collapse-h\s*\{[^}]*grid-template-columns:\s*0fr[^}]*transition:\s*grid-template-columns\s+calc\([^)]*var\(--rf-xray-motion-scale[^}]*opacity\s+calc\([^)]*var\(--rf-xray-motion-scale"
                  #"\.rf-xray-filters-collapse-h\[data-open=\"true\"\]\s*\{[^}]*grid-template-columns:\s*1fr"]]
        (is (re-find re css) (str re))))))

;; ---- per-theme CSS variables --------------------------------------------

(deftest themes-css-publishes-each-palette-under-its-selector
  (testing "the light palette is the `:root` default, so vars resolve before
            a theme class lands, and each palette publishes under its class"
    (let [css (@#'gs/themes-css {:dark  {:bg-1 "#15171B"}
                                 :light {:bg-1 "#F1F3F6"}})]
      (is (re-find #":root\s*\{[^}]*--rf-xray-bg-1:\s*#F1F3F6" css))
      (is (re-find #"\.rf-xray-theme-dark\s*\{[^}]*--rf-xray-bg-1:\s*#15171B" css))
      (is (re-find #"\.rf-xray-theme-light\s*\{[^}]*--rf-xray-bg-1:\s*#F1F3F6" css)))))

;; ---- atmospheric grain overlay -----------------------------------------

(deftest grain-css-targets-shell-root-pseudo
  (testing "the grain is a ~3.5% inline-SVG `feTurbulence` data-URI on the
            shell root's `::before`: no host-page element, no asset fetch"
    (is (re-find #"\[data-testid=\"rf-xray-shell\"\]::before\s*\{[^}]*opacity:\s*0\.03[0-9]?[^}]*background-image:\s*url\(\"data:image/svg\+xml[^}]*feTurbulence"
                 @#'gs/grain-css))))

;; ---- React Flow stylesheets ---------------------------------------------

(deftest react-flow-base-css-carries-structural-rules
  (testing "the vendored xyflow base sheet keeps the rules React Flow needs
            to render, so a version bump that drops one is caught"
    (let [css @#'gs/react-flow-base-css]
      (is (re-find #"\.react-flow__node\s*\{[^}]*position:\s*absolute" css)
          "nodes are absolutely positioned (the stacked-box fix)")
      (is (re-find #"\.react-flow__edge-path\s*\{" css)
          "edge path rule present")
      (is (re-find #"\.react-flow__controls\b" css)
          "Controls chrome present")
      (is (re-find #"\.react-flow__container\s*\{" css)
          "viewport container rule present")
      (is (re-find #"\.react-flow__viewport\s*\{" css)
          "viewport transform rule present"))))

(deftest react-flow-xray-theme-css-is-scoped-to-xray-rf2-3x7nj-25-7
  (testing "`install!` appends the override to the HOST document's head, so
            an unscoped `.react-flow` rule would re-theme, and hide the
            attribution of, every React Flow the host renders. Each selector
            sits under Xray's own `[data-rf-xray-mode]` surface."
    (let [css       @#'gs/react-flow-xray-theme-css
          selectors (->> (str/split css #"\}")
                         (keep #(when-let [i (str/index-of % "{")] (subs % 0 i)))
                         (mapcat #(str/split % #","))
                         (map str/trim)
                         (remove str/blank?))]
      (is (some #(str/ends-with? % ".react-flow") selectors)
          "sanity: the palette rule is found")
      (is (some #(str/ends-with? % ".react-flow__attribution") selectors)
          "sanity: the attribution rule is found")
      (doseq [s selectors]
        (is (str/starts-with? s "[data-rf-xray-mode] ")
            (str "scoped under Xray's surface: " s))))))

;; ---- install-into! — second-window pop-out stylesheet hand-off ----------

(defn- mk-stub-style-node []
  (let [node (js-obj "id" "" "tagName" "STYLE")]
    (set! (.-text node) "")
    (set! (.-appendChild node)
          (fn [child]
            (set! (.-text node) (str (.-text node) (.-text child)))
            child))
    node))

(defn- mk-stub-document
  "The document surface `inject-style-node!` touches. Returns
  `{:doc … :by-id <atom of id → appended node>}`."
  []
  (let [by-id (atom {})
        head  (js-obj "tagName" "HEAD")]
    (set! (.-appendChild head)
          (fn [node]
            (swap! by-id assoc (.-id node) node)
            node))
    {:doc   (js-obj
             "head"          head
             "createElement" (fn [_tag] (mk-stub-style-node))
             "createTextNode" (fn [css] (js-obj "text" css))
             "getElementById" (fn [id] (get @by-id id)))
     :by-id by-id}))

(deftest install-into!-injects-the-full-stylesheet-set
  (testing "the pop-out path writes every Xray style block into an
            arbitrary document's <head>, the themes block carrying the real
            palette so the detached window's var() reads resolve"
    (let [{:keys [doc by-id]} (mk-stub-document)]
      (gs/install-into! doc)
      (is (= #{"rf-xray-fonts" "rf-xray-motion-keyframes" "rf-xray-react-flow-base"
               "rf-xray-themes" "rf-xray-grain"}
             (set (keys @by-id))))
      (is (re-find #":root\s*\{[^}]*--rf-xray-bg-0:"
                   (.-text (get @by-id "rf-xray-themes")))))))

(deftest install-into!-is-idempotent-per-document
  (testing "a second install-into! on the same document creates no node:
            the id probe is the guard, since a pop-out document is a DOM the
            opener's `installed?` flag knows nothing about"
    (let [{:keys [doc]} (mk-stub-document)
          create-ct     (atom 0)
          orig          (.-createElement doc)]
      (set! (.-createElement doc)
            (fn [tag] (swap! create-ct inc) (orig tag)))
      (gs/install-into! doc)
      (gs/install-into! doc)
      (is (= 5 @create-ct)))))
