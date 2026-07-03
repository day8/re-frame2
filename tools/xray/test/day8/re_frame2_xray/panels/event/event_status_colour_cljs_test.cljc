(ns day8.re-frame2-xray.panels.event.event-status-colour-cljs-test
  "Pure-data tests for the canonical event-lifecycle status-colour map
  (rf2-b76v4).

  ## Why .cljc + _cljs_test naming

  Same dual-target pattern as `perf_tier_cljs_test.cljc` /
  `tokens_cljs_test.cljc` — Cognitect (`.*-test$` ns regex) + Shadow
  `:node-test` (`cljs-test$`).

  ## What's under test

    - `classify-status` resolves every per-state input to the right
      lifecycle keyword (`:in-flight` / `:settled-success` /
      `:settled-error` / `:paused-by-tool` / `:stale`).
    - The precedence contract — `:settled-error` always wins, `:stale`
      wins over `:in-flight`, etc.
    - `status->token` covers every status with a token keyword that
      resolves to a non-nil hex through `theme/tokens` (no nil drop-
      outs).
    - `event-status-colour` is a pure passthrough through
      `theme/tokens` (no inline hexes, one source of truth).
    - `event-bundle->state` projects the cascade + focus pair onto the
      input map consumed by the classifier."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

;; ---- vocabulary ---------------------------------------------------------

(deftest statuses-enumeration-is-stable
  (testing "the five canonical statuses ride in a stable render order
            so callers (chip rows, legends) can enumerate them
            deterministically."
    (is (= [:in-flight :settled-success :settled-error
            :paused-by-tool :stale]
           event-status/statuses))))

(deftest status-token-map-covers-every-status
  (testing "rf2-b76v4 — every status has a token keyword. No
            unmapped status leaks a nil into the palette."
    (is (= (set event-status/statuses)
           (set (keys event-status/status->token))))))

(deftest status-token-map-mirrors-tanstack-anchors
  (testing "rf2-b76v4 / rf2-ad7zx.13 — the per-status token assignments
            mirror the TanStack devtool's semantic anchors. The LIVE
            head (`:in-flight`) IS the current-epoch accent → the single
            `:accent` (GitHub blue); `:paused-by-tool` takes the fixed
            cool blue `:info` as a distinct peer."
    (is (= :accent         (event-status/status->token :in-flight)))
    (is (= :green          (event-status/status->token :settled-success)))
    (is (= :red            (event-status/status->token :settled-error)))
    (is (= :info           (event-status/status->token :paused-by-tool)))
    (is (= :yellow         (event-status/status->token :stale)))))

(deftest every-status-resolves-to-a-non-nil-hex
  (testing "the indirection chain (status → token-kw → hex) lands on
            a real hex for every status. No magenta-tinted gap, no
            missing key in `theme/tokens`. Drives off `dark-palette`
            directly (the hex source of truth) — `tokens` exposes
            CSS-variable strings post rf2-on4cm."
    (doseq [status event-status/statuses]
      (let [token-kw (event-status/status->token status)
            hex      (get tokens/dark-palette token-kw)]
        (is (string? hex) (str status " → " token-kw " resolves to a hex"))
        (is (re-find #"^#[0-9A-Fa-f]+$" hex)
            (str status " hex " hex " starts with #"))))))

;; ---- classifier — per-state coverage ------------------------------------

(deftest classify-status-settled-success
  (testing ":ok outcome with no other signals → :settled-success."
    (is (= :settled-success
           (event-status/classify-status {:outcome :ok})))))

(deftest classify-status-settled-error
  (testing ":error outcome → :settled-error regardless of any other
            slot. Errors override RETRO mode, in-flight, paused — the
            user MUST notice the red."
    (is (= :settled-error (event-status/classify-status {:outcome :error})))
    (is (= :settled-error (event-status/classify-status {:outcome :error :mode :retro})))
    (is (= :settled-error (event-status/classify-status {:outcome :error :paused? true})))
    (is (= :settled-error (event-status/classify-status {:outcome :error :stale? true})))
    (is (= :settled-error (event-status/classify-status {:outcome :error :in-flight? true})))))

(deftest classify-status-settled-warning-resolves-to-success
  (testing ":warning outcome → :settled-success. The yellow glyph
            ALREADY signals warning at the Event header; the row
            status colour reads 'settled' rather than re-amplifying
            the warning."
    (is (= :settled-success (event-status/classify-status {:outcome :warning})))))

(deftest classify-status-in-flight
  (testing ":in-flight? true with no terminal outcome → :in-flight.
            The LIVE-head cascade still building."
    (is (= :in-flight (event-status/classify-status {:in-flight? true})))
    (is (= :in-flight (event-status/classify-status {:in-flight? true :mode :live})))))

(deftest classify-status-in-flight-clears-on-outcome
  (testing "in-flight? + a settled outcome → the outcome wins. A
            cascade in mid-build that has just landed its :event/
            do-fx is logically settled."
    (is (= :settled-success
           (event-status/classify-status {:in-flight? true :outcome :ok})))
    (is (= :settled-error
           (event-status/classify-status {:in-flight? true :outcome :error})))))

(deftest classify-status-paused-by-tool
  (testing ":paused? true with no error → :paused-by-tool. A tool
            (story, MCP, the user via the spine pause button) has
            claimed the buffer; LIVE mode is paused."
    (is (= :paused-by-tool (event-status/classify-status {:paused? true})))
    (is (= :paused-by-tool (event-status/classify-status {:paused? true :mode :live})))))

(deftest classify-status-stale-from-explicit-flag
  (testing ":stale? true → :stale regardless of mode. Used for
            cascades replayed via time-travel / dispatch-replay."
    (is (= :stale (event-status/classify-status {:stale? true})))
    (is (= :stale (event-status/classify-status {:stale? true :outcome :ok})))))

(deftest classify-status-stale-from-retro-mode
  (testing ":retro mode → :stale even without the explicit flag. A
            user pinning a non-head cascade IS inspecting a stale
            row; the colour reflects that."
    (is (= :stale (event-status/classify-status {:mode :retro})))
    (is (= :stale (event-status/classify-status {:mode :retro :outcome :ok})))))

(deftest classify-status-error-wins-over-stale
  (testing "error trumps stale — a RETRO-replayed errored cascade
            still reads red so the user spots it among the yellow
            history."
    (is (= :settled-error
           (event-status/classify-status {:mode :retro :outcome :error})))
    (is (= :settled-error
           (event-status/classify-status {:stale? true :outcome :error})))))

(deftest classify-status-stale-wins-over-paused
  (testing "stale wins over paused — if the user has scrubbed to a
            historical cascade, the row is stale-by-virtue-of-mode
            regardless of the paused? slot value the spine stamped
            on the way past."
    (is (= :stale
           (event-status/classify-status {:mode :retro :paused? true})))
    (is (= :stale
           (event-status/classify-status {:stale? true :paused? true})))))

(deftest classify-status-empty-input-defaults-to-in-flight
  (testing "no signals at all → :in-flight. A cold-start row with
            no outcome / mode / focus reads as still-in-progress —
            the safest default (violet, the project's neutral
            causal-chain colour) rather than a misleading green."
    (is (= :in-flight (event-status/classify-status {})))
    (is (= :in-flight (event-status/classify-status nil)))))

;; ---- hex resolver --------------------------------------------------------

(deftest event-status-colour-resolves-through-tokens
  (testing "every state-input → colour matches the indirection
            (state → status → token → tokens value). No inline hexes
            in the resolver path. Post rf2-on4cm `tokens` exposes
            CSS-variable strings; both sides of the comparison go
            through the same map so the indirection is what's pinned."
    (doseq [[state expected-status]
            [[{:outcome :ok}              :settled-success]
             [{:outcome :error}           :settled-error]
             [{:outcome :warning}         :settled-success]
             [{:mode :retro}              :stale]
             [{:stale? true}              :stale]
             [{:in-flight? true}          :in-flight]
             [{:paused? true}             :paused-by-tool]
             [{}                          :in-flight]]]
      (let [expected-colour (get tokens/tokens
                                 (get event-status/status->token expected-status))]
        (is (= expected-colour (event-status/event-status-colour state))
            (str "state " state " resolves to " expected-status " colour"))))))

(deftest event-status-token-resolves-to-keyword
  (testing "`event-status-token` is the keyword-side of the resolver
            — useful for callers that compose styles through
            `theme/tokens` rather than inlining the hex."
    (is (= :red (event-status/event-status-token {:outcome :error})))
    (is (= :green (event-status/event-status-token {:outcome :ok})))
    (is (= :yellow (event-status/event-status-token {:mode :retro})))
    (is (= :info (event-status/event-status-token {:paused? true})))
    (is (= :accent (event-status/event-status-token {})))))

(deftest event-status-colour-fallback
  (testing "unknown status (shouldn't happen via the classifier, but
            defence-in-depth) falls back to the mode :accent so the
            row still renders a visible colour rather than nil."
    ;; Defence-in-depth check: an out-of-band status keyword routed
    ;; through the resolver fn surface still returns a usable hex.
    ;; We test by reaching into the public API with a deliberately-
    ;; malformed state shape — every key unrecognised — and ensure
    ;; the accent fallback rides.
    (is (string? (event-status/event-status-colour {:outcome :unknown :mode :unknown})))))

;; ---- cascade → state projection ----------------------------------------

(defn- mock-outcome [outcome]
  (fn [_cascade] {:outcome outcome}))

(deftest event-bundle->state-projects-focused-error
  (testing "a cascade that's focused + LIVE + errored → the state
            map carries :outcome :error + :focused? true. The
            classifier then resolves to :settled-error."
    (let [cascade {:dispatch-id 42}
          focus   {:dispatch-id 42 :mode :live :paused? false}
          state   (event-status/event-bundle->state cascade focus (mock-outcome :error))]
      (is (= :error (:outcome state)))
      (is (true? (:focused? state)))
      (is (false? (:stale? state)))
      (is (= :live (:mode state)))
      (is (= :settled-error (event-status/classify-status state))))))

(deftest event-bundle->state-projects-retro-stale
  (testing "a focused cascade in RETRO mode → :stale? true (derived
            from :mode :retro)."
    (let [cascade {:dispatch-id 7}
          focus   {:dispatch-id 7 :mode :retro :paused? false}
          state   (event-status/event-bundle->state cascade focus (mock-outcome :ok))]
      (is (true? (:stale? state)))
      (is (= :retro (:mode state)))
      (is (= :stale (event-status/classify-status state))))))

