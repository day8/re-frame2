(ns re-frame.bench.hicasso.ssr.instance-key
  "THE PAGE THE PAYLOAD OBLIGATION IS WITNESSED ON (rf2-2rtt6.99).

  One screen, two disclosure panels, and one `h/reg-state` concern
  (`re-frame.bench.hicasso.front.state`). The server's boot events open
  the first panel — `[::open? \"billing\" true]`, the ordinary setter
  `reg-state` mints — so the page this namespace describes is a page
  whose APPEARANCE depends on instance state a server-side event wrote.

  That is the whole reason it exists. `[:ui ::concern ikey]` is app-space
  data like any other, which means the hydration payload policy is what
  decides whether it crosses the wire, and the policy is fail-closed
  against ABSENCE but cannot be fail-closed against a WRONG allowlist: a
  list naming `[:panels]` is perfectly well formed and ships a page whose
  server bytes say open and whose client says shut. The corpus therefore
  carries this screen TWICE, under two allowlists, and
  `ssr/instance-key-payload-dom-cljs-test` reads what happens.

  ## The instance keys are AUTHORED DATA, and that is load-bearing

  [[panels]] is a roster written out here. Every instance key is a
  `:id` taken from it — never a render-order index, never a counter,
  never `random-uuid`, and never `useId`. That is `front/state`'s own
  determinism rule (\"if it would be a good React `:key`, it is a good
  instance key\") stated as a fixture: a server render and its client
  hydration agree on these keys because both read them out of the same
  seeded app-db, so a divergence anywhere in the two rows below is a
  divergence about the PAYLOAD and never about key generation.

  ## Deliberately small

  Two panels, one concern, one boolean. The rows this fixture serves are
  structural — did the instance state cross the wire, and what did React
  say when it did not — so every element on the page is either the thing
  being measured or the sibling that proves the page rendered at all."
  (:require [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.state :as state]
            [re-frame.core :as rf])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; The concern, and the authored roster
;; ---------------------------------------------------------------------------

(def open?
  "The `reg-state` concern this fixture is built on. Its `:default` is
  `false`, which is what makes the omission row's client render a CLOSED
  panel: an absent `[:ui ::open? ikey]` entry reads the default, and the
  default is the shut one."
  ::open?)

(def panels
  "The roster, as authored data. Instance keys are the `:id` values —
  domain ids, per `front/state`'s first taught rule, and stable across
  a server render and its client hydration because both read them from
  this same seeded value."
  [{:id "billing"  :title "Billing"}
   {:id "shipping" :title "Shipping"}])

(def open-key
  "The panel the SERVER's boot events open. The other panel stays shut in
  both rows, so \"the payload did not arrive\" is distinguishable from
  \"the page rendered nothing\"."
  "billing")

(def state-path
  "Where the opened panel's flag sits — `[:ui ::open? \"billing\"]`, the
  documented tier. Written out so the witness asserts on the same vector
  the sugar writes rather than on a hand-spelled copy of it."
  [state/ui-root open? open-key])

(defn seed-db
  "The domain half of the app-db: the roster and nothing else. The
  instance half is written by the boot event, which is the point."
  []
  {:panels panels})

;; ---------------------------------------------------------------------------
;; Registration — ordinary core artefacts, at ns load like every sibling
;; ---------------------------------------------------------------------------

(rf/reg-sub ::panel-ids (fn [db _] (mapv :id (:panels db))))

(rf/reg-sub ::panel-title
  (fn [db [_ id]] (some (fn [p] (when (= id (:id p)) (:title p))) (:panels db))))

(rf/reg-event ::seed (fn [_ _] {:db (seed-db)}))

;; The sugar's own registration. A plain call, at load, beside the
;; `reg-sub`s above — because that is all `reg-state` is: two ordinary
;; registrations and a naming convention. Re-registration with the same
;; `:default` is the no-op-shaped refresh `reg-state` documents, so a
;; reload of this namespace is safe.
(state/reg-state open? {:default false})

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(defview panel
  "A disclosure. It holds its own open flag under the instance key it was
  handed and knows nothing else about where it sits.

  The body is a CONDITIONAL ELEMENT rather than a changed attribute or a
  changed string: an element the server rendered and the client does not
  is an unambiguous structural divergence, where a text difference would
  be measuring React's SSR text-separator behaviour (rf2-2rtt6.88)
  alongside the thing under test."
  [{:keys [ikey title]}]
  (let [shown? (rt/sub [open? ikey])]
    [:section.panel {:data-ikey ikey}
     [:button.panel-toggle {:on-click [open? ikey (not shown?)]} title]
     (when shown?
       [:div.panel-body "the details"])]))

(defview screen
  "The page. A heading, then one [[panel]] per roster entry, keyed by the
  same authored id it is instance-keyed by."
  [_]
  [:div.instance-key-page
   [:h1.title "instance state across the wire"]
   [:div.panels
    (for [id (rt/sub [::panel-ids])]
      [panel {:key id :ikey id :title (rt/sub [::panel-title id])}])]])

;; ---------------------------------------------------------------------------
;; The two requests, minus their payload policies
;; ---------------------------------------------------------------------------

(def boot-events
  "What the SERVER runs before it renders: seed the roster, then open one
  panel through `reg-state`'s ordinary setter event. The second of these
  is the whole obligation — a render-affecting write into `[:ui …]`
  performed by a server-side event."
  [[::seed]
   [open? open-key true]])

(def domain-keys
  "The domain half of the allowlist, shared by both rows so the ONLY
  difference between them is whether `:ui` is named beside it."
  [:panels])
