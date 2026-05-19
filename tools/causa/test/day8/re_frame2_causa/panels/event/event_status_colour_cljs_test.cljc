(ns day8.re-frame2-causa.panels.event.event-status-colour-cljs-test
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
    - `cascade->state` projects the cascade + focus pair onto the
      input map consumed by the classifier."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.event.event-status-colour :as esc]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- vocabulary ---------------------------------------------------------

(deftest statuses-enumeration-is-stable
  (testing "the five canonical statuses ride in a stable render order
            so callers (chip rows, legends) can enumerate them
            deterministically."
    (is (= [:in-flight :settled-success :settled-error
            :paused-by-tool :stale]
           esc/statuses))))

(deftest status-token-map-covers-every-status
  (testing "rf2-b76v4 — every status has a token keyword. No
            unmapped status leaks a nil into the palette."
    (is (= (set esc/statuses)
           (set (keys esc/status->token))))))

(deftest status-token-map-mirrors-tanstack-anchors
  (testing "rf2-b76v4 — the per-status token assignments mirror the
            TanStack devtool's semantic anchors with one peer-pick
            substitution (paused-by-tool → :cyan rather than purple,
            because Causa already owns :accent-violet for the causal
            chain)."
    (is (= :accent-violet  (esc/status->token :in-flight)))
    (is (= :green          (esc/status->token :settled-success)))
    (is (= :red            (esc/status->token :settled-error)))
    (is (= :cyan           (esc/status->token :paused-by-tool)))
    (is (= :yellow         (esc/status->token :stale)))))

(deftest every-status-resolves-to-a-non-nil-hex
  (testing "the indirection chain (status → token-kw → hex) lands on
            a real hex for every status. No magenta-tinted gap, no
            missing key in `theme/tokens`."
    (doseq [status esc/statuses]
      (let [token-kw (esc/status->token status)
            hex      (get tokens/tokens token-kw)]
        (is (string? hex) (str status " → " token-kw " resolves to a hex"))
        (is (re-find #"^#[0-9A-Fa-f]+$" hex)
            (str status " hex " hex " starts with #"))))))

;; ---- classifier — per-state coverage ------------------------------------

(deftest classify-status-settled-success
  (testing ":ok outcome with no other signals → :settled-success."
    (is (= :settled-success
           (esc/classify-status {:outcome :ok})))))

(deftest classify-status-settled-error
  (testing ":error outcome → :settled-error regardless of any other
            slot. Errors override RETRO mode, in-flight, paused — the
            user MUST notice the red."
    (is (= :settled-error (esc/classify-status {:outcome :error})))
    (is (= :settled-error (esc/classify-status {:outcome :error :mode :retro})))
    (is (= :settled-error (esc/classify-status {:outcome :error :paused? true})))
    (is (= :settled-error (esc/classify-status {:outcome :error :stale? true})))
    (is (= :settled-error (esc/classify-status {:outcome :error :in-flight? true})))))

(deftest classify-status-settled-warning-resolves-to-success
  (testing ":warning outcome → :settled-success. The yellow glyph
            ALREADY signals warning at the Event header; the row
            status colour reads 'settled' rather than re-amplifying
            the warning."
    (is (= :settled-success (esc/classify-status {:outcome :warning})))))

(deftest classify-status-in-flight
  (testing ":in-flight? true with no terminal outcome → :in-flight.
            The LIVE-head cascade still building."
    (is (= :in-flight (esc/classify-status {:in-flight? true})))
    (is (= :in-flight (esc/classify-status {:in-flight? true :mode :live})))))

(deftest classify-status-in-flight-clears-on-outcome
  (testing "in-flight? + a settled outcome → the outcome wins. A
            cascade in mid-build that has just landed its :event/
            do-fx is logically settled."
    (is (= :settled-success
           (esc/classify-status {:in-flight? true :outcome :ok})))
    (is (= :settled-error
           (esc/classify-status {:in-flight? true :outcome :error})))))

(deftest classify-status-paused-by-tool
  (testing ":paused? true with no error → :paused-by-tool. A tool
            (story, MCP, the user via the spine pause button) has
            claimed the buffer; LIVE mode is paused."
    (is (= :paused-by-tool (esc/classify-status {:paused? true})))
    (is (= :paused-by-tool (esc/classify-status {:paused? true :mode :live})))))

(deftest classify-status-stale-from-explicit-flag
  (testing ":stale? true → :stale regardless of mode. Used for
            cascades replayed via time-travel / dispatch-replay."
    (is (= :stale (esc/classify-status {:stale? true})))
    (is (= :stale (esc/classify-status {:stale? true :outcome :ok})))))

(deftest classify-status-stale-from-retro-mode
  (testing ":retro mode → :stale even without the explicit flag. A
            user pinning a non-head cascade IS inspecting a stale
            row; the colour reflects that."
    (is (= :stale (esc/classify-status {:mode :retro})))
    (is (= :stale (esc/classify-status {:mode :retro :outcome :ok})))))

(deftest classify-status-error-wins-over-stale
  (testing "error trumps stale — a RETRO-replayed errored cascade
            still reads red so the user spots it among the yellow
            history."
    (is (= :settled-error
           (esc/classify-status {:mode :retro :outcome :error})))
    (is (= :settled-error
           (esc/classify-status {:stale? true :outcome :error})))))

(deftest classify-status-stale-wins-over-paused
  (testing "stale wins over paused — if the user has scrubbed to a
            historical cascade, the row is stale-by-virtue-of-mode
            regardless of the paused? slot value the spine stamped
            on the way past."
    (is (= :stale
           (esc/classify-status {:mode :retro :paused? true})))
    (is (= :stale
           (esc/classify-status {:stale? true :paused? true})))))

(deftest classify-status-empty-input-defaults-to-in-flight
  (testing "no signals at all → :in-flight. A cold-start row with
            no outcome / mode / focus reads as still-in-progress —
            the safest default (violet, the project's neutral
            causal-chain colour) rather than a misleading green."
    (is (= :in-flight (esc/classify-status {})))
    (is (= :in-flight (esc/classify-status nil)))))

;; ---- hex resolver --------------------------------------------------------

(deftest event-status-colour-resolves-through-tokens
  (testing "every state-input → hex matches the indirection
            (state → status → token → tokens hex). No inline hexes
            in the resolver path."
    (doseq [[state expected-status]
            [[{:outcome :ok}              :settled-success]
             [{:outcome :error}           :settled-error]
             [{:outcome :warning}         :settled-success]
             [{:mode :retro}              :stale]
             [{:stale? true}              :stale]
             [{:in-flight? true}          :in-flight]
             [{:paused? true}             :paused-by-tool]
             [{}                          :in-flight]]]
      (let [expected-hex (get tokens/tokens
                              (get esc/status->token expected-status))]
        (is (= expected-hex (esc/event-status-colour state))
            (str "state " state " resolves to " expected-status " hex"))))))

(deftest event-status-token-resolves-to-keyword
  (testing "`event-status-token` is the keyword-side of the resolver
            — useful for callers that compose styles through
            `theme/tokens` rather than inlining the hex."
    (is (= :red (esc/event-status-token {:outcome :error})))
    (is (= :green (esc/event-status-token {:outcome :ok})))
    (is (= :yellow (esc/event-status-token {:mode :retro})))
    (is (= :cyan (esc/event-status-token {:paused? true})))
    (is (= :accent-violet (esc/event-status-token {})))))

(deftest event-status-colour-fallback
  (testing "unknown status (shouldn't happen via the classifier, but
            defence-in-depth) falls back to :accent-violet so the
            row still renders a visible colour rather than nil."
    ;; Defence-in-depth check: an out-of-band status keyword routed
    ;; through the resolver fn surface still returns a usable hex.
    ;; We test by reaching into the public API with a deliberately-
    ;; malformed state shape — every key unrecognised — and ensure
    ;; the violet fallback rides.
    (is (string? (esc/event-status-colour {:outcome :unknown :mode :unknown})))))

;; ---- cascade → state projection ----------------------------------------

(defn- mock-outcome [outcome]
  (fn [_cascade] {:outcome outcome}))

(deftest cascade->state-projects-focused-error
  (testing "a cascade that's focused + LIVE + errored → the state
            map carries :outcome :error + :focused? true. The
            classifier then resolves to :settled-error."
    (let [cascade {:dispatch-id 42}
          focus   {:dispatch-id 42 :mode :live :paused? false}
          state   (esc/cascade->state cascade focus (mock-outcome :error))]
      (is (= :error (:outcome state)))
      (is (true? (:focused? state)))
      (is (false? (:stale? state)))
      (is (= :live (:mode state)))
      (is (= :settled-error (esc/classify-status state))))))

(deftest cascade->state-projects-retro-stale
  (testing "a focused cascade in RETRO mode → :stale? true (derived
            from :mode :retro)."
    (let [cascade {:dispatch-id 7}
          focus   {:dispatch-id 7 :mode :retro :paused? false}
          state   (esc/cascade->state cascade focus (mock-outcome :ok))]
      (is (true? (:stale? state)))
      (is (= :retro (:mode state)))
      (is (= :stale (esc/classify-status state))))))

