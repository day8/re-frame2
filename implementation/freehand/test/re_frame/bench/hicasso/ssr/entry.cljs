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

  ## THE RENDER HASH: THIS ROOT DOES NOT GET ONE (rf2-2rtt6.91)

  The payload carries NO `:rf/render-hash` and the document stamps NO
  `data-rf-render-hash`, and that is Spec 011's own answer for this tier
  rather than a gap.

  §Hydration-mismatch detection tiers detection **by render-tree
  representation, not by adapter brand**. The hash channel belongs to the
  HICCUP tier — Reagent and Reagent-slim, whose views are pure fns
  returning a hashable data tree, so the server hashes its tree and the
  client re-hashes its first render and the two compare. Every root that
  reaches React as an ELEMENT is in the other tier: a compiled
  `re-frame.ui` root, a native UIx root, and a Freehand root
  (§Hydration on the Freehand paved path). Those verify by **React-native
  adoption** — React diffs the client's first render against the server
  DOM and reports what it recovers from through the root's
  `onRecoverableError`, which the framework surfaces as the same
  `:rf.ssr/hydration-mismatch` diagnostic. 011 says of that tier, in as
  many words, that it \"deliberately carries **no** such hash\".

  This entry is that tier. `codec/root-element` hands React an element
  and the tree is walked INSIDE `renderToString`, so at no point does a
  data tree describing the page exist for anything to hash.

  **What it used to emit, and why a degenerate hash is worse than none.**
  This entry did ship one: `render-tree-hash` over the root hiccup as
  handed in. That form is `[<minted head> {props}]` — ONE vector whose
  head is a function — and `canonical-edn` renders every function as the
  identity-free token `#fn[]` (a RULED requirement, rf2-jsa2ml: no fn
  `.toString` is stable across JVM and CLJS, so dropping the identity is
  the only thing that keeps a hiccup-tier hash from firing a spurious
  mismatch on every page). So the whole canonical form was `[#fn[] {}]`,
  and MEASURED: the dogfood screen and the ~1,200-element Conduit feed
  page both hashed `83b865f8`, while a root whose hiccup is ordinary
  markup hashed differently. The value was a function of the root's
  SHAPE and carried no information about the page.

  Shipping that is strictly worse than shipping nothing. An absent key
  cannot be mistaken for evidence; a present one that always agrees is a
  fail-open gate wearing the shape of a check — the client would have
  compared two different pages and found them equal. Absence is also the
  shape the wire contract already wants: `:rf/render-hash` is
  `{:optional true} :string` in Spec-Schemas, and
  `payload-policy/build-payload` omits the key on a nil hash, so nothing
  Hicasso-specific touches the payload path (R0 holds — this namespace
  supplies the app-db and gets out of the way).

  **Why not a better hash.** The two candidates rf2-2rtt6.91 named both
  fail on the same fact. Normalising the minted head to its displayName
  reverses rf2-jsa2ml's ruling for one substrate and still only says WHICH
  screen rendered — every divergence WITHIN a screen, which is the entire
  class hydration mismatch exists to catch, would still compare equal.
  Accumulating the walked tree server-side would need the client to
  reproduce the same accumulation byte-for-byte, which is re-deriving the
  hiccup tier under a substrate built not to have one — and 011 already
  routes this tier to the adoption channel that rf2-2rtt6.97 wired.

  The exclusion is pinned by `the-interpreted-root-ships-no-render-hash`
  in `ssr/entry_cljs_test`, and the measurement above is kept live (over
  `ssr-hash/render-tree-hash` directly, where it is a fact about the hash
  fn rather than about this entry) by the witness rows in
  `ssr/spike_cljs_test` and `ssr/instance_key_payload_dom_cljs_test` that
  chose byte digests over it.

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
  "One page. The payload script follows the app root's close and the
  bootstrap `<script src>` is last — the order `ssr-ring`'s non-streaming
  shell writes, so a fixture baked here is recognisable to anyone who has
  read that one.

  **No `data-rf-render-hash` on the app root** (rf2-2rtt6.91). That marker
  is the hiccup tier's, and this is an adoption-tier root — see [[render]]'s
  §The render hash. `ssr-ring`'s shell stamps it because a Reagent root
  hands the server the same data tree the client will re-hash; a root React
  walks hands it no such tree, so the marker here could only ever carry the
  constant, and a constant on the root element is a fail-open check wearing
  the shape of a real one.

  Deliberately minimal: no head model, no `:html-attrs`/`:body-attrs`
  bags, no `:body-end` hook. Those are the production host's surface and
  the production host is not this bead."
  [{:keys  [html app-element-id script-src title]
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

(defn render
  "Render one request. Returns

      {:frame-id      the per-request gensym (destroyed by the time you
                      read it — it is here to be asserted on, not used)
       :html          the app root's INNER markup
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
                          ;; NO RENDER HASH — nil, and `build-payload` omits the
                          ;; key. See the namespace docstring's §The render hash.
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
