(ns day8.re-frame2-xray.panels.epoch.badge-cljs-test
  "Pure-data tests for the Epoch panel's badge taxonomy (rf2-sc3r1).

  ## Under test

    1. Every badge in `projection/badge-set` resolves to a non-blank
       CSS-variable string via `badge/colour`.
    2. Every badge resolves to a non-blank uppercase label via
       `badge/label`.
    3. `token-key` produces a known theme-token keyword for every
       badge.
    4. Fibonacci spacing scale produces stable px strings."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.badge :as badge]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]))

(deftest badge-colours-resolve-test
  (testing "every badge in the inventory resolves to a non-blank CSS-var string"
    (doseq [b proj/badge-set]
      (let [c (badge/colour b)]
        (is (string? c) (str "colour for " b))
        (is (re-find #"var\(--rf-xray-" c)
            (str "expected CSS variable for " b ", got " c))))))

(deftest badge-labels-resolve-test
  (testing "every badge label resolves to a non-blank string.

  Labels are uppercase keyword names by default (DISPATCH, COEFFECT,
  EVENT HANDLER, EFFECT HANDLERS, ...). Labels that lead with `:` are
  EDN-key-style and render through `badge-pill`'s mono-font,
  no-uppercase path. Both styles are valid; this test just pins that
  every badge has a label. (rf2-kt6js: the pre-rf2-kt6js `:FX` badge —
  which rendered as `\":fx\"` — became `:SIDE-EFFECTS` → `\"EFFECT
  HANDLERS\"` (rf2-iijnx renamed the display text).)"
    (doseq [b proj/badge-set]
      (let [l (badge/label b)]
        (is (string? l)
            (str "badge label for " b " is a string"))
        (is (seq l)
            (str "badge label for " b " is non-blank"))
        (is (or (= (str/upper-case l) l)
                (str/starts-with? l ":"))
            (str "badge label for " b " is uppercase OR EDN-key style: " l))))))

(deftest token-key-fallback-test
  (testing "unknown badge falls back to :text-tertiary"
    (is (= :text-tertiary (badge/token-key :NOT-A-BADGE))))
  (testing "known badges resolve to specific token keys"
    (is (= :accent (badge/token-key :HANDLER)))
    (is (= :accent (badge/token-key :FLOW)))
    (is (= :orange (badge/token-key :SIDE-EFFECTS)))
    (is (= :success (badge/token-key :VIEWS)))))

(deftest coeffect-and-subscriptions-pull-distinct-tokens-test
  ;; rf2-cgm4f — pre-fix both badges shared `:magenta`, so the
  ;; pipeline pills were near-indistinguishable in the live panel.
  ;; The 5 pipeline pills (DISPATCH / COEFFECT / HANDLER /
  ;; SUBSCRIPTIONS / VIEWS) MUST map to 5 distinct theme tokens.
  (testing "COEFFECT pulls a different token-key from SUBSCRIPTIONS"
    (is (not= (badge/token-key :COEFFECT)
              (badge/token-key :SUBSCRIPTIONS))
        "COEFFECT and SUBSCRIPTIONS must be visually distinct"))
  (testing "COEFFECT pulls :magenta (violet — mock #a855f7)"
    (is (= :magenta (badge/token-key :COEFFECT))))
  (testing "SUBSCRIPTIONS pulls :magenta-pink (pink — mock #ec4899)"
    (is (= :magenta-pink (badge/token-key :SUBSCRIPTIONS))))
  (testing "the five core pipeline pills carry five distinct token keys"
    (let [core-pills #{:DISPATCH :COEFFECT :HANDLER :SUBSCRIPTIONS :VIEWS}
          token-keys (set (map badge/token-key core-pills))]
      (is (= 5 (count token-keys))
          (str "expected 5 distinct token-keys, got " token-keys)))))

(deftest fib-px-test
  (testing "fibonacci helper resolves to px strings"
    (is (= "3px"  (badge/fib-px :f3)))
    (is (= "5px"  (badge/fib-px :f5)))
    (is (= "8px"  (badge/fib-px :f8)))
    (is (= "13px" (badge/fib-px :f13)))
    (is (= "21px" (badge/fib-px :f21)))
    (is (= "34px" (badge/fib-px :f34)))
    (is (= "55px" (badge/fib-px :f55)))
    (is (= "89px" (badge/fib-px :f89))))
  (testing "unknown key returns '0'"
    (is (= "0" (badge/fib-px :nope)))))

(deftest numbered-cascade-geometry-test
  (testing "the geometry constants exposed for the view are the ones the spec commits to"
    (is (= 21  badge/step-numbered-circle-diameter-px))
    (is (= 13  badge/vertical-line-offset-px))
    (is (= -44 badge/circle-left-offset-px))
    (is (= -34 badge/line-left-offset-px))))

;; ---- rf2-u69j7 — machine-cascade row chrome ----------------------------

(deftest cascade-kind-set-test
  (testing "rf2-u69j7 — the cascade-kind inventory matches the
            substrate trace ops the projection harvests (rf2-ugdas adds
            :no-op for the benign unhandled-event no-op; rf2-it4vt adds
            :start for the machine's birth [START] badge)"
    (is (= #{:guard :action :transition :timer :no-op :start}
           badge/cascade-kind-set))
    (is (badge/cascade-kind? :guard))
    (is (badge/cascade-kind? :action))
    (is (badge/cascade-kind? :transition))
    (is (badge/cascade-kind? :timer))
    (is (badge/cascade-kind? :no-op))
    (is (badge/cascade-kind? :start))
    (is (not (badge/cascade-kind? :NOT-A-KIND))))

  (testing "rf2-it4vt — the :start kind resolves to a green/success label +
            colour (a clean birth is a GOOD event), distinct from the muted
            no-op tone"
    (is (= "START" (badge/cascade-kind-label :start)))
    (is (string? (badge/cascade-kind-colour :start)))
    (is (re-find #"var\(--rf-xray-" (badge/cascade-kind-colour :start)))
    (is (not= (badge/cascade-kind-colour :start)
              (badge/cascade-kind-colour :no-op))
        "the green birth is distinct from the muted no-op tone"))

  (testing "rf2-ugdas / rf2-iu3no — the :no-op kind resolves to a muted (not
            red) label + colour, distinct from the error/warning hues. The
            label is `NO OP` (space, not hyphen — rf2-iu3no, the sole marker
            on the collapsed `[NO OP] staying in {state}` cell)"
    (is (= "NO OP" (badge/cascade-kind-label :no-op)))
    (is (string? (badge/cascade-kind-colour :no-op)))
    ;; The benign no-op uses the tertiary token (same as :guard / :timer's
    ;; muted family) — explicitly NOT the :error / :warning hue.
    (is (= (badge/cascade-kind-colour :guard)
           (badge/cascade-kind-colour :no-op))
        "muted/tertiary tone, not an alarmist hue")))

(deftest cascade-kind-resolver-test
  (testing "rf2-u69j7 — every cascade kind resolves to a non-blank
            CSS-variable colour + uppercase label"
    (doseq [k badge/cascade-kind-set]
      (let [c (badge/cascade-kind-colour k)
            l (badge/cascade-kind-label k)]
        (is (string? c))
        (is (re-find #"var\(--rf-xray-" c)
            (str "expected CSS variable for " k ", got " c))
        (is (string? l))
        (is (= (str/upper-case l) l)
            (str "kind label for " k " not uppercase: " l))))))

(deftest cascade-kind-token-key-mappings-test
  (testing "rf2-u69j7 — kind → token-key mappings are stable"
    (is (= :text-tertiary (badge/cascade-kind-token-key :guard)))
    (is (= :accent        (badge/cascade-kind-token-key :action)))
    (is (= :magenta       (badge/cascade-kind-token-key :transition)))
    (is (= :warning       (badge/cascade-kind-token-key :timer))))
  (testing "rf2-u69j7 — unknown kind falls back to :text-tertiary"
    (is (= :text-tertiary (badge/cascade-kind-token-key :NOT-A-KIND)))))

(deftest cascade-phase-set-test
  (testing "rf2-u69j7 — the cascade-phase closed set matches rf2-82a0u"
    (is (= #{:exit :transition :entry :always
             :after-action :initial-entry :destroy-exit}
           badge/cascade-phase-set))
    (doseq [p badge/cascade-phase-set]
      (is (badge/cascade-phase? p)))
    (is (not (badge/cascade-phase? :NOT-A-PHASE)))))

(deftest cascade-phase-label-test
  (testing "rf2-u69j7 — every phase produces a non-blank label"
    (doseq [p badge/cascade-phase-set]
      (let [l (badge/cascade-phase-label p)]
        (is (string? l))
        (is (seq l))))))

(deftest cascade-action-badge-label-test
  (testing "rf2-2hj0h item 5 — the merged ACTION badge folds the phase + the
            ACTION kind into one token"
    (is (= "EXIT ACTION"          (badge/cascade-action-badge-label :exit)))
    (is (= "ENTRY ACTION"         (badge/cascade-action-badge-label :entry)))
    (is (= "TRANSITION ACTION"    (badge/cascade-action-badge-label :transition)))
    (is (= "ALWAYS ACTION"        (badge/cascade-action-badge-label :always)))
    (is (= "AFTER-ACTION ACTION"  (badge/cascade-action-badge-label :after-action)))
    (is (= "INITIAL-ENTRY ACTION" (badge/cascade-action-badge-label :initial-entry)))
    (is (= "DESTROY-EXIT ACTION"  (badge/cascade-action-badge-label :destroy-exit))))
  (testing "rf2-2hj0h — every phase yields a `<PHASE> ACTION` token ending in
            ` ACTION`; a phase-less action falls back to the bare ACTION label"
    (doseq [p badge/cascade-phase-set]
      (let [l (badge/cascade-action-badge-label p)]
        (is (string? l))
        (is (str/ends-with? l " ACTION"))))
    (is (= (badge/cascade-kind-label :action)
           (badge/cascade-action-badge-label nil))
        "no phase → bare ACTION label (the kind label)"))
  (testing "rf2-2hj0h — the state-change TRANSITION ROW keeps its own single
            `TRANSITION` pill (distinct from a `TRANSITION ACTION`)"
    (is (= "TRANSITION" (badge/cascade-kind-label :transition)))))

(deftest event-bundle-outcome-resolver-test
  (testing "rf2-u69j7 — outcome → token-key + glyph mappings"
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

;; rf2-9wq0v retired the per-STAGE status primitive (`step-status-set` /
;; `step-status?` / `step-status-glyph` / `step-status-token-key` /
;; `step-status-colour`) along with its per-stage badge glyph — a clean
;; run painted a tick on every stage (no information) and a failure is
;; already shown by the inline exception card under the stage. The
;; per-EFFECT ledger glyphs (below) + the event-bundle-outcome banner glyph
;; (above) carry the surviving, distinct signals.

;; ---- flat SIDE EFFECTS ledger row glyphs (rf2-j630b) --------------------

(deftest fx-row-status-glyph-test
  (testing "rf2-j630b — the flat-ledger per-effect glyph closed set:
            ✓ :ok / ✗ :error / ✗ :rollback / ↺ :overridden / – :skipped"
    (is (= "✓" (badge/fx-row-status-glyph :ok)))
    (is (= "✗" (badge/fx-row-status-glyph :error)))
    (is (= "✗" (badge/fx-row-status-glyph :rollback)))
    (is (= "↺" (badge/fx-row-status-glyph :overridden)))
    (is (= "–" (badge/fx-row-status-glyph :skipped))
        "skipped is the muted en-dash 'n/a'")
    (is (= "✓" (badge/fx-row-status-glyph :whatever)) "quiet default")))

(deftest skipped-glyph-distinct-from-cancelled-test
  (testing "rf2-j630b — the skipped en-dash avoids the :cancelled
            middle-dot (`·`) used by the machine-cascade outcome chip"
    (is (= "–" badge/skipped-glyph))
    (is (not= badge/skipped-glyph (badge/cascade-outcome-glyph :cancelled))
        "the skipped glyph and the :cancelled middle-dot are distinct")
    (is (string? badge/skipped-hover))))

(deftest fx-row-status-token-key-test
  (testing "rf2-j630b — :error/:rollback → :error; :overridden → :accent;
            :skipped → :text-tertiary (muted, NEUTRAL); else → :success"
    (is (= :error         (badge/fx-row-status-token-key :error)))
    (is (= :error         (badge/fx-row-status-token-key :rollback)))
    (is (= :accent        (badge/fx-row-status-token-key :overridden)))
    (is (= :text-tertiary (badge/fx-row-status-token-key :skipped)))
    (is (= :success       (badge/fx-row-status-token-key :ok)))
    (is (= :success       (badge/fx-row-status-token-key nil)))))

(deftest fx-row-status-colour-resolves-css-var-test
  (testing "rf2-j630b — the row glyph colour resolver returns a
            CSS-variable string for every ledger status (theme-driven)"
    (doseq [s [:ok :error :rollback :overridden :skipped]]
      (let [c (badge/fx-row-status-colour s)]
        (is (string? c))
        (is (re-find #"var\(--rf-xray-" c)
            (str "expected CSS variable for " s ", got " c))))))
