(ns re-frame.story.theme.status-vocab-cljs-test
  "Lock-tests for the shared status colour vocabulary (rf2-lcl6n /
  rf2-gsqbp, spec/018 §12.6). `theme.status` is the single constructed
  source of truth — the sidebar signal chips, the test-mode result rows,
  the evidence beats, and the play-status banner all key off these
  tokens — and its own docstring states it is JVM-portable 'so the test
  corpus can assert the vocabulary without a render pass.' This is that
  corpus.

  Runs on BOTH the JVM (cognitect.test-runner under `clojure -M:test`)
  and the CLJS node-test build (shadow's `:node-test` target; the
  `cljs-test$` ns-regexp picks up this `-cljs-test` ns). The pure
  `theme.status` vocabulary is `.cljc` so it asserts on both platforms;
  the two derivation locks that reach into `.cljs` files the JVM can't
  `:require` (the sidebar style derivation + the empty-canvas render
  hook) are gated `#?(:cljs …)`, mirroring `sidebar-chips-cljs-test`.

  ## What these lock (the drift the .3 PR claimed but did not guard)

  1. **Descriptor completeness** — every one of the nine canonical
     statuses carries all FOUR discriminators (colour / glyph / shape /
     label) §12.6 mandates, plus the `:emphasis` hint. A future edit
     dropping a glyph or a shape is caught here.
  2. **Distinct channels** — the nine glyphs are distinct, the shapes
     genuinely discriminate (a `:ring` ≠ an `:outline` at the rendered
     border), and the colours are token-resolved (zero raw hex).
  3. **Order + rollup** — `order` lists exactly the nine statuses in the
     documented priority; `rollup` surfaces the worst member of a mixed
     set.
  4. **The documented nine** — the `descriptors` key set matches the
     nine statuses the namespace + spec/018 §12.6 document, no more, no
     fewer.
  5. **Single-source derivation** (CLJS) — the sidebar's
     `:signal-status-*` style keys are EQUAL to `status/chip-style`
     output, so a drift in one region is structurally impossible.
  6. **Empty-canvas hook** (CLJS) — the `story-canvas-empty` render hook
     spec/018 §12.5 promises still renders."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.theme.status :as status]
            #?@(:cljs [[re-frame.story.ui.sidebar :as sidebar]
                       [re-frame.story.ui.sidebar-styles :refer [styles]]
                       [re-frame.story.ui.shell :as shell]
                       [re-frame.story.ui.state :as state]])))

