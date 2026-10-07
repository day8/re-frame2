(ns day8.re-frame2-xray.theme.var-resolution-cljs-test
  "Pins the contract: every value an Xray inline `:style` site reads
  from `theme/tokens` resolves to a `var(--rf-xray-<key>)` CSS-variable
  reference, NOT a literal hex string.

  ## Why this matters

  Were the `tokens` map an alias for `dark-palette`, every inline
  `:style` declaration that referenced `(:bg-1 tokens)` would paint
  the dark-palette hex regardless of the active theme class. The
  light-theme class toggle (`rf-xray-theme-light` on the shell root)
  and the emitted CSS-variable block would both be ignored by inline
  styles, so light mode would render as paint-only-the-edges broken.

  `tokens` is a CSS-variable map (`{:bg-1
  \"var(--rf-xray-bg-1)\"}`), so every inline-style call site flows
  through the theme's class scope. The active theme class on the shell
  root decides which palette's hex actually paints.

  ## What this test pins

  `tokens` itself — every entry is `var(--rf-xray-<key>)`, over the key
  set both palettes share — is pinned in `tokens_cljs_test.cljc`, with
  `css-var`, `with-alpha` and `panel-accent`. This namespace pins the two
  layers built on it:

    1. **Helpers route through the var-map** — `op-family-colour`,
       `outcome-colour` and `event-status-colour` return a CSS-variable
       string.

    2. **Rendered hiccup carries var() references** — render small
       view fragments and walk every `:style` map: no value is a
       palette hex literal (`#7C5CFF`, `#15171B`, …); every colour
       value is either a `var(--rf-xray-…)` reference, a
       `color-mix(...)` composition, a non-colour string (`\"transparent\"`,
       `\"none\"`, etc.), or a non-string (numeric padding, line-height
       multipliers).

  ## Posture

  Validate the visual contract via CLJS unit tests reading
  rendered hiccup, NOT Playwright probes."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-xray.panels.trace-helpers :as trace-h]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

;; ---- (1) helpers route through the var-map ------------------------------

(deftest trace-band-colour-returns-css-variable-string
  (testing "`trace-helpers/op-family-colour`
            returns the per-row Trace op-family 3px left-border band
            colour as a CSS variable (spec/023 §8)."
    (doseq [[op-type op] [[:rf.event :rf.event/dispatched]
                          [:rf.event :rf.event/db-changed]
                          [:rf.fx :rf.fx/handled]
                          [:rf.sub :rf.sub/run]
                          [:rf.machine :rf.machine/transition]
                          [:error :rf.error/x]
                          [:warning :rf.warning/x]]]
      (let [v (trace-h/op-family-colour {:op-type op-type :operation op})]
        (is (string? v))
        (is (re-find #"^var\(--rf-xray-" v)
            (str "op-family-colour " op-type " resolves to a CSS variable"))))))

(deftest trace-outcome-colour-returns-css-variable-string
  (testing "`trace-helpers/outcome-colour` tints the
            what-happened column by outcome tier and resolves to a CSS
            variable (spec/023 §8)."
    (doseq [op [:rf.sub/run :rf.sub/skip :rf.sub/dispose :rf.error/x]]
      (let [v (trace-h/outcome-colour {:op-type (if (= op :rf.error/x) :error :rf.sub)
                                       :operation op})]
        (is (string? v))
        (is (re-find #"^var\(--rf-xray-" v)
            (str "outcome-colour " op " resolves to a CSS variable"))))))

(deftest event-status-colour-returns-css-variable-string
  (testing "the lifecycle-status helper consumed by the
            L2 row + Event header + Trace timeline returns a CSS
            variable."
    (doseq [state [{:outcome :ok} {:outcome :error} {:in-flight? true}
                   {:paused? true} {:stale? true}]]
      (let [v (event-status/event-status-colour state)]
        (is (string? v))
        (is (re-find #"^var\(--rf-xray-" v)
            (str "event-status-colour " state " resolves to a CSS variable"))))))

;; ---- (2) rendered hiccup carries no palette hex literals ----------------
;;
;; The strongest pin: walk a rendered hiccup tree and assert NO `:style`
;; value is a palette hex literal. Catches a regression where a new
;; inline-style site sneaks in a hardcoded `\"#1B1E24\"` instead of
;; reading through the token map.

(defn- collect-style-strings
  "Depth-first walk: collect every string value found in any `:style`
  map across the hiccup tree. Skips non-string values (numerics,
  line-height multipliers, etc.) since the contract is about colour
  values."
  [tree]
  (let [out (atom [])]
    (letfn [(walk-node [node]
              (when (vector? node)
                (let [attrs (when (map? (second node)) (second node))]
                  (when-let [style (:style attrs)]
                    (when (map? style)
                      (doseq [[_ v] style]
                        (when (string? v)
                          (swap! out conj v)))))
                  (doseq [child (rest node)]
                    (cond
                      (vector? child) (walk-node child)
                      (seq? child)    (doseq [c child] (walk-node c)))))))]
      (walk-node tree))
    @out))

(def ^:private palette-hex-pattern
  ;; A palette hex literal: `#` + 3-to-8 hex digits at a word boundary.
  ;; Matches `#7C5CFF`, `#a83a3a`, `#fff` — but does NOT match the inside
  ;; of a `var(--rf-xray-…)` reference (no hex digits in the var name).
  #"#[0-9A-Fa-f]{3,8}\b")

(deftest edn-inspector-rendered-hiccup-has-no-palette-hex-literals
  (testing "the canonical L4-panel value renderer
            (`views/edn-inspector/render-node`) emits hiccup whose
            every `:style` colour value flows through a CSS variable.
            Walk the rendered tree across a representative mix of
            values (collection, keyword, string, number, nil,
            sentinel) and assert NO `:style` string is a `#xxxxxx`
            palette hex literal. Guards against a regression where a
            new inline-style site is added with a hardcoded hex.

            There is no `theme/data-inspector`; the edn-inspector
            widget is the single value renderer."
    (doseq [v [{:a 1 :b 2 :c [1 2 3]}      ; map + nested vec
               [:foo :bar {:baz nil}]      ; vector with primitives + map
               #{1 2 3}                    ; set
               "a string"                  ; string leaf
               :keyword                    ; keyword leaf
               42                          ; number leaf
               true                        ; boolean leaf
               nil                         ; nil leaf
               :rf/redacted                ; redacted sentinel
               {:rf.size/large-elided      ; large sentinel (spec/015)
                {:path   [:blob]
                 :bytes  1234
                 :type   :string
                 :reason :schema
                 :hint   "preview hint"
                 :handle [:rf.elision/at [:blob]]}}]]
      (let [tree (ei/render-node {:value v
                                  :panel-id :rf.xray/var-resolution-test
                                  :mount-id "test"
                                  :path []
                                  :depth 0
                                  :expansion-map {}
                                  :opts {:default-expanded-depth 2}})
            styles (collect-style-strings tree)]
        (is (seq styles)
            (str "render of " (pr-str v) " produced style strings to inspect"))
        (doseq [s styles]
          (is (not (re-find palette-hex-pattern s))
              (str "style string " (pr-str s)
                   " in render of " (pr-str v)
                   " contains a palette hex literal "
                   "(should be a var(--rf-xray-…) reference)")))))))
