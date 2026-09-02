(ns hicasso.login.policy
  "The login arm's RENDER-STATE POLICY — one list, two readers, one file.

  `server.cljs` (Node) derives the sidecar entry's per-partition
  allowlists from it; `host.clj` (JVM) hands it to the Node renderer as
  `:render-state`. The two processes are deployed separately, so nothing
  can MAKE them agree at run time — but they cannot DRIFT in source,
  because there is only one list to edit.

  That is the whole reason this is a `.cljc` namespace of its own rather
  than a `def` in either neighbour. `server.cljs` cannot be read by
  Clojure and `host.clj` cannot be read by ClojureScript, so a policy
  written in either is a policy the other has to copy — and a copy that
  drifts the SAFE way is silent. The sidecar refuses a host that asks for
  MORE than the entry allows (its state-key-not-allowed refusal); a host
  that asks for LESS is served a page rendered from incomplete state, with
  no refusal to notice. The second is the direction a hand-kept copy
  actually fails in.

  ## What the render is allowed to see

  It is DISTINCT from the host's `:payload` policy — *what may the BROWSER
  see?* — and deliberately so:

    `:auth`                      the form slice at `[:auth :login-form]` —
                                 the draft the inputs are bound to. Also in
                                 the payload, because the client needs it to
                                 re-render the same controlled inputs. The
                                 draft PASSWORD is classified `:sensitive`
                                 by the shared model, so the projection
                                 redacts it on both wires: the render cannot
                                 print a secret it was never handed.
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
  "The entry identifier the sidecar's entry table is keyed by and the JVM
  host names in its renderer opts. One root, one entry; a bigger
  application publishes one per server-rendered route. Here for the same
  reason the policy is: two processes, one spelling."
  "hicasso.login/root")

(def render-state-policy
  "The one list. `{:app-db [<top-level keys>] :runtime-db [<top-level keys>]}`,
  the map shape `re-frame.ssr.render-state` validates and both halves read."
  {:app-db     [:auth :auth.login/server-notice]
   :runtime-db [:rf.runtime/machines]})
