(ns re-frame.hicasso.server
  "HICASSO ON THE SERVER — one request in, one document out (rf2-b6jkj).

  The optional module `docs/core/hicasso/18-ssr-and-hydration.md` is
  written against, and the fourth in the family beside
  [[re-frame.hicasso.forms]], [[re-frame.hicasso.overlay]] and
  [[re-frame.hicasso.motion]] (naming-ledger rows 5, 6, 16, 17, 22).
  The door names it nowhere, so a browser build that never requires it
  carries none of it — `scripts/check_optional_module_reachability.py`
  is what keeps that true.

      (ns app.server
        (:require [re-frame.hicasso.server :as server]))

      (:document (server/render {:hiccup [views/page {}] …}))

  ## THE PUBLIC SURFACE IS FOUR NAMES, and a ledger row owns them

  [[render]] is the product door. Beside it stand three composition
  helpers — [[payload-script]], [[document]] and [[render-twice]] — and
  they are public BY DECISION rather than by omission, which is the
  thing an ordinary `defn` cannot say on its own. Naming-ledger row 50
  carries that decision as an operator override (rf2-sc1dt), and the
  test each survivor passes is one test rather than three: **an external
  host does something with it that [[render]]'s returned values alone
  cannot do.**

  - [[payload-script]] — a host that mutates the payload and re-`pr-str`s
    it has to re-wrap the result, and the tag must stay byte-identical
    to `re-frame.ssr.ring.shell/payload-script-tag`. The id is the
    client bootstrap's `getElementById` contract, and the body goes
    through the framework's EDN-aware escaper, which fails loud on a
    genuine `</script` breakout. Public is what stops a host
    hand-writing the tag and getting the escaping wrong.
  - [[document]] — a host post-processing `:html` (a CSP nonce, say) has
    to rebuild the envelope, and by hand that means re-spelling
    `escape-html` and `escape-attr`. It is the one string of HTML in
    this package that is not React's.
  - [[render-twice]] — nondeterminism is a HOST-authored bug class (a
    view reading `Date.now`, a random id), and this is the ready-made
    host-side CI diagnostic, with `:differs-at` to make a red run
    diagnosable. It carries no incidental cost, since the double work
    happens only when it is called, and it cannot move to a test kit
    without dragging `react-dom/server` into that kit's dependency
    graph. Beside [[render]] is its only sane home.

  Three names are private, for two different reasons. `render-options`
  is private because nothing composes with it: it is `renderToString`'s
  options object and has no life outside the call below it.
  `fresh-frame-id` and `setup-events` are private because **no public
  path consumes their return values.** [[render]] mints its own frame
  id and installs it over `:frame-opts` — `:id` cannot be overridden —
  then destroys the frame before returning, so a caller could obtain a
  fresh id and do nothing with it through this surface. The setup
  vector is a three-line `cond->` over `:rf/set-db` plus ordinary
  events that [[render]] already accepts directly as `:snapshot` and
  `:initial-events`, so it is derivable rather than exposed. Both
  shipped public with the module and neither ever acquired a caller;
  a public name for either invites a host to couple to choreography
  [[render]] deliberately owns. Rebuilding that pipeline from public
  parts is impossible in any case — `impl.mount/tree` and
  `impl.roots/open-adoption-window!` are impl namespaces, and rf2-sc1dt
  records that reopening either privacy means reopening those doors
  first.

  **`check_facade_inventory.py` does not reach this list, by that
  gate's own design rather than by an omission.** It reads ONE door —
  `re-frame.hicasso` — and says so in terms, because deciding what an
  optional module's public roster IS is a judgement its own tier has to
  record and not a data change anyone can make. Row 50 is that
  judgement for this module. What guards the BOUNDARY these four sit
  behind is `check_optional_module_reachability.py`, whose roster
  gained this module under rf2-2a0ju, and `check_bundle_isolation.cjs`,
  which reads the same claim off a real `:advanced` browser bundle.

  ## There is ONE renderer, and this is not a second one

  The server engine is **the Hicasso runtime itself**, run under Node's
  `react-dom/server`. No JVM string emitter, no parallel hiccup walker,
  no server-only codec. Hydration parity is by construction or it is a
  claim, and the difference is a whole class of bug the adapters have
  already paid for.

  Running THIS runtime under `renderToString` was verified rather than
  assumed, in the 2026-08-04 SSR design programme and again by the
  prototype this module is the product form of
  (`test/re_frame/bench/hicasso/ssr/entry.cljs`):

  - every shell hands `(.-snapshot entry)` as BOTH the client and the
    server snapshot, so `useSyncExternalStore` calls the server snapshot
    and **never calls `subscribe`** — no registration is minted;
  - every [[re-frame.hicasso/sub]] read is therefore the mutation-free
    cold probe: a pure compute against one coherent frame-state
    snapshot, with no cache entry, no ref-count, no watch and no
    disposal obligation;
  - commits and effects never run. The whole render is HD-002's
    *abandoned render* by design.

  ## THE TREE IS `impl.mount/tree`'S, AND THAT IS THE WHOLE POINT

  This module exists because a hydrating root's tree is not the app
  subtree. `impl.mount/hydrate-root!` wraps the app in a Fragment whose
  first child is the adoption-window closer, with the adoption-window
  provider around the app — and **React derives a `useId` from tree
  POSITION as well as from the `identifierPrefix`**. A server that
  emitted the bare app subtree would hand the client bytes whose ids
  agree on the prefix and differ after it, on every page that reads
  `useId` at all. That was `dispositions.md` HS-11's obstruction 2,
  measured and unrepaired, and it is why `h/hydrate!` was held off the
  public door under rf2-k1mp.

  HS-11 named two candidate repairs and ruled on neither: *a matching
  server-render entry of this arm's own*, or *making the closer a
  wrapper rather than a sibling*. This module is the first. It does not
  reimplement the fork — it calls
  [[re-frame.hicasso.impl.mount/tree]], the same function the hydrating
  door calls, with a handle carrying this request's own window. The
  shape is therefore decided in ONE place for both halves of every SSR
  route, and a later change to the root's shape moves both sides at
  once. Mirroring the fork here instead would have re-created the exact
  failure the repair is for, one file further along.

  The second candidate stays unruled and is not foreclosed: making the
  closer a wrapper would change the client tree and this module would
  follow it for free, because it does not know what the shape is.

  ## The adoption window is OPEN around `renderToString`

  A server render is the FIRST HALF OF AN ADOPTION, so it runs in the
  window the client's hydrating half runs in — one window per request,
  scoped over that request's tree. `impl.roots/open-adoption-window!`
  names this module as the second minter and records it as *decided,
  not built*; this is the building.

  Without it the two halves disagree about motion. Presence starts a
  child `:mounting` and applies that child's `::h/mounting` attribute
  overrides while it is in that phase, so a windowless server render
  ships the ENTER appearance — the class, and the `opacity: 0`, an
  animation is about to move off. The hydrating client's first pass
  renders those same children `:present`, and React then reports a
  mismatch on every presence-managed node.

  **The window is per-request and unreachable from anywhere else**, so
  a render that throws leaks nothing: the window it opened is dropped
  with the request. Nothing module-level is set, which is what makes
  concurrent requests safe here — see §Concurrency.

  ## Concurrency: the frame is per-request, and so is everything else

  One fresh frame per request under a `gensym` id, destroyed in a
  `finally`. Two concurrent requests cannot read each other's app-db,
  and the id never reaches the wire — [[render-twice]] is the standing
  proof of both, since the two renders take different gensyms and are
  compared byte-for-byte.

  `renderToString` is synchronous, so one request's render completes
  before the next begins on that thread.

  ## The payload path is the FRAMEWORK'S, untouched

  `:payload` is `re-frame.ssr.payload-policy`'s fail-closed contract
  verbatim — a non-empty allowlist vector of top-level app-db keys, or
  `:rf.ssr.payload/whole-app-db` as an explicit opt-in — and absence
  raises `:rf.error/ssr-missing-payload-policy` from the framework's own
  validator, because this module hands the value straight to it and
  adds no check of its own. Nothing Hicasso-specific touches the
  payload path.

  **The wire frame id is the caller's `:client-frame-id`, never the
  per-request gensym.** Stamping the gensym guarantees
  `:rf.error/hydration-frame-id-mismatch` on every real page
  (rf2-lm2yzy). Omitting `:client-frame-id` omits the key, which is the
  anonymous-server-frame shape.

  ## THIS ROOT SHIPS NO RENDER HASH, by Spec 011's own tiering

  The payload carries no `:rf/render-hash` and the document stamps no
  `data-rf-render-hash`. Spec 011 §Hydration-mismatch detection tiers
  detection **by render-tree representation, not by adapter brand**: the
  hash channel belongs to the HICCUP tier, whose views are pure fns
  returning a hashable data tree. Every root that reaches React as an
  ELEMENT is in the other tier and verifies by React-native adoption —
  the client's `onRecoverableError`, which
  `impl.mount/hydration-reporter` surfaces as
  `:rf.ssr/hydration-mismatch`. 011 says of that tier, in as many words,
  that it \"deliberately carries **no** such hash\".

  This module is that tier: `codec/root-element` hands React an element
  and the tree is walked INSIDE `renderToString`, so at no point does a
  data tree describing the page exist for anything to hash. The
  prototype measured what a hash here would be worth — the dogfood
  screen and a ~1,200-element feed page both hashed `83b865f8`, because
  the root's canonical form is `[#fn[] {}]` — and a constant that always
  agrees is a fail-open gate wearing the shape of a check.

  ## `defhost`'s `:server` policy needs nothing here

  A reader looking for where the server honours `:client-only` and
  `{:fallback …}` will not find it, and that is the design. `mint-host!`
  mints ONE gate per declaration whose single `useSyncExternalStore`
  answers `false` from its SERVER snapshot, so under `renderToString`
  the gate renders the declaration's pre-walked fallback element — or
  nothing — **because it is the element's own type**. The policy is
  honoured by rendering, at every position including inside a boundary
  body, which a pre-walk over the handed-in tree could never reach.

  ## What this is NOT

  Not a production HTTP host. Spec 011's response contract — the
  response accumulator, cookies, redirects, CRLF fail-fast — is
  `re-frame.ssr.ring`'s, and nothing under `implementation/ssr` or
  `implementation/ssr-ring` is touched by this module. [[document]]
  mirrors that shell's envelope closely enough to be recognisable and
  is deliberately minimal: no head model, no attribute bags, no
  `:body-end` hook. **No streaming** — `renderToPipeableStream` is out
  of scope per the adversarial review, and out of scope here means
  absent, not deferred."
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
  "A frame id no other request holds. `gensym` rather than a UUID because
  the id is a projection target that lives for one synchronous render and
  is destroyed before [[render]] returns — it needs to be unique in this
  process and nothing more, and it never reaches the wire.

  **DO NOT RENAME THE `hicasso.ssr` KEYWORD NAMESPACE.**
  `scripts/check_bundle_isolation.cjs` pins the source literal
  `(keyword \"hicasso.ssr\"` in this file as the premise for the server
  module's bundle-side zero-rent sentinel (rf2-fn62g): the string is a
  runtime ARGUMENT, so `:advanced` can neither rename nor drop it while
  the code passing it is reachable, and it is co-reachable with
  `react-dom/server` by construction because that dependency enters a
  bundle by exactly one route and [[render]]'s first binding is this
  call. Privacy does not disturb that — the sentinel is about
  reachability from [[render]], which is unchanged — but renaming the
  namespace reds the premise check, loudly and by design."
  []
  (keyword "hicasso.ssr" (str (gensym "request-"))))

