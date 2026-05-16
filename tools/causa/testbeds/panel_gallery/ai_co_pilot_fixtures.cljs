(ns panel-gallery.ai-co-pilot-fixtures
  "Pure fixture builders for the Causa AI Co-Pilot panel gallery
  (rf2-8r20i, Phase 2).

  The AI Co-Pilot panel reads its rows from seven `:rf.causa/copilot-*`
  subs:

    - `:rf.causa/copilot-open?`            — boolean
    - `:rf.causa/copilot-conversation`     — vec of turn maps
    - `:rf.causa/copilot-provider`         — kw (`:claude` / `:openai` / ...)
    - `:rf.causa/copilot-cue-active?`      — boolean
    - `:rf.causa/copilot-redaction-settings` — map
    - `:rf.causa/copilot-streaming-token-count` — int
    - `:rf.causa/copilot-input-text`       — string

  Each variant seeds via a single gallery-local seed event that
  `assoc`s every slot in one go — Story's `:rf.story/*` runtime slots
  survive untouched per `tools/story/spec/002-Runtime.md` §Coexistence
  with hosting application state.

  ## Turn shape

  Per `ai-co-pilot-conversation-model` each turn is:

      {:role       :question | :answer
       :text       <string>
       :streaming? <bool>}

  Answers may contain `{:rf.copilot/chip <key> <value>}` fragments
  which the chip parser projects into citation chips at render
  time. Chip keys are `:dispatch-id` / `:path` / `:epoch-number` /
  `:handler-id`.")

;; ---- turn builders ------------------------------------------------------

(defn q
  "Build a question turn."
  [text]
  {:role :question :text text :streaming? false})

(defn a
  "Build a settled (non-streaming) answer turn."
  [text]
  {:role :answer :text text :streaming? false})

(defn a-streaming
  "Build a streaming answer turn — the panel renders a cursor caret
  at the tail."
  [text]
  {:role :answer :text text :streaming? true})

;; ---- conversation builders ---------------------------------------------

(defn empty-convo
  "Empty conversation. Panel renders the 'Ask Causa anything' empty-
  state with the sample-prompts ladder."
  []
  [])

(defn typing-convo
  "Empty conversation but the input has user-typed-but-not-submitted
  text. The panel surfaces the input value verbatim — no slash
  popover (text doesn't start with `/`)."
  []
  [])

(defn one-turn-convo
  "One question + one settled answer. The simplest non-empty
  conversation."
  []
  [(q "Why did :checkout/submit fire?")
   (a "Because the user clicked the Submit button — :checkout/submit was dispatched from cart/list:42 with payload {:order-id 1042}.")])

(defn mid-completion-convo
  "Question + streaming answer with content. The streaming cursor
  caret renders at the tail."
  []
  [(q "What changed in the last 5 epochs?")
   (a-streaming "Epoch 42 added :cart/items, epoch 43 modified :user/role, epoch 44 ")])

(defn error-convo
  "Question + answer reporting an LLM error. Surfaces the literal
  error string the provider returned."
  []
  [(q "Why is :cart/total returning 0?")
   (a "[error] Provider stream failed: HTTP 503 — upstream timeout. The conversation is preserved for retry.")])

(defn large-context-convo
  "Six-turn conversation exercising vertical scroll behaviour. The
  panel's conversation column is scrollable; under load the rail
  form clips to a fixed height."
  []
  [(q "Why did :auth/sign-in fire?")
   (a "Form submit → :auth/sign-in cascade with parent dispatch-id 100. The handler called http/login.")
   (q "Show the resulting app-db changes.")
   (a "App-db diff: :auth.status :anon → :authenticated, :session.user-id nil → 7, :session.expires-at 0 → 1700000000000.")
   (q "Any errors in that cascade?")
   (a-streaming "Yes — :rf.error/handler-threw at auth/load-permissions:18 (ClassCastException). Recovery: rollback. ")])

(defn redacted-convo
  "Conversation showing the panel's behaviour when the answer's
  embedded values arrive under `:rf/redacted` markers (per Spec 009
  §Privacy). The text is rendered verbatim — the panel does not
  re-redact; it surfaces whatever the LLM returned."
  []
  [(q "What were the auth/sign-in args?")
   (a "Per the panel's redaction-default-on policy, the args were forwarded as: [:auth/sign-in <redacted> <redacted>]. Unmask via the panel's redaction settings to inspect.")])

(defn with-chips-convo
  "Answer with structured citation chips. Three chip kinds in one
  answer surface the chip-row rendering — `:dispatch-id`, `:path`,
  and `:epoch-number`."
  []
  [(q "What changed?")
   (a (str "The cascade rooted at "
           "{:rf.copilot/chip :dispatch-id 42}"
           " mutated "
           "{:rf.copilot/chip :path [:cart :items]}"
           " between epochs "
           "{:rf.copilot/chip :epoch-number 7}"
           " and the current one."))])

(defn slash-typing-convo
  "Empty conversation; the input contains a partial slash command —
  the slash popover surfaces matching suggestions."
  []
  [])

(defn provider-cycle-convo
  "Two settled turns; the panel header surfaces the active provider
  chip ('claude' by default; the variant overrides to :openai to
  exercise the picker)."
  []
  [(q "Hello.")
   (a "Hi — I'm Causa's co-pilot. Ask anything about this runtime.")])

(defn multi-question-convo
  "Three back-to-back questions without intervening answers — exercises
  the panel's question-stacking behaviour (the user fires fast and
  the stream is rate-limited)."
  []
  [(q "Why did :counter/inc fire?")
   (q "What did :counter/inc handle?")
   (q "What rendered after :counter/inc?")])

(defn long-streaming-convo
  "One question + a long streaming answer. The token-count badge
  surfaces a non-zero value (the seed event sets the slot too)."
  []
  [(q "Walk me through the cascade.")
   (a-streaming (str "OK — the root :checkout/submit event was dispatched from cart/list at line 42. "
                     "The handler validated the payload (one fx invocation), persisted to localStorage "
                     "(a second fx invocation), then dispatched :checkout/charge as a child. The child "
                     "cascade involved three subs (cart/items, auth/user, ui/theme) and re-rendered "
                     "five views (app/root, nav/bar, cart/list, ..."))])