(deftest cascade->state-projects-non-focused-cascade
  (testing "a cascade that's NOT the spine focus → :focused? false +
            :mode nil. The classifier resolves to :settled-success
            for an :ok outcome — non-focused rows are rendered with
            their settled state, not the spine's RETRO scope."
    (let [cascade {:dispatch-id 1}
          focus   {:dispatch-id 99 :mode :retro :paused? true}
          state   (esc/cascade->state cascade focus (mock-outcome :ok))]
      (is (false? (:focused? state)))
      (is (nil? (:mode state)))
      (is (false? (:stale? state)))
      (is (false? (:paused? state)))
      (is (= :settled-success (esc/classify-status state))))))

(deftest cascade->state-with-nil-focus
  (testing "no focus map (e.g. test rig pre-mount) → :focused? false.
            The fn still resolves cleanly so JVM-side fixture
            builders can call it without a live spine."
    (let [state (esc/cascade->state {:dispatch-id 1} nil (mock-outcome :ok))]
      (is (false? (:focused? state)))
      (is (= :settled-success (esc/classify-status state))))))

;; ---- visual smoke — per-state hex landing on the right palette anchor --

(deftest visual-smoke-per-state-colour-mapping
  (testing "Visual smoke for the bead's acceptance criterion — each
            lifecycle state surfaces in its expected anchor colour
            across the dark palette. Failures here flag a palette
            drift (token renamed) or a classifier regression."
    (let [palette tokens/dark-palette]
      (is (= (:accent-violet palette)
             (esc/event-status-colour {:in-flight? true}))
          "in-flight rides violet — the project's causal-chain accent")
      (is (= (:green palette)
             (esc/event-status-colour {:outcome :ok}))
          "settled-success rides green")
      (is (= (:red palette)
             (esc/event-status-colour {:outcome :error}))
          "settled-error rides red")
      (is (= (:cyan palette)
             (esc/event-status-colour {:paused? true}))
          "paused-by-tool rides cyan (TanStack's purple was already
           Causa's :accent-violet)")
      (is (= (:yellow palette)
             (esc/event-status-colour {:mode :retro}))
          "stale rides yellow"))))
