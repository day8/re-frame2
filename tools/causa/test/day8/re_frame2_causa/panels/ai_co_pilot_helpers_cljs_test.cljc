(ns day8.re-frame2-causa.panels.ai-co-pilot-helpers-cljs-test
  "Pure-data tests for Causa's AI Co-Pilot rail panel helpers
  (Phase 5, rf2-rccf3).

  ## Why the `.cljc` + `_cljs_test` naming

  The file ends in `_cljs_test.cljc` so:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  Same dual-target pattern as
  `time_travel_helpers_cljs_test.cljc`.

  ## What's under test

  Each contract from `tools/causa/spec/009-AI-CoPilot.md` is asserted
  against the pure-data fns in `ai-co-pilot-helpers`. The view-side
  wiring is exercised in `ai_co_pilot_cljs_test.cljs` against the
  live Causa frame.

    1. **Slash command parsing** — the 8-entry catalogue per spec
       §Slash commands; head-token matching; rejection of unknown
       commands; arg splitting.
    2. **Slash popover matches** — partial-prefix matching for the
       dropdown.
    3. **Chip parsing** — the 4 supported chip kinds per spec §Chip
       types; literal-text fallback for malformed fragments; unknown
       chip-keys render literal (per spec §Why structured citations
       'malformed edn renders as the literal fragment').
    4. **Redaction filter** — event-vector args + app-db slice values
       redacted by default per spec §Redaction defaults; source-coord
       / handler-id / trace-metadata always pass through; opt-in
       toggles unmask.
    5. **Conversation shape** — append-question / start-answer /
       append-token / end-answer all preserve the per-turn invariants;
       no `:streaming?` corruption."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]))

;; ---- (1) slash command parsing ------------------------------------------

(deftest parse-slash-command-recognises-all-eight-commands
  (testing "every command listed in `slash-commands` parses out of a
            `/<cmd>` input"
    (doseq [{:keys [command]} h/slash-commands]
      (let [input  (str "/" (name command))
            parsed (h/parse-slash-command input)]
        (is (= command (:command parsed))
            (str "command " command " parses out of " input))))))

(deftest parse-slash-command-extracts-args
  (testing "args are whitespace-split into a vector"
    (is (= {:command :explain
            :args    ["epoch-42"]
            :raw     "/explain epoch-42"}
           (h/parse-slash-command "/explain epoch-42")))
    (is (= ["epoch-1" "epoch-2"]
           (:args (h/parse-slash-command "/diff epoch-1 epoch-2"))))
    (is (= []
           (:args (h/parse-slash-command "/clear"))))))

(deftest parse-slash-command-returns-nil-for-non-slash-input
  (testing "plain English questions are not slash commands"
    (is (nil? (h/parse-slash-command "why did this fire?")))
    (is (nil? (h/parse-slash-command "")))
    (is (nil? (h/parse-slash-command nil)))))

(deftest parse-slash-command-returns-nil-for-unknown-command
  (testing "head tokens outside the catalogue are rejected"
    (is (nil? (h/parse-slash-command "/notacommand")))
    (is (nil? (h/parse-slash-command "/foo bar")))))

(deftest parse-slash-command-tolerates-leading-whitespace
  (testing "an input with leading whitespace still parses (the chrome
            may pass a not-yet-trimmed string)"
    (is (= :clear (:command (h/parse-slash-command "   /clear"))))))

;; ---- (2) slash popover matches -----------------------------------------

(deftest popover-matches-empty-slash-returns-all
  (testing "`/` alone returns the full catalogue"
    (is (= (count h/slash-commands)
           (count (h/slash-popover-matches "/"))))))

(deftest popover-matches-prefix-filters
  (testing "`/wh` matches `/why` and `/whatif`"
    (let [matches (h/slash-popover-matches "/wh")
          cmds    (set (map :command matches))]
      (is (contains? cmds :why))
      (is (contains? cmds :whatif))
      (is (not (contains? cmds :explain))))))

(deftest popover-matches-nil-for-non-slash-input
  (testing "a plain-English input returns nil (the dropdown stays hidden)"
    (is (nil? (h/slash-popover-matches "why")))
    (is (nil? (h/slash-popover-matches "")))))

