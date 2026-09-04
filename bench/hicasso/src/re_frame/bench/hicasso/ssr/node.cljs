(ns re-frame.bench.hicasso.ssr.node
  "THE NODE ENTRY POINT — what the bake and the live demo call
  (rf2-2rtt6.86 clauses 3 and 4).

  This namespace is the *process* half of the render entry, and it is
  deliberately thin: it installs the reactive substrate (Spec 006 allows
  exactly one per process, so it is a boot-time decision and not a
  per-request one), registers what the corpus needs, and publishes a tiny
  JS API for the drivers.

  ## Why the drivers are `.cjs` and this is the only CLJS in the process

  Everything under this lane is compiled by `npm run
  test:hicasso-compile`, which rides `:hicasso-bench` — a **browser**
  build. A CLJS namespace here that required `node:fs` or `node:http`
  would fail that gate, and a namespace placed outside the lane to dodge
  it would be exactly the silently-uncovered file the gate exists to
  catch. So the split is not a preference: the CLJS renders and returns
  strings, and the file system and the socket belong to the drivers.
  `react-dom/server` is the one npm module here, and it resolves in both
  targets.

  ## The API, and why it hangs off `globalThis`

  `:node-script` emits a program, not a module — it runs `:main` on load
  and exports nothing. A driver therefore `require`s the bundle for its
  effect and then reads the API off `globalThis`, which is the smallest
  thing that works and needs no build-target change. [[-main]] is that
  effect.

  Every returned value is a plain JS object of STRINGS: a driver that
  wanted to see a ClojureScript map through this seam would be a driver
  reaching into the render entry, which is the coupling the seam exists
  to prevent."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.dogfood-collector :as rf.bench.hicasso.arm1.dogfood-collector]
            [re-frame.bench.hicasso.front.dogfood :as rf.bench.hicasso.front.dogfood]
            [re-frame.bench.hicasso.ssr.entry :as rf.bench.hicasso.ssr.entry]
            [re-frame.bench.hicasso.ssr.fixtures :as rf.bench.hicasso.ssr.fixtures]
            [re-frame.core :as rf]))

(def ^:const api-slot
  "The `globalThis` property the drivers read the API off."
  "HICASSO_SSR")

(defn- result->js
  [{:keys [html document payload-edn frame-id]}]
  #js {"html"       html
       "document"   document
       "payloadEdn" payload-edn
       ;; NO `renderHash` (rf2-2rtt6.91). An adoption-tier root carries no
       ;; structural hash — Spec 011 §Hydration-mismatch detection — and a
       ;; column that was always the same value for every fixture is the
       ;; kind of number a manifest is worse for carrying.
       ;; The per-request gensym, as a string, so a driver can SHOW that
       ;; two requests took two frames. It is already destroyed.
       "frameId"    (str frame-id)})

(defn- require-row
  [id]
  (or (rf.bench.hicasso.ssr.fixtures/row id)
      (throw (ex-info (str "No SSR corpus row named " (pr-str id) ". Known: "
                           (pr-str (rf.bench.hicasso.ssr.fixtures/ids)))
                      {:id id :known (rf.bench.hicasso.ssr.fixtures/ids)}))))

(defn render-fixture
  "Render the corpus row named `id`."
  [id]
  (result->js (rf.bench.hicasso.ssr.entry/render (require-row id))))

(defn render-fixture-twice
  "Render the corpus row named `id` TWICE and hand both documents back —
  the determinism check's raw material. The driver hashes them; the
  entry has already compared them byte-for-byte."
  [id]
  (let [{:keys [identical? differs-at] a :first b :second}
        (rf.bench.hicasso.ssr.entry/render-twice (require-row id))]
    #js {"first"      (result->js a)
         "second"     (result->js b)
         "identical"  identical?
         "differsAt"  (if differs-at differs-at -1)}))

(defn render-dogfood
  "The LIVE DEMO's own request: the dogfood screen seeded with `n` to-dos,
  with no bootstrap `<script src>` — this bead ships no client bundle,
  because the hydration door is rf2-2rtt6.84's.

  `n` comes off the request, so a reader watching the demo can see that
  the state is the REQUEST'S and not the process's."
  [n]
  (result->js
    (rf.bench.hicasso.ssr.entry/render {:hiccup   [rf.bench.hicasso.arm1.dogfood-collector/screen {}]
                   :snapshot (rf.bench.hicasso.front.dogfood/seed-db n)
                   :payload  rf.bench.hicasso.ssr.fixtures/dogfood-payload-keys
                   :title    (str "Hicasso SSR — live, " n " to-dos")})))

(defn boot!
  "Install the substrate and register what the corpus needs. Once per
  process — Spec 006's law, not a convention."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf.bench.hicasso.ssr.fixtures/register!)
  nil)

(defn install!
  "Publish the driver API."
  []
  (unchecked-set js/globalThis api-slot
                 #js {"ids"          (clj->js (rf.bench.hicasso.ssr.fixtures/ids))
                      "render"       render-fixture
                      "renderTwice"  render-fixture-twice
                      "renderDogfood" render-dogfood})
  nil)

(defn -main
  "Boot and publish. A `:node-script` runs this on load, which is exactly
  what a driver `require`ing the bundle wants."
  [& _args]
  (boot!)
  (install!)
  nil)
