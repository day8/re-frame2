(ns re-frame.bench.hicasso.ssr.fixtures
  "THE SSR CONFORMANCE CORPUS (rf2-2rtt6.86 clause 3) — the requests the
  bake driver bakes and the witnesses assert on.

  One ordered vector, because the corpus is a ROSTER and a roster whose
  order moves is a fixture set whose file names move with it. Each row is
  exactly the `opts` map [[re-frame.bench.hicasso.ssr.entry/render]]
  takes, plus an `:id` naming its output files and a `:why` naming what it
  is here to cover — a row that cannot say what it covers is a row nobody
  can decide to delete.

  ## What the census covers

  - **The dogfood screen**, which is what rf2-2rtt6.87's X-rows hydrate,
    at two sizes and through BOTH seeding doors (`:rf/set-db` snapshot-in
    and ordinary `:initial-events`) and BOTH arms of the hydration-payload
    contract (a key allowlist and the explicit whole-app-db opt-in).
  - **A tier-1 bulk shape** — the ~1,200-element Conduit feed page, which
    is the size the charter's bar rows are taken at and the only row here
    whose markup is a port of a real application's.
  - **`defhost`'s `:ssr` policy**, all three meanings and both USE-SITE
    positions: a host with no declared policy (the ruled `:client-only`
    default — its component's markup MUST be absent from the server
    HTML), a host declaring a fallback (whose placeholder markup MUST be
    present), and a host declaring `:render` (whose component, CHILDREN
    and declared context value MUST all be present — rf2-l0wfx), each
    rendered once from the hiccup the entry is handed and once from
    inside a `defview` body. See [[host-screen]],
    [[render-host-screen]] — separate for the reason its docstring
    gives — and [[nested-host-screen]], which carries all three.
  - **Presence's `::h/mounting` overrides** — and that row is a PINNED
    DEFECT rather than a feature. See [[presence-tray]].
  - **The instance-key payload obligation, TWICE** — one page whose boot
    events write `h/reg-state` instance state, rendered once with `:ui`
    on the allowlist and once without it. The two rows are a matched
    pair and only mean anything together; see
    [[re-frame.bench.hicasso.ssr.instance-key]] and the witness
    `ssr/instance-key-payload-dom-cljs-test`.

  ## The host rows are REAL DECLARATIONS (rf2-2rtt6.92)

  They were not always. rf2-2rtt6.86 wrote this corpus while
  rf2-2rtt6.85 — which owns `mint-host!`'s `:ssr` option — was still an
  open PR, so the fallback row stamped the policy slot onto a minted
  head by hand. A hand-stamped slot proves a READER, never the door: the
  two halves could have disagreed about the spelling forever and this
  corpus would have gone on passing. Both halves are on main now, so
  both rows are written the way an author writes them —
  `(defhost … {:ssr …})` — and the server HTML they assert on is
  therefore evidence about the public declaration."
  (:require [re-frame.bench.hicasso.arm1.dogfood-collector :as collector]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            ;; `defview` and `defhost` are not compilers — they expand to
            ;; `runtime/mint-view!` and `codec/mint-host!` calls, so the
            ;; two namespaces they name are ordinary runtime requires of
            ;; this one even though no alias is spelled below.
            [re-frame.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.front.codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.shapes.large-template :as large-template]
            [re-frame.bench.hicasso.shapes.model :as model]
            [re-frame.bench.hicasso.ssr.instance-key :as instance-key]
            [re-frame.routing :as routing]
            ["react" :as react])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defhost defview]]))

;; ---------------------------------------------------------------------------
;; The host rows
;; ---------------------------------------------------------------------------

