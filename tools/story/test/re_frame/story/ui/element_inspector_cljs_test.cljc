(ns re-frame.story.ui.element-inspector-cljs-test
  "Tests for the element-level click-to-code inspector (rf2-h0jc0).

  Runs on both the JVM (cognitect.test-runner via `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target picks up
  the `cljs-test$` ns-regex). The pure helpers — coord parsing,
  handler-keyword reconstruction — are CLJC; the side-effectful
  install/remove/toggle paths are CLJS-only.

  ## Coverage

  - **Pure data** (JVM + CLJS):
    - `parse-coord` round-trips the `<ns>:<sym>:<line>:<col>` DOM
      attribute into `{:ns :handler-id :line :col}` with malformed-
      input safety (mirror of skills/re-frame-pair2's parser tests so
      format drift surfaces here too).
    - `coord->handler-keyword` reconstructs the registered view id.

  - **CLJS-only side-effects**:
    - `toggle!` / `set-active!` flip the mode flag.
    - `resolve-source-coord` walks parsed coord + the registry's
      handler-meta to produce a launchable source-coord map.
    - The toolbar chip renders the right shape (data-test attr,
      `aria-haspopup`/`aria-expanded` per rf2-zll4h convention,
      label flips on toggle).
    - The overlay component renders nothing when inspect mode is off
      or no element is hovered; renders the outline + tooltip when
      both are set."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            #?@(:cljs [[re-frame.core :as rf]
                       [re-frame.source-coords :as source-coords]])
            [re-frame.story.ui.element-inspector :as inspector]))

;; ---- pure: parse-coord (JVM + CLJS) -------------------------------------

(deftest parse-coord-canonical
  (testing "canonical 4-segment shape — the same contract the DOM attr
            ships in re-frame2 dev builds (Spec 006 §Source-coord
            annotation)"
    (is (= {:ns "counter.core" :handler-id "counter-buttons"
            :line 47 :col 11}
           (inspector/parse-coord "counter.core:counter-buttons:47:11")))))

(deftest parse-coord-dotted-and-hyphenated
  (testing "dotted ns + hyphenated handler-id parse cleanly"
    (is (= {:ns "my-app.cart.view" :handler-id "apply-coupon-button"
            :line 125 :col 4}
           (inspector/parse-coord
             "my-app.cart.view:apply-coupon-button:125:4")))))

(deftest parse-coord-degraded
  (testing "programmatic registration emits `?:?` for missing line/col;
            parser surfaces them as nil"
    (is (= {:ns "rf.src-coord-test" :handler-id "programmatic"
            :line nil :col nil}
           (inspector/parse-coord "rf.src-coord-test:programmatic:?:?")))))

(deftest parse-coord-malformed-too-few-segments
  (testing "fewer than 4 segments → nil"
    (is (nil? (inspector/parse-coord "ns:view:42")))
    (is (nil? (inspector/parse-coord "ns:view")))
    (is (nil? (inspector/parse-coord "")))
    (is (nil? (inspector/parse-coord nil)))))

(deftest parse-coord-malformed-too-many-segments
  (testing "more than 4 segments → nil (strict 4-segment contract)"
    (is (nil? (inspector/parse-coord "a:b:c:d:e")))))

(deftest parse-coord-empty-segments
  (testing "empty `<ns>` or `<handler-id>` → nil"
    (is (nil? (inspector/parse-coord ":handler:1:2")))
    (is (nil? (inspector/parse-coord "ns::1:2")))))

(deftest parse-coord-non-string-input
  (testing "non-string input → nil (never throws)"
    (is (nil? (inspector/parse-coord 42)))
    (is (nil? (inspector/parse-coord :keyword)))
    (is (nil? (inspector/parse-coord ["v" "e" "c"])))))

(deftest coord->handler-keyword-shape
  (testing "parsed coord round-trips to the registered view-id keyword"
    (is (= :counter.core/counter-buttons
           (inspector/coord->handler-keyword
             {:ns "counter.core" :handler-id "counter-buttons"
              :line 47 :col 11}))))
  (testing "missing :ns or :handler-id → nil"
    (is (nil? (inspector/coord->handler-keyword {:handler-id "x"})))
    (is (nil? (inspector/coord->handler-keyword {:ns "x"})))
    (is (nil? (inspector/coord->handler-keyword nil)))))

;; ---- CLJS-only: mode toggle + chip + overlay ----------------------------

#?(:cljs
   (defn reset-inspector! []
     (inspector/set-active! false)
     (reset! inspector/state {:active? false :hover nil})))

#?(:cljs
   (use-fixtures :each {:before reset-inspector!
                        :after  reset-inspector!}))

#?(:cljs
   (deftest mode-toggle-flips-active-flag
     (testing "set-active! + toggle! drive the active? predicate"
       (is (false? (inspector/active?)))
       (inspector/set-active! true)
       (is (true? (inspector/active?)))
       (inspector/toggle!)
       (is (false? (inspector/active?)))
       (inspector/toggle!)
       (is (true? (inspector/active?))))))

#?(:cljs
   (deftest set-active-false-clears-hover
     (testing "turning the inspector OFF must clear any pending hover
               snapshot so a stale outline doesn't survive the toggle"
       (swap! inspector/state assoc
              :active? true
              :hover {:coord-attr "x:y:1:1"
                      :handler-id :x/y
                      :rect {:top 0 :left 0 :width 10 :height 10}})
       (inspector/set-active! false)
       (is (false? (inspector/active?)))
       (is (nil? (:hover @inspector/state))))))

#?(:cljs
   (deftest inspect-chip-renders-toggle-state
     (testing "chip renders with `aria-haspopup` (not aria-pressed) per
               rf2-zll4h reset-gate convention"
       (let [hiccup (inspector/inspect-chip)
             props  (second hiccup)]
         (is (= :button (first hiccup)))
         (is (= "story-toolbar-inspect" (:data-test props)))
         (is (= "true" (:aria-haspopup props))
             "aria-haspopup must be present — the toolbar reset assertion
              counts [aria-pressed=\"true\"] and we don't want this chip
              to trip it")
         (is (= "false" (:aria-expanded props))
             "off-state aria-expanded")
         (is (not (contains? props :aria-pressed))
             "MUST NOT use aria-pressed — see rf2-zll4h")
         (is (fn? (:on-click props)))))))

#?(:cljs
   (deftest inspect-chip-renders-on-state
     (testing "chip's data attrs flip after toggle"
       (inspector/set-active! true)
       (let [hiccup (inspector/inspect-chip)
             props  (second hiccup)]
         (is (= "true" (:aria-expanded props)))))))

#?(:cljs
   (deftest overlay-renders-nothing-when-off
     (testing "overlay returns nil when inspector mode is off"
       (is (nil? (inspector/overlay))))))

#?(:cljs
   (deftest overlay-renders-nothing-when-no-hover
     (testing "active but no hover → no outline"
       (inspector/set-active! true)
       (is (nil? (inspector/overlay))))))

#?(:cljs
   (deftest resolve-source-coord-pulls-file-from-handler-meta
     (testing "the DOM attribute carries line+col; `:file` lives on the
               registered view's meta. `resolve-source-coord` walks the
               registry to produce the full source-coord shape
               `editor-uri/editor-uri` expects."
       (let [view-id :rf.inspector-test/sample-view]
         ;; Seed the always-on error-coord registry so the resolver finds
         ;; a :file even when handler-meta is unset (mirrors the
         ;; production-elided dev meta case).
         (source-coords/remember-error-coords!
           :view view-id
           {:ns "rf.inspector-test" :file "src/sample.cljs"
            :line 12 :column 4})
         (let [parsed   (inspector/parse-coord
                          "rf.inspector-test:sample-view:42:7")
               resolved (inspector/resolve-source-coord parsed)]
           (is (= "src/sample.cljs" (:file resolved))
               ":file pulled from the error-coords registry fallback")
           (is (= 42 (:line resolved))
               "DOM-side line beats meta-side line (the attr is the
                most-recent ground truth)")
           (is (= 7 (:column resolved))
               "DOM-side col beats meta-side col")
           (source-coords/forget-error-coords!))))))

#?(:cljs
   (deftest resolve-source-coord-defaults-when-attr-degraded
     (testing "programmatic-registration coords arrive as `?:?` — the
               resolver falls through to meta-side line/col when both
               are present, else 1/1"
       (let [view-id :rf.inspector-test/degraded]
         (source-coords/remember-error-coords!
           :view view-id
           {:ns "rf.inspector-test" :file "src/d.cljs"
            :line 99 :column 3})
         (let [parsed   (inspector/parse-coord
                          "rf.inspector-test:degraded:?:?")
               resolved (inspector/resolve-source-coord parsed)]
           (is (= 99 (:line resolved))
               "meta-side line fills in when DOM-side is nil")
           (is (= 3 (:column resolved))
               "meta-side column fills in when DOM-side is nil")
           (source-coords/forget-error-coords!))))))

#?(:cljs
   (deftest overlay-renders-outline-and-tooltip-when-hovering
     (testing "active + hover snapshot present → outline + tooltip
               hiccup with the right test attrs"
       (swap! inspector/state assoc
              :active? true
              :hover {:coord-attr "counter.core:counter:47:11"
                      :handler-id :counter.core/counter
                      :parsed     {:ns "counter.core"
                                   :handler-id "counter"
                                   :line 47 :col 11}
                      :rect       {:top 100 :left 200
                                   :width 300 :height 40}})
       (let [root (inspector/overlay)]
         (is (vector? root))
         (is (= :div (first root)))
         (is (= "story-element-inspector-overlay"
                (:data-test (second root))))
         ;; Two children: outline + tooltip
         (is (= 4 (count root))
             "root + props + outline + tooltip = 4 elements")
         (let [outline (nth root 2)
               tooltip (nth root 3)]
           (is (= :div (first outline)))
           (is (= "story-element-inspector-tooltip"
                  (:data-test (second tooltip))))
           (is (= ":counter.core/counter"
                  (:data-handler-id (second tooltip))))
           ;; The tooltip text carries the handler id + line:col so the
           ;; user can sanity-check before clicking.
           (is (clojure.string/includes? (nth tooltip 2) "47")))))))
