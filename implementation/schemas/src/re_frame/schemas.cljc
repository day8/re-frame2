(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :schema metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  Per Spec 010 §Per-frame schemas, `app-db` schemas are **frame-scoped**:
  registered against the active frame at registration time and looked
  up per frame at validation time. Stories, multi-instance widgets,
  per-test fixtures need shape-flexibility — a stripped-down schema
  registered against `:story.foo/empty` does NOT bleed into another
  frame's contract. EP-0002 — context-required frame-local: `(reg-app-schema
  path schema)` resolves the frame through the carried-invariant scope
  chain (a `with-frame` / frame-provider scope, or a frame `:on-create`
  hook) and raises `:rf.error/no-frame-context` under no scope; `(reg-app-schema
  path schema {:frame frame-id})` names the frame explicitly (the *override*).

  Per Spec 010 §Non-Malli validators the validator/explainer fns are
  pluggable via `set-schema-validator!` / `set-schema-explainer!`. The
  default validator delegates to Malli; apps drop Malli by registering
  a different fn (or `nil` for a hard no-op).

  **Schema implies validation (rf2-v96fh).** Loading this artefact
  `:require`s `re-frame.schemas.malli`, which publishes Malli's
  `validate` / `explain` / `humanize` into the late-bind hook table at
  ns-load time. The CLJS reference therefore validates against the
  default Malli validator the moment a schema is registered — there is
  no inert \"registered a schema but it silently validates nothing\"
  state. (Pre-rf2-v96fh the adapter had to be required *separately* at
  app boot; an app that forgot the require registered schemas that
  soft-passed every value — a footgun where \"I registered a schema\"
  did NOT imply \"it validates.\")

  Because Malli is now wired by *the schemas artefact* (not by core),
  bundle isolation is preserved at the artefact boundary: an app that
  never `:require`s `re-frame.schemas` (the no-feature counter
  reference app) pays nothing for Malli. An app that DOES use schemas
  pays the Malli surface (~24 KB gzipped, Spec 010 §Bundle cost) — the
  deliberate tradeoff Ruling A accepts so registration always implies
  validation. Apps that want schemas-as-inert-data without the Malli
  surface call `(set-schema-validator! nil)` at boot to disable every
  validation site (Spec 010 §Worked example — installing a no-op
  validator); note that under static CLJS compilation requiring the
  artefact still loads Malli's body, so the nil opt-out disables
  validation *behaviour* but does not remove Malli from the bundle —
  the only Malli-free posture is not requiring the schemas artefact.

  Public façade over `re-frame.schemas.{validator,storage,walker,
  digest,validate}` — re-exports the symbols external consumers reach
  through (tests, `re-frame.core-schemas`, the late-bind hook table)
  and owns the late-bind hook publication."
  (:require [re-frame.late-bind :as late-bind]
            ;; rf2-v96fh — schema implies validation. Requiring the
            ;; schemas artefact pulls the Malli adapter so the default
            ;; validator is LIVE (publishes `:schemas/malli-validate`
            ;; etc. at ns-load); `reg-app-schema` then validates rather
            ;; than soft-passing into a silent no-op. The adapter ns
            ;; requires only `malli.core` + `malli.error` + late-bind —
            ;; no cycle back to this facade. Malli becomes a dependency
            ;; of the schemas artefact, NOT of core: an app that never
            ;; requires `re-frame.schemas` stays Malli-free (Spec 010
            ;; §Bundle cost; verified by the counter bundle-isolation
            ;; gate). Loaded for its ns-load side effect only.
            [re-frame.schemas.malli]
            [re-frame.schemas.digest :as digest]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validate :as validate]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]))

;; ---- raw schema atoms are NOT re-exported (encapsulated-only posture) ------
;;
;; The four authoritative atoms — `storage/schemas-by-frame` (the per-frame
;; registry) and `validator/{validator-fn,explainer-fn,printer-fn}` (the
;; pluggable validation bundle) — are deliberately NOT re-exported onto this
;; facade. The supported public surface is the encapsulated snapshot / restore
;; / clear API below; tests and fixtures go through it rather than reaching the
;; raw atoms.
;;
;; Posture (rf2-l5r974 ruling, option (a) end-state — reverses the earlier
;; rf2-1gm0o "public-by-design fixture primitive" framing). Exposing the atoms
;; as Vars was an encapsulation leak that is worse than aesthetic:
;;   1. `schemas-by-frame` is the authoritative store, so the rep cannot
;;      evolve without breaking ad-hoc consumers — a future companion
;;      side-table (or a change to the map shape itself) would have to be
;;      cleared through `clear-schemas-by-frame!`, but raw
;;      `(reset! schemas-by-frame {})` sites would silently skip it.
;;   2. The public `printer-fn` atom bypassed the never-nil invariant
;;      `run-printer` relies on with NO read guard — `(reset! printer-fn nil)`
;;      NPEs the digest path; the setters exist precisely to coerce
;;      nil → default.
;;
;; The supported encapsulated replacements (all below):
;;   - REGISTRY:  `snapshot-schemas-by-frame` / `restore-schemas-by-frame!` /
;;                `clear-schemas-by-frame!`.
;;   - BUNDLE:    `snapshot-schema-fns` / `restore-schema-fns!` (rf2-l4ljvr) +
;;                `set-schema-fns!` / `set-schema-{validator,explainer,printer}!`
;;                / `reset-schema-validator!`.
;;
;; The schemas artefact's OWN white-box tests (implementation/schemas/test/…)
;; may still reach `storage/schemas-by-frame` / `validator/validator-fn` via
;; those home namespaces directly — that is legitimate internal testing, not
;; public-facade use. Everything outside the schemas artefact uses the
;; encapsulated API. A guard test (`re-frame.schemas-atom-privacy-test`) pins
;; that the four atoms are not publicly resolvable from `re-frame.schemas`.

;; Validator / explainer / printer (rf2-froe + rf2-wla45). Each fn has
;; its own single-purpose setter (returning the single fn it installs);
;; `set-schema-fns!` installs the bundle atomically (rf2-13meg — the
;; honest bundle name) and returns the installed bundle map
;; `{:validate … :explain … :print …}` (rf2-qdtcx2 — a bundle setter
;; returns its bundle, not just the validator).
(def set-schema-validator!   validator/set-schema-validator!)
(def set-schema-explainer!   validator/set-schema-explainer!)
(def set-schema-printer!     validator/set-schema-printer!)
(def set-schema-fns!         validator/set-schema-fns!)
(def reset-schema-validator! validator/reset-schema-validator!)

;; Bundle-level snapshot / restore (rf2-l4ljvr). The validator/explainer/
;; printer companion to the registry's `snapshot-schemas-by-frame` /
;; `restore-schemas-by-frame!` (below). `snapshot-schema-fns` captures the
;; live bundle as one opaque value; `restore-schema-fns!` reinstalls it
;; (coercing a nil `:print` to the default like `set-schema-fns!`, so the
;; printer-never-nil invariant holds). Together with the registry pair they
;; let test-support capture+restore the WHOLE schema runtime through the
;; encapsulated API rather than reaching the raw `validator-fn` /
;; `explainer-fn` / `printer-fn` atoms.
(def snapshot-schema-fns     validator/snapshot-schema-fns)
(def restore-schema-fns!     validator/restore-schema-fns!)

;; Printer / walker memo clear hooks (rf2-17sqc). Test-support: the
;; printer + sensitive-walker memos are process-lifetime caches bounded
;; by the registered-schema cardinality (schemas register once at boot).
;; Tests that register many distinct fresh schemas clear them in fixture
;; teardown so the caches don't grow unbounded across the suite. See
;; [010 §Schema digest].
(def clear-edn-print-cache!       validator/clear-edn-print-cache!)
(def clear-sensitive-paths-cache! walker/clear-sensitive-paths-cache!)

;; Registration + per-frame query (Spec 010 §Per-frame schemas).
(def reg-app-schema       storage/reg-app-schema)
(def reg-app-schemas      storage/reg-app-schemas)
(def app-schema-at        storage/app-schema-at)
(def app-schema-meta-at   storage/app-schema-meta-at)
(def app-schemas          storage/app-schemas)
(def frame-schema-entries storage/frame-schema-entries)

;; Test-support snapshot / restore / clear.
(def snapshot-schemas-by-frame storage/snapshot-schemas-by-frame)
(def restore-schemas-by-frame! storage/restore-schemas-by-frame!)
(def clear-schemas-by-frame!   storage/clear-schemas-by-frame!)
(def on-frame-destroyed!       storage/on-frame-destroyed!)
(def clear-validator-unavailable-warned!
  storage/clear-validator-unavailable-warned!)
(def clear-walker-opaque-warned!
  storage/clear-walker-opaque-warned!)

;; Per-slot flag walker (rf2-nwv63 / rf2-kj51z / rf2-oghml). The
;; parameterised `walk-flagged-schema` recursion primitive stays internal
;; to `re-frame.schemas.walker`; the public surface is the two single-flag
;; entry points below (the late-bind hooks the machine `:data-schema`
;; bridge + story-mcp consume — NOT the app-db egress registry, which is
;; frame-owned per EP-0015 §8) plus the two sensitivity predicates (the
;; schema-validation-failure-trace redactor) — not the raw flag-key-
;; parameterised walker (rf2-7gclb: dead re-export dropped).
(def extract-large-paths-from-schema  walker/extract-large-paths-from-schema)
(def extract-sensitive-paths-from-schema walker/extract-sensitive-paths-from-schema)
(def schema-has-sensitive?            walker/schema-has-sensitive?)
(def schema-sensitive-at?             walker/schema-sensitive-at?)
;; rf2-vmhu4i — the `:large?` whole-schema predicate (mirror of
;; `schema-has-sensitive?` on the other per-slot flag) the validation
;; emit-site consults to elide a `:large?` slot's value to the
;; `:rf.size/large-elided` marker. rf2-u9bjgr — `schema-opaque?` is the
;; fail-closed predicate for a compiled / opaque `m/schema` value the
;; walker cannot introspect (its failure redacts as sensitive).
(def schema-has-large?                walker/schema-has-large?)
(def schema-opaque?                   walker/schema-opaque?)
;; rf2-ss06u.1 — `walker/sanitize-sensitive-path` (the `:path`-tag sanitiser
;; that scrubs value-bearing `:set`-element segments so a sensitive `:set`
;; element never ships verbatim in the structural `:path` tag) is an INTERNAL
;; redaction helper consumed only by `validate-app-schema!`; it is NOT a
;; public facade export, so it is deliberately not re-exported here.

;; Schema digest (Spec 010 §Digest algorithm).
(def app-schemas-digest digest/app-schemas-digest)

;; Validation entry points (Spec 010 §Validation order).
;; Per rf2-s2jgz the family is named on the kind axis —
;; validate-event! / validate-fx! / validate-sub! /
;; validate-app-schema!. The earlier validate-app-db! /
;; validate-sub-return! names were renamed for symmetry.
;;
;; The injection-time `validate-cofx!` fn was RETIRED per rf2-nkf4l3.
;; EP-0017 removed the ctx-mutating `inject-cofx` injection point its
;; `:where :cofx` trace described; the live cofx schema contract is
;; `re-frame.cofx/validate-recordable-value!` (a production hard error →
;; `:rf.error/cofx-value-invalid`), not a dev-only
;; `:rf.error/schema-validation-failure :where :cofx`. See Spec 010
;; §Validation order step 2.
(def validate-app-schema! validate/validate-app-schema!)
(def validate-event!      validate/validate-event!)
(def validate-fx!         validate/validate-fx!)
(def validate-sub!        validate/validate-sub!)

;; Production-side boundary-validation seam (rf2-r2uh).
(def validate-with-registered-fn validate/validate-with-registered-fn)
(def explain-with-registered-fn  validate/explain-with-registered-fn)
;; rf2-a5kzs / rf2-o69h5 — THE shared schema-aware redaction seam for
;; every validation-failure trace emitted outside the schemas artefact:
;; the boundary interceptor (`re-frame.spec`), machine `:data` validation,
;; the `:sub-override` path, and flow-output validation each build their
;; own failure tags and route them through this fn so a `:sensitive?`-marked
;; schema redacts the value-bearing slots identically to the dev-time
;; `validate-*!` hot path.
(def redact-validation-tags      validate/redact-validation-tags)

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.router, re-frame.cofx, re-frame.subs, re-frame.elision,
;; re-frame.epoch, re-frame.test-support, and re-frame.core-schemas
;; need to call into schema validation but cannot `:require` this
;; namespace without forcing the schemas artefact onto every consumer's
;; classpath (per rf2-p7va — schemas is optional). Publish entry points
;; through the late-bind hook registry. See re-frame.late-bind.
;;
;; Calls are written as literal `set-fn!` invocations with a literal
;; keyword (one per line) rather than collapsed into a data-driven
;; doseq, so the late-bind drift gate
;; (`re-frame.late-bind-drift-test`) can detect each publication via
;; regex — matching the convention of every other artefact's
;; publication block (flows / machines / routing / http / ssr).

;; Validation hot-path hooks (consumed by router / subs / epoch).
;; NB there is no `:schemas/validate-cofx!` hook — the injection-time cofx
;; validator was retired per rf2-nkf4l3 (EP-0017). The live cofx schema path
;; is `re-frame.cofx/validate-recordable-value!` →
;; `:rf.error/cofx-value-invalid`, which routes redaction through the
;; `:schemas/redact-validation-tags` hook below.
(late-bind/set-fn! :schemas/validate-app-schema! validate-app-schema!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub!        validate-sub!)
(late-bind/set-fn! :schemas/validate-fx!         validate-fx!)
(late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)
;; Frame-destroy cleanup hook (consumed by frame/destroy-frame!,
;; mirrors :machines/on-frame-destroyed! and :ssr/on-frame-destroyed).
(late-bind/set-fn! :schemas/on-frame-destroyed!  on-frame-destroyed!)

;; Boundary-validation seam (rf2-r2uh integration).
(late-bind/set-fn! :schemas/validate-with-registered-fn validate-with-registered-fn)
(late-bind/set-fn! :schemas/explain-with-registered-fn  explain-with-registered-fn)
;; rf2-a5kzs / rf2-o69h5 — shared schema-aware validation-failure redaction
;; hook (boundary interceptor + machine-data + sub-override + flow-output).
(late-bind/set-fn! :schemas/redact-validation-tags      redact-validation-tags)

;; Public-API re-export hooks (consumed by re-frame.core-schemas).
(late-bind/set-fn! :schemas/reg-app-schema        reg-app-schema)
(late-bind/set-fn! :schemas/reg-app-schemas       reg-app-schemas)
(late-bind/set-fn! :schemas/app-schema-at         app-schema-at)
(late-bind/set-fn! :schemas/app-schema-meta-at    app-schema-meta-at)
(late-bind/set-fn! :schemas/app-schemas           app-schemas)
(late-bind/set-fn! :schemas/app-schemas-digest    app-schemas-digest)
(late-bind/set-fn! :schemas/set-schema-validator! set-schema-validator!)
(late-bind/set-fn! :schemas/set-schema-explainer! set-schema-explainer!)
(late-bind/set-fn! :schemas/set-schema-printer!   set-schema-printer!)
(late-bind/set-fn! :schemas/set-schema-fns!       set-schema-fns!)

;; Schema-walker hooks: the pure-data per-slot `:large?` / `:sensitive?`
;; extractors. Per rf2-ynnq0 Option A — schemas owns the deep walker.
;;
;; EP-0015 §8 (rf2-d2r3um): these NO LONGER feed the app-db egress registry
;; (`re-frame.elision`) — schemas describe shape, not durable app-db egress
;; policy, which is now frame-owned. The hooks remain for the surviving
;; schema-prop owners: the machine `:data-schema` redaction bridge
;; (`re-frame.machines`, EP-0005) and story-mcp's tool-egress projector.
(late-bind/set-fn! :schemas/extract-large-paths-from-schema     extract-large-paths-from-schema)
(late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema extract-sensitive-paths-from-schema)

;; Test-support hooks (consumed by re-frame.test-support's
;; make-reset-runtime-fixture).
(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
