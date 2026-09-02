(ns re-frame.hicasso.server
  "Hicasso on the server: one request in, one document out, rendered by
  the Hicasso runtime itself under Node's `react-dom/server`. The optional
  module `docs/core/hicasso/18-ssr-and-hydration.md` is written against;
  nothing in `re-frame.hicasso` names it, so a browser build that never
  requires it carries none of it
  (`implementation/hicasso/scripts/check_optional_module_reachability.py`).

      (ns app.server
        (:require [re-frame.hicasso.server :as server]))

      (:document (server/render {:hiccup [views/page {}] …}))

  `render` is the door. `payload-script` and `document` are the
  composition helpers a host needs to rebuild the envelope without
  writing a second renderer; the roster and the argument for it are
  naming-ledger rows 22 and 50
  (`docs/design/hicasso/product/naming-ledger.md`). The determinism
  probe over `render` is the test kit's
  (`re-frame.hicasso.test.server/render-twice`), not this door's.

  There is ONE renderer. The server runs the same runtime and the same
  codec under `renderToString`, so hydration parity holds by construction
  rather than by claim: every shell hands its server snapshot to
  `useSyncExternalStore`, so nothing subscribes; nothing commits or runs
  an effect; and the whole render is one synchronous call, which is what
  makes a per-request frame and the render-extent globals safe across
  concurrent requests (`docs/design/hicasso/product/globals.md`, §Request
  scope, S1–S3). Streaming is out of scope — absent, not deferred —
  because a render that awaits is the one change that would put S3 at
  risk.

  Two facts the module exists for, both at `render`: the element is
  `impl.mount/tree`'s, the same fork the hydrating client root adopts, so
  a `useId` React derives from tree position agrees on both sides of the
  wire (`docs/design/hicasso/product/dispositions.md`, HS-11 obstruction
  2); and the adoption window is open around the render, so
  presence-managed children render `:present` on both halves. This is an
  adoption-tier root and ships no `:rf/render-hash`, by Spec 011's tiering
  (`docs/design/hicasso/studio/ssr-spike-witness.md`). It is not a
  production HTTP host: the response contract is `re-frame.ssr.ring`'s."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.ssr.constants :as ssr-constants]
            [re-frame.ssr.html-helpers :as ssr-html]
            [re-frame.ssr.payload-policy :as payload-policy]
            ["react-dom/server" :as rdom-server]))

;; ---------------------------------------------------------------------------
;; The per-request frame
;; ---------------------------------------------------------------------------

(defn- fresh-frame-id
  "A frame id no other request holds: a `gensym` under the `hicasso.ssr`
  keyword namespace, unique in this process, destroyed before `render`
  returns and never on the wire.

  DO NOT RENAME the `hicasso.ssr` namespace.
  `implementation/hicasso/scripts/check_bundle_isolation.cjs` pins the
  source literal `(keyword \"hicasso.ssr\"` as the premise for the server
  module's zero-rent sentinel — a runtime argument `:advanced` can neither
  rename nor drop while `render` is reachable — and reds on the rename by
  design."
  []
  (keyword "hicasso.ssr" (str (gensym "request-"))))

(defn- setup-events
  "The frame's construction-time setup vector: `[:rf/set-db snapshot]`
  when a snapshot was given (the framework's reserved seeding event,
  EP-0027), then `initial-events` in the order given."
  [snapshot initial-events]
  (cond-> []
    (some? snapshot)     (conj [:rf/set-db snapshot])
    (seq initial-events) (into initial-events)))

;; ---------------------------------------------------------------------------
;; The document envelope
;; ---------------------------------------------------------------------------

(defn payload-script
  "The id-pinned `<script type=\"application/edn\">` carrying the
  already-`pr-str`'d hydration payload.

  Byte-for-byte the shape `re-frame.ssr.ring.shell/payload-script-tag`
  emits, and it must stay that way: the id is
  `re-frame.ssr.constants/payload-script-id`, the client bootstrap's
  `getElementById` contract, and the body goes through the framework's
  EDN-aware escaper, which keeps `<` readable in token position, escapes
  it inside string literals, and fails loud on a genuine `</script`
  breakout. That shell namespace is JVM-only, so the three lines are
  re-spelled here on the two shared constants rather than required."
  [payload-edn]
  (str "<script id=\"" ssr-constants/payload-script-id "\" type=\"application/edn\">"
       (ssr-html/escape-edn-script-body payload-edn)
       "</script>"))

(defn document
  "One page: the app root's inner `html`, then the payload script, then
  the bootstrap `<script src>` last — the order `ssr-ring`'s
  non-streaming shell writes. No `data-rf-render-hash` on the app root:
  that marker is the hiccup tier's (see the namespace docstring)."
  [{:keys  [html app-element-id script-src title]
    script :payload-script}]
  ;; `or`, not `:or` — a caller threading `nil` through for an option it
  ;; did not set supplies the KEY, so a destructuring default never fires
  ;; and the page would silently get `id=""`.
  (str "<!DOCTYPE html>"
       "<html lang=\"en\">"
       "<head><meta charset=\"utf-8\"><title>"
       (ssr-html/escape-html (or title "Hicasso SSR")) "</title></head>"
       "<body>"
       "<div id=\"" (ssr-html/escape-attr (or app-element-id "app")) "\">"
       html
       "</div>"
       script
       (when script-src
         (str "<script src=\"" (ssr-html/escape-attr script-src) "\"></script>"))
       "</body></html>"))

