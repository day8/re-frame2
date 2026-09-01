(ns {{namespace}}.schema
  "App-db schema for validation at state-change boundaries.

   re-frame2 attaches schemas at paths. The framework validates writes
   against registered path schemas after a handler returns a `:db` effect.
   A non-conforming write is rolled back and reported as
   `:rf.error/schema-validation-failure`.

   The starter app registers ONE schema at the empty path `[]` — the
   whole-app-db form (`[]` means the whole map). For multi-feature apps,
   split schemas across feature prefix paths such as `[:cart]` and `[:auth]`.
   See
   [Spec 010 §`app-db` schemas — path-based](https://github.com/day8/re-frame2/blob/main/spec/010-Schemas.md#app-db-schemas--path-based)
   and the [Feature-modularity prefix
   convention](https://github.com/day8/re-frame2/blob/main/spec/Conventions.md#feature-modularity-prefix-convention).

   Requiring `re-frame.schemas` from events.cljs installs the default Malli
   validator before this schema is registered.

   Schemas validate **in dev** (and on JVM unless production-hardened);
   the validation check elides automatically under `:advanced`
   `goog.DEBUG=false` builds — schema attachments stay in source but
   cost nothing in production hot paths.

   A schema validates shape; it does NOT classify durable app-db egress.
   The event that writes a sensitive or large path declares that fact via
   commit-plane effects. See core.cljs and the README privacy section."
  (:require [re-frame.core :as rf]))

;; --- Whole-app-db schema ---------------------------------------------------
;;
;; A closed map catches misspelled keys at the boundary. Choose open schemas
;; deliberately when a feature needs to admit keys outside this contract.

(def CounterDb
  [:map {:closed true}
   [:counter/value :int]])

;; App-db schemas are frame-local, so core calls this explicitly instead of
;; registering it as a namespace-load side effect. The registration names the
;; app frame EXPLICITLY (`{:frame :rf/default}`), so it can run BEFORE the
;; `rf/frame-root` mount creates the frame — the frame's `:initial-events`
;; seed is then validated from the very first write.
;;
;; BECAUSE it is a fn call and not a load-time side effect, a hot reload of
;; THIS file re-evaluates `CounterDb` but re-registers nothing on its own.
;; That is why core's `^:dev/after-load` hook calls it again: editing the
;; schema above must reach the live frame without a page refresh. Re-
;; registering the same (frame, path) replaces the entry in place — the
;; frame, its app-db and the React root are untouched (Spec 010 §Multiple
;; schemas at the same path). When the live app-db no longer fits a
;; tightened schema, the runtime emits a `:rf.schema/violation` warning
;; and keeps running; it does not clear or rewind your state.
(defn register-schema!
  "Attach the whole-app-db schema to the app's frame. Called at boot and
   again from core's `^:dev/after-load` hook, so an edited schema takes
   effect on the next save rather than the next page refresh."
  []
  (rf/reg-app-schema [] {:frame :rf/default} CounterDb))
