(ns re-frame.elision-probe
  "Production-elision probe (Spec 009 §Production builds).

  This namespace is compiled under `:advanced` with
  `:closure-defines {goog.DEBUG false}` by the `:elision-probe` shadow-cljs
  build. The resulting JS bundle is then grep'd by
  `scripts/check-elision.cjs` for dev-only string sentinels — they MUST
  NOT appear, because their containing branches are gated on
  `re-frame.interop/debug-enabled?` (an alias of `goog.DEBUG`).

  The probe touches every gated surface so that, in a non-elided control
  build (`goog.DEBUG=true`), the sentinels reliably *do* appear — that
  control is what gives the elision assertion teeth.

  Surfaces exercised:

  - `register-listener!` / `unregister-listener!` / `emit-trace-event!`
    (Spec 009 §Emitting trace events)
  - `reg-app-schema` + `validate-*!` (Spec 010 §Production builds)
  - `register!` / `unregister!` / `clear-kind!` registrar trace emit
    (Spec 009 §:op-type vocabulary — :rf.registry/*)
  - dispatch through `dispatch-sync` to walk the router → events → fx
    pipeline (Spec 009 §Where trace emission lives)
  - `:rf.http/managed` Spec 014 trace ops (`:rf.http/retry-attempt`)
    — emitted only inside `(when interop/debug-enabled? ...)` branches.
  - `re-frame.epoch` public surface — `epoch-history`, `restore-epoch!`,
    `register-epoch-listener!`, `unregister-epoch-listener!`, `configure :epoch-history`,
    plus the `:rf.epoch/*` trace ops emitted by `settle!` and
    `restore-epoch!` (rf2-gox8 follow-up to rf2-shjf).
  - `re-frame.views` reg-view* wrapper — `:view/render` trace op
    (Spec 004 §Render-tree primitives, rf2-piag / rf2-t5tx). The
    instance-token mint, the `*render-key*` binding, and the late-
    bind emit must elide.
  - `re-frame.frame/safe-call-hook!` — the EP-0008 R2 per-hook
    `:rf.warning/teardown-hook-exception` DEV DIAGNOSTIC (rf2-inkdqh).
    The diagnostic emit rides `trace/emit-error!` (gated on
    `interop/debug-enabled?`), so its body — including the
    `:rf.warning/teardown-hook-exception` operation keyword — must DCE
    under `:advanced` + `goog.DEBUG=false`. Rooting a throwing-hook
    `destroy-frame!` puts the gated emit in the reachability graph so the
    control build (DEBUG=true) contains the sentinel and the production
    build must not.

  Note the probe does NOT need to assert anything at runtime; it exists
  to root the dead-code-elimination graph at every surface. The grep
  test is the assertion."
  (:require [re-frame.core         :as rf]
            [re-frame.registrar    :as registrar]
            [re-frame.schemas      :as schemas]
            [re-frame.trace        :as trace]
            [re-frame.late-bind    :as late-bind]
            ;; rf2-qwm0a — listener + buffer surface lives in
            ;; `re-frame.trace.tooling` (production-DCE split). The
            ;; probe touches it to keep both surfaces reachable so
            ;; their `interop/debug-enabled?` gates are tested in the
            ;; closure DCE pass, not surface-pruned before DCE runs.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.epoch        :as epoch]
            [re-frame.epoch.listeners :as epoch.listeners]
            [re-frame.http.managed :as http-managed]
            [re-frame.views        :as views]
            [re-frame.machines]
            ;; rf2-32siq3.40 — EP-0023 image-loaded frames. The probe roots the
            ;; registration SOURCE STORE so `:rf.provenance/ns` is in the
            ;; :advanced reachability graph (it must SURVIVE DCE — a production
            ;; descriptor field, not a dev-only sentinel). It does NOT root the
            ;; image-loading path (make-frame / assemble / check-capabilities!),
            ;; so those image-assembly fns DCE when unused.
            [re-frame.source-store :as source-store]
            [re-frame.source-coords :as source-coords]))

;; ---- trace listener API ---------------------------------------------------

(defn ^:export touch-trace! []
  ;; Reach into every documented trace API so :advanced keeps the surface
  ;; alive and we're testing the *body* gates, not surface-pruning.
  (trace-tooling/register-listener! ::probe (fn [_ev] nil))
  (rf/emit-trace-event! :event :rf.probe/touched {:source :probe})
  (trace-tooling/unregister-listener! ::probe)
  ;; rf2-g1b2m / rf2-8uwce — per-frame cascade-keyed trace rings (Spec 009).
  ;; These public entry points must elide their bodies in production.
  (trace-tooling/configure-trace-buffer! {:cascades-retained 50})
  (let [_buf (trace-tooling/trace-buffer :rf/default {:op-type :rf.event :flat true})]
    nil)
  (trace-tooling/clear-trace-buffer! :rf/default))

;; ---- schemas surface ------------------------------------------------------

(defn ^:export touch-schemas! []
  (rf/reg-app-schema [:user :name] {:schema :string})
  (schemas/validate-app-schema! {:user {:name "ok"}})
  (schemas/validate-app-schema! {:user {:name 42}}    :probe/event)
  (schemas/validate-event!  :probe/event [:probe/event 1] {:schema :int})
  (schemas/validate-fx!     :probe/fx :probe/event {} {:schema :map})
  (schemas/validate-sub!    :probe/sub [:probe/sub] :foo {:schema :keyword}))

;; ---- registrar trace emit -------------------------------------------------

(defn ^:export touch-registrar! []
  ;; reg-event / dispatch-sync exercise registrar/register! and the
  ;; router/events/fx trace emit sites in one shot.
  (rf/reg-event :probe/init  (fn [{:keys [db]} _ev] {:db {:counter 0}}))
  (rf/reg-event :probe/inc   (fn [{:keys [db]} _ev] {:db (update db :counter inc)}))
  (rf/reg-sub      :probe/count (fn [db _q] (:counter db)))
  (rf/dispatch-sync [:probe/init])
  (rf/dispatch-sync [:probe/inc])
  ;; Re-register to fire :rf.registry/handler-replaced.
  (rf/reg-event :probe/inc   (fn [{:keys [db]} _ev] {:db (update db :counter (fnil inc 0))}))
  ;; Exercise the registrar's unregister!/clear-kind! emit sites so that
  ;; the :rf.registry/handler-cleared keyword has a path into the
  ;; reachability graph too.
  (registrar/unregister!  :event :probe/inc)
  (registrar/clear-kind!  :sub))

;; ---- Spec 014 :rf.http/managed surface (rf2-cfig, rf2-omsae) --------------

(defn ^:export touch-http-managed! []
  ;; Spec 014 — `:rf.http/managed` ships gated trace ops:
  ;;
  ;;   :rf.http/retry-attempt              (info trace, both transports)
  ;;   :rf.http/aborted-on-actor-destroy   (info trace, rf2-wvkn cancellation cascade)
  ;;
  ;; All emit sites are wrapped in `(when interop/debug-enabled? ...)`,
  ;; so under :advanced + goog.DEBUG=false the branches DCE and the
  ;; string sentinels (e.g. "rf.http/retry-attempt") should NOT appear
  ;; in the production bundle.
  ;;
  ;; rf2-cdmle (supersedes rf2-omsae) — the canned-stub fxs
  ;; (`:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`)
  ;; moved out of `re-frame.http.managed`'s load-time side effects to a
  ;; sibling test-support namespace, `re-frame.http.test-support`. The
  ;; gate is no longer `(when interop/debug-enabled? ...)` but instead
  ;; the require boundary: production code paths must not require
  ;; `re-frame.http.test-support`. The elision-probe namespace MUST NOT
  ;; require it either — under :advanced + goog.DEBUG=false the
  ;; canned-stub fx-id string fragments must not appear in the
  ;; production bundle, and the gate that pins that absence is now
  ;; classpath / require-closure absence rather than DCE of a `(when
  ;; ...)` block. The same `scripts/check-elision.cjs` sentinels apply
  ;; — what changed is the source-of-truth for absence.
  ;;
  ;; The probe roots the dependency graph by:
  ;;   1. requiring re-frame.http.managed (forces its ns body to be
  ;;      compiled into the bundle, which is where the gated trace-emit
  ;;      branches live);
  ;;   2. touching `dispatch-reply!`, `build-reply-event`, and the
  ;;      surrounding retry / decode ctx through the public `:rf.http/
  ;;      managed-abort` fx (which is dev+prod) and the abort-on-actor-
  ;;      destroy path so the gated trace emits sit in a reachable
  ;;      module graph (DCE only eliminates branches it can prove dead;
  ;;      reachability comes from the require + the dispatch path).
  ;;
  ;; The canned-stub fx-id keywords are NEVER referenced from probe code;
  ;; the test-support namespace that registers them is NEVER required by
  ;; the probe. That is exactly what we want to assert lives only in the
  ;; control bundle.
  (rf/reg-event :probe/http-abort-touch
    (fn [_ _]
      {:fx [[:rf.http/managed-abort :probe/never-issued-request]]}))
  (rf/dispatch-sync [:probe/http-abort-touch])
  ;; Touch clear-all-in-flight! so the in-flight registry surface is
  ;; reachable; the probe doesn't actually issue a real request.
  (http-managed/clear-all-in-flight!)
  ;; rf2-wvkn — touch the public abort-on-actor-destroy fn so its body
  ;; (with the gated `:rf.http/aborted-on-actor-destroy` trace emit)
  ;; sits in a reachable module graph for the elision check.
  (http-managed/abort-on-actor-destroy :probe/never-spawned-actor-id))

;; ---- Tool-Pair §Time-travel epoch surface (rf2-gox8) ----------------------

(defn ^:export touch-epoch! []
  ;; Per Tool-Pair §Time-travel and Spec 009 §`register-epoch-listener!`, the
  ;; epoch ns ships gated trace ops:
  ;;
  ;;   :rf.epoch/snapshotted                       (settle! after drain-empty)
  ;;   :rf.epoch/restored                          (restore-epoch! happy path)
  ;;   :rf.epoch/restore-during-drain              (failure mode 2)
  ;;   :rf.epoch/restore-unknown-epoch             (failure mode 3)
  ;;   :rf.epoch/restore-schema-mismatch           (failure mode 4)
  ;;   :rf.epoch/restore-missing-handler           (failure mode 5)
  ;;   :rf.epoch/restore-version-mismatch          (failure mode 6)
  ;;   :rf.epoch/restore-non-ok-record             (rf2-v0jwt — failure mode 7)
  ;;   :rf.epoch/db-replaced                       (rf2-zq55 — replace-app-db! happy path)
  ;;   :rf.epoch/replace-during-drain       (rf2-zq55 — failure mode A)
  ;;   :rf.epoch/replace-schema-mismatch    (rf2-zq55 — failure mode B)
  ;;   :rf.epoch/replace-history-disabled   (rf2-unpldn — failure mode C, depth 0)
  ;;   :rf.warning/epoch-redact-fn-exception       (rf2-wp70d — redact-fn throw)
  ;;
  ;; Every emit site sits inside `(when interop/debug-enabled? ...)`
  ;; (or guarded by an `if-not interop/debug-enabled?` early-return in
  ;; restore-epoch!) so under :advanced + goog.DEBUG=false the bodies
  ;; DCE and the `:rf.epoch/*` string fragments must NOT appear in the
  ;; production bundle.
  ;;
  ;; The probe roots the dependency graph for the epoch surface by:
  ;;   1. requiring re-frame.epoch directly (forces the ns body — and
  ;;      therefore every gated branch — into the bundle, where DCE can
  ;;      then prove the bodies dead under goog.DEBUG=false);
  ;;   2. referencing every public symbol surfaced via re-frame.core so
  ;;      the listener / history / restore / configure entry points
  ;;      stay live;
  ;;   3. exercising the drain-settle path via the dispatch-sync calls
  ;;      in touch-registrar! — the eventual queue-empty fires
  ;;      `:rf.epoch/snapshotted` through trace/emit!, sourcing the
  ;;      snapshotted sentinel.
  (rf/configure! {:epoch-history {:depth 10}})
  ;; rf2-wp70d.5 — install a throwing :redact-fn so the gated
  ;; `maybe-redact` branch (and its `:rf.warning/epoch-redact-fn-
  ;; exception` emit site) is REACHED at probe time. Without an
  ;; installed fn the `(if-let [f (redact-fn)] ...)` branch is taken
  ;; via the nil arm and the catch's literals never see the closure
  ;; reachability graph from the probe — the catalogue sentinel would
  ;; still survive in the control bundle because the keyword literal
  ;; lives in the source ns (which is required), but rooting the
  ;; gated body through an actual call sharpens the methodology
  ;; assertion: the control build proves the (when interop/debug-
  ;; enabled? ...) gate is the load-bearing surface, not require-
  ;; closure alone. The throw is caught by `maybe-redact` itself
  ;; (`:rf.warning/epoch-redact-fn-exception` fires under DEBUG=true)
  ;; so the probe does not crash.
  (rf/configure! {:epoch-history {:redact-fn (fn [_record]
                              (throw (ex-info "probe-redact-throw" {})))}})
  ;; Register an event we own here (not :probe/inc, which
  ;; touch-registrar! has already unregistered) and dispatch it so
  ;; settle! reaches `maybe-redact` with the throwing fn installed.
  ;; The catch branch fires `:rf.warning/epoch-redact-fn-exception`
  ;; under DEBUG=true, exercising the gated body's literal
  ;; reachability through an actual call site rather than relying
  ;; on require-closure alone.
  (rf/reg-event :probe/redact-fire (fn [{:keys [db]} _ev] {:db {:redact :fired}}))
  (rf/dispatch-sync [:probe/redact-fire])
  ;; Clear so subsequent probe sites (replace-app-db! below,
  ;; on-frame-destroyed!) are not perturbed by the throwing fn.
  (rf/configure! {:epoch-history {:redact-fn nil}})
  (rf/register-epoch-listener! ::probe-epoch (fn [_record] nil))
  (let [_history (rf/epoch-history :rf/default)]
    nil)
  (rf/unregister-epoch-listener! ::probe-epoch)
  ;; Drive a restore failure-mode emit site so the unknown-epoch
  ;; sentinel has a path through a documented entry point. The
  ;; remaining failure ops survive via their literal occurrence in
  ;; the gated emit-restore-failure! call sites in re-frame.epoch.
  (rf/restore-epoch! :rf/default 999999)
  ;; rf2-zq55 — replace-app-db! is the Tool-Pair §Pair-tool-writes
  ;; surface. The body is gated by an `(if-not interop/debug-enabled?
  ;; false ...)` early-return — the success branch fires
  ;; :rf.epoch/db-replaced, the in-drain rejection fires
  ;; :rf.epoch/replace-during-drain, the schema-mismatch
  ;; rejection fires :rf.epoch/replace-schema-mismatch. All
  ;; three string fragments must elide under :advanced + goog.DEBUG=
  ;; false. Calling against a frame ID without forcing an actual
  ;; replace is enough — the literal sentinels live in the gated body.
  (rf/replace-app-db! :rf/default {})
  ;; Reference epoch's lower-level entry points directly so the ns is
  ;; not pruned even before DCE looks at the gated bodies.
  (epoch/clear-history!)
  ;; rf2-sh5g6: clear-frame-history! is `defn-` (test-only seam); the
  ;; un-scoped `clear-history!` above already pins the namespace for
  ;; the elision walker.
  (epoch/clear-epoch-listeners!)
  (let [_cfg (epoch/current-config)]
    nil)
  ;; rf2-d656 — on-frame-destroyed! emits :rf.epoch.cb/silenced-on-frame-destroy
  ;; per (frame-id, cb-id) pair when a frame previously observed by a
  ;; register-epoch-listener! callback is destroyed. The whole body sits inside
  ;; `(when interop/debug-enabled? ...)`; the string fragment must elide
  ;; under :advanced + goog.DEBUG=false. Touch the implementation seam
  ;; (`re-frame.epoch.listeners/on-frame-destroyed!`) directly — the
  ;; `re-frame.epoch` facade publishes through the
  ;; `:epoch/on-frame-destroyed` late-bind hook only (per rf2-e0lva the
  ;; facade-side wrapper is private).
  (epoch.listeners/on-frame-destroyed! :rf/default nil nil nil))

;; ---- Spec 004 §Render-tree primitives — reg-view* wrapper (rf2-piag) -----

(defn ^:export touch-views! []
  ;; Per Spec 004 §Render-tree primitives (rf2-piag / rf2-t5tx Option C),
  ;; the reg-view* wrapper emits a `:view/render` trace per render. The
  ;; emit site sits inside `(when interop/debug-enabled? ...)`, along
  ;; with the *render-key* binding and the late-bind lookup. Under
  ;; :advanced + goog.DEBUG=false the body must DCE; the operation
  ;; keyword's "view/render" string fragment must NOT survive.
  ;;
  ;; Also per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1):
  ;; the wrapper's source-coord injection branch sits inside the same
  ;; `interop/debug-enabled?` gate; the format-source-coord output and
  ;; the literal `data-rf2-source-coord` string fragment must NOT
  ;; survive in the production bundle. The probe touches both the
  ;; format helper (via reg-view*) and the wrapper render path so
  ;; both are reachable in the control build but DCE'd in production.
  ;;
  ;; The probe roots reachability by:
  ;;   1. requiring re-frame.views directly (forces the ns body and the
  ;;      gated emit-render-trace! body into the bundle);
  ;;   2. registering a view via reg-view* (the public surface that
  ;;      installs the wrapping fn carrying the gated body);
  ;;   3. invoking the wrapper so the wrapper's body — including the
  ;;      gated emit — is reachable code, not just declared-but-dead.
  (rf/reg-view* :probe/render-key
    {:ns 're-frame.elision-probe :file "probe.cljs" :line 1 :column 1}
    (fn render-probe [] [:span "probe"]))
  (let [wrapper (rf/view :probe/render-key)]
    (when wrapper (wrapper)))
  ;; rf2-rpgq8 — render a view WITH args so the render-args capture path
  ;; (`(when interop/debug-enabled? args)` in the wrapper + the
  ;; `:rf.view/render-args` `cond->` assoc in the gated emit body) sits in
  ;; the reachability graph. Both ride `interop/debug-enabled?`, so under
  ;; :advanced + goog.DEBUG=false the capture + assoc DCE and no raw
  ;; user render-args reach the production bundle — that absence rides the
  ;; existing `rf.view/rendered` sentinel (same gated body). (The
  ;; `:rf.view/render-args` slot KEYWORD itself legitimately survives via
  ;; the always-reachable `re-frame.classification/project-trace-event` chokepoint,
  ;; same as `:rf.event/db` — so no keyword sentinel is added for it.)
  (rf/reg-view* :probe/render-args
    {:ns 're-frame.elision-probe :file "probe.cljs" :line 1 :column 1}
    (fn render-args-probe [_props] [:span "probe-args"]))
  (let [wrapper (rf/view :probe/render-args)]
    (when wrapper (wrapper {:probe-prop "value"})))
  ;; Also touch the public mint / current-render-key entry points so
  ;; their bodies stay in the reachability graph (DCE only proves the
  ;; gated branches dead; the public surface itself remains).
  (let [_t (views/mint-instance-token!)
        _k (views/current-render-key)]
    nil)
  ;; rf2-9hoos — touch the view-side capture surfaces so their gated
  ;; bodies (the `:rf.view/unmounted` emit, the deref-sink push, the
  ;; mount-vs-rerender discriminator) sit in a reachable module graph
  ;; for the elision check. The `:rf.view/rendered` sentinel already
  ;; covers the rendered-trace body (where `:mount?` / `:deref-subs`
  ;; ride); the unmount op needs its own touch so the control build
  ;; contains the sentinel and the methodology check has teeth. Under
  ;; :advanced + goog.DEBUG=false every body DCEs.
  (views/emit-view-unmounted! :probe/unmounted [:probe/unmounted 1] :rf/default)
  (let [_rea (views/install-unmount-hook! :probe/unmounted [:probe/unmounted 1] :rf/default)]
    nil)
  (views/record-view-deref! [:probe/sub])
  (let [_m (views/first-render?! [:probe/unmounted 1])]
    nil)
  (views/clear-seen-render-keys!)
  ;; Touch the surviving warn-once clear-fn so the warn-once ns body stays
  ;; in the reachability graph for the elision check. (The retired
  ;; `warned-plain-fn-frame-pairs` cache + `clear-plain-fn-warned-pairs!`
  ;; were removed in rf2-k4xous — their warning is retired per EP-0002,
  ;; superseded by the always-on :rf.error/no-frame-context.)
  (views/clear-warned-non-dom-roots!))

;; ---- Spec 005 §Source-coord stamping — reg-machine macro (rf2-8bp3) -------

(defn ^:export touch-machines! []
  ;; Per Spec 005 §Source-coord stamping (rf2-npvsx + rf2-vqja2, supersedes
  ;; rf2-8bp3) the reg-machine macro walks the literal spec form at expansion
  ;; time and emits an `(if interop/debug-enabled? <dev> <prod>)` branch. The
  ;; DEV arm co-locates per-element source (`{:fn .. :source-coords ..
  ;; :source-code ..}`) onto each `:guards` / `:actions` entry AND co-locates
  ;; a reference-site `:source-coords` onto each `:states`-tree map node
  ;; (state-node / transition map; rf2-vqja2 dropped the old flat
  ;; `:rf.machine/state-coords` side-index); the PROD arm collapses each
  ;; element entry to `{:fn <fn>}` and runs NO state-source splice. Under
  ;; :advanced + goog.DEBUG=false the closure compiler folds the gate to
  ;; false and DCEs the ENTIRE dev arm — every co-located `:source-code`
  ;; string and every coord literal (the per-element coords AND the
  ;; state-node / transition-map `:source-coords`) must elide; the prod
  ;; state-nodes ship clean (just the user's `{:on …}`).
  ;;
  ;; Per rf2-jbbp7 the `:data-schema` key on `reg-machine` adds a second
  ;; gated surface: the `re-frame.machines.data-validation` ns's
  ;; emit-failure! body sits inside `(if interop/debug-enabled? ...)`
  ;; and its " :data failed schema at boundary :where :machine-data "
  ;; reason-string fragment must also elide under :advanced.
  ;;
  ;; The probe roots reachability for the machines surface by:
  ;;   1. requiring re-frame.machines (forces ns body — the late-bind
  ;;      hook registration, the :rf/machine sub, the spawn / destroy
  ;;      fx handlers, the data-validation emit body — into the bundle);
  ;;   2. registering a machine via `rf/reg-machine` so the macro's
  ;;      gated co-location dev arm is in reachable code, not just
  ;;      declared-but-dead.
  ;;   3. registering a machine WITH `:data-schema` and forcing a violation
  ;;      so the emit-failure! call site is reached in the control build
  ;;      (DEBUG=true) and its sentinel string lands in the bundle.
  ;;
  ;; The sentinel strings are the distinctive co-located `:source-code`
  ;; fn-body fragments (`(fn probe-never-guard`, `(fn probe-noop-action`) and
  ;; " :data failed schema at boundary :where :machine-data " (data-
  ;; validation emit body). All must elide under :advanced + goog.DEBUG=
  ;; false. Because the state-node / transition-map `:source-coords`
  ;; co-location (rf2-vqja2) rides the SAME dev arm, the fn-body sentinels
  ;; transitively prove the state-source splice DCE'd: there is no
  ;; state-specific sentinel keyword anymore (`:rf.machine/state-coords` is
  ;; gone), and `:source-coords` is a shared keyword the guards/actions arm
  ;; also uses, so the fn-body strings are the unambiguous load-bearing
  ;; grep. The fn bodies carry distinctive named-fn symbols so the `pr-str`
  ;; source string is unambiguous under a global grep.
  (rf/reg-machine :rf.probe/machine
    {:initial :idle
     :guards  {:never? (fn probe-never-guard [_] false)}
     :actions {:noop   (fn probe-noop-action [_] {})}
     :states  {:idle {:on {:tick {:target :idle :guard :never? :action :noop}}}}})
  ;; Force a `:where :machine-data` failure to root the data-validation
  ;; emit body for the elision control. The validator never installs
  ;; (the spawn-time gate or post-commit gate emits + rejects), so the
  ;; failing machine leaves no state behind.
  (rf/reg-machine :rf.probe/machine-with-schema
    {:initial     :idle
     :data        {:n 0}                              ;; 0 violates pos-int?
     :data-schema [:map [:n pos-int?]]
     :states      {:idle {}}})
  (rf/dispatch-sync [:rf.probe/machine-with-schema [:noop]]))

;; ---- rf2-ts1a: call-site source-coord macros ------------------------------
;;
;; The `dispatch` / `dispatch-sync` / `subscribe` macros stamp an
;; `:rf.trace/call-site` map at compile time (per Q3=B dev-only elision).
;; Under `:advanced` + `goog.DEBUG=false`, the macro's
;; `(if interop/debug-enabled? <stamp-branch> <no-stamp-branch>)` expansion
;; folds away — the stamp branch DCE's and the literal map vanishes from
;; the bundle. The keyword `:rf.trace/call-site`'s string fragment must
;; NOT appear in the production bundle. (`inject-cofx` is removed in EP-0017
;; and no longer stamps a call-site.)

(defn ^:export touch-call-site-macros! []
  ;; Each macro form below emits a literal `:rf.trace/call-site` map
  ;; under DEBUG=true. The stamp-branches must DCE under DEBUG=false.
  (rf/reg-event :probe/cs-event (fn [{:keys [db]} _ev] {:db db}))
  (rf/reg-sub      :probe/cs-sub   (fn [db _q] db))
  (rf/reg-cofx     :probe/cs-cofx  (fn [] :ok))
  ;; dispatch + dispatch-sync macros
  (rf/dispatch [:probe/cs-event])
  (rf/dispatch-sync [:probe/cs-event])
  ;; subscribe macro
  (let [_r (rf/subscribe [:probe/cs-sub])] nil))

;; ---- rf2-cry25: reg-view-injected dispatch/subscribe call-site -----------
;;
;; The `reg-view` MACRO injects the view's source-coord into the
;; lexically-bound `dispatch` / `subscribe` NOUNS so a view's on-click
;; `#(dispatch [...])` stamps `:source :ui` + the view's
;; `:rf.trace/call-site` (Option A). Each injected arg rides its OWN
;; `(if interop/debug-enabled? <dev-coord> <prod>)` gate — the dispatcher
;; prod branch is `{:source :ui}` (no call-site) and the subscriber prod
;; branch is `nil` — so under `:advanced` + `goog.DEBUG=false` the dev
;; coord literal AND the `:rf.trace/call-site` keyword DCE. The existing
;; `rf.trace/call-site` sentinel (check-elision.cjs) covers it; this
;; touch roots the reg-view-macro-injected coord in the reachability
;; graph so the control build sees it and the methodology check has teeth.

(defn ^:export touch-reg-view-injection! []
  (rf/reg-view probe-injected-view [_n]
    [:button {:on-click #(dispatch [:probe/cs-event])}
     [:span @(subscribe [:probe/cs-sub])]])
  (let [_v (rf/view :re-frame.elision-probe/probe-injected-view)]
    nil))

;; ---- EP-0008 R2 teardown-hook DEV DIAGNOSTIC (rf2-inkdqh) -----------------
;;
;; `re-frame.frame/safe-call-hook!` runs the late-bound optional-artefact
;; cleanup hooks during `destroy-frame!`. When a hook throws it does two
;; things on two channels (Spec 009 §Observability channels):
;;
;;   1. ALWAYS-ON axis — accumulates a `:hook-failures` entry that
;;      `destroy-frame!` flushes as ONE `:rf.error/frame-teardown-failed`
;;      report (this survives production — it is NOT gated by
;;      `interop/debug-enabled?`).
;;   2. DIAGNOSTIC channel (EP-0008 R2) — emits a per-hook
;;      `:rf.warning/teardown-hook-exception` trace AT ITS CAUSAL POSITION
;;      via `trace/emit-error!`, which IS gated on `interop/debug-enabled?`,
;;      so production CLJS bundles DCE it.
;;
;; Before rf2-inkdqh the probe never touched the destroy/teardown path, so
;; the R2 diagnostic body was never in the DCE reachability graph and
;; check-elision.cjs had no sentinel for it — its production absence was
;; UNVERIFIED. This touch installs a throwing late-bound cleanup hook and
;; calls `destroy-frame!`, rooting the `safe-call-hook!` catch arm + the
;; gated warn emit so the control build (DEBUG=true) contains the
;; `:rf.warning/teardown-hook-exception` keyword sentinel and the production
;; build (DEBUG=false) must DCE it.

(defn ^:export touch-teardown! []
  ;; Install a throwing cleanup hook under a late-bound teardown hook key,
  ;; snapshotting the prior binding so the install doesn't leak. The hook
  ;; key (`:ssr/on-frame-destroyed`) is one safe-call-hook! fires during the
  ;; teardown recipe; a throw routes through safe-call-hook!'s catch arm,
  ;; which emits the gated `:rf.warning/teardown-hook-exception` diagnostic.
  (let [hook-key :ssr/on-frame-destroyed
        original (late-bind/get-fn hook-key)]
    (late-bind/set-fn! hook-key
                       (fn [& _] (throw (ex-info "probe teardown-hook throw"
                                                 {:hook :probe}))))
    (rf/reg-frame :rf.probe/teardown-frame {:doc "throwing teardown hook"})
    (try
      ;; The accumulated always-on report flushes (survives prod), and the
      ;; per-hook DEV diagnostic emits at its causal position (DCEs in prod).
      (rf/destroy-frame! :rf.probe/teardown-frame)
      (finally
        (late-bind/set-fn! hook-key original)))))

;; ---- rf2-9wwkcm: pure-documentation registration metadata (:doc) ----------
;;
;; Per Spec 001 §Production elision contract, `:doc` is the one PURE-
;; documentation registration-metadata key — zero production runtime use,
;; zero production observability use. `re-frame.registrar/register!` strips
;; the pure-documentation keys (`strip-pure-documentation`) from the stored
;; metadata under `:advanced` + `goog.DEBUG=false`, so `(rf/handler-meta
;; kind id)` carries no `:doc` in production.
;;
;; The DCE of the dev-only `:doc` STRING bytes from the bundle rides the
;; existing `re-frame.events/merge-form-source` gate: a `reg-event` call
;; with a `:doc` carries the WHOLE form (including the `:doc` string) into
;; `:rf.handler/source` under `(if interop/debug-enabled? ~src-string nil)`,
;; so under `goog.DEBUG=false` the macro-emitted gate DCEs the form-source
;; literal — including its `:doc "…"` bytes — entirely. The distinctive
;; sentinel below is minted ONLY inside a `reg-event` form-source so the
;; control build (DEBUG=true) contains it (via the captured form-source) and
;; the production build (DEBUG=false) must DCE it.

(defn ^:export touch-doc-metadata! []
  ;; The `:doc` value carries a distinctive byte sequence
  ;; (`rf2-9wwkcm-doc-elision-sentinel`) so a global grep is unambiguous.
  ;; Under DEBUG=true the reg-event macro captures the whole form's
  ;; `pr-str` into `:rf.handler/source`, so the sentinel lands in the
  ;; control bundle; under DEBUG=false the form-source gate DCEs it.
  (rf/reg-event :probe/doc-event
    {:doc "rf2-9wwkcm-doc-elision-sentinel: pure-documentation metadata"}
    (fn [{:keys [db]} _ev] {:db db})))

;; ---- rf2-9vx0jk: dev-only :rf.interceptor/override-summary run-start tag ----
;;
;; Per Spec 009 §`:tags` interceptor family, the router stamps a
;; `:rf.interceptor/override-summary` tag onto the `:rf.event/run-start` TRACE
;; emit when this dispatch's merged `:interceptor-overrides` acted on the chain.
;; The summary value (the `{:matched … :replaced … :removed … :count …}` map)
;; is constructed by `re-frame.router/override-summary` and fed ONLY into the
;; run-start `trace/emit!` call, whose body sits inside
;; `(when interop/debug-enabled? ...)` — so the whole emit, and the summary
;; construction that feeds it, DCE under :advanced + goog.DEBUG=false.
;;
;; This touch roots the override-bearing dispatch path so the gated summary
;; construction is in the reachability graph: under DEBUG=true the run-start
;; emit fires with the summary tag present (the distinctive override id appears
;; in the control bundle's trace data); under DEBUG=false the emit body DCEs.
;;
;; NOTE (parallels rf2-rpgq8 `:rf.view/render-args`): NO keyword sentinel for
;; `:rf.interceptor/override-summary` is added in check-elision.cjs, because the
;; keyword literal LEGITIMATELY survives in production via the always-reachable
;; marks chokepoint `re-frame.classification/project-trace-event` (the fail-closed
;; `(contains? tags :rf.interceptor/override-summary)` branch). The privacy
;; guarantee is that the VALUES (the id/count summary) are constructed only in
;; the gated emit path and never reach the production bundle. The distinctive
;; override-summary id strings the probe mints below ride that gated
;; construction — they are the elision sentinel.

(defn ^:export touch-interceptor-override-summary! []
  ;; Register two interceptors and an event whose chain references them, then
  ;; dispatch with a per-call :interceptor-overrides that removes one and
  ;; replaces the other. The distinctive override-key id keywords
  ;; (`:rf.probe/override-summary-removed-ic`,
  ;; `:rf.probe/override-summary-replaced-ic`) flow into the gated run-start
  ;; override-summary tag construction; under DEBUG=true they reach the trace
  ;; data, under DEBUG=false the construction DCEs.
  (rf/reg-interceptor* :rf.probe/override-summary-removed-ic
    {:before (fn [ctx] ctx)})
  (rf/reg-interceptor* :rf.probe/override-summary-replaced-ic
    {:before (fn [ctx] ctx)})
  (rf/reg-interceptor* :rf.probe/override-summary-stub-ic
    {:before (fn [ctx] ctx)})
  (rf/reg-event :rf.probe/override-summary-event
    {:interceptors [:rf.probe/override-summary-removed-ic
                    :rf.probe/override-summary-replaced-ic]}
    (fn [{:keys [db]} _ev] {:db db}))
  (rf/dispatch-sync [:rf.probe/override-summary-event]
                    {:interceptor-overrides
                     {:rf.probe/override-summary-removed-ic  nil
                      :rf.probe/override-summary-replaced-ic :rf.probe/override-summary-stub-ic}}))

;; ---- rf2-32siq3.40: EP-0023 image-loaded frames — provenance survives, ----
;;       image-assembly fns elide when unused
;;
;; EP-0023 §Namespace-Selected Images: every `reg-*` descriptor carries
;; `:rf.provenance/ns` as a CANONICAL STRING — a PRODUCTION descriptor field,
;; not optional debug metadata. `:include-ns` image assembly reads it, so it
;; MUST SURVIVE `:advanced` + goog.DEBUG=false or namespace-selected images
;; cannot work. Before this probe touch the survival was only HAND-MODELED
;; (source_store_cljs_test/provenance-from-pending-coords-when-ns-stripped binds
;; `*pending-coords*` and hand-strips `:ns`); it was never run under a real
;; elision gate.
;;
;; This is the OPPOSITE direction from the dev-only sentinels above: the
;; `rf.provenance/ns` keyword's string fragment must be PRESENT in the
;; production bundle (it is asserted under PROD_SURVIVING_SENTINELS in
;; check-elision.cjs, not DEV_ONLY_SENTINELS). The probe roots it by recording a
;; descriptor through the SAME `record-descriptor!` path `reg-*` walks — under
;; `:advanced` the descriptor's `:ns` slot is stripped (merge-coords returns the
;; user metadata unchanged) but `record-descriptor!` derives the canonical
;; provenance string from the surviving `*pending-coords*` binding and stamps
;; `:rf.provenance/ns`, exactly the production path. The probe also asserts the
;; survival at RUNTIME (the build's init-fn would throw if the keyword DCE'd and
;; the read-back returned nil), making the methodology assertion belt-and-braces.
;;
;; Conversely, the EP-0023 image-ASSEMBLY fns (make-frame / assemble /
;; check-capabilities!) carry NO debug gate and ride the runtime/SSR
;; image-loading path; image_assembly.cljc's own docstring states "an app that
;; never assembles an image never reaches these fns (Closure DCE removes them)."
;; The probe deliberately does NOT root that path, so the distinctive
;; assembly-only string `check-capabilities!` mints
;; ("rf/make-frame: the image requires capabilities the frame does not supply")
;; must be ABSENT from the production bundle — asserted under
;; PROD_ABSENT_WHEN_UNUSED_SENTINELS in check-elision.cjs.

(defn ^:export touch-image-frame-provenance! []
  ;; Model the production descriptor shape: the macro path's `:ns` slot is
  ;; stripped under :advanced, but the reg-* macro's `*pending-coords*` binding
  ;; survives (prod-coords-form keeps `:ns`). record-descriptor! derives
  ;; `:rf.provenance/ns` from the live binding — the production-surviving path.
  (binding [source-coords/*pending-coords* {:ns "re-frame.elision-probe.image"}]
    (let [stored (source-store/record-descriptor!
                   :event :rf.probe/image-frame-provenance
                   {:handler-fn (fn [{:keys [db]} _] {:db db})})]
      ;; Runtime assertion: provenance MUST survive :advanced. If the
      ;; `:rf.provenance/ns` keyword were DCE'd, this read returns nil and the
      ;; init-fn throws, failing the build run.
      (when-not (string? (:rf.provenance/ns stored))
        (throw (ex-info "elision-probe: :rf.provenance/ns did not survive :advanced"
                        {:stored stored})))))
  ;; Touch the public read API so the provenance keyword stays in the
  ;; reachability graph through a documented entry point too.
  (let [_d (source-store/descriptor-for :event :rf.probe/image-frame-provenance
                                        "re-frame.elision-probe.image")]
    nil)
  ;; Clean up the probe's source-store slot so it does not perturb other touches.
  (source-store/forget-descriptor! :event :rf.probe/image-frame-provenance
                                   "re-frame.elision-probe.image"))

;; ---- entry point ----------------------------------------------------------

(defn ^:export run []
  (touch-trace!)
  (touch-schemas!)
  (touch-registrar!)
  (touch-http-managed!)
  (touch-epoch!)
  (touch-views!)
  (touch-machines!)
  (touch-call-site-macros!)
  (touch-reg-view-injection!)
  (touch-teardown!)
  (touch-doc-metadata!)
  (touch-interceptor-override-summary!)
  (touch-image-frame-provenance!)
  ;; Reference trace/emit! directly through the trace ns alias so its
  ;; body, not just the public re-frame.core re-export, is reachable.
  (trace/emit! :event :rf.probe/direct-touch {:source :probe}))
