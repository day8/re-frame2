(ns re-frame.story.fx-stubs
  "Built-in `force-fx-stub` decorator. Per /spec/007-Stories.md §Effect mocking +
  `004-Assertions.md` §Canonical assertion vocabulary + `005-SOTA-Features.md` (the 'MSW-shaped effect
  mocking' surface).

  ## Authoring shape

      (story/reg-variant :story.auth/login-pending
        {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]]
         :script     [[:dispatch [:auth/login]]
                      [:dispatch [:rf.assert/effect-emitted :http]]
                      [:dispatch [:rf.assert/path-equals [:auth :status] :pending]]]})

  ## Authoring shape — value-form

  `force-fx-stub` is a regular decorator registered under
  `:rf.story/force-fx-stub`. The decorator's ref-args carry
  `(:fx-id, :response)`; user variants reference it as

      [:rf.story/force-fx-stub <fx-id> <response>]

  ## Semantics

  At variant mount time the decorator stack's `:fx-override` slot
  classifies this decorator and `decorators/fx-overrides-map`
  synthesises a stub id of the form `:rf.story.fx-stub/<dec-id>`.
  The frames runtime then:

    1. Registers a `reg-fx` handler under the stub id. The handler records
       the original fx id, payload, and canned response in the per-frame
       stub-call log instead of performing the real effect.
    2. Stamps `{:fx-overrides {<fx-id> <stub-id>}}` onto the
       variant frame's config so re-frame's router redirects any effect
       emission under `<fx-id>` to the stub handler.

  The stub handler appends the original `:fx-id` to the per-frame stub-call
  log; `:rf.assert/effect-emitted` observes that the fx was emitted *as if
  it had run* by reading that log via `observed-fx-ids` (the SSOT for
  stub-redirected fx-ids), unioned with the epoch tape's effects.

  ## Why a registered decorator and not a magic builtin

  Per /spec/007-Stories.md §Effect mocking the framework hooks are
  `:fx-overrides` (registered against `make-frame`); `force-fx-stub`
  is a *library* convenience over the framework hook. The same shape
  authors can use for their own decorators: register a `:fx-override`
  decorator with `:fx-id` + `:response`, reference it by id in the
  variant body. Story's built-in is just a particularly common shape.

  ## Decorator vs. ref-args

  The decorator's `:body` shape stays simple — it owns nothing but
  the metadata `{:kind :fx-override :fx-id <ignored> :response <ignored>}`.
  The actual fx-id + response are supplied at the *reference* site
  via the ref-args:

      [:rf.story/force-fx-stub :http {:status :pending}]
                              └──┬──┘ └──────┬──────┘
                                fx-id      response

  The decorator resolver rewrites the ref-args into a
  per-reference decorator body so `decorators/fx-overrides-map` sees
  `{:fx-id :http :response {:status :pending}}`. This keeps the
  decorator registration single-shot while allowing per-reference
  configuration."
  (:require [re-frame.core         :as rf]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.registrar :as rf.story.registrar]))

;; ---------------------------------------------------------------------------
;; The decorator id
;; ---------------------------------------------------------------------------

(def force-fx-stub-id
  "Stable id for the built-in `force-fx-stub` decorator. Registered at
  Story boot via `install-canonical-fx-stubs!`."
  :rf.story/force-fx-stub)

;; ---------------------------------------------------------------------------
;; The built-in decorator body
;;
;; The decorator's body is the marker `:kind :fx-override` — the actual
;; fx-id + response live in the ref-args. The decorator resolver reads
;; them from the ref and stamps them
;; onto a per-reference decorator-body clone before `decorators/
;; fx-overrides-map` runs.
;; ---------------------------------------------------------------------------

(def force-fx-stub-body
  "The `:rf.story/force-fx-stub` decorator's registered body. The
  ref-args drive the fx-id + response; the body is a marker that the
  decorator resolver expands.

  Per /spec/007-Stories.md §Effect mocking the user-visible shape is
  `[force-fx-stub :http {:status :pending}]`; the decorator registry
  treats `force-fx-stub` as the id."
  {:doc       "Built-in: stub an fx for the lifetime of the variant's frame."
   :kind      :fx-override
   :ref-args? true       ;; marker — the decorator resolver reads ref-args
   })

(defn install-canonical-fx-stubs!
  "Register `:rf.story/force-fx-stub` against Story's decorator
  registrar. Idempotent. Per /spec/007-Stories.md §Effect mocking this is the
  v1 MSW-shaped surface.

  Called from `re-frame.story/install-canonical-vocabulary!` at boot."
  []
  (when rf.story.config/enabled?
    (rf.story.registrar/reg-decorator* force-fx-stub-id force-fx-stub-body))
  nil)

;; ---------------------------------------------------------------------------
;; ref-args adapter — expand `[id fx-id response]` into a per-call body
;; with `{:fx-id fx-id :response response}`
;;
;; The decorators module's `resolve-ref` reads the registered body
;; verbatim; for `force-fx-stub` the *registered* body is just a marker,
;; so we expand the ref-args into a synthesized body that the rest of
;; `fx-overrides-map` consumes naturally.
;; ---------------------------------------------------------------------------

(defn expand-ref-args
  "Given a `[:rf.story/force-fx-stub fx-id response]` ref, return a
  per-reference body map `{:kind :fx-override :fx-id <fx-id>
  :response <response>}`. Returns nil for refs that aren't
  force-fx-stub.

  Used by `re-frame.story.decorators` to expand the ref before
  classification. The expansion is pure — no side effects."
  [ref]
  (when (and (sequential? ref)
             (= force-fx-stub-id (first ref)))
    (let [[_ fx-id response] ref]
      {:kind     :fx-override
       :fx-id    fx-id
       :response response})))

;; ---------------------------------------------------------------------------
;; Stub-event log — read by assertion handlers
;; ---------------------------------------------------------------------------

(defn observed-fx-ids
  "Return the set of fx-ids that the variant's stub fx-handlers
  observed. Reads the per-frame stub-call log from
  `re-frame.story.frames/stub-call-log-for`. Each entry carries the
  original `:fx-id`; we set-ify."
  [variant-id]
  (let [entries (try
                  (rf.story.frames/stub-call-log-for variant-id)
                  (catch #?(:clj Throwable :cljs :default) _ []))]
    (set (keep :fx-id entries))))

;; ---------------------------------------------------------------------------
;; Stub-redirected fx-ids are owned by the stub-call log
;;
;; `rf.story.frames/ensure-stub-event!` registers an fx handler under
;; `:rf.story.fx-stub/<decorator-id>` that appends to the per-frame
;; stub-call log. `:rf.assert/effect-emitted` projects from the epoch tape,
;; but a STUBBED fx lands on the tape under its REWRITTEN stub id, not its
;; original id — so the assertion's emitted-fx SSOT unions the tape effects
;; with `observed-fx-ids` (the stub-call log read via the
;; `:stub-observed-fx-ids` late-bind hook), the authoritative record of
;; which ORIGINAL fx-ids were redirected.
;;
;; `observed-fx-ids` reads that log directly; no parallel trace accumulator
;; mirrors this fact.
;; ---------------------------------------------------------------------------
