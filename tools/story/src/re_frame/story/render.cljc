(ns re-frame.story.render
  "`render-variant` — the workshop render verb that drives the SAME
  variant-plan the test runner consumes
  (`tools/story/spec/017-Testing-Story.md` §Args, controls, and
  `render-variant` + §Storytelling superset).

  ## One plan, two outputs

  spec/017 §Args: *`render-variant` renders the workshop view from the
  SAME plan the runner consumes. The live controls / visual-narrative
  experience and the test runner drive one plan, not two paths.* This ns
  is that single render entry point. It:

  1. normalizes `target` through `re-frame.story.plan/variant-plan` (the
     ONE compiler — a keyword target resolves a registered variant, a map
     target is an inline plan, exactly as `story/run`);
  2. layers the control-panel overrides (`:control-overrides`) on top of
     the plan's `[:world :effective-args]` to produce the POST-OVERRIDE
     effective args — the args that actually feed the rendered view;
  3. re-validates those post-override effective args against the plan's
     `[:world :view-args-schema]` (the §View-arg-schema contract), so a
     control that drives an invalid value STOPS render before the view is
     called (the `:invalid-args` status);
  4. resolves the active view id + the render inputs (`:sub-overrides`,
     `:decorators`, `:effective-args`) the host renderer needs;
  5. renders the active view through the late-bound `:render-host` hook
     (the host-render seam — CLJS-only; absent on the bare JVM, where the
     verb returns `:cannot-run` because nothing can render);
  6. returns the documented result shape.

  ## What it does NOT do

  `render-variant` prepares `:world` for rendering and renders the active
  view. It MUST NOT execute `:script` or terminal `:expect` — rendering a
  workshop view is not a test run (spec/017 §Args — *MUST NOT execute
  `:script` or terminal `:expect` unless a future option explicitly asks
  for a test run*). The plan's `:script` / `:expect` ride the result's
  `:plan` slot (so a caller can see them), but this verb never drives them.

  ## Result shape (spec/017 §Args — P1 render API)

      {:status         :rendered | :invalid-args | :cannot-run | :error
       :plan           normalized-plan
       :plan-hash      string
       :frame          frame-id
       :effective-args {...}          ; POST control-override
       :validation     optional-validation-result
       :rendered       host-render-result}

  - `:rendered`    — present on `:rendered` (the host render hook's return,
                     e.g. a hiccup tree / React element / a render handle).
  - `:validation`  — present whenever a view-arg schema was on file (the
                     `:ok` outcome on a successful render; the `:invalid`
                     outcome on `:invalid-args`).
  - `:cannot-run`  — carries the capability refusal (spec/017
                     §`:cannot-run`) when no host can render.
  - `:error`       — carries the thrown ex-data when the host render fn
                     throws.

  ## Plan-hash agreement (acceptance)

  The `:plan-hash` is `re-frame.story.fingerprint/plan-hash` over the SAME
  normalized plan, so a runner (`story/run`) and `render-variant` agree on
  the `:plan-hash` whenever the behaviour-relevant plan inputs match. A
  control override that changes `[:world :effective-args]` perturbs the
  hash exactly as it would on the run path — render and run cannot diverge
  on what the plan was.

  ## Purity / elision

  `prepare-render` is pure data → data (it threads the same explicit
  `:lookup` / `:view-lookup` / `:validator-fns` / `:sub-lookup` opts the
  plan compiler takes), so the render-prep core is JVM-runnable under
  `clojure -M:test` with NO host. The host render itself is the late-bound
  `:render-host` hook a CLJS host installs; the bare JVM has none, so
  `render-variant` returns `:cannot-run` there — never a silent empty
  render. Per the §6 elision contract, a production CLJS build with Story
  disabled renders nothing (`config/enabled?` is false)."
  (:require [re-frame.story.args        :as args]
            [re-frame.story.config      :as config]
            [re-frame.story.error       :as story-error]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.late-bind   :as late-bind]
            [re-frame.story.plan        :as plan]))

;; ===========================================================================
;; Render statuses
;; ===========================================================================

