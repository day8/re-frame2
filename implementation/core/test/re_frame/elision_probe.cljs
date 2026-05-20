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

  - `register-trace-cb!` / `remove-trace-cb!` / `emit-trace-event!`
    (Spec 009 §Emitting trace events)
  - `reg-app-schema` + `validate-*!` (Spec 010 §Production builds)
  - `register!` / `unregister!` / `clear-kind!` registrar trace emit
    (Spec 009 §:op-type vocabulary — :rf.registry/*)
  - dispatch through `dispatch-sync` to walk the router → events → fx
    pipeline (Spec 009 §Where trace emission lives)
  - `:rf.http/managed` Spec 014 trace ops (`:rf.http/retry-attempt`,
    `:rf.warning/decode-defaulted`) — emitted only inside
    `(when interop/debug-enabled? ...)` branches.
  - `re-frame.epoch` public surface — `epoch-history`, `restore-epoch`,
    `register-epoch-cb!`, `remove-epoch-cb!`, `configure :epoch-history`,
    plus the `:rf.epoch/*` trace ops emitted by `settle!` and
    `restore-epoch` (rf2-gox8 follow-up to rf2-shjf).
  - `re-frame.views` reg-view* wrapper — `:view/render` trace op
    (Spec 004 §Render-tree primitives, rf2-piag / rf2-t5tx). The
    instance-token mint, the `*render-key*` binding, and the late-
    bind emit must elide.

  Note the probe does NOT need to assert anything at runtime; it exists
  to root the dead-code-elimination graph at every surface. The grep
  test is the assertion."
  (:require [re-frame.core         :as rf]
            [re-frame.registrar    :as registrar]
            [re-frame.schemas      :as schemas]
            [re-frame.trace        :as trace]
            ;; rf2-qwm0a — listener + buffer surface lives in
            ;; `re-frame.trace.tooling` (production-DCE split). The
            ;; probe touches it to keep both surfaces reachable so
            ;; their `interop/debug-enabled?` gates are tested in the
            ;; closure DCE pass, not surface-pruned before DCE runs.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.epoch        :as epoch]
            [re-frame.http-managed :as http-managed]
            [re-frame.views        :as views]
            [re-frame.machines]))

;; ---- trace listener API ---------------------------------------------------

(defn ^:export touch-trace! []
  ;; Reach into every documented trace API so :advanced keeps the surface
  ;; alive and we're testing the *body* gates, not surface-pruning.
  (trace-tooling/register-trace-cb! ::probe (fn [_ev] nil))
  (rf/emit-trace-event! :event :rf.probe/touched {:source :probe})
  (trace-tooling/remove-trace-cb! ::probe)
  ;; rf2-smee — trace ring buffer (Spec 009 §Retain-N).  These public
  ;; entry points must elide their bodies in production.
  (trace-tooling/configure-trace-buffer! {:depth 50})
  (let [_buf (trace-tooling/trace-buffer {:op-type :event})]
    nil)
  (trace-tooling/clear-trace-buffer!))

;; ---- schemas surface ------------------------------------------------------

(defn ^:export touch-schemas! []
  (rf/reg-app-schema [:user :name] :string)
  (schemas/validate-app-db! {:user {:name "ok"}})
  (schemas/validate-app-db! {:user {:name 42}}    :probe/event)
  (schemas/validate-event!  :probe/event [:probe/event 1] {:spec :int})
  (schemas/validate-cofx!   :probe/cofx :probe/event {} {:spec :map})
  (schemas/validate-fx!     :probe/fx :probe/event {} {:spec :map})
  (schemas/validate-sub-return! :probe/sub [:probe/sub] :foo {:spec :keyword}))

;; ---- registrar trace emit -------------------------------------------------

(defn ^:export touch-registrar! []
  ;; reg-event-db / dispatch-sync exercise registrar/register! and the
  ;; router/events/fx trace emit sites in one shot.
  (rf/reg-event-db :probe/init  (fn [_db _ev] {:counter 0}))
  (rf/reg-event-db :probe/inc   (fn [db _ev] (update db :counter inc)))
  (rf/reg-sub      :probe/count (fn [db _q] (:counter db)))
  (rf/dispatch-sync [:probe/init])
  (rf/dispatch-sync [:probe/inc])
  ;; Re-register to fire :rf.registry/handler-replaced.
  (rf/reg-event-db :probe/inc   (fn [db _ev] (update db :counter (fnil inc 0))))
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
  ;;   :rf.warning/decode-defaulted        (warning trace, both transports)
  ;;   :rf.http/aborted-on-actor-destroy   (info trace, rf2-wvkn cancellation cascade)
  ;;
  ;; All emit sites are wrapped in `(when interop/debug-enabled? ...)`,
  ;; so under :advanced + goog.DEBUG=false the branches DCE and the
  ;; string sentinels (e.g. "rf.http/retry-attempt") should NOT appear
  ;; in the production bundle.
  ;;
  ;; rf2-cdmle (supersedes rf2-omsae) — the canned-stub fxs
  ;; (`:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`)
  ;; moved out of `re-frame.http-managed`'s load-time side effects to a
  ;; sibling test-support namespace, `re-frame.http-test-support`. The
  ;; gate is no longer `(when interop/debug-enabled? ...)` but instead
  ;; the require boundary: production code paths must not require
  ;; `re-frame.http-test-support`. The elision-probe namespace MUST NOT
  ;; require it either — under :advanced + goog.DEBUG=false the
  ;; canned-stub fx-id string fragments must not appear in the
  ;; production bundle, and the gate that pins that absence is now
  ;; classpath / require-closure absence rather than DCE of a `(when
  ;; ...)` block. The same `scripts/check-elision.cjs` sentinels apply
  ;; — what changed is the source-of-truth for absence.
  ;;
  ;; The probe roots the dependency graph by:
  ;;   1. requiring re-frame.http-managed (forces its ns body to be
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
  (rf/reg-event-fx :probe/http-abort-touch
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
  ;; Per Tool-Pair §Time-travel and Spec 009 §`register-epoch-cb!`, the
  ;; epoch ns ships gated trace ops:
  ;;
  ;;   :rf.epoch/snapshotted                       (settle! after drain-empty)
  ;;   :rf.epoch/restored                          (restore-epoch happy path)
  ;;   :rf.epoch/restore-during-drain              (failure mode 2)
  ;;   :rf.epoch/restore-unknown-epoch             (failure mode 3)
  ;;   :rf.epoch/restore-schema-mismatch           (failure mode 4)
  ;;   :rf.epoch/restore-missing-handler           (failure mode 5)
  ;;   :rf.epoch/restore-version-mismatch          (failure mode 6)
  ;;   :rf.epoch/restore-non-ok-record             (rf2-v0jwt — failure mode 7)
  ;;   :rf.epoch/db-replaced                       (rf2-zq55 — reset-frame-db! happy path)
  ;;   :rf.epoch/reset-frame-db-during-drain       (rf2-zq55 — failure mode A)
  ;;   :rf.epoch/reset-frame-db-schema-mismatch    (rf2-zq55 — failure mode B)
  ;;   :rf.warning/epoch-redact-fn-exception       (rf2-wp70d — redact-fn throw)
  ;;
  ;; Every emit site sits inside `(when interop/debug-enabled? ...)`
  ;; (or guarded by an `if-not interop/debug-enabled?` early-return in
  ;; restore-epoch) so under :advanced + goog.DEBUG=false the bodies
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
  (rf/configure :epoch-history {:depth 10})
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
  (rf/configure :epoch-history
                {:redact-fn (fn [_record]
                              (throw (ex-info "probe-redact-throw" {})))})
  ;; Register an event we own here (not :probe/inc, which
  ;; touch-registrar! has already unregistered) and dispatch it so
  ;; settle! reaches `maybe-redact` with the throwing fn installed.
  ;; The catch branch fires `:rf.warning/epoch-redact-fn-exception`
  ;; under DEBUG=true, exercising the gated body's literal
  ;; reachability through an actual call site rather than relying
  ;; on require-closure alone.
  (rf/reg-event-db :probe/redact-fire (fn [_db _ev] {:redact :fired}))
  (rf/dispatch-sync [:probe/redact-fire])
  ;; Clear so subsequent probe sites (reset-frame-db! below,
  ;; on-frame-destroyed!) are not perturbed by the throwing fn.
  (rf/configure :epoch-history {:redact-fn nil})
  (rf/register-epoch-cb! ::probe-epoch (fn [_record] nil))
  (let [_history (rf/epoch-history :rf/default)]
    nil)
  (rf/remove-epoch-cb! ::probe-epoch)
  ;; Drive a restore failure-mode emit site so the unknown-epoch
  ;; sentinel has a path through a documented entry point. The
  ;; remaining failure ops survive via their literal occurrence in
  ;; the gated emit-restore-failure! call sites in re-frame.epoch.
  (rf/restore-epoch :rf/default 999999)
  ;; rf2-zq55 — reset-frame-db! is the Tool-Pair §Pair-tool-writes
  ;; surface. The body is gated by an `(if-not interop/debug-enabled?
  ;; false ...)` early-return — the success branch fires
  ;; :rf.epoch/db-replaced, the in-drain rejection fires
  ;; :rf.epoch/reset-frame-db-during-drain, the schema-mismatch
  ;; rejection fires :rf.epoch/reset-frame-db-schema-mismatch. All
  ;; three string fragments must elide under :advanced + goog.DEBUG=
  ;; false. Calling against a frame ID without forcing an actual
  ;; replace is enough — the literal sentinels live in the gated body.
  (rf/reset-frame-db! :rf/default {})
  ;; Reference epoch's lower-level entry points directly so the ns is
  ;; not pruned even before DCE looks at the gated bodies.
  (epoch/clear-history!)
  ;; rf2-sh5g6: clear-frame-history! is `defn-` (test-only seam); the
  ;; un-scoped `clear-history!` above already pins the namespace for
  ;; the elision walker.
  (epoch/clear-epoch-cbs!)
  (let [_cfg (epoch/current-config)]
    nil)
  ;; rf2-d656 — on-frame-destroyed! emits :rf.epoch.cb/silenced-on-frame-destroy
  ;; per (frame-id, cb-id) pair when a frame previously observed by a
  ;; register-epoch-cb! callback is destroyed. The whole body sits inside
  ;; `(when interop/debug-enabled? ...)`; the string fragment must elide
  ;; under :advanced + goog.DEBUG=false. Touch the entry point through
  ;; the public surface (frame destroy walks call into it via the
  ;; :epoch/on-frame-destroyed late-bind hook).
  (epoch/on-frame-destroyed! :rf/default))

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
  ;; Also touch the public mint / current-render-key entry points so
  ;; their bodies stay in the reachability graph (DCE only proves the
  ;; gated branches dead; the public surface itself remains).
  (let [_t (views/mint-instance-token!)
        _k (views/current-render-key)]
    nil)
  ;; Per Spec 004 §Plain Reagent fns and Spec 006 §Plain-fn-under-non-
  ;; default-frame warning (rf2-d3k3): the warn-once helper sits inside
  ;; `(when interop/debug-enabled? ...)`. Touch the helper's public
  ;; entry points so the reachability graph keeps the ns body — the
  ;; trace-emit branch itself is gated on `(some? (r/current-component))`
  ;; which is nil at probe time (no Reagent render in flight), so the
  ;; emit-site keyword is DCE'd from BOTH bundles by closure dataflow.
  ;; The browser-runner test
  ;; (re-frame.cross-spec-dom-cljs-test/plain-fn-under-non-default-frame) is
  ;; the load-bearing assertion that the gate fires under DEBUG=true;
  ;; production elision rests on the surrounding
  ;; `(when interop/debug-enabled? ...)` constant-folding to false the
  ;; same way every other warn site does.
  (rf/reg-sub :probe/sub (fn [_db _q] nil))
  (let [_r (rf/subscribe [:probe/sub])]
    (views/clear-plain-fn-warned-pairs!)
    (views/maybe-warn-plain-fn-under-non-default-frame!
      :rf/default [:probe/sub])
    nil))

;; ---- Spec 005 §Source-coord stamping — reg-machine macro (rf2-8bp3) -------

(defn ^:export touch-machines! []
  ;; Per Spec 005 §Source-coord stamping (rf2-8bp3) the reg-machine macro
  ;; walks the literal spec form at expansion time and emits a stamping
  ;; branch wrapped in `(if interop/debug-enabled? ...)`. Under :advanced
  ;; + goog.DEBUG=false the closure compiler folds the gate to false and
  ;; DCEs the entire `:rf.machine/source-coords` literal — every spec-
  ;; element string fragment must elide.
  ;;
  ;; The probe roots reachability for the machines surface by:
  ;;   1. requiring re-frame.machines (forces ns body — the late-bind
  ;;      hook registration, the :rf/machine sub, the spawn / destroy
  ;;      fx handlers — into the bundle);
  ;;   2. registering a machine via `rf/reg-machine` so the macro's
  ;;      gated source-coord-stamping branch is in reachable code, not
  ;;      just declared-but-dead.
  ;;
  ;; The sentinel string is the keyword `:rf.machine/source-coords` (the
  ;; spec map key the macro injects under the gate). It must NOT appear
  ;; in the production bundle.
  (rf/reg-machine :rf.probe/machine
    {:initial :idle
     :guards  {:never? (fn [_ _] false)}
     :actions {:noop   (fn [_ _] {})}
     :states  {:idle {:on {:tick {:target :idle :guard :never? :action :noop}}}}}))

;; ---- rf2-ts1a: call-site source-coord macros ------------------------------
;;
;; The `dispatch` / `dispatch-sync` / `subscribe` / `inject-cofx` macros stamp
;; an `:rf.trace/call-site` map at compile time (per Q3=B dev-only elision).
;; Under `:advanced` + `goog.DEBUG=false`, the macro's
;; `(if interop/debug-enabled? <stamp-branch> <no-stamp-branch>)` expansion
;; folds away — the stamp branch DCE's and the literal map vanishes from
;; the bundle. The keyword `:rf.trace/call-site`'s string fragment must
;; NOT appear in the production bundle.

(defn ^:export touch-call-site-macros! []
  ;; Each macro form below emits a literal `:rf.trace/call-site` map
  ;; under DEBUG=true. The stamp-branches must DCE under DEBUG=false.
  (rf/reg-event-db :probe/cs-event (fn [db _ev] db))
  (rf/reg-sub      :probe/cs-sub   (fn [db _q] db))
  (rf/reg-cofx     :probe/cs-cofx  (fn [ctx] ctx))
  ;; dispatch + dispatch-sync macros
  (rf/dispatch [:probe/cs-event])
  (rf/dispatch-sync [:probe/cs-event])
  ;; subscribe macro
  (let [_r (rf/subscribe [:probe/cs-sub])] nil)
  ;; inject-cofx macro (interceptor construction site)
  (let [_i (rf/inject-cofx :probe/cs-cofx)] nil))

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
  ;; Reference trace/emit! directly through the trace ns alias so its
  ;; body, not just the public re-frame.core re-export, is reachable.
  (trace/emit! :event :rf.probe/direct-touch {:source :probe}))
