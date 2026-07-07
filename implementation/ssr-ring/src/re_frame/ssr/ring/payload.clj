(ns re-frame.ssr.ring.payload
  "Hydration payload construction for the Ring host adapter.

  Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/runtime-db` and `:rf/schema-digest`.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9) —
  see that namespace for the contract. The Ring host adapter validates
  the policy at handler-construction time via `validate-policy-opts!`
  so misconfigured deployments fail at boot, not at first request. The
  `:rf/runtime-db` slice is projected per `project-runtime-db` (EP-0001
  rf2-30kzz2 — the serializable durable runtime-db facts; transient side
  channels excluded).

  Version resolution (`:rf/version`) and the canonical payload
  assembly live once in `re-frame.ssr.payload-policy` (rf2-8wrzz.4),
  shared verbatim with the streaming SSR path
  (`re-frame.ssr.streaming/build-final-payload`). This namespace owns
  only the non-streaming wrapper: project the handed-in `app-db` +
  runtime-db, then assemble."
  (:require [re-frame.ssr.payload-policy :as payload-policy]))

(set! *warn-on-reflection* true)

(defn build-payload
  "Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/runtime-db`, `:rf/schema-digest`, and
  `:rf/head-hash` (rf2-1oxjxk — the separate client-reconstructible
  head-model hash; pass it through `policy-opts`' `:head-hash` key,
  computed via `re-frame.ssr.ring.lifecycle/render-head-hash`; nil
  omits the key). Schema-digest is supplied
  by the caller when their app participates in the schema-digest
  check; nil otherwise. Version source-of-truth:
  `re-frame.ssr.payload-policy/resolve-version` — caller opt wins,
  falling back to the `:rf2/runtime-version` late-bind hook so server
  and client read from the same source.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9,
  rf2-pffil single-opt consolidation): callers MUST declare `:payload`
  as either a vector allowlist of top-level keys (recommended) or the
  keyword `:rf.ssr.payload/whole-app-db` (explicit opt-in to shipping
  the whole `app-db`). Absence throws
  `:rf.error/ssr-missing-payload-policy`. The host adapter validates
  at handler-construction time so misconfigured deployments fail at
  boot, not at first request.

  EP-0001 (rf2-30kzz2): the optional `runtime-db` arg is the frame's live
  runtime-db value; it is projected via `payload-policy/project-runtime-db`
  (the serializable durable slice — machine snapshots, route `:current` slice,
  elision declarations, SSR metadata) and rides the payload as
  `:rf/runtime-db` so the client `:rf/hydrate` handler installs a coherent
  frame-state. nil / empty runtime-db omits the optional key.

  The non-streaming wrapper over the shared
  `re-frame.ssr.payload-policy/build-payload`: it is handed `app-db` +
  `runtime-db` directly (the streaming path reads them from the live frame
  instead), projects them, then assembles the canonical payload."
  ([frame-id app-db render-hash policy-opts]
   (build-payload frame-id app-db nil render-hash policy-opts))
  ([frame-id app-db runtime-db render-hash {:as policy-opts}]
   (payload-policy/build-payload
    frame-id
    ;; rf2-bt9kct — allowlist FIRST (`apply-policy`), THEN run the surviving
    ;; slice through the centralized `:rf.egress/ssr-hydration` projection
    ;; seeded at the request frame, so a frame-classified sensitive/large path
    ;; inside an allowlisted (or whole-app-db) slice redacts/elides as
    ;; defense-in-depth before it serializes into `:rf/app-db` (EP-0015 §14).
    (payload-policy/project-app-db-egress
     (payload-policy/apply-policy app-db policy-opts)
     frame-id)
    render-hash
    (assoc policy-opts :runtime-db (payload-policy/project-runtime-db runtime-db)))))