;; ---------------------------------------------------------------------------
;; The render entry
;; ---------------------------------------------------------------------------

(defn- render-options
  "`renderToString`'s options object, or nil: one key, React's own
  `identifierPrefix`, handed over untouched exactly as `impl.mount`
  hands it to `hydrateRoot`. Written through `unchecked-set` as a string
  key so Closure cannot rename it under `:advanced` — a renamed
  `identifierPrefix` is a prefix React never sees."
  [identifier-prefix]
  (when (some? identifier-prefix)
    (let [o #js {}]
      (unchecked-set o "identifierPrefix" identifier-prefix)
      o)))

(defn render
  "Render one request. Returns

      {:frame-id       the per-request gensym (destroyed by the time you
                       read it — it is here to be asserted on, not used)
       :html           the app root's INNER markup
       :payload        the `:rf/hydration-payload` map
       :payload-edn    that map, `pr-str`'d
       :payload-script the `__rf_payload` <script> element
       :document       the whole page}

  `opts`:

      :hiccup            REQUIRED. The root hiccup form.
      :snapshot          a map seeded whole through `:rf/set-db`.
      :initial-events    ordinary events, run after the snapshot.
      :payload           REQUIRED: `re-frame.ssr.payload-policy`'s
                         fail-closed contract verbatim — a non-empty
                         allowlist of top-level app-db keys, or
                         `:rf.ssr.payload/whole-app-db` as an explicit
                         opt-in. Absent, the framework's own validator
                         raises `:rf.error/ssr-missing-payload-policy`.
      :client-frame-id   the STABLE wire `:rf/frame-id`, or absent to
                         omit the key (the anonymous-server-frame shape).
                         Never the per-request gensym, which would
                         guarantee `:rf.error/hydration-frame-id-mismatch`.
      :identifier-prefix React's `identifierPrefix`. The hydrating root
                         must be handed the same string, or every
                         `useId` in the tree diverges.
      :app-element-id    :script-src  :title   the document envelope's.
      :frame-opts        merged UNDER the id and the setup vector, so a
                         request needing `:images`, `:url-strategy` or
                         `:fx-overrides` declares them the way any other
                         frame does. `:id` and `:initial-events` are
                         this module's and cannot be overridden.
      :version           :schema-digest   passed to `build-payload`.

  The first eight are the spellings `18-ssr-and-hydration.md` teaches
  (naming-ledger row 22, which settles them as a set).

  One fresh frame per request, and the adoption window open around the
  render; the window is closed and the frame destroyed in a `finally`, in
  that order, so a render that threw leaks no more than one that returned."
  [{:keys [hiccup snapshot initial-events payload frame-opts client-frame-id
           identifier-prefix version schema-digest app-element-id script-src title]}]
  (let [frame-id (fresh-frame-id)
        ;; Per REQUEST and reachable from nothing else — never a
        ;; module-level flag, which would let one request's throw leave
        ;; every later request born-present.
        window   (roots/open-adoption-window!)]
    (try
      (rf/make-frame (assoc frame-opts
                            :id             frame-id
                            :initial-events (setup-events snapshot initial-events)))
      (let [;; The hiccup as written, through the same `tree` the hydrating
            ;; door calls: `:adoption` is what it branches on, so this is
            ;; the Fragment-plus-closer tree `hydrate-root!` will adopt,
            ;; position for position.
            element     (mount/tree {:frame frame-id :adoption window} hiccup)
            ropts       (render-options identifier-prefix)
            html        (if ropts
                          (rdom-server/renderToString element ropts)
                          (rdom-server/renderToString element))
            policy-opts (cond-> {:payload payload}
                          (some? client-frame-id) (assoc :client-frame-id client-frame-id)
                          (some? version)         (assoc :version version)
                          (some? schema-digest)   (assoc :schema-digest schema-digest))
            payload-map (payload-policy/build-payload
                          ;; WIRE id — the caller's stable one or nil.
                          ;; NEVER `frame-id`.
                          (:client-frame-id policy-opts)
                          (payload-policy/project-app-db-egress
                            (payload-policy/apply-policy (rf/app-db-value frame-id) policy-opts)
                            ;; PROJECTION frame — the real per-request one.
                            frame-id)
                          ;; NO RENDER HASH — nil, and `build-payload`
                          ;; omits the key.
                          nil
                          policy-opts)
            payload-edn (pr-str payload-map)
            script      (payload-script payload-edn)]
        {:frame-id       frame-id
         :html           html
         :payload        payload-map
         :payload-edn    payload-edn
         :payload-script script
         :document       (document {:html           html
                                    :payload-script script
                                    :app-element-id app-element-id
                                    :script-src     script-src
                                    :title          title})})
      (finally
        ;; The window first — `destroy-frame!` may itself throw, and the
        ;; window must already be shut when it runs.
        (roots/close-adoption-window! window)
        (rf/destroy-frame! frame-id)))))