(deftest event-bundle->state-projects-non-focused-event-bundle
  (testing "a cascade that's NOT the spine focus → :focused? false +
            :mode nil. The classifier resolves to :settled-success
            for an :ok outcome — non-focused rows are rendered with
            their settled state, not the spine's RETRO scope."
    (let [cascade {:dispatch-id 1}
          focus   {:dispatch-id 99 :mode :retro :paused? true}
          state   (event-status/event-bundle->state cascade focus (mock-outcome :ok))]
      (is (false? (:focused? state)))
      (is (nil? (:mode state)))
      (is (false? (:stale? state)))
      (is (false? (:paused? state)))
      (is (= :settled-success (event-status/classify-status state))))))

(deftest event-bundle->state-with-nil-focus
  (testing "no focus map (e.g. test rig pre-mount) → :focused? false.
            The fn still resolves cleanly so JVM-side fixture
            builders can call it without a live spine."
    (let [state (event-status/event-bundle->state {:dispatch-id 1} nil (mock-outcome :ok))]
      (is (false? (:focused? state)))
      (is (= :settled-success (event-status/classify-status state))))))

(deftest event-bundle->state-frame-strict-focused-rf2-bz7flo
  (testing "rf2-bz7flo — when a multi-frame caller renders two cascades
            sharing a dispatch-id in different frames, only the cascade
            in the FOCUSED frame is :focused?. A dispatch-id-only check
            would mark BOTH focused/paused/stale."
    (let [focus    {:dispatch-id 7 :frame :frame/b :mode :retro :paused? true}
          in-frame (event-status/event-bundle->state
                     {:dispatch-id 7 :frame :frame/b} focus (mock-outcome :ok))
          foreign  (event-status/event-bundle->state
                     {:dispatch-id 7 :frame :frame/a} focus (mock-outcome :ok))]
      (is (true? (:focused? in-frame))
          "the focused-frame cascade is focused")
      (is (true? (:paused? in-frame)))
      (is (true? (:stale? in-frame)))
      (is (false? (:focused? foreign))
          "the same-id cascade in a DIFFERENT frame is NOT focused")
      (is (false? (:paused? foreign)))
      (is (false? (:stale? foreign)))))

  (testing "rf2-bz7flo — degrades to a dispatch-id-only match when either
            the cascade or the focus is frameless (single-frame focus /
            JVM rigs building the cascade by hand)"
    (let [focus {:dispatch-id 7 :mode :live}]
      (is (true? (:focused? (event-status/event-bundle->state
                              {:dispatch-id 7 :frame :frame/a} focus (mock-outcome :ok))))
          "frameless focus + framed cascade → id-only match, focused")
      (is (true? (:focused? (event-status/event-bundle->state
                              {:dispatch-id 7} {:dispatch-id 7 :frame :frame/a :mode :live}
                              (mock-outcome :ok))))
          "framed focus + frameless cascade → id-only match, focused"))))

;; ---- visual smoke — per-state hex landing on the right palette anchor --

(deftest visual-smoke-per-state-colour-mapping
  (testing "Visual smoke for the bead's acceptance criterion — each
            lifecycle state surfaces in its expected anchor colour
            across the palette. Failures here flag a palette drift
            (token renamed) or a classifier regression. Post rf2-on4cm
            `event-status-colour` returns CSS-variable strings (the
            class toggle on the shell root decides whether the dark or
            light hex resolves at paint time); we compare against the
            same var-map (`tokens/tokens`)."
    (let [palette tokens/tokens]
      (is (= (:accent palette)
             (event-status/event-status-colour {:in-flight? true}))
          "in-flight rides the mode accent — the current-epoch accent")
      (is (= (:green palette)
             (event-status/event-status-colour {:outcome :ok}))
          "settled-success rides green")
      (is (= (:red palette)
             (event-status/event-status-colour {:outcome :error}))
          "settled-error rides red")
      (is (= (:info palette)
             (event-status/event-status-colour {:paused? true}))
          "paused-by-tool rides the fixed cool blue :info (distinct
           from the in-flight accent)")
      (is (= (:yellow palette)
             (event-status/event-status-colour {:mode :retro}))
          "stale rides yellow"))))
