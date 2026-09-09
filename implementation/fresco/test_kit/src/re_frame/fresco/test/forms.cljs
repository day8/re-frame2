(ns re-frame.hicasso.test.forms
  "THE FORMS MODULE'S PROTOCOL IDS — for a test that drives a
  `forms/buffered-field` by hand rather than through its `<input>`.

      (:require [re-frame.hicasso.test.forms :as tf])

      (rf/dispatch-sync [tf/edit-id control revision \"typed\"])
      (rf/dispatch-sync [tf/commit-id control revision [:todo/title-committed 7]])

  `re-frame.hicasso.forms` registers its three events under these
  keywords and writes them into the field's own intents, so they are
  already visible in a rendered tree, in a captured intent and in Xray;
  what this namespace adds is a NAME for each, so a test spells neither
  the literal nor a var the product module keeps for itself. It requires
  nothing: the ids are data, and a kit that required the module would put
  the module into every test that used the kit
  (`implementation/hicasso/scripts/check_optional_module_reachability.py`).
  Design record: docs/design/hicasso/product/naming-ledger.md row 49.")

(def edit-id
  "`[edit-id control revision text]` — the keystroke event, the field's
  `:on-input`."
  :re-frame.hicasso.forms/edit)

(def commit-id
  "`[commit-id control revision on-commit]` — the commit event, Enter
  and blur alike."
  :re-frame.hicasso.forms/commit)

(def cancel-id
  "`[cancel-id control revision on-cancel]` — the cancel event, Escape."
  :re-frame.hicasso.forms/cancel)