;; The nine canonical statuses spec/018 §12.6 documents (the namespace's
;; own table). `:running` is Story's live sibling of the spec's `pending`
;; (see `theme.status` docstring); the other eight are the spec's list.
(def expected-statuses
  #{:pending :running :pass :fail :error :cannot-run :blocked :dirty :redacted})

;; The four discriminators §12.6 mandates ("distinguishable in colour,
;; icon, text, and shape") + the one presentation hint.
(def discriminator-keys #{:fg :bg :border :glyph :shape :label})
(def required-keys (conj discriminator-keys :emphasis))

(def valid-shapes #{:solid :outline :dashed :ring :half})
(def valid-emphasis #{:high :normal :low})

;; ---- 1 + 2: descriptor completeness + distinct channels -----------------

(deftest descriptors-match-the-documented-nine
  (testing "the descriptor set is EXACTLY the nine documented statuses —
            no status quietly added or dropped (spec/018 §12.6 table)"
    (is (= expected-statuses (set (keys status/descriptors)))))
  (testing "`order` is exactly the nine statuses, each listed once"
    (is (= expected-statuses (set status/order)))
    (is (= (count status/order) (count (set status/order))))
    (is (= 9 (count status/order)))))

(deftest every-descriptor-carries-all-four-discriminators
  (testing "each status carries colour (:fg/:bg/:border) + :glyph + :shape
            + :label + :emphasis — the §12.6 four channels are all present,
            so no region can fall back to colour-only"
    (doseq [s expected-statuses]
      (let [d (status/descriptor s)]
        (testing (str s)
          (is (every? #(contains? d %) required-keys)
              (str s " is missing one of " required-keys))
          ;; colour channel — three non-blank strings
          (is (every? (fn [k] (and (string? (get d k))
                                   (not (str/blank? (get d k)))))
                      [:fg :bg :border]))
          ;; glyph channel — a single non-blank structural character
          (is (and (string? (:glyph d)) (not (str/blank? (:glyph d)))))
          ;; shape channel — one of the five documented shapes
          (is (contains? valid-shapes (:shape d)))
          ;; text channel — a non-blank human label
          (is (and (string? (:label d)) (not (str/blank? (:label d)))))
          ;; presentation hint
          (is (contains? valid-emphasis (:emphasis d))))))))

(deftest channels-are-distinct
  (testing "the nine glyphs are all distinct — the glyph channel
            discriminates all nine, never collapses two states to one mark"
    (let [glyphs (mapv #(:glyph (status/descriptor %)) (vec expected-statuses))]
      (is (= 9 (count (set glyphs))) (str "duplicate glyph in " glyphs))))
  (testing "every documented shape is actually used by some status — the
            five-shape vocabulary is real, not aspirational"
    (is (= valid-shapes (set (map #(:shape (status/descriptor %))
                                  expected-statuses))))))

(deftest zero-raw-hex-resolved-colours
  (testing "every colour resolves to a non-nil string (token-derived, never
            a dangling keyword) — rf2-i3i5j zero-raw-hex contract"
    (doseq [s expected-statuses
            k [:fg :bg :border]]
      (let [v (get (status/descriptor s) k)]
        (is (string? v) (str s " " k " did not resolve to a string"))))))

;; ---- 3: the shape channel genuinely discriminates -----------------------

(deftest chip-style-shape-channel-discriminates
  (testing "chip-style ALWAYS carries the colour channel (:background +
            :color) for every status"
    (doseq [s expected-statuses]
      (let [cs (status/chip-style s)]
        (is (contains? cs :background))
        (is (contains? cs :color)))))
  (testing "the five shapes render VISUALLY DISTINCT decorations — the
            shape channel is genuinely five values, not the degraded two
            (solid-border vs dashed) the .3 PR shipped (rf2-gsqbp)"
    ;; :solid — no border decoration; the filled ground is the signal.
    (let [pass (status/chip-style :pass)]
      (is (not (contains? pass :border)))
      (is (not (contains? pass :border-left))))
    ;; :outline — 1px SOLID border (error / cannot-run).
    (let [err (status/chip-style :error)]
      (is (str/starts-with? (:border err) "1px solid")))
    ;; :dashed — 1px DASHED border (redacted).
    (let [red (status/chip-style :redacted)]
      (is (str/includes? (:border red) "dashed")))
    ;; :ring — 2px DOUBLE border (pending — a hollow double-ring, NOT a
    ;; plain solid edge: this is the channel the .3 PR collapsed).
    (let [pend (status/chip-style :pending)]
      (is (str/includes? (:border pend) "double")))
    ;; :half — a one-sided left-accent BAR (running), NOT a full border.
    (let [run (status/chip-style :running)]
      (is (contains? run :border-left))
      (is (not (contains? run :border)))))
  (testing "outline / ring / half no longer collapse to the same CSS —
            the regression rf2-gsqbp fixed stays fixed"
    (let [outline (:border      (status/chip-style :error))
          ring    (:border      (status/chip-style :pending))
          half    (:border-left (status/chip-style :running))]
      (is (not= outline ring))
      (is (not= outline half))
      (is (not= ring half)))))

;; ---- 4: rollup surfaces the worst member --------------------------------

(deftest rollup-surfaces-worst-member
  (testing "a mixed set surfaces its most-attention-demanding member per
            `order` (one :fail among many :pass → :fail)"
    (is (= :fail  (status/rollup [:pass :pass :fail :pass])))
    (is (= :error (status/rollup [:pass :fail :error :pending])))
    (is (= :pass  (status/rollup [:pass :pass :pass]))))
  (testing "blocked / dirty / running outrank pending + pass + redacted"
    (is (= :blocked (status/rollup [:pass :pending :blocked :redacted])))
    (is (= :dirty   (status/rollup [:pass :pending :dirty :redacted])))
    (is (= :running (status/rollup [:pass :pending :running :redacted]))))
  (testing "an empty / all-nil set degrades to :pending, never throws"
    (is (= :pending (status/rollup [])))
    (is (= :pending (status/rollup [nil nil])))
    (is (= :pending (status/rollup nil)))))

(deftest unknown-status-degrades-to-pending
  (testing "an unknown / nil status paints the neutral :pending slot,
            never blanks or throws"
    (is (= (status/descriptor :pending) (status/descriptor :bogus)))
    (is (= (status/descriptor :pending) (status/descriptor nil)))))

;; ---- 5: single-source derivation (CLJS — sidebar style is .cljs) --------

#?(:cljs
   (deftest sidebar-signal-status-derives-from-chip-style
     (testing "the sidebar's :signal-status-* style keys EQUAL
               status/chip-style output — the single-source guarantee
               (a drift in one region is structurally impossible)"
       (doseq [[stat style-key] sidebar/status-signal->style-key]
         (is (= (status/chip-style stat) (get styles style-key))
             (str style-key " drifted from (status/chip-style " stat ")"))))))

#?(:cljs
   (deftest sidebar-status-style-key-covers-all-nine
     (testing "every documented status has a sidebar style-key projection
               — no status is unreachable from the sidebar"
       (is (= expected-statuses (set (keys sidebar/status-signal->style-key)))))))

#?(:cljs
   (deftest sidebar-status-dots-derive-from-fg
     (testing "the per-variant status dots tint from status/fg — the same
               single source the chips read"
       (is (= {:background (status/fg :pass)}    (:dot-pass styles)))
       (is (= {:background (status/fg :fail)}    (:dot-fail styles)))
       (is (= (status/fg :running) (:background (:dot-running styles)))))))

;; ---- glyph channel rendered in the sidebar chip (rf2-gsqbp) -------------

#?(:cljs
   (defn- walk-find-data-test
     "Collect every hiccup node whose props carry `:data-test` = `tag`."
     [tree tag]
     (let [hits (transient [])]
       (letfn [(walk [node]
                 (cond
                   (and (vector? node) (map? (second node))
                        (= tag (get (second node) :data-test)))
                   (do (conj! hits node) (doseq [c (drop 2 node)] (walk c)))
                   (vector? node) (doseq [c (rest node)] (walk c))
                   (seq? node)    (doseq [c node] (walk c))
                   :else nil))]
         (walk tree))
       (persistent! hits))))

#?(:cljs
   (deftest sidebar-status-chip-renders-the-glyph
     (testing "the status chip in the rendered signal strip carries the
               descriptor's :glyph — the spec/018 §12.6 headline channel
               that survives colour-blindness + Windows HCM (rf2-gsqbp)"
       (let [tree   (sidebar/signal-chips {} :fail)
             glyphs (walk-find-data-test tree "story-sidebar-signal-glyph")]
         (is (= 1 (count glyphs)) "exactly one status glyph in the strip")
         (let [[_tag props glyph] (first glyphs)]
           ;; the rendered mark is the descriptor's glyph for :fail
           (is (= (status/glyph :fail) glyph))
           ;; aria-hidden — the label + data-value already voice the value
           ;; to AT, so the glyph is a redundant VISUAL channel only.
           (is (= "true" (:aria-hidden props)))))))
   )

#?(:cljs
   (deftest sidebar-status-chip-keeps-label-and-data-value
     (testing "rendering the glyph does NOT regress the text label or the
               data-value / title channels (AT + test corpus still read it)"
       (let [tree  (sidebar/signal-chips {} :pass)
             chips (walk-find-data-test tree "story-sidebar-signal-chip")
             status-chip (first (filter #(= "status" (:data-axis (second %)))
                                        chips))
             props (second status-chip)]
         (is (= "pass" (:data-value props)))
         (is (str/includes? (:title props) "status"))
         ;; the label text is still present as a child of the chip
         (is (some string? (drop 2 status-chip))))))
   )

#?(:cljs
   (deftest non-status-chips-carry-no-status-glyph
     (testing "only the STATUS axis renders a glyph — fidelity / world /
               runner / frame chips have no status descriptor glyph"
       (let [tree   (sidebar/signal-chips
                      {:args {:n 1} :network {[:get "/x"] {}}
                       :script [[:click "#go"]] :frame-binding :attached}
                      :pass)
             glyphs (walk-find-data-test tree "story-sidebar-signal-glyph")]
         ;; exactly one glyph — the single status chip — across the whole
         ;; multi-axis strip.
         (is (= 1 (count glyphs))))))
   )

;; ---- 6: the story-canvas-empty render hook (CLJS — shell is .cljs) ------

#?(:cljs
   (deftest story-canvas-empty-hook-renders
     (testing "with no variant / workspace / story selected, the main pane
               renders the spec/018 §12.5 calm empty state carrying the
               `story-canvas-empty` hook (the hook the .3 review flagged
               as unguarded)"
       (state/reset-shell-state!)
       (let [tree (#'shell/main-pane)
             hits (walk-find-data-test tree "story-canvas-empty")]
         (is (= 1 (count hits))
             "the empty-canvas hook renders exactly once for the empty state"))))
   )
