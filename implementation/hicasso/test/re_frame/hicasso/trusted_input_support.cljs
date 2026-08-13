(ns re-frame.hicasso.trusted-input-support
  "A REAL KEY PRESS, FROM INSIDE A cljs.test ROW (rf2-il7b).

  Three suites in this package used to STATE a gap rather than measure
  it, and they were right to: a page cannot forge a trusted event, and
  the half of an event a page cannot forge is the DEFAULT ACTION. A
  synthetic `keydown` reaches every listener — React's included, which
  is why `combobox-keyboard-dom-cljs-test` drives arrows and Enter for
  real — and then the engine does nothing with it. Tab moves focus
  nowhere. Escape closes no dialog.

  Measured in this repo's own headless Chromium, on a bare page with two
  buttons and a modal `<dialog>`:

  | key | synthetic `KeyboardEvent` | `page.keyboard.press` |
  |---|---|---|
  | Tab | `dispatchEvent` → true, `activeElement` unchanged | focus moves to the next control |
  | Escape | `dispatchEvent` → true, 0 `cancel` events, dialog still `:modal` | `cancel` fires once, dialog closed |

  ## What this is

  The page half of a bridge whose other half is
  `implementation/scripts/run-browser-tests.cjs`. It is the same shape as
  that runner's deterministic-GC bridge: publish a request on a
  well-known global, suspend, and let the runner — which owns a
  Playwright page and therefore owns the only trusted input there is —
  perform the press and acknowledge it.

  It is NOT a general RPC channel to the runner. The only thing it will
  ever ask for is a key press.

  ## Why every caller is an `async` row

  There is no synchronous way to be pressed. The press happens in
  another process, and a page that spun waiting for it would be holding
  the very thread the engine needs in order to deliver the key. So a row
  that wants a real press is an `(async done …)` row, and its namespace
  needs MAP fixtures — `{:async? true}` on
  `re-frame.test-support/make-reset-runtime-fixture` — because cljs.test
  refuses an async row under a positional one by throwing a bare string
  that unwinds the entire lane (rf2-u0j8).

  ## Why the readings need no settle

  `page.keyboard.press` dispatches into the renderer over CDP, and the
  acknowledging `page.evaluate` is queued into that same renderer
  afterwards. So the engine has already run the key's default action by
  the time [[press!]]'s callback fires, and a row may read
  `document.activeElement` on the next line. React's own re-render, if
  the press caused one, still wants the usual settle."
  (:require [cljs.test :refer-macros [is]]))

;; The two halves of the protocol, spelled once. Drift here is what the
;; runner's `THE BROWSER RUN STALLED ON THE TRUSTED-INPUT BRIDGE` arm
;; exists to name.
(def ^:private request-key "__RF2_TOOL_TRUSTED_INPUT_REQUEST__")
(def ^:private bridge-key "__RF2_TOOL_TRUSTED_INPUT_BRIDGE__")

(defn bridge?
  "Is a runner listening on the other end?

  The runner publishes [[bridge-key]] with `addInitScript`, i.e. before
  any page script runs, so this is decidable at the top of a row rather
  than by timing out. `:node-test` has no runner and answers false, as
  does any browser lane driven by something other than
  `scripts/run-browser-tests.cjs`."
  []
  (and (exists? js/globalThis)
       (true? (unchecked-get js/globalThis bridge-key))))

(defonce ^:private !seq (atom 0))

(defn press!
  "Ask the runner to press `keys` — a seq of Playwright key names such as
  `[\"Tab\"]` or `[\"Escape\"]` — and call `k` with
  `{:pressed n :error e}` when it has.

  `:pressed` is how many of them actually went through and `:error` is
  the engine's complaint if one did not, so a caller can assert on the
  press itself rather than only on its consequence.

  Fails FAST rather than hanging when there is no bridge: a suspended
  row is a suspended LANE (shadow.test runs every namespace inside one
  `cljs.test/run-block`), so the one thing this must never do is wait
  for an answer that is not coming."
  [keys k]
  (if-not (bridge?)
    (js/setTimeout
      (fn []
        (k {:pressed 0
            :error   (str "no trusted-input bridge on this page: `" bridge-key
                          "` is not set. Only scripts/run-browser-tests.cjs "
                          "installs it.")}))
      0)
    (let [token (str "rf2-trusted-input-" (swap! !seq inc))]
      (unchecked-set
        js/globalThis request-key
        #js {:token token
             :keys  (to-array (mapv str keys))
             :ack   (fn [^js result]
                      (k {:pressed (or (.-pressed result) 0)
                          :error   (.-error result)}))})))
  nil)

(defn press-once!
  "[[press!]] for a single key, with the press itself asserted.

  Every caller wants the same two things — the key went through, and
  nothing went wrong doing it — and a row that forgot to check would
  read an unchanged DOM as a failed assertion about the DOM rather than
  as a bridge that never fired."
  [key k]
  (press! [key]
          (fn [{:keys [pressed error]}]
            (is (= 1 pressed)
                (str "the runner pressed `" key "` — pressed=" pressed
                     (when error (str ", error=" error))))
            (k))))

(defn walk!
  "Press `key` `n` times, calling `read` after each press, and hand the
  vector of readings to `k`.

  The traversal recorder: `(walk! \"Tab\" 4 #(.-id (.-activeElement js/document)) k)`
  answers the four ids a user would land on. One request per press,
  deliberately — a batch would be one round trip and would see none of
  the states in between, and the states in between are the order."
  [key n read k]
  (letfn [(step [i acc]
            (if (>= i n)
              (k acc)
              (press-once! key (fn [] (step (inc i) (conj acc (read)))))))]
    (step 0 [])))

(defn unwitnessed!
  "The honest red for a browser lane with no bridge.

  Not a skip: `:node-test` has no DOM at all and every row in these
  suites already states a skip there, but a BROWSER lane that cannot
  press a key is a misconfiguration, and a stated gap that nobody
  notices is how a bead like this one gets filed twice."
  [why]
  (is false
      (str "a real key press needs scripts/run-browser-tests.cjs's "
           "trusted-input bridge, and this lane has none — " why)))
