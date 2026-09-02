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

  `render` is the door for a host that owns the whole page.
  `render-body` is the door for one that owns everything EXCEPT the
  body: it takes a render-state projection the JVM already made, seeds
  a fresh frame from it, and answers the inner markup alone — the
  bounded Node sidecar's half of the ssr-node crossing (rf2-8arzr,
  shared contract S2). Neither is built on the other, and neither
  changes the other's spellings. `payload-script` and `document` are
  the composition helpers a host needs to rebuild the envelope without
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
            [re-frame.error :as error]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.ssr.constants :as ssr-constants]
            [re-frame.ssr.html-helpers :as ssr-html]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.render-state :as render-state]
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

;; ---------------------------------------------------------------------------
;; The recovered-error verdict — both doors
;; ---------------------------------------------------------------------------

(def ^:private render-listener-id
  "The id `render` registers its error-stream listener under. See
  `render-body-listener-id` for why the two doors do not share one."
  ::render-recovered-error)

(def ^:private render-body-listener-id
  "The id `render-body` registers its error-stream listener under.

  ONE id per door, and DIFFERENT ids deliberately. Two windows cannot
  overlap in the ordinary way — a render is one synchronous
  `renderToString` call on a single-threaded runtime, and the sidecar's
  isolate admits one at a time (`implementation/ssr-node`'s worker refuses
  a second with its service-saturated refusal) — but that argument is
  `render-body`'s alone, and `render` is a general host door with no
  saturation guard behind it. The one way to break it is re-entrancy: a
  view that called the other door mid-render. Sharing an id there would
  let the inner registration REPLACE the outer listener and the inner
  `finally` unregister it, leaving the outer render blind for the rest of
  its pass — the exact silent-wrong-page failure both doors exist to
  catch. Distinct ids leave both watches live and each door reads only its
  own atom, so the hole is closed by construction rather than by argument.
  A door re-entering ITSELF still reuses one id, where re-registering
  REPLACES and the outer render loses a listener rather than accumulating
  one."
  ::render-body-recovered-error)

(defn- refuse-recovered-render-error!
  "Fail the render, from the door named by `where`, because the runtime
  recorded an error it RECOVERED from during the pass.

  A sub that throws mid-render does not take the render down: the
  framework's built-in recovery yields `nil`, the view renders a hole, and
  the pass returns a string. A wrong page served as a success is worse
  than a loud failure, so the renderer throws instead.

  BOTH doors raise it, and each has to for a reason of its own — the
  common half being that nothing downstream can see the record:

    - `render-body` renders across the ssr-node crossing. The record is
      made in the NODE process and the projector is on the JVM, so nothing
      on the JVM can see it: the sidecar would answer 200 with a page
      whose content is quietly wrong. The sidecar turns this throw into
      its render-threw refusal, the JVM adapter turns that refusal into
      `:rf.error/ssr-node-refused`, and the request lands on the error
      view — the same destination a JVM-local path would reach, by a
      longer road.

    - `render` renders in the host's own process, where the natural
      expectation is that `re-frame.ssr`'s per-frame projection turns the
      record into a 5xx before any bytes ship. IT DOES NOT, and that
      expectation is exactly what rf2-ct24 and rf2-ypom were filed
      against. `error-emit-projection-listener`
      (`implementation/ssr/src/re_frame/ssr/error_listener.cljc`) buffers
      a record only when THREE things hold: the category is outside the
      recoverable-degradation skip set, the record carries a non-nil
      `:frame`, and that frame is a registered `:platform :server` frame.
      Hicasso's cold reads go through pure `compute-sub`, whose
      `:rf.error/sub-exception` is stamped `:frame nil` BY CONSTRUCTION —
      a pure fn has no frame in scope to stamp — so the second condition
      already drops it; and `render` does not set `:platform :server` on
      its per-request frame, so the third would drop it even if the second
      did not. The buffer stays empty and the host ships a 200.

  The verdict is deliberately BOUNDED and deliberately BLUNT: any error
  record inside the window fails the page, including one the application
  itself caught and continued past. The stream cannot tell a handled
  refusal from an unhandled one, and a check that tried to would be
  guessing on exactly the class of fault it exists to catch.

  The id is `:rf.error/ssr-render-failed` — the SSR family's render-time
  failure, reused rather than multiplied. Nothing about this failure is
  Hicasso's: it is the framework's render-failure category, raised from
  the hosts that have to raise it by hand."
  [where frame-id {:keys [error] :as record} recorded]
  (error/throw-error!
    :rf.error/ssr-render-failed
    where
    (str "the render completed but the runtime recorded " recorded
         " error" (when (not= 1 recorded) "s") " it recovered from during the "
         "pass — the first was " (pr-str error) " — so the markup is not what "
         "the application meant to render. Nothing downstream can see that "
         "record, so returning here would ship a wrong page as a success; the "
         "render is failed instead. Fix the surface the record names, not the "
         "renderer.")
    {:recovery :fail-the-render
     :extra    {:frame    frame-id
                :recorded recorded
                :record   record}}))

