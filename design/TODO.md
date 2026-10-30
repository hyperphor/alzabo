# interactivity revamp
## Search widget should be on all pages, not just main schema
## Jeff: search should highlight node in graph
## graph and the kind list should mutually highlight
## bk: hover on node highlights  relations
## want some single-page mode where graph click shows kind info in a pane, rather than seprate page.



# alternatices to graphivz
https://mermaid.ai
Wait this is a while AI diagram generator, even does ontologies (as hierarchies, but presumably easy enough to extend) https://mermaid.ai/app/projects/1aef4032-230e-43a1-b058-888707833e6b/diagrams/2fdc71b9-a027-4a9a-9bfd-8088c8d9671e/version/v0.1/edit
Weird their graphics seems very capable but doesn't include any examples of arbitrary graphs



# IDEA jev-based API for regularizing a spreadheet (eg) to an ontology

It might not belong here (ontogeny is a better fit)

But fuck it, I'm starting to realize ellum and alzabo should be combined, the value-add is how these things work
together. Elzabo! Well that is very clever, zero people will get that!


# FIXED (pending review) BUG search on AACT is broken
"purp" returns nothing although their are docstrings and entities that should match: designs.primary_purpose

Root cause: lexicon.cljc only split identifier words on `-`, not `_`, and
never indexed a kind's own :doc text (only per-field docs). Fixed in
search/lexicon.cljc, with a regression test.

# FIXED (pending review) BUG inverse links appear not to work? Empty on most new schemas

Root cause: schema/inverse-fields reduced over the whole schema map instead
of (:kinds schema), so it was structurally empty for any real schema.
One-line fix in schema.cljc, with a regression test.

# TODO enum page should have back references

# TODO breakout ui into separate project
Maybe, only if library wants to be used wthout the UI dependencies. I
