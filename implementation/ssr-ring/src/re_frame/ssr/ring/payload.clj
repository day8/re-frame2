(ns re-frame.ssr.ring.payload
  "Hydration payload construction for the Ring host adapter.

  Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/schema-digest`.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9) —
  see that namespace for the contract. The Ring host adapter validates
  the policy at handler-construction time via `validate-policy-opts!`
  so misconfigured deployments fail at boot, not at first request.

  Version resolution (`:rf/version`) and the canonical four-key payload
  assembly live once in `re-frame.ssr.payload-policy` (rf2-8wrzz.4),
  shared verbatim with the streaming SSR path
  (`re-frame.ssr.streaming/build-final-payload`). This namespace owns
  only the non-streaming wrapper: project the handed-in `app-db` per the
  policy, then assemble."
  (:require [re-frame.ssr.payload-policy :as payload-policy]))

(set! *warn-on-reflection* true)

(defn build-payload
  "Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/schema-digest`. Schema-digest is supplied
  by the caller when their app participates in the schema-digest
  check; nil otherwise. Version source-of-truth:
  `re-frame.ssr.payload-policy/resolve-version` — caller opt wins,
  falling back to the `:rf2/runtime-version` late-bind hook so server
  and client read from the same source.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9):
  callers MUST declare `:payload-keys` (allowlist, recommended) or
  `:payload-policy :rf.ssr.payload/whole-app-db` (explicit opt-in to
  shipping the whole `app-db`). Absence of both throws
  `:rf.error/ssr-missing-payload-policy`. The host adapter validates
  at handler-construction time so misconfigured deployments fail at
  boot, not at first request.

  The non-streaming wrapper over the shared
  `re-frame.ssr.payload-policy/build-payload`: it is handed `app-db`
  directly (the streaming path reads it from the live frame instead),
  projects it, then assembles the canonical payload."
  [frame-id app-db render-hash {:as policy-opts}]
  (payload-policy/build-payload
   frame-id
   (payload-policy/apply-policy app-db policy-opts)
   render-hash
   policy-opts))