(defn- watch-recovered-errors!
  "Register a listener on the always-on ERROR-EMIT stream under
  `listener-id` for the extent of one render, and return the atom it
  fills.

  The always-on stream, not `re-frame.ssr`'s per-frame buffer, and the
  choice is measured rather than stylistic. That buffer is keyed by frame,
  and it is filled only for records that CARRY a routable server frame —
  while Hicasso's render reads its subscriptions through the pure
  `compute-sub` path (`impl.collector`'s cold read), whose
  `:rf.error/sub-exception` record is stamped `:frame nil` by construction
  and documented as such in `re-frame.subs` (\"a `compute-sub`-driven SSR
  harness that wants the per-frame projection must use the reactive
  `subscribe` path\"). A per-frame check therefore reads CLEAN on exactly
  the failure these entries exist to catch — measured, not reasoned: the
  first cut of `render-body` used the per-frame buffer and its refusal row
  rendered markup. `refuse-recovered-render-error!` sets out the other two
  conditions the buffer imposes, either of which drops the same record.

  The stream is what survives production hardening (`goog.DEBUG=false`),
  which is the posture Spec 011 mandates SSR run in, so this reads the
  same in a release bundle as it does under test. Scoping it to the render
  window is what makes an unattributed record safe to act on: a render is
  one synchronous call, so anything arriving inside the window came from
  the render.

  Registering ANY `:errors` listener takes corpus-wide ownership and
  quiets `re-frame.error-emit`'s unowned-error dev console fallback. That
  is a window one synchronous render wide, and the door re-raises what it
  saw, so nothing goes unreported."
  [listener-id]
  (let [!recorded (atom {:n 0 :first-record nil})]
    (rf/register-listener! :errors listener-id
                           (fn [record]
                             (swap! !recorded
                                    (fn [seen]
                                      {:n            (inc (:n seen))
                                       :first-record (or (:first-record seen) record)}))))
    !recorded))

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

  **A render that recorded a recovered error is FAILED, not returned** —
  `:rf.error/ssr-render-failed`, the same verdict and the same bounded
  window as `render-body`. See `watch-recovered-errors!` for what is
  watched and `refuse-recovered-render-error!` for why a hole in the page
  is worse than a refusal, and why the per-frame buffer a JVM-local host
  would reach for cannot see the record (rf2-ct24, rf2-ypom).

  One fresh frame per request, and the adoption window open around the
  render; the listener is unregistered, the window closed and the frame
  destroyed in a `finally`, in that order, so a render that threw leaks no
  more than one that returned."
  [{:keys [hiccup snapshot initial-events payload frame-opts client-frame-id
           identifier-prefix version schema-digest app-element-id script-src title]}]
  (let [frame-id  (fresh-frame-id)
        ;; Per REQUEST and reachable from nothing else — never a
        ;; module-level flag, which would let one request's throw leave
        ;; every later request born-present.
        window    (roots/open-adoption-window!)
        ;; Armed BEFORE the frame is made, so the setup vector is inside
        ;; the window too: a boot event that throws and recovers leaves the
        ;; page rendered over state the request never established, which is
        ;; the same silent wrong page a recovered sub gives.
        !recorded (watch-recovered-errors! render-listener-id)]
    (try
      (rf/make-frame (assoc frame-opts
                            :id             frame-id
                            :initial-events (setup-events snapshot initial-events)))
      (let [;; The hiccup as written, through the same `tree` the hydrating
            ;; door calls: `:adoption` is what it branches on, so this is
            ;; the Fragment-plus-closer tree `hydrate-root!` will adopt,
            ;; position for position.
            element (mount/tree {:frame frame-id :adoption window} hiccup)
            ropts   (render-options identifier-prefix)
            html    (if ropts
                      (rdom-server/renderToString element ropts)
                      (rdom-server/renderToString element))
            {:keys [n first-record]} @!recorded]
        ;; The verdict on the MARKUP, taken before the envelope is built: a
        ;; page being refused has no payload worth assembling, and building
        ;; one first would let `apply-policy`'s own fail-closed refusal
        ;; mask the render's.
        (when (pos? n)
          (refuse-recovered-render-error! 're-frame.hicasso.server/render
                                          frame-id first-record n))
        (let [policy-opts (cond-> {:payload payload}
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
                                      :title          title})}))
      (finally
        ;; Unregistered FIRST, so the teardown below is outside the window:
        ;; a destroy-time fault belongs to the process's logs, not to the
        ;; verdict on markup that has already been decided.
        (rf/unregister-listener! :errors render-listener-id)
        ;; The window before the frame — `destroy-frame!` may itself throw,
        ;; and the window must already be shut when it runs.
        (roots/close-adoption-window! window)
        (rf/destroy-frame! frame-id)))))

