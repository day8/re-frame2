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
  - **`defhost`'s `:ssr` policy**, both meanings: a host with no declared
    policy (the ruled `:client-only` default — its component's markup MUST
    be absent from the server HTML) and a host declaring a fallback (whose
    placeholder markup MUST be present).
  - **Presence's `::h/mounting` overrides** — and that row is a PINNED
    DEFECT rather than a feature. See [[presence-tray]].

  ## Why the host rows stamp the policy slot by hand

  rf2-2rtt6.85 owns `mint-host!`'s `:ssr` option and has NOT landed —
  its PR (#7468) was still open when this file was written. The DEFAULT
  row needs nothing (a head with no policy already reads `:client-only`)
  and the FALLBACK row stamps the slot directly, which is the ordinary
  way to test a reader ahead of its writer.

  Checked against #7468 rather than assumed: that PR stores the policy
  under the same own-property this stamp writes, and enforces it at the
  element's own TYPE — so when it lands, [[fallback-host]] becomes
  `defhost … {:ssr {:fallback …}}`, the two host rows keep asserting
  exactly what they assert now, and the server WALK they exercise
  ([[re-frame.bench.hicasso.ssr.host-policy]]) is retired under
  rf2-2rtt6.92."
  (:require [re-frame.bench.hicasso.arm1.dogfood-collector :as collector]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.shapes.large-template :as large-template]
            [re-frame.bench.hicasso.shapes.model :as model]
            [re-frame.bench.hicasso.ssr.host-policy :as host-policy]
            [re-frame.routing :as routing]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; The two host rows
;; ---------------------------------------------------------------------------

(defn- client-widget
  "A foreign component whose markup is the tell. Both host rows render
  THIS, so a fixture's verdict is a substring test on one distinctive
  string rather than a structural argument."
  [_js-props]
  (react/createElement "em" #js {"className" "client-widget"} "CLIENT-ONLY-WIDGET"))

(def default-host
  "A host declaring no `:ssr` policy at all — which is the ruled
  `:client-only` default, reached with no stamping because
  `host-policy/policy-of` answers it for an absent slot."
  (codec/mint-host! "ssr-fixture/default" client-widget))

(def fallback-host
  "A host declaring `{:fallback …}`. The stamp is rf2-2rtt6.85's to
  replace; see the namespace docstring."
  (let [head (codec/mint-host! "ssr-fixture/fallback" client-widget)]
    (unchecked-set head host-policy/policy-slot
                   {:fallback [:span.host-fallback "loading…"]})
    head))

(def host-screen
  "One page carrying both hosts, nested inside ordinary markup and inside
  a `for` — the lazy position, because a walk that stopped at a seq would
  pass a root-level test and miss every row."
  [:div.hosts
   [:h1 "hosts"]
   [default-host {:kind "default"}]
   [:ul
    (for [i (range 2)]
      [:li {:key i} [fallback-host {:kind "fallback" :i i}]])]])

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
    :why    "defhost regions honour the :ssr policy server-side — default :client-only renders nothing, {:fallback} renders the fallback"
    :hiccup host-screen
    :snapshot {}
    :payload  :rf.ssr.payload/whole-app-db
    :title    "Hicasso SSR — defhost :ssr policy"
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
