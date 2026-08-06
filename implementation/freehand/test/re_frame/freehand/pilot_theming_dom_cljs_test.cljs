(ns re-frame.freehand.pilot-theming-dom-cljs-test
  "THE THEMING PILOT in a real browser — the F5b proof a tree cannot hold:
  that a caller targeting a named part changes the COMPUTED style, and that
  a theme switch recolours through the CSS cascade while the substrate
  renders nothing.

  `pilot-theming-cljs-test` fills in the structural half — the addresses
  are in the tree, the roster is closed, no transform seam is exported.
  This file fills in the half only a live DOM has: `getComputedStyle`.

  Three claims, each a real `react-dom/client` mount of the pilot's own
  `chip`, every value read back off the browser's own style resolution:

  - **A part address is a style handle.** A caller's stylesheet selects
    `[data-part=\"label\"]` under the `data-component` scope and the label's
    computed colour is what the rule said — the whole point of a semantic
    part address (acceptance 1).
  - **An inline token reaches a part.** The dynamic-residue accent, carried
    as an inline `--acme-chip-accent` custom property, resolves through a
    `var()` on the dot's computed background.
  - **Restyling costs no React work.** With the token bundle selected
    by an ancestor `data-theme`, flipping that one attribute — never
    re-rendering the chip — recolours the chip through inheritance, and the
    chip's DOM node is the SAME object across the switch. No token
    subscription per leaf, no remount.

  This suite rides the browser lane through its `-dom-cljs-test` suffix; it
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly."
  (:require ["react-dom/client" :as rdc]
            ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [re-frame.freehand.pilot-theming :as chip-lib]
            [re-frame.freehand.react :as fr]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- inject-style! [css]
  (let [el (js/document.createElement "style")]
    (set! (.-textContent el) css)
    (.appendChild js/document.head el)
    el))

(defn- container!
  [theme]
  (let [c (js/document.createElement "div")]
    (when theme (.setAttribute c "data-theme" theme))
    (.appendChild js/document.body c)
    c))

(defn- computed [node prop]
  (-> (js/getComputedStyle node) (.getPropertyValue prop)))

(defn- norm
  "Colours read back from `getComputedStyle` differ only in spacing across
  engines; compare on the spaces-stripped form."
  [s]
  (str/replace (or s "") " " ""))

;; ---------------------------------------------------------------------------
;; Acceptance 1 — a caller targets a named part, and the style applies
;; ---------------------------------------------------------------------------

(deftest a-part-address-is-a-style-handle
  (testing "A stylesheet selecting the `label` part under the `acme/chip`
            scope sets the label's computed colour."
    (if-not (browser?)
      (is true "a real browser is required for a computed-style assertion")
      (async done
        (let [style     (inject-style!
                          (str "[data-component=\"acme/chip\"] [data-part=\"label\"]"
                               " { color: rgb(9, 105, 218); }"))
              container  (container! nil)
              root       (rdc/createRoot container)
              cleanup    (fn [] (.remove style) (.remove container) (done))]
          (-> (act #(.render root (fr/element [chip-lib/chip {:label "Ready"}])))
              (.then (fn [_]
                       (let [label (.querySelector container "[data-part=\"label\"]")]
                         (is (some? label) "the label part mounted")
                         (is (= (norm "rgb(9, 105, 218)") (norm (computed label "color")))
                             "the part-targeted rule reaches the label's computed style"))
                       (act #(.unmount root))))
              (.then (fn [_] (cleanup)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (cleanup)))))))))

;; ---------------------------------------------------------------------------
;; The token plane — an inline custom property resolves through var()
;; ---------------------------------------------------------------------------

(deftest an-inline-token-reaches-a-parts-computed-style
  (testing "The dynamic-residue accent, carried inline as `--acme-chip-accent`,
            resolves through a `var()` on the dot's computed background."
    (if-not (browser?)
      (is true "a real browser is required for a computed-style assertion")
      (async done
        (let [style     (inject-style!
                          "[data-part=\"dot\"] { background-color: var(--acme-chip-accent); }")
              container  (container! nil)
              root       (rdc/createRoot container)
              cleanup    (fn [] (.remove style) (.remove container) (done))]
          (-> (act #(.render root (fr/element [chip-lib/chip {:label "Ready"
                                                              :accent "rgb(1, 2, 3)"}])))
              (.then (fn [_]
                       (let [dot (.querySelector container "[data-part=\"dot\"]")]
                         (is (some? dot) "the dot part mounted")
                         (is (= (norm "rgb(1, 2, 3)") (norm (computed dot "background-color")))
                             "the inline token resolves on the dot's computed style"))
                       (act #(.unmount root))))
              (.then (fn [_] (cleanup)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (cleanup)))))))))

;; ---------------------------------------------------------------------------
;; The cascade — a theme switch recolours without a re-render
;; ---------------------------------------------------------------------------

(deftest a-theme-switch-recolours-through-the-cascade-without-remounting
  (testing "With the token bundle selected by an ancestor `data-theme`, flipping
            that one attribute — never re-rendering the chip — recolours the chip
            through inheritance, and the chip's DOM node is unchanged."
    (if-not (browser?)
      (is true "a real browser is required for a computed-style assertion")
      (async done
        (let [style     (inject-style!
                          (str ".acme-chip { background-color: var(--acme-chip-bg); }"
                               " [data-theme=\"light\"] { --acme-chip-bg: rgb(240, 240, 240); }"
                               " [data-theme=\"dark\"]  { --acme-chip-bg: rgb(20, 20, 20); }"))
              container  (container! "light")
              root       (rdc/createRoot container)
              cleanup    (fn [] (.remove style) (.remove container) (done))]
          (-> (act #(.render root (fr/element [chip-lib/chip {:label "Ready"}])))
              (.then (fn [_]
                       (let [before (.querySelector container "[data-component=\"acme/chip\"]")]
                         (is (= (norm "rgb(240, 240, 240)") (norm (computed before "background-color")))
                             "the light token colours the chip")
                         ;; Flip the ancestor scope ONLY — no re-render.
                         (.setAttribute container "data-theme" "dark")
                         (let [after (.querySelector container "[data-component=\"acme/chip\"]")]
                           (is (= (norm "rgb(20, 20, 20)") (norm (computed after "background-color")))
                               "the dark token recolours the chip through the cascade")
                           (is (identical? before after)
                               "the same DOM node — the switch re-rendered nothing")))
                       (act #(.unmount root))))
              (.then (fn [_] (cleanup)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (cleanup)))))))))
