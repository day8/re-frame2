(ns re-frame.bench.hicasso.ssr.entry
  "THE HICASSO SSR RENDER ENTRY (rf2-2rtt6.86) — one renderer, run in two
  places.

  The server engine is **the existing Hicasso runtime** under Node's
  `react-dom/server` `renderToString`. There is no second renderer here
  and there is not going to be one: hydration parity is by construction
  or it is a claim, and the difference is a whole class of bug the
  adapters have already paid for.

  ## Why running THIS runtime under renderToString is safe

  Verified in the 2026-08-04 SSR design programme rather than assumed:

  - Every shell passes `getServerSnapshot` (`arm1/runtime.cljs` — both
    [[re-frame.bench.hicasso.arm1.runtime/shell]] and its frame-prop
    twin hand `(.-snapshot entry)` as BOTH the client and server
    snapshot). Under `renderToString` React calls the server snapshot and
    **never calls `subscribe`**, so no registration is minted.
  - Every `sub` read is therefore the mutation-free cold probe
    (`runtime/cold-read!`): a pure compute against one coherent
    frame-state snapshot, with no cache entry, no ref-count, no watch and
    no disposal obligation.
  - Commits and effects never run. The whole render is HD-002's
    *abandoned render* by design, and the ledger discipline holds for the
    same reason it holds for a render React throws away in the browser.

  ## `defhost`'s `:ssr` POLICY NEEDS NOTHING HERE (rf2-2rtt6.92)

  A reader looking for where the server honours `:client-only` and
  `{:fallback …}` will not find it in this namespace, and that is the
  design rather than an omission. `mint-host!` mints ONE gate per
  declaration (`front/codec.cljs` — [[re-frame.bench.hicasso.front.codec/mint-host!]]),
  a component whose single `useSyncExternalStore` answers `false` from
  its SERVER snapshot, so under `renderToString` the gate renders the
  declaration's pre-walked fallback element — or nothing — **because it
  is the element's own type**. The policy is honoured by rendering.

  This entry did carry a second mechanism for one clause of a fortnight:
  a `ssr.host-policy/apply-policy` pre-walk over the hiccup handed in,
  written by rf2-2rtt6.86 while rf2-2rtt6.85 was still an open PR and
  clause 6 had to be real on main. It is retired, and the reason is
  worth one sentence because it is the argument against ever writing it
  again: **a pre-walk can only reach the tree it is handed**, and a host
  used inside a boundary BODY is not in that tree — that body runs
  inside `renderToString` and the codec's own crossing creates its
  element. The gate reaches both positions, costs no per-request tree
  rebuild, and cannot disagree with the client about a policy it never
  reads twice. Both positions are witnessed on the server HTML by
  `ssr/entry_cljs_test` (`a-host-with-no-declared-policy-renders-nothing`,
  `a-host-declaring-a-fallback-renders-the-fallback`, and
  `a-host-used-inside-a-defview-body-honours-its-policy`).

  ## What one request is

  [[render]] is the whole of it, and the order is the interesting part:

    1. a per-request frame under a **gensym id** — never a shared one, so
       two concurrent requests cannot read each other's app-db;
    2. seeded through the FRAMEWORK'S doors — `:initial-events`, and the
       reserved `:rf/set-db` for a snapshot handed in whole;
    3. rendered as `(provider frame (codec/root-element frame hiccup))`,
       the same two calls `arm1/mount/render!` makes in the browser,
       **inside an open adoption window** — see below;
    4. the payload built out of the FRAMEWORK'S OWN BYTES —
       `payload-policy/apply-policy` (the fail-closed `:payload`
       contract), `project-app-db-egress`, `build-payload`, and
       `html-helpers/escape-edn-script-body` under the pinned
       `__rf_payload` script id. Nothing Hicasso-specific touches the
       payload path (R0); this namespace supplies the app-db and gets out
       of the way;
    5. the adoption window closed and `destroy-frame!` run in a
       `finally`, so a render that threw leaks no more than one that
       returned.

  ## The wire frame-id is nil, deliberately

  `payload-policy/build-payload`'s first argument is the WIRE
  `:rf/frame-id`, which its docstring is explicit must be a stable id both
  ends agreed on ahead of time and **never a per-request server gensym**
  (rf2-lm2yzy: stamping the gensym guarantees
  `:rf.error/hydration-frame-id-mismatch` on every real page). The
  per-request gensym is the PROJECTION frame — it drives
  `project-app-db-egress` — and the wire id comes from the caller's
  `:client-frame-id` or is omitted. An absent `:rf/frame-id` is no
  conflict, which is precisely the anonymous-server-frame shape.

  ## Determinism

  Same bundle + same snapshot ⇒ byte-identical HTML. There is no `useId`
  anywhere in the lane, no randomness on the render path, and
  `renderToString` runs synchronously so the runtime's module-level render
  context is sound per request. [[render-twice]] renders the same request
  twice and compares the documents byte-for-byte; the driver hashes them.
  The gensym differs between the two renders and MUST NOT be observable —
  it never reaches the wire, which is one of the things the comparison
  proves.

  **No streaming.** `renderToPipeableStream` is out of scope per the
  adversarial review, and out of scope here means absent, not deferred.

  ## THE ADOPTION WINDOW IS OPEN AROUND `renderToString` (rf2-2rtt6.94)

  A server render is the FIRST HALF OF AN ADOPTION, so it runs in the
  same window the client's hydrating half does
  (`arm1/runtime.cljs` — [[re-frame.bench.hicasso.arm1.runtime/adopting?]]
  says as much: *\"A server render entry opens the same window around its
  own `renderToString`, so the two halves of an SSR route answer this
  identically\"*).

  Without it the two halves disagree. Presence starts a child at
  `:mounting` (`arm1/presence.cljs` — `(react/useState
  presence/initial)`) and applies that child's `::h/mounting` attribute
  overrides while it is in that phase, so a windowless server render
  ships the ENTER appearance — the class (and, in the shapes that use
  one, the `opacity: 0` style) an animation is about to move off. The
  hydrating client's first pass renders those same children `:present`
  (born-present, rf2-2rtt6.84), and React then reports a hydration
  mismatch on every presence-managed node. Measured on the corpus's
  `presence-mounting` row, which baked `toast--enter` twice before this
  window existed and is now pinned the other way by
  `the-server-render-ships-no-mounting-overrides`.

  The window is one flag and exactly one thing reads it — presence's
  born-present seeding. Opening it changes no transform, adds no fiber
  and installs no effect; the ordinary render path is untouched.

  **It closes in the `finally`, beside `destroy-frame!`, and that
  placement is the point**: the flag is module-level, so a render that
  threw with the window still open would leave the whole PROCESS
  adopting and every later request born-present — which is what
  [[re-frame.bench.hicasso.arm1.runtime/close-adoption-window!]]'s own
  docstring warns about. A per-request window is a per-request window
  for the same reason a per-request frame is.

  ## What this is NOT

  Not a production host. Spec 011's HTTP response contract — the response
  accumulator, cookies, redirects, CRLF fail-fast — stays `ssr-ring`'s,
  and no file under `implementation/ssr` or `implementation/ssr-ring` is
  touched by this bead. [[document]] mirrors `ssr-ring`'s own envelope
  shape closely enough to be recognisable and is a BENCH-LANE page, priced
  and ruled elsewhere."
  (:require [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.core :as rf]
            [re-frame.ssr.constants :as ssr-constants]
            [re-frame.ssr.hash :as ssr-hash]
            [re-frame.ssr.html-helpers :as ssr-html]
            [re-frame.ssr.payload-policy :as payload-policy]
            ["react-dom/server" :as rdom-server]))

;; ---------------------------------------------------------------------------
;; The per-request frame
;; ---------------------------------------------------------------------------

(defn fresh-frame-id
  "A frame id no other request holds. `gensym` rather than a UUID because
  the id is a projection target that lives for one synchronous render and
  is destroyed before the function returns — it needs to be unique in this
  process and nothing more, and it never reaches the wire."
  []
  (keyword "hicasso.ssr" (str (gensym "request-"))))

(defn setup-events
  "The construction-time setup vector for one request.

  Both doors are the framework's own. `:rf/set-db` is the reserved
  app-db seeding event (EP-0027) and is the SNAPSHOT-IN door — a host that
  already has the request's state as a map hands it over whole. Anything
  else the request needs (a route, a fetch already resolved) rides
  `:initial-events` as ordinary events, in the order given, after the
  snapshot."
  [snapshot initial-events]
  (cond-> []
    (some? snapshot)     (conj [:rf/set-db snapshot])
    (seq initial-events) (into initial-events)))

;; ---------------------------------------------------------------------------
;; The document envelope — bench lane, NOT the production host
;; ---------------------------------------------------------------------------

(defn payload-script
  "The id-pinned `<script type=\"application/edn\">` carrying the
  already-`pr-str`'d hydration payload.

  Byte-for-byte the shape `re-frame.ssr.ring.shell/payload-script-tag`
  emits, and it must stay that way: the id is
  `re-frame.ssr.constants/payload-script-id` (the contract with the
  client bootstrap's `getElementById` read) and the body goes through the
  framework's EDN-aware escaper, which keeps `<` readable in token
  position, escapes it inside string literals, and fails loud on a
  genuine `</script` breakout. That namespace is JVM-only, so the three
  lines are re-spelled here rather than required; the two CONSTANTS they
  are made of are shared, which is the part that could drift."
  [payload-edn]
  (str "<script id=\"" ssr-constants/payload-script-id "\" type=\"application/edn\">"
       (ssr-html/escape-edn-script-body payload-edn)
       "</script>"))

(defn document
  "One page. The app root carries the body-only structural hash as
  `data-rf-render-hash`, the payload script follows the root's close, and
  the bootstrap `<script src>` is last — the order `ssr-ring`'s
  non-streaming shell writes, so a fixture baked here is recognisable to
  anyone who has read that one.

  Deliberately minimal: no head model, no `:html-attrs`/`:body-attrs`
  bags, no `:body-end` hook. Those are the production host's surface and
  the production host is not this bead."
  [{:keys  [html app-element-id script-src render-hash title]
    script :payload-script}]
  ;; `or`, not `:or` — a caller who threads `nil` through for an option it
  ;; did not set (which is every caller with one options map) supplies the
  ;; KEY, so destructuring defaults never fire and the page silently gets
  ;; `id=""`. Caught by the first baked fixture, which is what a bake is
  ;; for.
  (str "<!DOCTYPE html>"
       "<html lang=\"en\">"
       "<head><meta charset=\"utf-8\"><title>"
       (ssr-html/escape-html (or title "Hicasso SSR")) "</title></head>"
       "<body>"
       "<div id=\"" (ssr-html/escape-attr (or app-element-id "app")) "\""
       " data-rf-render-hash=\"" (ssr-html/escape-attr (str render-hash)) "\">"
       html
       "</div>"
       script
       (when script-src
         (str "<script src=\"" (ssr-html/escape-attr script-src) "\"></script>"))
       "</body></html>"))

;; ---------------------------------------------------------------------------
;; The render entry
;; ---------------------------------------------------------------------------

(defn render
  "Render one request. Returns

      {:frame-id      the per-request gensym (destroyed by the time you
                      read it — it is here to be asserted on, not used)
       :html          the app root's INNER markup
       :render-hash   the body-only structural hash of the render tree
       :payload       the `:rf/hydration-payload` map
       :payload-edn   that map, `pr-str`'d
       :payload-script the `__rf_payload` <script> element
       :document      the whole page}

  `opts`:

      :hiccup          REQUIRED. The root hiccup form.
      :snapshot        a map seeded whole through `:rf/set-db`.
      :initial-events  ordinary events, run after the snapshot.
      :payload         REQUIRED, and the framework's fail-closed
                       hydration-payload policy verbatim: a non-empty
                       vector allowlist of top-level app-db keys, or
                       `:rf.ssr.payload/whole-app-db`. Absence throws
                       `:rf.error/ssr-missing-payload-policy` — from the
                       framework's own validator, because this entry
                       hands the value straight to it.
      :frame-opts      merged UNDER the id and the setup vector, so a
                       request that needs `:images`, `:url-strategy` or
                       `:fx-overrides` declares them the same way any
                       other frame does. `:id` and `:initial-events` are
                       this entry's and cannot be overridden.
      :client-frame-id the STABLE wire `:rf/frame-id`, or absent to omit
                       the key (the anonymous-server-frame shape).
      :version         :schema-digest  passed through to `build-payload`.
      :app-element-id  :script-src  :title  the document envelope's.

  The adoption window is closed and the frame destroyed in a `finally`,
  in that order and for the same reason — see the namespace docstring's
  §The adoption window. `destroy-frame!`'s lifecycle was
  verified sound for this use in the design programme (`frame.cljc` —
  destroy tears down the frame's containers and registrations), so a
  per-request frame is a per-request frame and not a per-request leak."
  [{:keys [hiccup snapshot initial-events payload frame-opts client-frame-id version
           schema-digest app-element-id script-src title]}]
  (let [frame-id (fresh-frame-id)]
    (try
      (rf/make-frame (assoc frame-opts
                            :id             frame-id
                            :initial-events (setup-events snapshot initial-events)))
      ;; The server half of an adoption renders inside the same window as
      ;; the client half — see the namespace docstring. Closed in the
      ;; `finally`.
      (rt/open-adoption-window!)
      (let [;; The hiccup as WRITTEN — there is no server-only tree here.
            ;; `defhost`'s `:ssr` policy is honoured by the gate that is
            ;; the host element's own type, so this entry hands React the
            ;; same form the browser mount hands it; see the namespace
            ;; docstring's §`defhost`'s `:ssr` policy needs nothing here.
            html        (rdom-server/renderToString
                          (mount/provider frame-id (codec/root-element frame-id hiccup)))
            ;; The hash is therefore of the tree BOTH ENDS SHARE by
            ;; construction, which is what the instrument wants: a
            ;; `:client-only` region is a deliberate server/client
            ;; difference in the MARKUP, while the render tree's
            ;; structural identity is the same on both sides.
            ;;
            ;; FINDING, WITNESSED AND FILED rather than papered over: this
            ;; hash is DEGENERATE for an interpreted root. Spec 011's
            ;; hydration-mismatch instrument hashes the RENDER TREE, and a
            ;; substrate that renders hiccup→hiccup hands it the whole
            ;; tree. Hicasso's root hiccup is `[<minted head> {props}]`
            ;; and the tree is walked INSIDE React, so what reaches the
            ;; hash is one vector whose head is a function —
            ;; `canonical-edn` renders every function identically, so two
            ;; DIFFERENT screens hash the same (measured: the dogfood
            ;; screen and the 1,200-element Conduit feed both hash
            ;; `83b865f8`). The instrument is therefore fail-open here.
            ;; It is left as the framework's own call (R0 — this bead does
            ;; not invent a payload byte) and pinned by
            ;; `the-render-hash-is-degenerate-for-an-interpreted-root` so
            ;; nobody can start relying on it by accident. The repair is
            ;; rf2-2rtt6.91, and it is the SSR programme's rather than
            ;; this bead's, because a real fix is a server AND client
            ;; contract: a normalisation invented here alone would make
            ;; every page a MISMATCH instead of a non-check.
            render-hash (ssr-hash/render-tree-hash hiccup)
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
                          render-hash
                          policy-opts)
            payload-edn (pr-str payload-map)
            script      (payload-script payload-edn)]
        {:frame-id       frame-id
         :html           html
         :render-hash    render-hash
         :payload        payload-map
         :payload-edn    payload-edn
         :payload-script script
         :document       (document {:html           html
                                    :payload-script script
                                    :render-hash    render-hash
                                    :app-element-id app-element-id
                                    :script-src     script-src
                                    :title          title})})
      (finally
        ;; FIRST, and unconditionally: the window is a module-level flag,
        ;; so a throw here that skipped it would leave the whole process
        ;; adopting. `destroy-frame!` is the per-request cleanup that may
        ;; itself throw; the window must already be shut when it runs.
        (rt/close-adoption-window!)
        (rf/destroy-frame! frame-id)))))

(defn render-twice
  "[[render]] the same request twice and compare the documents
  byte-for-byte — the determinism check, run where the renderer is rather
  than trusted to a docstring.

  Two renders in one process take two DIFFERENT gensym frame ids, so this
  is also the standing proof that the per-request id is invisible on the
  wire. Returns `{:first :second :identical? :differs-at}`, where
  `:differs-at` is the index of the first differing character (nil when
  identical) — a diff position is what makes a red run diagnosable."
  [opts]
  (let [a (render opts)
        b (render opts)
        x (:document a)
        y (:document b)]
    {:first      a
     :second     b
     :identical? (= x y)
     :differs-at (when (not= x y)
                   (let [n (min (count x) (count y))]
                     (loop [i 0]
                       (cond
                         (= i n)                          n
                         (not= (.charAt x i) (.charAt y i)) i
                         :else                            (recur (inc i))))))}))
