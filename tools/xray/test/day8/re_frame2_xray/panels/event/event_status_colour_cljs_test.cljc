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

(deftest every-status-resolves-to-a-non-nil-hex
  (testing "the indirection chain (status → token-kw → hex) lands on
            a real hex for every status. No magenta-tinted gap, no
            missing key in `theme/tokens`. Drives off `dark-palette`
            directly (the hex source of truth) — `tokens` exposes
            CSS-variable strings."
    (doseq [status event-status/statuses]
      (let [token-kw (event-status/status->token status)
            hex      (get tokens/dark-palette token-kw)]
        (is (re-find #"^#[0-9A-Fa-f]+$" hex)
            (str status " → " token-kw " resolves to hex " hex))))))

;; ---- classifier — per-state coverage and precedence ---------------------

(deftest classify-status-resolves-each-input-in-precedence-order
  (are [input status] (= status (event-status/classify-status input))
    ;; :error wins over every other slot — the user MUST notice the red,
    ;; even among the yellow RETRO history.
    {:outcome :error :mode :retro}     :settled-error
    ;; Stale by flag (time-travel / dispatch-replay) or by RETRO mode (a
    ;; pinned non-head cascade); stale wins over paused and over a settled
    ;; outcome.
    {:stale? true :paused? true}       :stale
    {:mode :retro :outcome :ok}        :stale
    ;; In flight with no terminal outcome is the LIVE-head cascade still
    ;; building; a landed outcome settles it.
    {:in-flight? true}                 :in-flight
    {:in-flight? true :outcome :ok}    :settled-success
    ;; A tool (story, MCP, the spine pause button) has claimed the buffer.
    {:paused? true}                    :paused-by-tool
    ;; A :warning outcome settles as success — the yellow glyph ALREADY
    ;; signals the warning at the Event header, so the row colour reads
    ;; 'settled' rather than re-amplifying it.
    {:outcome :warning}                :settled-success
    ;; No signals at all reads as still in progress — the safe default
    ;; (violet, the neutral causal-chain colour), never a misleading green.
    {}                                 :in-flight))

;; ---- cascade → state projection ----------------------------------------

(defn- mock-outcome [outcome]
  (fn [_cascade] {:outcome outcome}))

(deftest event-bundle->state-projects-focused-error
  (testing "a cascade that's focused + LIVE + errored → the state
            map carries :outcome :error, :focused? true and the focus's
            mode, and is neither stale nor paused"
    (is (= {:outcome :error :focused? true :paused? false :mode :live
            :in-flight? false :stale? false}
           (event-status/event-bundle->state
             {:dispatch-id 42}
             {:dispatch-id 42 :mode :live :paused? false}
             (mock-outcome :error))))))

(deftest event-bundle->state-projects-non-focused-event-bundle
  (testing "a cascade that's NOT the spine focus → :focused? false +
            :mode nil, and none of the focus's RETRO / paused scope leaks
            onto it — non-focused rows are rendered with their settled
            state"
    (is (= {:outcome :ok :focused? false :paused? false :mode nil
            :in-flight? false :stale? false}
           (event-status/event-bundle->state
             {:dispatch-id 1}
             {:dispatch-id 99 :mode :retro :paused? true}
             (mock-outcome :ok))))))

(deftest event-bundle->state-frame-strict-focused-rf2-bz7flo
  (testing "when a multi-frame caller renders two cascades
            sharing a dispatch-id in different frames, only the cascade
            in the FOCUSED frame is :focused? (and so paused / stale). A
            dispatch-id-only check would mark BOTH."
    (let [focus    {:dispatch-id 7 :frame :frame/b :mode :retro :paused? true}
          in-frame (event-status/event-bundle->state
                     {:dispatch-id 7 :frame :frame/b} focus (mock-outcome :ok))
          foreign  (event-status/event-bundle->state
                     {:dispatch-id 7 :frame :frame/a} focus (mock-outcome :ok))]
      (is (= [true false] (map :focused? [in-frame foreign])))
      (is (= [true true] ((juxt :paused? :stale?) in-frame)))))

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
  (testing "`event-status-colour` composes the classifier with the token
            map and returns CSS-variable strings (the class toggle on the
            shell root decides whether the dark or light hex resolves at
            paint time); we compare against the same var-map
            (`tokens/tokens`)."
    (is (= (:red tokens/tokens)
           (event-status/event-status-colour {:outcome :error}))
        "settled-error rides red")))