(defn- client-widget
  "A foreign component whose markup is the tell. Every host row renders
  THIS, so a fixture's verdict is a substring test on one distinctive
  string rather than a structural argument."
  [_js-props]
  (react/createElement "em" #js {"className" "client-widget"} "CLIENT-ONLY-WIDGET"))

(defhost default-host
  "A declaration that writes no `:ssr` at all, which is how most authors
  will write one — so the policy under test is the ruled `:client-only`
  DEFAULT, taken from the door rather than named here."
  client-widget)

(defhost fallback-host
  "A declaration whose policy is markup: `{:fallback …}` renders that
  hiccup where the host sits until the client has adopted the page."
  client-widget
  {:ssr {:fallback [:span.host-fallback "loading…"]}})

(def ^:private corpus-context
  "A React context, so the `:render` row can carry the shape that filed
  rf2-l0wfx — a transparent wrapper whose whole job is a subtree."
  (react/createContext "unset"))

(defn- context-reader
  "A consumer BELOW the provider. Its text is the corpus's evidence that
  `:ssr :render` puts the DECLARED context value in the server bytes and
  not the default — the one property that separated the ruled policy
  from the rejected `:ssr :children`."
  [_js-props]
  (react/createElement "em" #js {"className" "context-reader"}
                       (react/useContext corpus-context)))

(defhost render-host
  "A declaration whose policy is `:render`: the author asserts the
  component is safe on the server, so the declaration mints no gate and
  the component IS the element's type. A context provider is the case
  where that assertion is trivially true."
  (.-Provider corpus-context)
  {:ssr :render})

(defhost render-reader-host
  "The consumer, also `:render`, because a consumer that did not render
  server-side could not witness the value."
  context-reader
  {:ssr :render})

(def host-screen
  "One page carrying both GATED hosts at HANDED-IN positions — nested
  inside ordinary markup and inside a `for`, the lazy position."
  [:div.hosts
   [:h1 "hosts"]
   [default-host {:kind "default"}]
   [:ul
    (for [i (range 2)]
      [:li {:key i} [fallback-host {:kind "fallback" :i i}]])]])

(def render-host-screen
  "The `:render` policy's own row, and it is separate from
  [[host-screen]] for a reason that is a fact about React rather than a
  taste. React 19's `ctx.Provider` **is the context object**, and that
  object carries a `Provider` key pointing back at itself — a cycle. Any
  walk that descends into a hiccup form's JS values therefore recurses
  forever on this head, and
  `re-frame.ssr.hash/render-tree-hash` is one such walk
  (`entry_cljs_test` → `the-hash-this-root-would-have-had-is-a-constant`
  takes it over [[host-screen]] as its non-vacuity control). Nothing on
  a live path hashes an interpreted root — this tier deliberately ships
  no `:rf/render-hash` at either end (rf2-2rtt6.91) — so the cycle is
  filed rather than worked around, and this row keeps the measurement
  row's input free of it."
  [:div.render-hosts
   [:h1 "render"]
   [render-host {:value "dark"}
    [:p.render-subtree "RENDER-SUBTREE"]
    [render-reader-host {}]]])

(defview nested-host-panel
  "THE POSITION NO PRE-WALK CAN REACH. All three hosts are used inside a
  BOUNDARY BODY, so their elements do not exist when the render entry is
  handed its hiccup: this body runs inside `renderToString`, and the
  codec's own crossing creates them there.

  The `:ssr` policy is honoured all the same, because it is enforced at
  the element's TYPE — for the two gated policies `mint-host!` mints a
  gate whose `useSyncExternalStore` answers `false` from its server
  snapshot, and for `:render` it mints no gate at all and the component
  is the type. That is the argument; [[nested-host-screen]] rendered
  through the entry is the witness."
  [_]
  [:div.nested-hosts
   [:h2 "nested"]
   [default-host {:kind "nested-default"}]
   [fallback-host {:kind "nested-fallback"}]
   [render-host {:value "dark"}
    [:p.nested-render-subtree "NESTED-RENDER-SUBTREE"]
    [render-reader-host {}]]])

(def nested-host-screen
  "[[nested-host-panel]] under an ordinary root, so \"the host region is
  absent\" stays distinguishable from \"the page rendered nothing\"."
  [:div.nested-page
   [:h1 "nested hosts"]
   [nested-host-panel {}]])

;; ---------------------------------------------------------------------------
;; The presence row — the hydration-parity guard
;; ---------------------------------------------------------------------------

(def presence-tray
  "A presence tray whose children carry `::h/mounting` attribute
  overrides.

  This row MEASURED a defect the rf2-2rtt6.84 worker predicted, and now
  guards its repair. Presence's machine starts a child at `:mounting`
  (`arm1/presence.cljs` — `(react/useState presence/initial)`), and
  while a child is in that phase the tray applies its `::h/mounting`
  overrides. A server render with no adoption window open therefore
  shipped the ENTER appearance — the `opacity: 0` class an animation is
  about to move off — into the HTML, while the hydrating client's first
  pass rendered those same children `:present` (born-present under an
  open window): a hydration mismatch on every presence-managed node.

  rf2-2rtt6.94 opened the same window around `renderToString`, so this
  row's server bytes are born-present too and carry no `toast--enter` at
  all. `the-server-render-ships-no-mounting-overrides` asserts exactly
  that, and this row is the only shape in the corpus that can go red if
  the window is ever removed. See [[re-frame.bench.hicasso.ssr.entry]]
  §The adoption window."
  [presence {:timeout-ms 200}
   (for [i (range 2)]
     [:div.toast {:key                          i
                  :data-id                      i
                  :re-frame.hicasso/mounting    {:class "toast--enter"}
                  :re-frame.hicasso/unmounting  {:class "toast--exit"}}
      (str "toast " i)])])

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def dogfood-payload-keys
  "The dogfood app-db's top-level keys — the allowlist arm of the
  hydration-payload contract, written out rather than derived, so a new
  key is a decision to ship it rather than an accident of a `keys` call."
  [:todos :order :drafts :filter :next-id])

(def corpus
  "The ordered roster. See the namespace docstring."
  [{:id     "dogfood-snapshot"
    :why    "the dogfood screen, seeded through the :rf/set-db snapshot-in door, with a key allowlist"
    :hiccup [collector/screen {}]
    :snapshot (dogfood/seed-db 8)
    :payload  dogfood-payload-keys
    :title    "Hicasso SSR — dogfood (snapshot-in)"
    :script-src "/main.js"}

   {:id     "dogfood-initial-events"
    :why    "the same screen through the :initial-events door, and the whole-app-db payload opt-in"
    :hiccup [collector/screen {}]
    :initial-events [[:dogfood/seed 3]
                     [:dogfood/toggle 1]
                     [:dogfood/edit-draft dogfood/new-draft-key "half typed"]]
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — dogfood (initial-events)"
    :script-src "/main.js"}

   {:id     "conduit-feed"
    :why    "a tier-1 bulk shape — the ~1,200-element Conduit feed page, at the size the bar rows are taken at"
    :hiccup [large-template/page {}]
    :snapshot   (model/seed-db large-template/seed)
    ;; The census is a HASH-URL app and its anchors go through routing's
    ;; `link-model`, whose strategy consult defaults to the history
    ;; strategy when a frame declares none — so the frame declares the
    ;; one `shapes/model/make-frame!` declares, through the same door.
    :frame-opts {:url-strategy routing/hash-url-strategy}
    :payload    [:articles :order :tags :user :page :your-feed?]
    :title      "Hicasso SSR — conduit feed"
    :script-src "/main.js"}

   {:id     "presence-mounting"
    :why    "presence's children are born PRESENT server-side — the adoption window is open around renderToString, so no ::h/mounting override reaches the HTML (rf2-2rtt6.94)"
    :hiccup presence-tray
    :snapshot {}
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — presence (born present)"
    :script-src "/main.js"}

   {:id     "defhost-ssr-policy"
    :why    "defhost regions honour the :ssr policy server-side — default :client-only renders nothing, {:fallback} renders the fallback, :render renders the component with its children under the declared context value"
    :hiccup host-screen
    :snapshot {}
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — defhost :ssr policy"
    :script-src "/main.js"}

   {:id     "defhost-ssr-render"
    :why    "the third policy (rf2-l0wfx) — :render mints no gate, so the component, its CHILDREN and the declared context value are all in the server bytes; the only policy under which a crossing's subtree reaches the response at all"
    :hiccup render-host-screen
    :snapshot {}
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — defhost :ssr :render"
    :script-src "/main.js"}

   {:id     "defhost-ssr-nested"
    :why    "the SAME declarations used INSIDE a defview body — the position no walk over the handed-in hiccup could reach, honoured because the policy is the element's own type"
    :hiccup nested-host-screen
    :snapshot {}
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — defhost :ssr policy, nested"
    :script-src "/main.js"}

   ;; THE MATCHED PAIR. Same hiccup, same boot events, same everything
   ;; but the `:payload` allowlist — which is the only way to state the
   ;; obligation as a measurement rather than as prose. Both allowlists
   ;; are WELL FORMED: the fail-closed policy refuses an ABSENT one and
   ;; has no way to refuse a wrong one, so what catches the second row is
   ;; React's own hydration-mismatch machinery and nothing else.
   {:id     "instance-key-payload"
    :why    "server boot events write reg-state instance state and the allowlist NAMES :ui — the obligation met"
    :hiccup [instance-key/screen {}]
    :initial-events instance-key/boot-events
    :payload  (conj instance-key/domain-keys :ui)
    :title    "Hicasso SSR — instance state, :ui shipped"
    :script-src "/main.js"}

   {:id     "instance-key-payload-omitted"
    :why    "the SAME page with :ui OMITTED — a well-formed allowlist that strands the instance state, and the red-by-design half of the obligation witness"
    :hiccup [instance-key/screen {}]
    :initial-events instance-key/boot-events
    :payload  instance-key/domain-keys
    :title    "Hicasso SSR — instance state, :ui stranded"
    :script-src "/main.js"}])

(defn ids
  "The corpus row ids, in roster order."
  []
  (mapv :id corpus))

(defn row
  "The corpus row named `id`, or nil."
  [id]
  (first (filter #(= id (:id %)) corpus)))

(defn register!
  "Everything the corpus needs registered before a request is rendered.

  Only the census's route table today — `re-frame.routing/reg-route` is
  a GLOBAL registration, not a frame's, so it cannot ride `:frame-opts`.
  Idempotent, and called by every driver and every witness before the
  first render so no caller has to remember an ordering."
  []
  (model/register-routes!)
  nil)
