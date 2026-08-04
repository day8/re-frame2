(ns re-frame.bench.hicasso.arm1.dogfood-script
  "THE ONE REAL-DOM INTENT SCRIPT for the dogfood screen (rf2-2rtt6.67),
  and the intents it is EXPECTED to dispatch.

  Written once, driven by everything that wants to ask whether a
  rendering of that screen MEANS the same thing when a user touches it:

  - `arm1_dogfood_dom_cljs_test` drives it at the collector rendering and
    at raw UIx, and asserts the two captures equal each other and equal
    [[interaction-intents]];
  - `ssr/spike_dom_cljs_test` (rf2-2rtt6.87, X4) drives it at the
    HYDRATED screen, and asserts the capture equals the same vector.

  It lives here rather than in whichever file happened to need it first
  because the script's SIZE is part of every claim made with it. A
  merged-PR audit (#7395) found an equivalence published over eight
  handler sites while the script drove five; the repair was to drive all
  eight and to carry the coverage map beside the steps. A second copy of
  the script is a second thing that can shrink without its claim
  shrinking with it.

  ## What the script is made of, and why not Playwright

  Real DOM events, fired by hand: a real `click`, a keystroke written
  through `HTMLInputElement.prototype`'s own `value` setter, a real
  `keydown` carrying the NATIVE `isComposing` / `keyCode` signals. That
  is deliberate — React's synthetic keyboard event DROPS `isComposing`,
  so a gate that read the synthetic event would be deaf to the composing
  law these steps exist to assert.

  Nothing here reads a subscription, mounts anything or asserts
  anything. The steps take a mount HANDLE (`{:container …}`) and the
  caller owns the mount, the capture and the assertions."
  (:require [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]))

;; ---------------------------------------------------------------------------
;; The three ways a user touches a page
;; ---------------------------------------------------------------------------

(defn q1 [handle sel] (.querySelector (:container handle) sel))

(defn set-native-value!
  "Write `v` through `HTMLInputElement.prototype`'s OWN value setter,
  bypassing React's per-instance change tracker — the same door
  `arm1_controlled_grid_dom_cljs_test` documents. Assigning through the
  instance property updates the tracker too, and React then dedupes the
  `input` event as a no-change echo."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)))

(defn type-into!
  "Append `text` and fire the `input` event, the way a browser orders the
  two: field first, event second."
  [node text]
  (set-native-value! node (str (.-value node) text))
  (.dispatchEvent node (js/Event. "input" #js {:bubbles true}))
  nil)

(defn keydown!
  "Fire a real `keydown`. `:composing?` and `:key-code` ride the NATIVE
  event, which is where every rendering's gate reads them."
  [node {:keys [key composing? key-code]}]
  (.dispatchEvent node (js/KeyboardEvent. "keydown"
                                          #js {:key         key
                                               :bubbles     true
                                               :isComposing (boolean composing?)
                                               :keyCode     (or key-code 0)}))
  nil)

;; ---------------------------------------------------------------------------
;; The script, and its stated expectation
;; ---------------------------------------------------------------------------

(def interaction-intents
  "What the script is EXPECTED to dispatch, written out rather than
  computed from any rendering. The two composing keystrokes appear
  nowhere in it — their expectation is silence.

  Stating the expectation rather than only comparing two captures is
  what lets a gate answer false against a drift the captures SHARE."
  [[:dogfood/toggle 1]
   [:dogfood/set-filter :done]
   [:dogfood/set-filter :active]
   [:dogfood/set-filter :all]
   [:dogfood/edit-draft dogfood/new-draft-key "milk"]
   [:dogfood/cancel dogfood/new-draft-key]
   [:dogfood/edit-draft dogfood/new-draft-key "milk"]
   [:dogfood/create]
   [:dogfood/edit-draft 0 "x"]
   [:dogfood/cancel 0]
   [:dogfood/edit-draft 3 "renamed"]
   [:dogfood/commit 3]
   [:dogfood/remove 2]
   [:dogfood/edit-draft dogfood/new-draft-key "bread"]
   [:dogfood/create]])

(defn interaction-steps
  "The one script, as thunks over `handle`'s container. Steps are run one
  per macrotask ([[run-steps!]]) because raw UIx's `dispatch` is the
  router's asynchronous door — the drain is a next-turn task — while the
  collector's is HD-019's synchronous one. The script must not care which
  it is driving, and a step's target can be a node the previous step's
  drain revealed.

  **Every handler site the screen has, and every branch of both key-maps,
  is driven here** (rf2-2rtt6.67, merged-PR audit of #7395 — the first
  cut left three unexercised while the page claimed equivalence over all
  of them). The eight sites against the steps that reach them:

  | Handler site | Steps |
  |---|---|
  | toggle click | 1 |
  | filter click | 2, 3, 4 — all three filters, so no branch of `visible-ids` is untaken |
  | new-item `:on-input` | 5, 9, 16 |
  | new-item keys | 6, 7 (composing, silent), 8 (Escape), 10 (Enter) |
  | row `:on-input` | 11, 13 |
  | row keys | 12 (Escape), 14 (Enter) |
  | remove click | 15 |
  | form submit | 17 |

  `:dogfood/move` has no affordance in the markup, so it is correctly
  absent rather than missing."
  [handle]
  [#(.click (q1 handle "[data-id=\"1\"] .toggle"))         ; 1  the narrow write
   #(.click (q1 handle ".filter[data-filter=\"done\"]"))   ; 2  the broad write…
   #(.click (q1 handle ".filter[data-filter=\"active\"]")) ; 3  …its other branch…
   #(.click (q1 handle ".filter[data-filter=\"all\"]"))    ; 4  …and back
   #(type-into! (q1 handle ".new-input") "milk")           ; 5
   ;; 6, 7 — the two composing keystrokes: the law is that NEITHER commits.
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? true  :key-code 13})
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? false :key-code 229})
   ;; 8 — the new-item field's OTHER key branch: Escape discards the draft,
   ;; which is why step 9 can retype it from empty.
   #(keydown! (q1 handle ".new-input") {:key "Escape" :composing? false :key-code 27})
   #(type-into! (q1 handle ".new-input") "milk")           ; 9
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? false :key-code 13}) ; 10
   #(type-into! (q1 handle "[data-id=\"0\"] .draft") "x")  ; 11
   #(keydown! (q1 handle "[data-id=\"0\"] .draft") {:key "Escape" :composing? false :key-code 27}) ; 12
   ;; 13, 14 — a row draft taken all the way to a COMMIT, the row key-map's
   ;; branch the first cut never drove.
   #(type-into! (q1 handle "[data-id=\"3\"] .draft") "renamed")
   #(keydown! (q1 handle "[data-id=\"3\"] .draft") {:key "Enter" :composing? false :key-code 13})
   #(.click (q1 handle "[data-id=\"2\"] .remove"))         ; 15
   #(type-into! (q1 handle ".new-input") "bread")          ; 16
   #(.click (q1 handle ".add"))])                          ; 17 a real form submission

(defn run-steps!
  "Run each thunk, then yield a macrotask — room for the router's drain —
  and settle React's sync lane before the next step reads the page."
  [steps k]
  (if (empty? steps)
    (k)
    (do ((first steps))
        (js/setTimeout (fn [] (mount/settle!) (run-steps! (rest steps) k)) 8))))
