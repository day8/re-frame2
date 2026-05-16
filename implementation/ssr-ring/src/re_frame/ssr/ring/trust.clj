(ns re-frame.ssr.ring.trust
  "Trusted-shell-hook contract — naming + structural validation
  (rf2-o6ndb).

  Four shell-envelope opts cross the trust boundary into the rendered
  HTML response as RAW strings — no escaping, no validation, no
  sandbox:

    :head            — verbatim HTML inside `<head>...</head>`
    :body-end        — verbatim HTML before `</body>`
    :script-src      — written into `<script src=\"...\">` unescaped
    :app-element-id  — written into `<div id=\"...\">` unescaped

  These are TRUSTED-STRING opts in the same sense `:rf.server/redirect`
  is caller-trusted (Spec 011 §HTTP response contract / Security.md
  §Open-redirect mitigation): the framework's contract is that the
  caller composes them from caller-controlled data at handler-
  construction time (app boot decides what analytics tag / asset URL /
  doctype-extension to inject). Apps that wire any of the four from
  untrusted input — a CMS field, a tenant-admin form, a query-string
  parameter — accept an arbitrary-script-injection XSS vector. The
  framework's defence is naming the boundary and rejecting the
  structural shape mistake (a map / vector / symbol) at handler-
  construction time; the trust call itself is the caller's.

  The structured alternative for untrusted-customization use cases is
  the structured-fx surface (`:rf.server/set-header`, the head/meta
  registry — `reg-head` per Spec 011 §Head/meta) plus the registered-
  view surface (`reg-view*` for the bespoke admin-injectable
  subtree). Both run through the standard SSR emitter, which applies
  position-appropriate escaping (Security.md §XSS at output
  boundaries) at every leaf.

  Why a sibling namespace: both `re-frame.ssr.ring/ssr-handler` and
  `re-frame.ssr.ring.streaming/stream-handler` validate the same four
  opts at construction time — colocating the contract here avoids the
  circular-require between the streaming sub-namespace and the ring
  façade. Sibling to `re-frame.ssr.payload-policy` (the equivalent
  consolidation of the fail-closed hydration-payload policy contract
  per rf2-gtgf9)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def trusted-shell-string-opts
  "The four trusted-string opts the shell injects RAW into the
  rendered HTML. Construction-time validation gates structural shape
  only; the trust semantic is documented at the handler docstring and
  in Spec 011 §Trusted shell hook contract."
  [:head :body-end :script-src :app-element-id])

(defn validate-trusted-shell-opts!
  "Validate the four trusted-shell-hook opts are STRINGS (or nil).
  Surfaces `:rf.error/ssr-trusted-shell-opt-invalid` at handler-
  construction time on anything else — a map / vector / symbol /
  number is a structural error (the shell would throw a
  `ClassCastException` deep in the rendering path otherwise; this
  gives a clean structured error at boot).

  Strings are accepted unchanged — the framework names the boundary
  but does not gate the content (per the trusted-string contract).
  Apps wiring these from untrusted input are responsible for upstream
  escaping; see Spec 011 §Trusted shell hook contract for the
  structured alternative (`reg-head` + `reg-view*` + `:rf.server/*` fx)
  for untrusted-customization use cases. Per rf2-o6ndb.

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly, like `payload-policy/validate-policy-opts!`."
  [opts]
  (doseq [k trusted-shell-string-opts]
    (when (contains? opts k)
      (let [v (get opts k)]
        (when (and (some? v) (not (string? v)))
          (throw (ex-info ":rf.error/ssr-trusted-shell-opt-invalid"
                          {:reason   (str "ssr-handler / stream-handler " (pr-str k)
                                          " must be a string (or nil) — the four "
                                          "trusted shell-hook opts ("
                                          (str/join ", " (map pr-str trusted-shell-string-opts))
                                          ") are injected RAW into the rendered HTML "
                                          "envelope (trusted-string contract per "
                                          "Spec 011 §Trusted shell hook contract). "
                                          "Got: " (pr-str (type v)) ".")
                           :opt-key  k
                           :got      v
                           :got-type (type v)
                           :recovery :supply-string-or-nil}))))))
  opts)