;; ---------------------------------------------------------------------------
;; The body-only entry
;; ---------------------------------------------------------------------------

(defn render-body
  "Render one request to the app root's INNER MARKUP and nothing else —
  no payload, no document, no head, no status. The body-only half of the
  ssr-node crossing (rf2-8arzr shared contract S2): the JVM owns the
  request frame, the boot-event drain, `__rf_payload`, the shell and the
  response; this renders the body from a projection of what the JVM had
  settled, and returns a string.

  `opts`:

      :hiccup            REQUIRED. The root hiccup form.
      :render-state      REQUIRED. The two-partition envelope
                         `{:rf/app-db {…} :rf/runtime-db {…}}` that
                         `re-frame.ssr.render-state/project` produced on the
                         JVM and `deserialize` read back here. Installed in
                         ONE atomic frame-state write by
                         `render-state/restore!`.
      :identifier-prefix React's `identifierPrefix`. The hydrating root must
                         be handed the same string, or every `useId` in the
                         tree diverges.
      :frame-opts        merged UNDER the id, the platform and the empty
                         setup vector, so a request needing `:images`,
                         `:url-strategy` or `:fx-overrides` declares them the
                         way any other frame does.

  Three things this deliberately does NOT do, each because the JVM already
  did it:

    - **no boot-event replay.** `:initial-events` is forced empty even when
      `frame-opts` carries one, exactly as `render` forces its own. The JVM
      drained the boot events and the projection IS the settled result;
      running them again here would be a second drain against a state that
      has already moved past them.
    - **no `:snapshot` re-seeding.** `render`'s `:rf/set-db` door seeds the
      app-db partition alone. The render state is BOTH partitions — the
      route slice and the machine snapshots live in runtime-db — so it
      installs through `restore!`, which is the surface that can write
      both at once.
    - **no payload.** `__rf_payload` is built on the JVM from the JVM's own
      app-db under the SEPARATE `:payload` policy. A renderer that built one
      would be a renderer deciding what the browser may see.

  The frame is `:platform :server`. That is not decoration: it is what
  gates client-only fx out of a render that has no client.

  **A render that recorded a recovered error is FAILED, not returned** —
  see `watch-recovered-errors!` for what is watched and
  `refuse-recovered-render-error!` for why a hole in the page is worse
  than a refusal.

  One fresh frame per request, and the adoption window open around the
  render; the listener is unregistered, the window closed and the frame
  destroyed in a `finally`, in that order, so a render that threw leaks no
  more than one that returned."
  [{:keys [hiccup render-state identifier-prefix frame-opts]}]
  (let [frame-id  (fresh-frame-id)
        window    (roots/open-adoption-window!)
        ;; Armed BEFORE the frame is made, so a fault in construction or in
        ;; the restore is inside the window too — those are part of the
        ;; pass, and a page rendered over a half-installed state is the same
        ;; silent wrong page a recovered sub gives.
        !recorded (watch-recovered-errors! render-body-listener-id)]
    (try
      (rf/make-frame (assoc frame-opts
                            :id             frame-id
                            :platform       :server
                            ;; This module's, and not overridable — see the
                            ;; docstring's first bullet.
                            :initial-events []))
      (render-state/restore! frame-id render-state)
      (let [element (mount/tree {:frame frame-id :adoption window} hiccup)
            ropts   (render-options identifier-prefix)
            html    (if ropts
                      (rdom-server/renderToString element ropts)
                      (rdom-server/renderToString element))
            {:keys [n first-record]} @!recorded]
        (when (pos? n)
          (refuse-recovered-render-error! 're-frame.hicasso.server/render-body
                                          frame-id first-record n))
        html)
      (finally
        ;; Unregistered FIRST, so the teardown below is outside the window:
        ;; a destroy-time fault belongs to the process's logs, not to the
        ;; verdict on markup that has already been decided.
        (rf/unregister-listener! :errors render-body-listener-id)
        ;; The window before the frame — `destroy-frame!` may itself throw,
        ;; and the window must already be shut when it runs.
        (roots/close-adoption-window! window)
        (rf/destroy-frame! frame-id)))))