(defn- setup-events
  "The construction-time setup vector for one request.

  Both doors are the framework's own. `:rf/set-db` is the reserved
  app-db seeding event (EP-0027) and is the SNAPSHOT-IN door — a host
  that already has the request's state as a map hands it over whole.
  Anything else the request needs (a route, a fetch already resolved)
  rides `:initial-events` as ordinary events, in the order given, after
  the snapshot."
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
  `re-frame.ssr.constants/payload-script-id` — the contract with the
  client bootstrap's `getElementById` read — and the body goes through
  the framework's EDN-aware escaper, which keeps `<` readable in token
  position, escapes it inside string literals, and fails loud on a
  genuine `</script` breakout. That shell namespace is JVM-only, so the
  three lines are re-spelled here rather than required; the two
  CONSTANTS they are made of are shared, which is the part that could
  drift."
  [payload-edn]
  (str "<script id=\"" ssr-constants/payload-script-id "\" type=\"application/edn\">"
       (ssr-html/escape-edn-script-body payload-edn)
       "</script>"))

(defn document
  "One page. The payload script follows the app root's close and the
  bootstrap `<script src>` is last — the order `ssr-ring`'s
  non-streaming shell writes, so a fixture baked here is recognisable to
  anyone who has read that one.

  **No `data-rf-render-hash` on the app root.** That marker is the
  hiccup tier's and this is an adoption-tier root — see the namespace
  docstring's §This root ships no render hash."
  [{:keys  [html app-element-id script-src title]
    script :payload-script}]
  ;; `or`, not `:or` — a caller who threads `nil` through for an option
  ;; it did not set (which is every caller with one options map) supplies
  ;; the KEY, so destructuring defaults never fire and the page silently
  ;; gets `id=""`.
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
  "`renderToString`'s options object, or nil.

  ONE key, `:identifier-prefix` — React's own `identifierPrefix`, handed
  over untouched, exactly as `impl.mount/root-options` hands it to
  `createRoot` and `hydrateRoot` (rf2-hic-046). **Hand the hydrating
  root the same string**: `useId` is numbered per root and prefixed by
  this option, so the two sides agree on it or every generated id in the
  tree diverges.

  A string key through `unchecked-set`, for `root-options`' reason: it
  is what keeps the property off Closure's renamer under `:advanced`,
  and an `identifierPrefix` renamed is a prefix React never sees."
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
      :payload           REQUIRED, and the framework's fail-closed
                         hydration-payload policy verbatim — see the
                         namespace docstring's §The payload path.
      :client-frame-id   the STABLE wire `:rf/frame-id`, or absent to
                         omit the key (the anonymous-server-frame shape).
      :identifier-prefix React's `identifierPrefix`. **The hydrating
                         root must be handed the same string.**
      :app-element-id    :script-src  :title   the document envelope's.
      :frame-opts        merged UNDER the id and the setup vector, so a
                         request needing `:images`, `:url-strategy` or
                         `:fx-overrides` declares them the way any other
                         frame does. `:id` and `:initial-events` are
                         this module's and cannot be overridden.
      :version           :schema-digest   passed to `build-payload`.

  The eight spellings `18-ssr-and-hydration.md` teaches are the first
  eight above (naming-ledger row 22, which settles them as a set).

  **The tree is `impl.mount/tree`'s and the window is open around the
  render** — the two facts this module exists for; both are the
  namespace docstring's. The window is closed and the frame destroyed in
  a `finally`, in that order, so a render that threw leaks no more than
  one that returned."
  [{:keys [hiccup snapshot initial-events payload frame-opts client-frame-id
           identifier-prefix version schema-digest app-element-id script-src title]}]
  (let [frame-id (fresh-frame-id)
        ;; Per REQUEST, and reachable from nothing else — the second
        ;; minter `impl.roots/open-adoption-window!` names. Never a
        ;; module-level flag: that is what would make one request's throw
        ;; leave every later request born-present.
        window   (roots/open-adoption-window!)]
    (try
      (rf/make-frame (assoc frame-opts
                            :id             frame-id
                            :initial-events (setup-events snapshot initial-events)))
      (let [;; The hiccup as WRITTEN — there is no server-only tree here.
            ;; `defhost`'s `:server` policy is honoured by the gate that
            ;; is the host element's own type.
            ;;
            ;; THE HANDLE IS THE HYDRATING SHAPE. `:adoption` is what
            ;; `tree` branches on, so this is the Fragment-plus-closer
            ;; tree `hydrate-root!` will adopt, position for position.
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
        ;; The window first — `destroy-frame!` is the per-request cleanup
        ;; that may itself throw, and the window must already be shut when
        ;; it runs.
        (roots/close-adoption-window! window)
        (rf/destroy-frame! frame-id)))))

(defn render-twice
  "[[render]] the same request twice and compare the documents
  byte-for-byte — the determinism check, run where the renderer is
  rather than trusted to a docstring.

  Two renders in one process take two DIFFERENT gensym frame ids, so
  this is also the standing proof that the per-request id is invisible
  on the wire. Returns `{:first :second :identical? :differs-at}`, where
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
                         (= i n)                            n
                         (not= (.charAt x i) (.charAt y i)) i
                         :else                              (recur (inc i))))))}))
