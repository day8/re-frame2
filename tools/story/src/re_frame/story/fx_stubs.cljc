(ns re-frame.story.fx-stubs
  "Built-in `force-fx-stub` decorator. Per spec/007 §Effect mocking +
  IMPL-SPEC §3.5 (Phase-2 §5.1 #6) + §13.2 (the 'MSW-shaped effect
  mocking' surface).

  ## Authoring shape

      (story/reg-variant :story.auth/login-pending
        {:decorators [[:rf.story/force-fx-stub :http {:status :pending}]]
         :play       [[:auth/login]
                      [:rf.assert/effect-emitted :http]
                      [:rf.assert/path-equals [:auth :status] :pending]]})

  ## Authoring shape — value-form

  `force-fx-stub` is a regular decorator registered under
  `:rf.story/force-fx-stub`. The decorator's ref-args carry
  `(:fx-id, :response)`; user variants reference it as

      [:rf.story/force-fx-stub <fx-id> <response>]

  ## Semantics

  At variant mount time the decorator stack's `:fx-override` slot
  classifies this decorator and `decorators/fx-overrides-map`
  synthesises a stub-event id of the form `:rf.story.fx-stub/<dec-id>`.
  The Stage 3 frames runtime then:

    1. Registers a `reg-event-fx` under the stub-event id (returning
       `{:db (-> db (update :rf.story.fx-stub/log conj ...))}`).
    2. Stamps `{:fx-overrides {<fx-id> <stub-event-id>}}` onto the
       variant frame's config so re-frame's router redirects any
       dispatch of `<fx-id>` to the stub event.

  Stage 5 adds: per-frame trace-bus accumulator updates so
  `:rf.assert/effect-emitted` can observe that the fx was emitted
  *as if it had run*. The `force-fx-stub` decorator's stub event also
  appends the fx-id to the frame's emitted-fx accumulator (see
  `re-frame.story.assertions/record-emitted-fx!`).

  ## Why a registered decorator and not a magic builtin

  Per spec/007 §Effect mocking the framework hooks are
  `:fx-overrides` (registered against `reg-frame`); `force-fx-stub`
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

  Stage 5 adds a small adapter that rewrites the ref-args into a
  per-reference decorator body so `decorators/fx-overrides-map` sees
  `{:fx-id :http :response {:status :pending}}`. This keeps the
  decorator registration single-shot while allowing per-reference
  configuration."
  (:require [re-frame.core         :as rf]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.config :as config]
            [re-frame.story.registrar :as registrar]))

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
;; fx-id + response live in the ref-args. The Stage 5 fx-override-map
;; adapter `resolve-ref-args` reads them from the ref and stamps them
;; onto a per-reference decorator-body clone before `decorators/
;; fx-overrides-map` runs.
;; ---------------------------------------------------------------------------

(def force-fx-stub-body
  "The `:rf.story/force-fx-stub` decorator's registered body. The
  ref-args drive the fx-id + response; the body is a marker that
  Stage 5's `resolve-ref-args` adapter expands.

  Per spec/007 §Effect mocking the user-visible shape is
  `[force-fx-stub :http {:status :pending}]`; the decorator registry
  treats `force-fx-stub` as the id."
  {:doc       "Built-in: stub an fx for the lifetime of the variant's frame."
   :kind      :fx-override
   :ref-args? true       ;; marker — Stage 5's adapter reads ref-args
   })

(defn install-canonical-fx-stubs!
  "Register `:rf.story/force-fx-stub` against Story's decorator
  registrar. Idempotent. Per spec/007 §Effect mocking this is the
  v1 MSW-shaped surface.

  Stage 5 (rf2-h8et). Called from `re-frame.story/install-canonical-
  vocabulary!` at boot."
  []
  (when config/enabled?
    (registrar/reg-decorator* force-fx-stub-id force-fx-stub-body))
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
  (let [resolve-fn
        (try
          #?(:clj  (requiring-resolve 're-frame.story.frames/stub-call-log-for)
             :cljs (some-> (find-ns 're-frame.story.frames)
                           (ns-resolve 'stub-call-log-for)))
          (catch #?(:clj Throwable :cljs :default) _ nil))
        entries (if resolve-fn (resolve-fn variant-id) [])]
    (set (keep :fx-id entries))))

;; ---------------------------------------------------------------------------
;; Wire the stub-event log into the assertion accumulator
;;
;; The Stage 3 `frames/ensure-stub-event!` registers an event-fx under
;; `:rf.story.fx-stub/<decorator-id>` that appends to the log. Stage 5
;; needs that event to ALSO record into the assertion
;; emitted-fx-accumulator so `:rf.assert/effect-emitted` works.
;;
;; Rather than re-register a different event shape, we expose a
;; `tap-stub-event!` helper the frames runtime can call from inside
;; the stub event's :db handler. The actual call site is in
;; `re-frame.story.frames/ensure-stub-event!` (Stage 3) — Stage 5
;; updates that function to call `tap-stub-event!`.
;; ---------------------------------------------------------------------------

(defn tap-stub-event!
  "Update the assertion module's `emitted-fx-accumulator` to record
  that `fx-id` fired against `frame-id`. The frames runtime's
  `ensure-stub-event!` calls this when the stub event handles a
  redirected fx call."
  [frame-id fx-id]
  (assertions/record-emitted-fx! frame-id fx-id)
  nil)