(def statuses
  "The four `render-variant` statuses (spec/017 §Args — P1 render API):

  - `:rendered`     — the active view was prepared + rendered;
  - `:invalid-args` — the POST-override effective args violate the view-arg
                      schema (render stopped BEFORE the view call);
  - `:cannot-run`   — no host can render (the bare JVM, or Story disabled);
  - `:error`        — the host render fn threw."
  #{:rendered :invalid-args :cannot-run :error})

;; ===========================================================================
;; Host-render hook
;; ===========================================================================

(def ^:private render-host-hook-key
  "The `re-frame.story.late-bind` hook key the CLJS host registers its
  render fn under. The fn signature is `(render-inputs) → host-result`,
  where `render-inputs` is the map `prepare-render` returns (the active
  view id, the post-override effective args, the resolved sub-overrides,
  the decorator stack, the frame id, and the plan). Absent on the bare JVM
  (no DOM / no substrate), so `render-variant` returns `:cannot-run`."
  :render-host)

(defn install-render-host!
  "Register the host render fn under the `:render-host` late-bind hook.
  The CLJS shell calls this once at boot (from
  `install-canonical-vocabulary!`); the fn takes the `prepare-render`
  output map and returns the host render result (a hiccup tree / React
  element / a mounted-handle). Idempotent (re-registration replaces)."
  [render-fn]
  (late-bind/set-fn! render-host-hook-key render-fn)
  nil)

(defn render-host-fn
  "Return the installed `:render-host` fn, or nil when no host registered
  one (the bare JVM render-prep-only path)."
  []
  (late-bind/get-fn render-host-hook-key))

;; ===========================================================================
;; Effective-args control overrides
;; ===========================================================================
;;
;; spec/017 §Args: `:effective-args` are *the args after control-panel
;; overrides*. The plan compiler records the PLAN-TIME effective args (the
;; resolved arg-map, BEFORE controls) at `[:world :effective-args]`;
;; `render-variant` layers the live control overrides on top to produce the
;; POST-override effective args that feed the rendered view. Both are the
;; same deep-merge precedence the run path uses (`args/deep-merge`,
;; later-wins), so a control override changes the effective args — and the
;; plan-hash — exactly as it would on a run.

(defn apply-control-overrides
  "Layer control-panel `overrides` on top of the plan's `plan-eff-args`
  (the resolved plan-time effective args). Pure data → data — deep-merge,
  later wins (`args/deep-merge`), matching the run-path arg precedence
  (`re-frame.story.args/resolve-args` — `variant-args < cell-overrides`).
  A nil/empty `overrides` returns the plan effective args unchanged."
  [plan-eff-args overrides]
  (if (seq overrides)
    (args/deep-merge (or plan-eff-args {}) overrides)
    (or plan-eff-args {})))

;; ===========================================================================
;; Sub-override re-substitution against the post-override effective args
;; ===========================================================================
;;
;; The plan compiler substitutes `[:arg key]` placeholders in the
;; `:sub-overrides` VALUES against the plan-time args. When a control
;; override changes an arg that a sub-override value references (e.g.
;; `{[:login/error] [:arg :message]}` driven by a `:message` control), the
;; render path must re-resolve the overrides against the POST-override
;; effective args so the live view reflects the control. We re-run the SAME
;; one-level substitution (`plan/substitute-args`) the plan compiler +
;; the canvas render path use, against the post-override args. The plan
;; already proved the overrides valid at compile time; re-substituting a
;; control value cannot introduce a missing-arg (the control supplies the
;; value), so this never throws under the render path.

(defn resolve-render-sub-overrides
  "Re-resolve the variant's `:sub-overrides` against the POST-override
  effective args, so a control-driven override value (e.g.
  `{[:login/error] [:arg :message]}` with a `:message` control) reflects
  the LIVE control rather than the plan-time arg. Pure data → data; nil
  when the plan carries no overrides.

  Re-substitutes the RAW (pre-`[:arg]`) overrides the plan kept at
  `[:render-raw :sub-overrides]` against `eff-args` — the SAME one-level
  `plan/substitute-args` the plan compiler + the canvas render path use.
  Falls back to the already-resolved `[:world :render :sub-overrides]` slot
  for a plan that carries no raw form (e.g. a hand-built inline plan that
  pre-resolved its overrides)."
  [plan eff-args]
  (if-let [raw (get-in plan [:render-raw :sub-overrides])]
    (plan/substitute-args raw (or eff-args {}) (atom []))
    (let [ovr (get-in plan [:world :render :sub-overrides])]
      (when (seq ovr)
        (plan/substitute-args ovr (or eff-args {}) (atom []))))))

;; ===========================================================================
;; Render preparation — the pure, JVM-testable core
;; ===========================================================================

(defn prepare-render
  "Prepare the render inputs for `target` from its normalized variant
  plan. Pure data → data — the JVM-testable core of `render-variant`.

  `opts` accepts the plan-compiler opts (`:lookup` / `:view-lookup` /
  `:validator-fns` / `:sub-lookup` / `:fragment-lookup` / `:check-lookup`)
  PLUS:

  - `:control-overrides` — the live control-panel arg overrides (a
    `{arg-key value}` map), deep-merged on top of the plan's effective
    args (spec/017 §Args — the post-control-override args).

  Returns one of:

  - `{:status :invalid-args :plan … :plan-hash … :frame … :effective-args
      … :validation invalid-validation}` — the POST-override effective args
    violate the plan's view-arg schema. Render is STOPPED before the active
    view is called (spec/017 §View-arg-schema failures stop render).

  - `{:status :prepared :plan … :plan-hash … :frame … :effective-args …
      :validation ok-validation-or-nil :render-inputs {…}}` — the render
    inputs are ready. `:render-inputs` carries `:view` (the active view
    id), `:effective-args`, `:sub-overrides`, `:decorators`, `:frame`, and
    `:plan`, the map the host `:render-host` hook consumes.

  Plan construction itself may throw a `:rf.error/story-*` ex-info (an
  unknown variant, a missing `[:arg …]`, a plan-time view-arg violation —
  the COMPILE-TIME effective args, before controls); `render-variant`
  catches that and projects it to `:error`. This fn re-validates the
  POST-CONTROL effective args (the plan validated the pre-control ones), so
  a control that drives an invalid value is the `:invalid-args` path here,
  distinct from a malformed registration."
  ([target] (prepare-render target nil))
  ([target {:keys [control-overrides validator-fns] :as opts}]
   (let [compile-opts (select-keys opts [:lookup :view-lookup :validator-fns
                                         :sub-lookup :fragment-lookup :check-lookup])
         plan         (plan/variant-plan target compile-opts)
         frame        (:variant/id plan)
         plan-eff     (get-in plan [:world :effective-args] {})
         eff-args     (apply-control-overrides plan-eff control-overrides)
         schema       (get-in plan [:world :view-args-schema])
         ;; Re-validate the POST-override effective args. The plan already
         ;; validated the PRE-override (resolved) args; a control override
         ;; can drive a value the registration never carried, so the live
         ;; render path re-checks before calling the view (spec/017 §Args —
         ;; view-arg-schema failures stop render).
         validation   (when schema
                        (plan/validate-effective-args schema eff-args validator-fns))
         ;; The plan-hash is over the normalized plan — render + run agree
         ;; on it. When controls change the effective args, reflect them in
         ;; the hashed plan so the hash tracks what is actually rendered.
         render-plan  (assoc-in plan [:world :effective-args] eff-args)
         plan-hash    (fingerprint/plan-hash render-plan)
         base         {:plan           render-plan
                       :plan-hash      plan-hash
                       :frame          frame
                       :effective-args eff-args}]
     (if (and validation (= :invalid (:status validation)))
       (assoc base :status :invalid-args :validation validation)
       (let [sub-overrides (resolve-render-sub-overrides plan eff-args)
             render-inputs  (cond-> {:view           (get-in plan [:world :component])
                                     :effective-args eff-args
                                     :decorators     (get-in plan [:world :decorators])
                                     :frame          frame
                                     :plan           render-plan}
                              (some? sub-overrides) (assoc :sub-overrides sub-overrides))]
         (cond-> (assoc base :status :prepared :render-inputs render-inputs)
           (some? validation) (assoc :validation validation)))))))

;; ===========================================================================
;; render-variant — the public verb
;; ===========================================================================

(defn- cannot-run-result
  "The `:cannot-run` render result — no host can render (spec/017
  §`:cannot-run`, the distinct THIRD status). Reuses the capability-axis
  refusal vocabulary: rendering an active view requires the
  `:hiccup-structure` proof (a host that can render-to-hiccup), which the
  bare JVM lacks."
  [prepared reason]
  (-> (select-keys prepared [:plan :plan-hash :frame :effective-args :validation])
      (assoc :status           :cannot-run
             :required-runner  #{:hiccup-structure}
             :available-runner #{}
             :reason           reason)))

