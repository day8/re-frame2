(ns day8.re-frame2-xray.panels.epoch.badge-cljs-test
  "Pure-data tests for the Epoch panel's badge taxonomy: pill tokens, the
  numbered-cascade geometry, the machine-cascade kind / phase labels, and
  the outcome and SIDE EFFECTS ledger glyphs."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.badge :as badge]))

(deftest coeffect-and-subscriptions-pull-distinct-tokens-test
  ;; Two badges sharing one token (`:magenta`) would make the pipeline
  ;; pills near-indistinguishable in the live panel.
  (testing "the five core pipeline pills carry five distinct token keys"
    (let [core-pills #{:DISPATCH :COEFFECT :HANDLER :SUBSCRIPTIONS :VIEWS}
          token-keys (set (map badge/token-key core-pills))]
      (is (= 5 (count token-keys))
          (str "expected 5 distinct token-keys, got " token-keys)))))

(deftest numbered-cascade-geometry-test
  (testing "the geometry constants exposed for the view are the ones the spec commits to"
    (is (= 13  badge/vertical-line-offset-px))
    (is (= -34 badge/line-left-offset-px))))

;; ---- machine-cascade row chrome ----------------------------------------

(deftest cascade-kind-label-and-colour-test
  (testing "the :microstep kind resolves to the magenta
            transition-family colour + an ALWAYS label"
    (is (= "ALWAYS" (badge/cascade-kind-label :microstep)))
    (is (= (badge/cascade-kind-colour :transition)
           (badge/cascade-kind-colour :microstep))
        "an :always round is a state change — shares the transition hue"))

  (testing "the :start kind resolves to a green/success label +
            colour (a clean birth is a GOOD event), distinct from the muted
            no-op tone"
    (is (= "START" (badge/cascade-kind-label :start)))
    (is (not= (badge/cascade-kind-colour :start)
              (badge/cascade-kind-colour :no-op))
        "the green birth is distinct from the muted no-op tone"))

  (testing "the :no-op kind resolves to a muted (not red) label +
            colour, distinct from the error/warning hues. The label is
            `NO OP` (space, not hyphen — the sole marker on the collapsed
            `[NO OP] staying in {state}` cell)"
    (is (= "NO OP" (badge/cascade-kind-label :no-op)))
    ;; The benign no-op uses the tertiary token (same as :guard / :timer's
    ;; muted family) — explicitly NOT the :error / :warning hue.
    (is (= (badge/cascade-kind-colour :guard)
           (badge/cascade-kind-colour :no-op))
        "muted/tertiary tone, not an alarmist hue")))

(deftest cascade-action-badge-label-test
  (testing "the merged ACTION badge folds the phase + the
            ACTION kind into one token"
    (is (= "EXIT ACTION"          (badge/cascade-action-badge-label :exit)))
    (is (= "ENTRY ACTION"         (badge/cascade-action-badge-label :entry)))
    (is (= "TRANSITION ACTION"    (badge/cascade-action-badge-label :transition)))
    (is (= "ALWAYS ACTION"        (badge/cascade-action-badge-label :always)))
    (is (= "AFTER-ACTION ACTION"  (badge/cascade-action-badge-label :after-action)))
    (is (= "INITIAL-ENTRY ACTION" (badge/cascade-action-badge-label :initial-entry)))
    (is (= "DESTROY-EXIT ACTION"  (badge/cascade-action-badge-label :destroy-exit))))
  (testing "a phase-less action falls back to the bare ACTION label"
    (is (= (badge/cascade-kind-label :action)
           (badge/cascade-action-badge-label nil))
        "no phase → bare ACTION label (the kind label)"))
  (testing "the state-change TRANSITION ROW keeps its own single
            `TRANSITION` pill (distinct from a `TRANSITION ACTION`)"
    (is (= "TRANSITION" (badge/cascade-kind-label :transition)))))

(deftest event-bundle-outcome-resolver-test
  (testing "outcome → token-key + glyph mappings"
    (is (= :success       (badge/cascade-outcome-token-key :pass)))
    (is (= :success       (badge/cascade-outcome-token-key :ok)))
    (is (= :warning       (badge/cascade-outcome-token-key :fail)))
    (is (= :error         (badge/cascade-outcome-token-key :threw)))
    (is (= :text-tertiary (badge/cascade-outcome-token-key :cancelled)))
    (is (= "✓" (badge/cascade-outcome-glyph :pass)))
    (is (= "✓" (badge/cascade-outcome-glyph :ok)))
    (is (= "▲" (badge/cascade-outcome-glyph :fail)))
    (is (= "✗" (badge/cascade-outcome-glyph :threw)))
    (is (= "·" (badge/cascade-outcome-glyph :cancelled)))))

;; There is no per-STAGE status glyph: a clean run would paint a tick on
;; every stage (no information), and the inline exception card under the
;; stage already shows a failure. The per-EFFECT ledger glyphs (below) +
;; the event-bundle-outcome banner glyph (above) carry the distinct
;; signals.

;; ---- flat SIDE EFFECTS ledger row glyphs --------------------------------

(deftest fx-row-status-glyph-test
  (testing "the flat-ledger per-effect glyph closed set:
            ✓ :ok / ✗ :error / ✗ :rollback / ↺ :overridden / – :skipped"
    (is (= "✓" (badge/fx-row-status-glyph :ok)))
    (is (= "✗" (badge/fx-row-status-glyph :error)))
    (is (= "✗" (badge/fx-row-status-glyph :rollback)))
    (is (= "↺" (badge/fx-row-status-glyph :overridden)))
    (is (= "–" (badge/fx-row-status-glyph :skipped))
        "skipped is the muted en-dash 'n/a'")))

(deftest overridden-hover-names-the-replacement-test
  (testing "the ↺ row's hover names the replacement"
    (is (str/includes? (badge/overridden-hover :http/fake) ":http/fake"))
    (is (str/includes? (badge/overridden-hover :re-frame.fx/fn-value) "with a function"))))

(deftest fx-row-status-token-key-test
  (testing ":error/:rollback → :error; :overridden → :accent;
            :skipped → :text-tertiary (muted, NEUTRAL); else → :success"
    (is (= :error         (badge/fx-row-status-token-key :error)))
    (is (= :error         (badge/fx-row-status-token-key :rollback)))
    (is (= :accent        (badge/fx-row-status-token-key :overridden)))
    (is (= :text-tertiary (badge/fx-row-status-token-key :skipped)))
    (is (= :success       (badge/fx-row-status-token-key :ok)))))
