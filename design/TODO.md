Hyperphor era TODO

# BUG search on AACT is broken
"purp" returns nothing although their are docstrings and entities that should match: designs.primary_purpose

# DONE icons should be on graph

`:icon` (a display string, e.g. an emoji like "🎯") is now a recognized
field on `:kinds` entries (backported from nlq/nlq-aact usage). No SVG
postprocessing hack needed — graphviz renders label text natively, so
`write-graphviz` just prepends the icon to the node's label when set.