(defn- error-result
  "The `:error` render result — plan construction or the host render fn
  threw. Carries the structured ex-data so tools surface the failure the
  same way a run error surfaces.

  The `:error` sub-map is the shared
  `re-frame.story.error/throwable->error-map` projection — the canonical
  `{:message :stack :data}` shape.

  When the throw happened AFTER `prepare-render` succeeded (the host render
  fn threw), the prepared `:plan` / `:plan-hash` / `:effective-args`
  already exist, so thread them onto the result to match the documented
  shape (spec/017 §Args — P1 render API: those slots are always present
  once prepared). The `:frame` slot prefers the prepared frame id (an
  inline-map target has no keyword frame, but the plan still resolved one)
  and falls back to the keyword target. When `prepared` is nil (plan
  CONSTRUCTION threw — no plan exists), only `:frame` + `:error` carry."
  ([target e] (error-result target e nil))
  ([target e prepared]
   (cond-> {:status :error
            :frame  (or (:frame prepared) (when (keyword? target) target))
            :error  (story-error/throwable->error-map e)}
     prepared (merge (select-keys prepared
                                  [:plan :plan-hash :effective-args])))))

(defn render-variant
  "Render `target`'s active workshop view from its normalized variant plan
  (spec/017 §Args, controls, and `render-variant`). `target` is a keyword
  (a registered variant) or a map (an inline plan) — the SAME target shapes
  `story/run` normalizes.

  `opts`:

  - `:control-overrides` — live control-panel arg overrides, deep-merged on
    top of the plan's effective args (the post-control-override args).
  - `:lookup` / `:view-lookup` / `:validator-fns` / `:sub-lookup` /
    `:fragment-lookup` / `:check-lookup` — threaded to the plan compiler
    (host-free test seams; production reads the registrars).

  Returns the documented result map (see ns docstring + spec/017 §Args —
  P1 render API):

      {:status         :rendered | :invalid-args | :cannot-run | :error
       :plan           normalized-plan
       :plan-hash      string
       :frame          frame-id
       :effective-args {...}
       :validation     optional-validation-result
       :rendered       host-render-result}

  It prepares `:world` for rendering and renders the active view; it does
  NOT execute `:script` or terminal `:expect` (rendering is not a test
  run). When Story is disabled (a production CLJS build), it returns
  `:cannot-run` immediately — the render verb never throws under elision."
  ([target] (render-variant target nil))
  ([target opts]
   (if-not config/enabled?
     ;; Production / disabled: nothing to render. The verb fails closed
     ;; into `:cannot-run` rather than throwing (the §6 elision contract).
     {:status :cannot-run :frame (when (keyword? target) target)
      :required-runner #{:hiccup-structure} :available-runner #{}
      :reason :story-disabled}
     (try
       (let [prepared (prepare-render target opts)]
         (case (:status prepared)
           :invalid-args
           ;; Re-shape to the public result: drop the internal :status
           ;; alias, surface the documented slots (spec/017 P1 render API).
           (select-keys prepared
                        [:status :plan :plan-hash :frame :effective-args :validation])

           :prepared
           (if-let [host (render-host-fn)]
             (try
               (let [rendered (host (:render-inputs prepared))]
                 (-> (select-keys prepared
                                  [:plan :plan-hash :frame :effective-args :validation])
                     (assoc :status :rendered :rendered rendered)))
               ;; Host render threw — `prepared` already carries
               ;; :plan/:plan-hash/:effective-args, so thread them onto the
               ;; :error result rather than dropping the context.
               (catch #?(:clj Throwable :cljs :default) e
                 (error-result target e prepared)))
             ;; No host render hook (the bare JVM / a pre-boot CLJS build):
             ;; the render-prep is complete, but nothing can paint the view.
             (cannot-run-result prepared :no-render-host))

           ;; Defensive — prepare-render only returns the two states above.
           (cannot-run-result prepared :render-prep-incomplete)))
       (catch #?(:clj Throwable :cljs :default) e
         (error-result target e))))))
