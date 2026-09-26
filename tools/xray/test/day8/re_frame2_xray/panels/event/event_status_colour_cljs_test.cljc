(ns day8.re-frame2-xray.panels.event.event-status-colour-cljs-test
  "Pure-data tests for the canonical event-lifecycle status-colour map.

  ## Why .cljc + _cljs_test naming

  Same dual-target pattern as `section_cljs_test.cljc` /
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
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
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

(deftest every-status-resolves-to-a-non-nil-hex
  (testing "the indirection chain (status → token-kw → hex) lands on
            a real hex for every status. No magenta-tinted gap, no
            missing key in `theme/tokens`. Drives off `dark-palette`
            directly (the hex source of truth) — `tokens` exposes
            CSS-variable strings."
    (doseq [status event-status/statuses]
      (let [token-kw (event-status/status->token status)
            hex      (get tokens/dark-palette token-kw)]
        (is (string? hex) (str status " → " token-kw " resolves to a hex"))
        (is (re-find #"^#[0-9A-Fa-f]+$" hex)
            (str status " hex " hex " starts with #"))))))

;; ---- classifier — per-state coverage and precedence ---------------------

(deftest classify-status-resolves-each-input-in-precedence-order
  (are [input status] (= status (event-status/classify-status input))
    ;; A settled :ok outcome is success, and so is :warning — the yellow
    ;; glyph ALREADY signals the warning at the Event header, so the row
    ;; colour reads 'settled' rather than re-amplifying it.
    {:outcome :ok}                     :settled-success
    {:outcome :warning}                :settled-success
    ;; :error wins over every other slot — RETRO, pause, stale, in-flight:
    ;; the user MUST notice the red, even among the yellow history.
    {:outcome :error}                  :settled-error
    {:outcome :error :mode :retro}     :settled-error
    {:outcome :error :paused? true}    :settled-error
    {:outcome :error :stale? true}     :settled-error
    {:outcome :error :in-flight? true} :settled-error
    ;; In flight with no terminal outcome is the LIVE-head cascade still
    ;; building; a landed outcome settles it.
    {:in-flight? true}                 :in-flight
    {:in-flight? true :mode :live}     :in-flight
    {:in-flight? true :outcome :ok}    :settled-success
    ;; A tool (story, MCP, the spine pause button) has claimed the buffer.
    {:paused? true}                    :paused-by-tool
    {:paused? true :mode :live}        :paused-by-tool
    ;; Stale by flag (time-travel / dispatch-replay) or by RETRO mode (a
    ;; pinned non-head cascade), and stale wins over paused.
    {:stale? true}                     :stale
    {:stale? true :outcome :ok}        :stale
    {:mode :retro}                     :stale
    {:mode :retro :outcome :ok}        :stale
    {:mode :retro :paused? true}       :stale
    {:stale? true :paused? true}       :stale
    ;; No signals at all reads as still in progress — the safe default
    ;; (violet, the neutral causal-chain colour), never a misleading green.
    {}                                 :in-flight
    nil                                :in-flight))

;; ---- hex resolver --------------------------------------------------------

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

(deftest event-bundle-outcome-reads-the-producer-duration-key
  (testing "`:duration-ms` is read off the run-end trace's
            `:rf.event/elapsed-ms`, the key the producer stamps"
    (is (= 8 (:duration-ms
               (event-status/event-bundle-outcome
                 {:event   [:poll/tick]
                  :handler {:operation :rf.event/run-end
                            :tags      {:rf.event/elapsed-ms 8}}})))))
  (testing "a `:duration-ms` tag is a fallback"
    (is (= 3 (:duration-ms
               (event-status/event-bundle-outcome
                 {:event   [:poll/tick]
                  :handler {:operation :rf.event/run-end
                            :tags      {:duration-ms 3}}}))))))

(deftest event-bundle->state-frame-strict-focused-rf2-bz7flo
  (testing "when a multi-frame caller renders two cascades
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

  (testing "degrades to a dispatch-id-only match when either
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
  (testing "Visual smoke — each
            lifecycle state surfaces in its expected anchor colour
            across the palette. Failures here flag a palette drift
            (token renamed) or a classifier regression.
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
