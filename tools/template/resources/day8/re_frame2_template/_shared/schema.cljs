(ns {{namespace}}.schema
  "App-db schema — typed-at-boundaries posture (Spec 010).

   re-frame2 attaches schemas at paths. The framework validates writes
   against every registered path-schema after each handler completes a
   state mutation. A non-conforming write rolls back the `:db` effect
   and emits a structured `:rf.error/schema-validation-failure` trace —
   the runtime treats it as a failed dispatch (flows don't evaluate,
   `:fx` doesn't walk), but the queue continues to drain.

   The starter app registers ONE schema at the empty path `[]` — the
   whole-app-db form (`get-in`/`assoc-in` semantics: `[]` means \"the
   whole map\"). For multi-feature apps, split per-feature schemas at
   their prefix paths (`[:cart]`, `[:auth]`, ...) per
   [Spec 010 §`app-db` schemas — path-based](https://github.com/day8/re-frame2/blob/main/spec/010-Schemas.md#app-db-schemas--path-based)
   and the [Feature-modularity prefix
   convention](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md#feature-modularity-prefix-convention).

   The schemas artefact (`day8/re-frame2-schemas`) and the Malli adapter
   (`re-frame.schemas.malli`) are loaded as side-effects in events.cljs
   — that publishes Malli's `validate` and `explain` into the framework's
   late-bind hook table before any `reg-app-schema` call runs.

   Schemas validate **in dev** (and on JVM unless production-hardened);
   the validation check elides automatically under `:advanced`
   `goog.DEBUG=false` builds — schema attachments stay in source but
   cost nothing in production hot paths.

   A schema validates **shape**; it does NOT classify durable app-db
   egress. Whether an app-db path is sensitive or large — and therefore
   redacted/elided at observation boundaries (Xray, Story, traces,
   off-box export) — is FRAME-owned policy declared on `reg-frame`, not
   re-attached here (Spec 015 §Schemas describe shape, not durable app-db
   egress policy: one owner, one route). See `core.cljs` (where the frame
   is registered) and README §Privacy / egress classification."
  (:require [re-frame.core :as rf]))

;; --- Whole-app-db schema ---------------------------------------------------
;;
;; A closed map: a typo like `:countr/value` (one missing `e`) is caught
;; at the boundary instead of producing a silent `nil` downstream. Open
;; vs closed is a team decision — open admits new keys mid-development;
;; closed catches typos. Best practice for a starter scaffold: closed.

(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; EP-0002 (carried-frame invariant, Spec 002 §Frame target resolution):
;; app-db schemas are FRAME-LOCAL — `reg-app-schema` targets a frame and the
;; runtime never synthesises one from absence. At namespace-load time no
;; frame is established, so this registration MUST NOT run as a load-time
;; side-effect (it would raise `:rf.error/no-frame-context`). Instead it is a
;; boot step: `core/init` calls `register-schema!` AFTER `reg-frame` makes the
;; app's `:rf/default` frame live, inside a `with-frame :rf/default` scope.
;; (Contrast events/subs above, which are frame-agnostic global registrations
;; and are fine at ns-load.)
(defn register-schema!
  "Attach the whole-app-db schema to the app's frame. Call once at boot,
   under the app's frame scope (see core/init)."
  []
  (rf/reg-app-schema [] {:schema CounterDb}))