;; ---- (3) chip parsing ---------------------------------------------------

(deftest parse-streamed-answer-extracts-dispatch-id-chip
  (testing "a `:dispatch-id` chip is extracted from streamed text"
    (let [segments (h/parse-streamed-answer
                     "fired by {:rf.copilot/chip :dispatch-id 100} earlier.")]
      (is (= 3 (count segments)))
      (is (= {:kind :text :text "fired by "} (first segments)))
      (is (= :chip (:kind (nth segments 1))))
      (is (= :dispatch-id (:chip-key (nth segments 1))))
      (is (= 100 (:value (nth segments 1))))
      (is (= " earlier." (:text (nth segments 2)))))))

(deftest parse-streamed-answer-extracts-path-chip
  (testing "a `:path` chip with a vector value parses correctly"
    (let [segments (h/parse-streamed-answer
                     "the path {:rf.copilot/chip :path [:cart :items 3 :qty]}")
          chip     (first (filter #(= :chip (:kind %)) segments))]
      (is (= :path (:chip-key chip)))
      (is (= [:cart :items 3 :qty] (:value chip))))))

(deftest parse-streamed-answer-extracts-epoch-number-chip
  (testing "a `:epoch-number` chip parses correctly"
    (let [segments (h/parse-streamed-answer
                     "see {:rf.copilot/chip :epoch-number 47}.")
          chip     (first (filter #(= :chip (:kind %)) segments))]
      (is (= :epoch-number (:chip-key chip)))
      (is (= 47 (:value chip))))))

(deftest parse-streamed-answer-extracts-handler-id-chip
  (testing "a `:handler-id` chip with a keyword value parses correctly"
    (let [segments (h/parse-streamed-answer
                     "by {:rf.copilot/chip :handler-id :cart/finalise}")
          chip     (first (filter #(= :chip (:kind %)) segments))]
      (is (= :handler-id (:chip-key chip)))
      (is (= :cart/finalise (:value chip))))))

(deftest parse-streamed-answer-handles-no-chips
  (testing "plain prose returns a single :text segment"
    (let [segments (h/parse-streamed-answer
                     "Dispatched at events.cljs:213 by :cart/finalise.")]
      (is (= 1 (count segments)))
      (is (= :text (:kind (first segments)))))))

(deftest parse-streamed-answer-handles-multiple-chips
  (testing "a streamed answer with multiple chips returns the
            segments in order"
    (let [segments (h/parse-streamed-answer
                     (str "from {:rf.copilot/chip :dispatch-id 1} via "
                          "{:rf.copilot/chip :handler-id :foo} done."))
          chips    (filter #(= :chip (:kind %)) segments)]
      (is (= 2 (count chips)))
      (is (= [:dispatch-id :handler-id]
             (mapv :chip-key chips))))))

(deftest parse-streamed-answer-unknown-chip-key-renders-literal
  (testing "an unrecognised chip-key renders as literal text (per
            spec §Why structured citations 'malformed edn renders as
            the literal fragment')"
    (let [segments (h/parse-streamed-answer
                     "see {:rf.copilot/chip :bogus 42}.")
          chips    (filter #(= :chip (:kind %)) segments)]
      (is (empty? chips)
          "no chip is emitted for an unknown chip-key")
      (is (some #(re-find #"\{:rf.copilot/chip :bogus 42\}" (:text % ""))
                segments)
          "the literal raw fragment is preserved as text"))))

(deftest parse-streamed-answer-handles-malformed-fragment
  (testing "a `{:rf.copilot/chip` prefix without a closing `}` renders
            as literal text (defensive — a partial stream tail might
            land mid-fragment)"
    (let [segments (h/parse-streamed-answer
                     "tail {:rf.copilot/chip :dispatch-id 100")]
      (is (every? #(= :text (:kind %)) segments)
          "no chip is emitted when the fragment is unbalanced"))))

(deftest parse-streamed-answer-empty-input
  (testing "empty / non-string input is handled gracefully"
    (is (= [] (h/parse-streamed-answer "")))
    (is (= [] (h/parse-streamed-answer nil)))))

;; ---- (3a) resolve-chip --------------------------------------------------

(deftest resolve-chip-returns-glyph-and-target
  (testing "resolve-chip looks up glyph + target for each known chip-key"
    (let [resolved (h/resolve-chip {:chip-key :dispatch-id :value 100})]
      (is (= :rf.causa/select-dispatch-id (:target resolved)))
      (is (= "◆" (:glyph resolved)))
      (is (= 100 (:value resolved))))
    (is (= :rf.causa/select-epoch
           (:target (h/resolve-chip {:chip-key :epoch-number :value 47}))))
    (is (= :rf.causa.copilot/open-path
           (:target (h/resolve-chip {:chip-key :path :value [:a :b]}))))
    (is (= :rf.causa.copilot/open-handler
           (:target (h/resolve-chip {:chip-key :handler-id :value :foo}))))))

(deftest resolve-chip-returns-nil-for-unknown-key
  (testing "unrecognised chip-keys return nil (the view falls back to
            literal text)"
    (is (nil? (h/resolve-chip {:chip-key :wat :value 1})))))

;; ---- (4) redaction filter ----------------------------------------------

(deftest default-redaction-settings-are-privacy-by-default
  (testing "per spec §Redaction defaults — event-vector args + app-db
            slice values are redacted by default"
    (is (false? (:unmask-event-args h/default-redaction-settings)))
    (is (false? (:unmask-app-db h/default-redaction-settings)))))

(deftest redact-event-vector-args-by-default
  (testing "a trace event's event-vector args are stamped <redacted>
            under the default settings; the event-id is preserved"
    (let [ev        {:op-type :event
                     :operation :event/dispatched
                     :tags {:event [:user/login {:email "ada@example.com"}]
                            :dispatch-id 100}}
          redacted  (h/redact-trace-event h/default-redaction-settings ev)
          event-vec (get-in redacted [:tags :event])]
      (is (= :user/login (first event-vec))
          "event-id (head of the vector) is preserved")
      (is (= "<redacted>" (second event-vec))
          "args are stamped <redacted>")
      (is (= 100 (get-in redacted [:tags :dispatch-id]))
          "trace-metadata pass-through — :dispatch-id is not redacted"))))

(deftest unmask-event-args-toggle-passes-through
  (testing "with :unmask-event-args true the event-vector is unchanged"
    (let [ev       {:op-type :event
                    :tags {:event [:user/login {:email "ada@example.com"}]
                           :dispatch-id 100}}
          redacted (h/redact-trace-event {:unmask-event-args true} ev)]
      (is (= [:user/login {:email "ada@example.com"}]
             (get-in redacted [:tags :event]))))))

(deftest redact-app-db-by-default
  (testing "app-db leaf values are stamped <redacted>; keys + structure
            are preserved (per spec rationale — 'Source coords + handler
            IDs + trace metadata are sufficient for the canonical
            why-did-this-render questions')"
    (let [db       {:user {:email "ada@example.com"
                           :id    42}
                    :cart {:items [{:qty 3} {:qty 1}]}}
          redacted (h/redact-app-db h/default-redaction-settings db)]
      (is (contains? redacted :user) "structure preserved")
      (is (= "<redacted>" (get-in redacted [:user :email])))
      (is (= "<redacted>" (get-in redacted [:user :id])))
      (is (vector? (get-in redacted [:cart :items])) "vector kept")
      (is (= "<redacted>" (get-in redacted [:cart :items 0 :qty]))))))

(deftest unmask-app-db-toggle-passes-through
  (testing "with :unmask-app-db true the app-db is unchanged"
    (let [db       {:user {:id 42}}
          redacted (h/redact-app-db {:unmask-app-db true} db)]
      (is (= 42 (get-in redacted [:user :id]))))))

(deftest redact-payload-applies-both-filters
  (testing "redact-payload walks trace-events + app-db; other slots
            (source coords, handler ids, etc) pass through unchanged"
    (let [payload  {:trace-events [{:op-type :event
                                    :tags {:event [:user/login {:pw "x"}]
                                           :dispatch-id 100
                                           :handler-id  :user/login}}]
                    :app-db       {:user {:id 42}}
                    :source-coord "src/cart/events.cljs:213"
                    :question     "Why did this fire?"}
          redacted (h/redact-payload h/default-redaction-settings payload)
          ev       (first (:trace-events redacted))]
      (is (= "<redacted>" (get-in ev [:tags :event 1]))
          "event-vector args redacted")
      (is (= 100 (get-in ev [:tags :dispatch-id]))
          ":dispatch-id pass-through")
      (is (= :user/login (get-in ev [:tags :handler-id]))
          ":handler-id pass-through")
      (is (= "<redacted>" (get-in redacted [:app-db :user :id]))
          "app-db redacted")
      (is (= "src/cart/events.cljs:213" (:source-coord redacted))
          "source-coord pass-through")
      (is (= "Why did this fire?" (:question redacted))
          "question pass-through"))))

;; ---- (5) conversation shape ---------------------------------------------

(deftest empty-conversation-is-empty-vec
  (is (= [] (h/empty-conversation))))

(deftest append-question-adds-question-turn
  (testing "append-question appends a question turn with :role :question"
    (let [conv (h/append-question [] "Why?")]
      (is (= 1 (count conv)))
      (is (= :question (:role (first conv))))
      (is (= "Why?" (:text (first conv))))
      (is (false? (:streaming? (first conv)))))))

(deftest start-answer-adds-streaming-answer-turn
  (testing "start-answer appends an empty answer turn with :streaming? true"
    (let [conv (h/start-answer [{:role :question :text "Why?" :streaming? false}])]
      (is (= 2 (count conv)))
      (is (= :answer (:role (second conv))))
      (is (= "" (:text (second conv))))
      (is (true? (:streaming? (second conv)))))))

(deftest append-token-accretes-onto-streaming-answer
  (testing "append-token extends the trailing answer's :text"
    (let [conv (-> []
                   (h/append-question "Why?")
                   (h/start-answer)
                   (h/append-token "Be")
                   (h/append-token "cause"))]
      (is (= 2 (count conv)))
      (is (= "Because" (:text (second conv))))
      (is (true? (:streaming? (second conv)))))))

(deftest append-token-noop-when-trailing-turn-is-question
  (testing "append-token does nothing when there's no in-flight answer"
    (let [conv (-> []
                   (h/append-question "Why?")
                   (h/append-token "stray"))]
      (is (= 1 (count conv)))
      (is (= "Why?" (:text (first conv)))
          "question's text untouched by stray token"))))

(deftest append-token-noop-on-empty-conversation
  (testing "append-token over an empty conversation is a no-op"
    (is (= [] (h/append-token [] "stray")))))

(deftest end-answer-marks-trailing-not-streaming
  (testing "end-answer flips :streaming? false on the trailing answer"
    (let [conv (-> []
                   (h/append-question "Why?")
                   (h/start-answer)
                   (h/append-token "Because")
                   (h/end-answer))]
      (is (false? (:streaming? (second conv)))
          "answer turn is no longer streaming")
      (is (= "Because" (:text (second conv)))
          "text preserved"))))

(deftest end-answer-noop-when-trailing-turn-is-finalised
  (testing "end-answer is a no-op when the trailing turn is already
            non-streaming (defensive — a duplicate :stream-end event
            should not corrupt the buffer)"
    (let [conv (-> []
                   (h/append-question "Why?")
                   (h/start-answer)
                   (h/end-answer)
                   (h/end-answer))]
      (is (false? (:streaming? (second conv)))))))

;; ---- (6) chip-key set + glyph + target maps -----------------------------

(deftest chip-key-set-is-the-four-supported-kinds
  (testing "exactly the 4 chip-keys per spec §Chip types"
    (is (= #{:dispatch-id :path :epoch-number :handler-id}
           h/chip-key-set))))

(deftest chip-glyphs-cover-the-four-kinds
  (testing "each chip-key has a glyph"
    (doseq [k h/chip-key-set]
      (is (some? (get h/chip-glyphs k))
          (str k " has a glyph")))))

(deftest chip-targets-cover-the-four-kinds
  (testing "each chip-key has a panel-jump target"
    (doseq [k h/chip-key-set]
      (is (keyword? (get h/chip-targets k))
          (str k " has a target keyword")))))
