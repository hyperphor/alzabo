Hyperphor era TODO

# FIXED (pending review) BUG search on AACT is broken
"purp" returns nothing although their are docstrings and entities that should match: designs.primary_purpose

Root cause: lexicon.cljc only split identifier words on `-`, not `_`, and
never indexed a kind's own :doc text (only per-field docs). Fixed in
search/lexicon.cljc, with a regression test.

# FIXED (pending review) BUG inverse links appear not to work? Empty on most new schemas

Root cause: schema/inverse-fields reduced over the whole schema map instead
of (:kinds schema), so it was structurally empty for any real schema.
One-line fix in schema.cljc, with a regression test.

# DONE (pending review) IDEA schema-gen should add icons

Added `add-icons` in schema_gen_llm.clj: a standalone step (not wired into
sgen) that asks the LLM for a conservative {kind icon} map (skipping kinds
that already have one) and merges it in with plain Clojure, avoiding
add-doc's known "incomplete EDN" failure mode. Verified live against
jazz.edn — iconed 6/17 kinds, left the rest alone. Also surfaced (but did
not fix) pre-existing bugs in this file: sgen/add-doc/improve-doc's
llm/complete calls are broken against the current ellum API (missing
:provider, wrong response key) and can't currently be invoked at all.

