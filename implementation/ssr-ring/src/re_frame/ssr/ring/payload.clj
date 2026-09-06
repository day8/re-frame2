(ns re-frame.ssr.ring.payload
  "Hydration payload construction for the Ring host adapter.

  Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/runtime-db` and `:rf/schema-digest`.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` —
  see that namespace for the contract. The Ring host adapter validates
  the policy at handler-construction time via `validate-policy-opts!`
  so misconfigured deployments fail at boot, not at first request. The
  `:rf/runtime-db` slice contains serializable durable runtime facts;
  transient side channels are excluded.

  Version resolution (`:rf/version`) and the canonical payload
  assembly live once in `re-frame.ssr.payload-policy`,
  shared verbatim with the streaming SSR path
  (`re-frame.ssr.streaming/build-final-payload`). This namespace owns
  only the non-streaming wrapper: project the handed-in `app-db` +
  runtime-db, then assemble."
  (:require [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

(set! *warn-on-reflection* true)

(defn build-payload
  "Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/runtime-db`, `:rf/schema-digest`, and
  `:rf/head-hash`, the separately client-reconstructible head-model hash.
  Pass it through `policy-opts`; nil omits the key. Schema-digest is supplied
  by the caller when their app participates in the schema-digest
  check; nil otherwise. Version source-of-truth:
  `re-frame.ssr.payload-policy/resolve-version` — caller opt wins,
  falling back to the SSR-owned `payload-policy/pattern-protocol-version`
  constant so server and client read from the same source.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy`: callers MUST declare `:payload`
  as either a vector allowlist of top-level keys (recommended) or the
  keyword `:rf.ssr.payload/whole-app-db` (explicit opt-in to shipping
  the whole `app-db`). Absence throws
  `:rf.error/ssr-missing-payload-policy`. The host adapter validates
  at handler-construction time so misconfigured deployments fail at
  boot, not at first request.

  The optional `runtime-db` arg is the frame's live
  runtime-db value; it is projected via `payload-policy/project-runtime-db`
  under the EXPLICIT `frame-id` (rf2-f02diw — the same target the app-db
  projection uses, never the ambient scope), yielding the serializable durable
  slice — machine snapshots, route `:current` slice, elision declarations, SSR
  metadata — that rides the payload as `:rf/runtime-db` so the client
  `:rf/hydrate` handler installs a coherent frame-state. nil / empty runtime-db
  omits the optional key.

  The `frame-id` arg is the REAL per-request PROJECTION frame — it drives
  `project-app-db-egress` / `project-runtime-db` (frame-scoped, fail-closed
  egress classification). The WIRE `:rf/frame-id` is a SEPARATE concern
  (rf2-lm2yzy): it is sourced from the optional `:client-frame-id`
  policy-opt (a STABLE id both sides agreed on ahead of time) and defaults
  to nil — an anonymous per-request server frame OMITS `:rf/frame-id`
  rather than stamping its throwaway gensym, which the client's hydrate
  guard would reject as `:rf.error/hydration-frame-id-mismatch` on every
  page. A deployment that wants a wire id passes `:client-frame-id`.

  The non-streaming wrapper over the shared
  `re-frame.ssr.payload-policy/build-payload`: it is handed `app-db` +
  `runtime-db` directly (the streaming path reads them from the live frame
  instead), projects them under the explicit `frame-id`, then assembles
  the canonical payload."
  ([frame-id app-db render-hash policy-opts]
   (build-payload frame-id app-db nil render-hash policy-opts))
  ([frame-id app-db runtime-db render-hash {:as policy-opts}]
   (rf.ssr.payload-policy/build-payload
    ;; WIRE :rf/frame-id — the stable, ahead-of-time client id, or nil to omit
    ;; (never the per-request projection gensym). Decoupled per rf2-lm2yzy.
    (:client-frame-id policy-opts)
    ;; Apply the allowlist before the frame-scoped hydration-egress projection,
    ;; so classified values inside the surviving slice are still elided. The
    ;; projection target is the REAL per-request frame-id.
    (rf.ssr.payload-policy/project-app-db-egress
     (rf.ssr.payload-policy/apply-policy app-db policy-opts)
     frame-id)
    render-hash
    ;; rf2-f02diw — project the runtime-db under the EXPLICIT carried `frame-id`
    ;; (the same target the `project-app-db-egress` above uses), NOT an ambient
    ;; one. Mirrors the streaming builder (`streaming/build-final-payload`,
    ;; rf2-3fc89f.15): the non-streaming wrapper must not rely on a MATCHING
    ;; `rf/with-frame` being ambient at the build site to project classified
    ;; route / machine / resource runtime state under the right frame's policy.
    ;; The host's request-frame binding stays a correctness convenience for other
    ;; code, never the projector's target authority (finishing the clean break).
    (assoc policy-opts :runtime-db (rf.ssr.payload-policy/project-runtime-db runtime-db frame-id)))))
