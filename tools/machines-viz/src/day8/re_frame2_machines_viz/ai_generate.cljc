(ns day8.re-frame2-machines-viz.ai-generate
  "AI-generate-a-machine surface (rf2-1bncf · v1.1).

  Stately Studio 2026 ships AI-generation: given a description,
  generate a machine. This namespace hosts the re-frame2 equivalent:
  a pure library fn that takes a natural-language prompt and returns
  a normalised re-frame2 machine spec (the same shape `reg-machine`
  consumes and `(rf/machine-meta id)` returns).

  ## Two-layer design

  The implementation separates two concerns:

  1. **The prompt-resolver seam (`:resolver`).** A pluggable fn the
     caller injects: `(fn [prompt-string] llm-response-string)`. This
     fn is the I/O boundary — production callers wire in an
     anthropic-API / OpenAI-API / local-LLM bridge here; tests
     inject a stub returning canned EDN.

     The resolver receives the full system+user prompt as a single
     string and returns the LLM's response as a string. The
     namespace knows nothing about HTTP, secrets, streaming, or
     rate-limits — those live in the caller's resolver.

  2. **The parse-and-validate step (this ns).** Walks the resolver's
     string output, extracts the first EDN form (the LLM's response
     may include prose before/after a fenced code block, which
     authors and IDEs treat as commentary), and validates the
     extracted spec against the same minimum shape `spec->scxml`
     requires: `:initial` + non-empty `:states` (or `:type :parallel`
     + non-empty `:regions`).

  Round-trip is informational, not contractual: the LLM's
  generations are non-deterministic by design. Callers wanting
  determinism inject a deterministic resolver (e.g. a stub mapped
  from `prompt` → `spec`).

  ## Reserved namespaces

  Generated machines use re-frame2's normal id conventions — feature-
  prefixed keywords (`:auth/idle`, `:cart/loading`), hyphenated
  bare names (`:idle`, `:loading-failed`). The system prompt asks the
  LLM to follow these conventions; the parser doesn't enforce them
  (an LLM that generates `:loadingFailed` produces a working spec
  that the caller can ergonomic-cleanup or accept as-is).

  ## Public API

  - `(generate-machine prompt opts)` — Main entry point. Takes a
    natural-language prompt and an opts map. Returns the parsed +
    validated machine spec.
  - `system-prompt` — The full system prompt as a string. Exposed so
    callers can compose it into multi-turn chats, sandbox it for
    quality testing, or audit it before shipping.

  Per [`API.md`](../../spec/API.md) §AI-generate-a-machine."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            ;; rf2-egupfk — the SHARED definition-shape validators + value-free
            ;; summary (`valid-definition?` / `parallel-definition?` /
            ;; `definition-summary`) + the EP-0029 desugar seam, so this emitter
            ;; accepts EXACTLY the shapes the Mermaid + SCXML emitters do.
            [day8.re-frame2-machines-viz.grammar :as g]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

;; ---------------------------------------------------------------------------
;; Value-free error diagnostics (rf2-8nzxib)

(defn- response-summary
  "EP-0015 / Spec 015 §exception-path residual — a value-FREE diagnostic
  for an LLM resolver `response` (or the fence-stripped form). The raw
  string can carry prompt artefacts, user text, or LLM-returned secrets,
  and projection cannot walk `ex-data` after the fact, so the thrown
  error reports only the TYPE + character length — never the content."
  [s]
  (if (string? s)
    {:type :string :length (count s)}
    {:type (cond (nil? s) :nil (keyword? s) :keyword (map? s) :map
                 (vector? s) :vector (sequential? s) :seq :else :value)}))

;; rf2-egupfk — the parsed-spec summary is the SHARED
;; `grammar/definition-summary` (value-free structural facts only), so the AI
;; emitter's invalid-spec diagnostic matches Mermaid + SCXML. Stashed under this
;; surface's `:spec-summary` ex-data key.

;; ---------------------------------------------------------------------------
;; System prompt

(def system-prompt
  "The canonical system prompt this namespace passes to the resolver.

  Public Var so callers can audit the wording, compose multi-turn
  chats around it, or fork it. Changes to this string are part of
  the public contract — bumping the prompt's behaviour expectations
  (\"always include `:final?` states for terminal outcomes\";
  \"mark FAILURE terminals `:error? true`\") is a semver-relevant
  change."
  (str/join "\n"
    ["You are an assistant that generates re-frame2 state-machine specs."
     ""
     "A re-frame2 machine spec is a Clojure EDN map of the shape:"
     ""
     "  {:initial :idle"
     "   :states  {:idle    {:on {:start :loading}}"
     "             :loading {:on {:ok :success :err :failed}}"
     "             :success {:final? true}"
     "             :failed  {:final? true :error? true}}}"
     ""
     "Conventions:"
     ""
     "- State ids are keywords. Use bare keywords (:idle) for simple"
     "  machines or namespaced keywords (:auth/idle, :cart/loading)"
     "  when the machine belongs to a feature subdomain."
     "- Event ids are keywords (:start, :submit, :rf/load)."
     "- :initial names the starting state."
     "- :states is a map of state-id → state-node. A state-node is a"
     "  map with optional :on (event-id → transition), :final? true,"
     "  :initial (for compound states), and :states (a child map)."
     "- A transition value may be a target keyword (:loading) or a"
     "  map with :target + optional :guard (:auth-ok?) and :action."
     "- :final? true marks a terminal state — the machine has finished."
     "- :error? true (only on a :final? terminal) marks a FAILURE"
     "  terminal. A non-error :final? terminal completes :status :ok"
     "  and routes a spawning parent's :on-done; an :error? terminal"
     "  completes :status :error and routes the parent's :on-error"
     "  instead. So if an outcome means the machine FAILED (a load"
     "  error, an auth rejection, a payment decline), give that"
     "  terminal {:final? true :error? true}; a successful outcome"
     "  stays {:final? true}. Do NOT mark an ordinary in-flow error UI"
     "  state that the user can retry from (it keeps running, so it is"
     "  not :final? at all) — :error? is only for a terminal that ends"
     "  the machine in failure."
     "- For compound states, nest :states and provide a child"
     "  :initial."
     "- For parallel regions, use {:type :parallel :regions {...}}"
     "  where each region carries its own :initial + :states."
     ""
     "Return ONLY the EDN spec inside a fenced code block:"
     ""
     "```clojure"
     "{:initial :idle"
     " :states  {...}}"
     "```"
     ""
     "Do not include explanatory prose outside the fenced block."]))

;; ---------------------------------------------------------------------------
;; EDN extraction

(defn- strip-fences
  "If `s` contains a ```clojure / ``` fenced block, return the block's
  body. Otherwise return `s` unchanged. Strips leading / trailing
  whitespace either way.

  Tolerates the LLM emitting:

  - A bare EDN form with no fence.
  - A ``` (untagged) fence.
  - A ```clojure / ```edn / ```cljs fence.
  - Surrounding prose."
  [s]
  (let [m (re-find #"(?s)```(?:clojure|cljs|edn)?\s*\n(.*?)```" s)]
    (str/trim (if m (second m) s))))

(defn- parse-edn
  "Parse a string as one EDN form. Returns `[:ok form]` or
  `[:err reason]`."
  [s]
  (try
    [:ok (edn/read-string s)]
    (catch #?(:clj Exception :cljs :default) e
      [:err (ex-message e)])))

;; ---------------------------------------------------------------------------
;; Validation
;;
;; The minimum shape we enforce is what the rest of the substrate
;; (`spec->scxml`, `mermaid/emit`, the chart renderer) requires:
;; either {:initial K :states {non-empty}} or {:type :parallel
;; :regions {non-empty maps each with :initial + :states}}.
;;
;; rf2-egupfk — the shape check itself is the SHARED
;; `grammar/valid-definition?` so this emitter accepts EXACTLY what Mermaid +
;; SCXML do (keyword `:initial`, well-formed parallel regions). We desugar
;; (`grammar/desugar-grammar`) first, exactly as the other two emitters do, so
;; a `:timeout` / `:choice` authoring form validates on its lowered shape. We
;; keep only the AI-surface reason strings (embedded in the thrown message) and
;; return the ORIGINAL authored spec (the runtime desugars again at
;; registration, so the author's `:timeout` / `:choice` intent is preserved).

(defn- validate-definition
  "Return `[:ok]` if the (desugared) `definition` is a recognised machine
  shape, otherwise `[:err reason]` carrying an AI-surface reason string."
  [definition]
  (cond
    (not (map? definition))
    [:err "spec must be a map"]

    (g/parallel-definition? definition)
    (if (g/valid-definition? definition)
      [:ok]
      [:err "parallel spec must carry a non-empty :regions map; each region must carry a keyword :initial + non-empty :states"])

    (g/valid-definition? definition)
    [:ok]

    :else
    [:err "spec must carry a keyword :initial + non-empty :states (or :type :parallel + :regions)"]))

;; ---------------------------------------------------------------------------
;; Default resolver
;;
;; Refuses-by-default. Production callers MUST inject a resolver fn;
;; tests inject a stub. We do NOT ship a default that calls an
;; external LLM because (a) it'd make this namespace depend on
;; network I/O, (b) it'd require shipping API keys / secret-handling,
;; and (c) the principal use case is the IDE-side AI pair (Xray's
;; chat / re-frame2-pair-mcp) which already has an LLM seam.

(defn- default-resolver [_prompt]
  (error/throw-error!
    :ai-generate/no-resolver
    'machines-viz/generate-machine
    (str "AI machine generation: generate-machine was called without a "
         ":resolver opt. Inject a resolver fn (string -> string) that bridges "
         "to your LLM of choice; see the ns docstring for the contract.")
    {:recovery :inject-a-resolver-fn}))

;; ---------------------------------------------------------------------------
;; Public API

(defn build-prompt
  "Construct the full prompt string the resolver receives.

  Pure-data — composable from the public Vars `system-prompt` and
  the caller's `user-prompt`. Exposed so tests can assert prompt
  shape without invoking the resolver."
  [user-prompt]
  (str system-prompt
       "\n\n"
       "User request:\n"
       user-prompt))

(defn generate-machine
  "Generate a re-frame2 machine spec from a natural-language prompt.

  ## Arguments

  - `user-prompt` — a natural-language description of the desired
    machine, e.g. `\"a machine for a login flow with idle, loading,
    success, and error states\"`.
  - `opts` — a map. Recognised keys:
    - `:resolver` (required) — `(fn [prompt-string] llm-response-string)`.
      Bridges to the caller's LLM. Tests inject a stub.

  ## Returns

  The parsed + validated machine spec — the same shape `reg-machine`
  accepts and `(rf/machine-meta id)` returns.

  ## Throws

  The canonical thrown-error shape (Spec 009 §The thrown-error shape) —
  `:rf.error/id :ai-generate/<kw>` is the discriminator, `:reason` is the
  human sentence, the message leads with the sentence + the
  `[:ai-generate/<kw>]` token — for:

  - `:ai-generate/no-resolver` — `:resolver` was not provided.
  - `:ai-generate/parse-failed` — the resolver's output could not
    be parsed as EDN.
  - `:ai-generate/invalid-spec` — the parsed value was not a valid
    machine shape.

  ## Determinism

  The fn itself is deterministic given a deterministic resolver. LLM
  resolvers are not deterministic by default; for reproducible tests
  inject a stub resolver that returns canned EDN per known prompt."
  ([user-prompt] (generate-machine user-prompt {}))
  ([user-prompt {:keys [resolver] :as _opts}]
   (let [resolver-fn   (or resolver default-resolver)
         full-prompt   (build-prompt user-prompt)
         response      (resolver-fn full-prompt)
         _             (when-not (string? response)
                         (error/throw-error!
                           :ai-generate/parse-failed
                           'machines-viz/generate-machine
                           (str "AI machine generation: the resolver must return "
                                "a string (the LLM's EDN response); it returned a "
                                "non-string value.")
                           {:recovery :return-a-string-from-the-resolver
                            ;; rf2-8nzxib — value-FREE; never the raw response.
                            :extra    {:response-summary (response-summary response)}}))
         stripped      (strip-fences response)
         [stage spec-or-reason] (parse-edn stripped)]
     (cond
       (= :err stage)
       (error/throw-error!
         :ai-generate/parse-failed
         'machines-viz/generate-machine
         (str "AI machine generation: could not parse the resolver output as "
              "EDN (" spec-or-reason "). The resolver must return a single EDN "
              "machine-spec map, optionally inside a ```edn fenced block.")
         {:recovery :return-valid-edn-from-the-resolver
          ;; rf2-8nzxib — value-FREE; the raw response/stripped text can
          ;; carry prompt artefacts or LLM-returned secrets.
          :extra    {:response-summary (response-summary response)
                     :stripped-summary (response-summary stripped)}})

       :else
       ;; rf2-egupfk — validate the DESUGARED form (as Mermaid + SCXML do) but
       ;; return the ORIGINAL authored spec so `:timeout` / `:choice` intent
       ;; survives to `reg-machine`.
       (let [[stage2 reason] (validate-definition (g/desugar-grammar spec-or-reason))]
         (if (= :ok stage2)
           spec-or-reason
           (error/throw-error!
             :ai-generate/invalid-spec
             'machines-viz/generate-machine
             (str "AI machine generation: the resolver output parsed as EDN but "
                  "is not a valid machine spec (" reason "). Have the "
                  "resolver return a spec with a keyword :initial + :states (or "
                  ":type :parallel + :regions).")
             {:recovery :return-a-valid-machine-spec
              ;; rf2-8nzxib — value-FREE; the response + parsed spec can
              ;; embed LLM-returned secrets / runtime :data.
              :extra    {:response-summary (response-summary response)
                         :spec-summary     (g/definition-summary spec-or-reason)}})))))))
