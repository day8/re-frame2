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

;; App-db schemas are frame-local, so core/init calls this after reg-frame
;; instead of registering it as a namespace-load side effect.
(defn register-schema!
  "Attach the whole-app-db schema to the app's frame. Call once at boot,
   under the app's frame scope (see core/init)."
  []
  (rf/reg-app-schema [] CounterDb))
