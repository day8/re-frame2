(ns re-frame.ssr.ring.payload
  "Hydration payload construction for the Ring host adapter.

  Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/schema-digest`.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9) —
  see that namespace for the contract. The Ring host adapter validates
  the policy at handler-construction time via `validate-policy-opts!`
  so misconfigured deployments fail at boot, not at first request."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.ssr.payload-policy :as payload-policy]))

(set! *warn-on-reflection* true)

(def ^:private default-pattern-protocol-version
  "The v1 pattern-protocol version stamp (per Spec-Schemas
  §`:rf/hydration-payload` line 1839 — \"integer; v1 = 1\"). Used as the
  terminal fallback in `resolve-version` so the canonical payload key
  is always present (Malli `:int` slot, not `:optional`)."
  1)

(defn resolve-version
  "Pick the `:rf/version` value to ship in the hydration payload. The
  resolution order is:

    1. An explicit `:version` opt from the caller (host-supplied stamp;
       wins so test fixtures and apps that ship their own version source
       stay in control).
    2. The framework-global `:rf2/runtime-version` late-bind hook — the
       same source the client-side `:rf.ssr/check-version` fx reads. When
       the host registers this hook at boot, both sides of the wire pin
       the same value with no further wiring (per Spec 011 §The hydration
       payload + §fx-input shape + Conventions §Late-bind hook key
       grammar).
    3. `default-pattern-protocol-version` (v1 = 1) — the canonical schema
       slot is required (per Spec-Schemas §`:rf/hydration-payload`), so a
       terminal numeric fallback is structurally necessary. The
       check-version fx still no-ops cleanly on the client when neither
       side has the hook registered (matched value → no mismatch).

  Audit rf2-asmj1 S8 / cluster rf2-l8fi6: prior to this the adapter
  hard-coded `(or version 1)` inline, which silently disagreed with
  whatever the client-side `:rf2/runtime-version` hook returned and
  defeated the version-mismatch check on every host that hadn't passed
  `:version` explicitly. Threading both sides through the same hook
  eliminates the silent-divergence trap; the terminal `1` is the v1
  pattern-protocol stamp, named instead of inlined so the convention
  travels back to Spec-Schemas via search."
  [explicit-version]
  (or explicit-version
      (when-let [f (late-bind/get-fn :rf2/runtime-version)]
        (f))
      default-pattern-protocol-version))

(defn build-payload
  "Per Spec 011 §The hydration payload — emit the four canonical keys
  (`:rf/version`, `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`)
  plus the optional `:rf/schema-digest`. Schema-digest is supplied
  by the caller when their app participates in the schema-digest
  check; nil otherwise. Version source-of-truth: `resolve-version`
  above — caller opt wins, falling back to the `:rf2/runtime-version`
  late-bind hook so server and client read from the same source.

  The `:rf/app-db` slice is projected per the explicit, fail-closed
  policy in `re-frame.ssr.payload-policy/apply-policy` (rf2-gtgf9):
  callers MUST declare `:payload-keys` (allowlist, recommended) or
  `:payload-policy :rf.ssr.payload/whole-app-db` (explicit opt-in to
  shipping the whole `app-db`). Absence of both throws
  `:rf.error/ssr-missing-payload-policy`. The host adapter validates
  at handler-construction time so misconfigured deployments fail at
  boot, not at first request."
  [frame-id app-db render-hash {:keys [version schema-digest] :as policy-opts}]
  (let [db-slice (payload-policy/apply-policy app-db policy-opts)]
    (cond-> {:rf/version     (resolve-version version)
             :rf/frame-id    frame-id
             :rf/app-db      db-slice
             :rf/render-hash render-hash}
      schema-digest (assoc :rf/schema-digest schema-digest))))
