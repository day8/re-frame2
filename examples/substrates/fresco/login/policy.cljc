(ns fresco.login.policy
  "The login arm's SHARED NAMESPACE — the entry id both halves name, and
  the RENDER-STATE POLICY the JVM host projects under.

  `host.clj` (JVM) hands `render-state-policy` to the Node renderer as
  `:render-state` and names `root-entry` as its `:entry`; `server.cljs`
  (Node) publishes `root-entry` as its entry table. The policy is the one
  render-visibility policy on this path: the sidecar checks the shape of
  what the JVM sends, never which keys, so a key this list names is a key
  the render receives — and a key it omits renders as `nil`, with no
  refusal to notice.

  It is a `.cljc` namespace of its own rather than a `def` in either
  neighbour because `server.cljs` cannot be read by Clojure and `host.clj`
  cannot be read by ClojureScript, so an id written in either is an id the
  other has to copy.

  ## What the render is allowed to see

  It is DISTINCT from the host's `:payload` policy — *what may the BROWSER
  see?* — and deliberately so:

    `:auth`                      the form slice at `[:auth :login-form]` —
                                 the draft the inputs are bound to. Also in
                                 the payload, because the client needs it to
                                 re-render the same controlled inputs. The
                                 draft PASSWORD is classified `:sensitive`
                                 by the shared model, so the projection
                                 redacts it on both wires and it arrives as
                                 the sentinel `:rf/redacted`. The render
                                 cannot print a secret it was never handed,
                                 so the view renders that sentinel empty. The
                                 client owns this value, so it sets it after
                                 hydrating (Spec 011's post-hydration rule).
    `:auth.login/server-notice`  a deployment notice the host resolves per
                                 request. NOT in the payload — the browser
                                 never receives it. A key in this position
                                 must not change the markup; see the note
                                 beside the sub in `core.cljs`.
    `:rf.runtime/machines`       the machine snapshots. `:auth.login/flow`
                                 is what decides which of the page's three
                                 faces renders, so without this partition
                                 the server would render the form for an
                                 authenticated visitor. It lives in
                                 runtime-db, which is exactly why the render
                                 state is TWO partitions and not one.")

(def root-entry
  "The entry identifier the sidecar's entry table publishes and the JVM
  host names in its renderer opts. One root, one entry; a bigger
  application publishes one per server-rendered route. Here because two
  processes need one spelling."
  "fresco.login/root")

(def render-state-policy
  "The one list. `{:app-db [<top-level keys>] :runtime-db [<top-level keys>]}`,
  the map shape `re-frame.ssr.render-state` validates and the JVM host
  projects under."
  {:app-db     [:auth :auth.login/server-notice]
   :runtime-db [:rf.runtime/machines]})
