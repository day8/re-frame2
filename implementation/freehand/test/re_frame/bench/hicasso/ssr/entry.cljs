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

  ## What one request is

  [[render]] is the whole of it, and the order is the interesting part:

    1. a per-request frame under a **gensym id** — never a shared one, so
       two concurrent requests cannot read each other's app-db;
    2. seeded through the FRAMEWORK'S doors — `:initial-events`, and the
       reserved `:rf/set-db` for a snapshot handed in whole;
    3. rendered as `(provider frame (codec/root-element frame hiccup))`,
       the same two calls `arm1/mount/render!` makes in the browser;
    4. the payload built out of the FRAMEWORK'S OWN BYTES —
       `payload-policy/apply-policy` (the fail-closed `:payload`
       contract), `project-app-db-egress`, `build-payload`, and
       `html-helpers/escape-edn-script-body` under the pinned
       `__rf_payload` script id. Nothing Hicasso-specific touches the
       payload path (R0); this namespace supplies the app-db and gets out
       of the way;
    5. `destroy-frame!` in a `finally`, so a render that threw leaks no
       more than one that returned.

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

  ## The one thing this entry does not do yet — THE ADOPTION WINDOW

  Predicted by the rf2-2rtt6.84 worker and CONFIRMED here by measurement,
  so it is a named gap rather than a surprise waiting for the spike
  witness.

  Presence starts a child at `:mounting` (`arm1/presence.cljs` —
  `(react/useState presence/initial)`) and applies that child's
  `::h/mounting` attribute overrides while it is in that phase. A server
  render therefore ships the ENTER appearance — the class an animation is
  about to move off. rf2-2rtt6.84 makes the hydrating client's first pass
  render those same children `:present` instead (born-present under an
  open adoption window), so the server's markup and the client's first
  pass disagree, and React reports a hydration mismatch on every
  presence-managed node. Measured on the corpus's `presence-mounting`
  row, which bakes `toast--enter` today and is pinned red-when-fixed by
  `the-server-render-ships-presences-mounting-overrides`.

  **The repair is one line, and it is not writable yet**:
  `renderToString` below must run between `rt/open-adoption-window!` and
  `rt/close-adoption-window!`, and neither function exists on main — they
  are in rf2-2rtt6.84's still-open PR #7469. Wiring it is rf2-2rtt6.94,
  which is blocked on that merge. Nothing here is edited to anticipate
  it: a call to a var that does not exist does not compile, and guessing
  at a var that does is how two beads race one file.

  ## What this is NOT

  Not a production host. Spec 011's HTTP response contract — the response
  accumulator, cookies, redirects, CRLF fail-fast — stays `ssr-ring`'s,
  and no file under `implementation/ssr` or `implementation/ssr-ring` is
  touched by this bead. [[document]] mirrors `ssr-ring`'s own envelope
  shape closely enough to be recognisable and is a BENCH-LANE page, priced
  and ruled elsewhere."
  (:require [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.ssr.host-policy :as host-policy]
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

  The frame is destroyed in a `finally`. `destroy-frame!`'s lifecycle was
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
      (let [;; The server walk consults `defhost`'s `:ssr` policy BEFORE
            ;; anything is turned into an element — see
            ;; `ssr.host-policy` for which hosts it reaches and which
            ;; rf2-2rtt6.85's own shell covers from the other side.
            tree        (host-policy/apply-policy hiccup)
            html        (rdom-server/renderToString
                          (mount/provider frame-id (codec/root-element frame-id tree)))
            ;; The hash is of the tree BOTH ENDS SHARE — the hiccup as
            ;; written, not the policy-applied server tree. A
            ;; `:client-only` region is a deliberate server/client
            ;; difference in the MARKUP; the render tree's structural
            ;; identity is the same on both sides, and hashing the server
            ;; tree would make every host region a hydration mismatch by
            ;; construction.
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
