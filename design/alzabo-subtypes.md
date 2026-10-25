# Proposal: subtypes in the Alzabo schema model

Source: `design/pici-TODO.org` "Extend to subtypes!" (line 12) and "Subtypes" (line 45).

## Status: half of this already exists

Kinds can already declare parents with `:extends`. That was added in commit 418b0b9
("add extends (aka subkind) relation") and is documented in `design/INHERITANCE_FEATURE.md`.
What it gives you today:

- `:extends :parent` or `:extends [:p1 :p2]` on a kind (`::extends` in `schema.cljc`).
- Field inheritance: `get-parents`, `get-ancestors`, `inherited-fields` and `all-fields` in
  `schema.cljc`. Inherited and local field defs are deep-merged, and their docs are concatenated.
- Validation that parents exist and that there are no cycles (`validate-inheritance`).
- HTML: an "Extends:" box on each kind page, inherited fields marked `[inherited]`, and dashed
  green child→parent edges in the graph.
- Datomic export flattens all fields into each kind's namespace.
- Sample schemas: `test/resources/schema/jazz.edn` and `inheritance-demo.edn`. Tests are in
  `schema_inheritance_test.clj`.

So `:extends` is **field inheritance** (a mixin or "copy these fields" relation). It is not yet
**subtyping**: nothing treats a `:composer` as *being* a `:musician` or a `:person`. The rest of
this doc covers that gap.

## The gaps (observed)

I checked these against `jazz.edn` with a REPL spike. In jazz, `composer` extends `musician`,
which extends `person`, and `performance.performers` has type `:musician`.

| # | Gap | Where | Observed |
|---|-----|-------|----------|
| 1 | Inverse relations aren't inherited. A reference typed `:musician` also applies to composers, but the composer page doesn't show it. | `schema/inverse-fields`, `html/kind->html` | inverse(:composer) = `(:composer)`; `:performers` is missing |
| 2 | No downward navigation. There's no `subkinds`/`descendants` function, and a parent's page doesn't list its subkinds. | `schema.cljc`, `html.clj` | `person` page has no link to `musician` or `producer` |
| 3 | No type-compatibility predicate. There's no `(subtype? schema :composer :person)`, so the `::type` TODO ("check that these are actual types") has nothing to build on. | `schema.cljc` | n/a |
| 4 | The Datomic export defeats polymorphism. Inherited attrs are re-minted in the child's namespace (`:composer/name`), so a query on `:person/name` never finds composers. | `export/datomic.clj` | the current test asserts this behavior |
| 5 | Some consumers read only the local `:fields`. | `search/lexicon.cljc` (inherited fields aren't indexed under the subkind); `datagen/generate-entities` (the prompt's field-list is local only, so a generated `musician` has no `name`); `export/graphql_gen.clj` (no `interface`/`implements`); `json.clj` import (drops `:extends`, though `:extends` would only need keywordizing) | local fields(:musician) = `(:instrument :style :performances)` vs. all-fields adds `:name :birth-date :biography` |
| 6 | Validation is easy to skip. `read-schema` has `validate-schema` commented out, so bad `:extends` is caught only on the Datomic path. | `schema.cljc` `read-schema` | n/a |

## Options

**A. Keep `:extends` and give it subtype semantics (recommended).** Treat `:extends` as both
"inherits fields" and "is-a". Add query functions, then make consumers use them. No schema format
change, and existing schemas keep working.

**B. Add a separate `:isa`/`:subtype-of` relation next to `:extends`.** This separates "reuse
these fields" (mixin) from "is a kind of" (taxonomy). It's more precise, but it gives two
near-identical keys to explain. Every schema seen so far, hand-written or LLM-generated
(`schema_gen_llm` already asks for "subtype (extends) relations"), uses `:extends` to mean is-a.
If a real need for pure mixins comes up, a kind-level `:mixin? true` flag (or `:abstract?`, see
below) covers it more cheaply.

**C. Do nothing beyond the docs and close the TODO.** This is defensible for HTML-only use.
But gaps 1, 4 and 5 are real bugs for anyone who uses the hierarchy, especially datagen and
Datomic.

## Recommendation

Choose option A and do it in phases. Make phase 1 the core change. Make the Datomic namespace
change (phase 3) its own decision, because it changes generated output.

### Datomic namespace choice (gap 4)

There are three choices:

1. **Status quo:** flatten into the child namespace (`:composer/name`). Each kind is self-contained,
   but there's no polymorphism and you get N copies of every inherited attribute.
2. **Declaring-kind namespace:** emit each attribute once, under the kind that declares it
   (`:person/name`), and don't re-emit inherited attributes. This is idiomatic Datomic, where
   attributes aren't owned by entity types. A composer entity just carries `:person/name`,
   `:musician/instrument` and `:composer/compositions`, and queries on `:person/name` find
   everyone. A child that *overrides* an inherited field (for example, a narrower `:doc`) has no
   separate attribute to put that on. Its doc would be lost, or it would need a new attribute.
3. **Both:** emit option 2, plus child-namespace aliases. This is noisy and gains little.

I recommend **option 2**, with an opt-out flag (`{:flatten-inheritance? true}`) that keeps today's
behavior. This changes the expectation in `schema_inheritance_test.clj`'s datomic-export test.
It's a breaking output change, so it belongs in a version bump.

### Optional extras (not core)

- `:abstract? true` on a kind means "never instantiated directly". Datagen would skip it, the
  graph could render it in italics, and GraphQL would emit it as an `interface`.
- A discriminator. In Datomic, a `:kind`-style enum attribute could record an entity's concrete
  kind. Only add this if a consumer needs it.
- Multiple-inheritance conflicts. When two parents define the same field differently, the result
  depends on order today. Add a validation warning before adding any resolution rules.

## Implementation plan

**Phase 1: schema queries** (`src/cljc/hyperphor/alzabo/schema.cljc`, plus tests in
`test/clj/hyperphor/alzabo/schema_inheritance_test.clj`)

- `subkinds [schema kind]` returns direct children. `descendants [schema kind]` returns the
  transitive closure. Memoize them like `inverse-fields`.
- `subtype? [schema sub super]` is true when `sub` is `super` or `super` is one of `sub`'s
  ancestors.
- `all-inverse-fields [schema kind]` merges `inverse-fields` for the kind and each of its
  ancestors. Mark which entries are inherited, as `all-fields` already does for fields.
- Optional: a `validate-types` check that every non-primitive, non-tuple `:type` is a kind or an
  enum (the `::type` TODO). Call `validate-schema` from `read-schema` behind a flag, so existing
  invalid schemas don't break on load.

**Phase 2: HTML** (`src/clj/hyperphor/alzabo/html.clj` `kind->html`)

- Add a "Subkinds:" box next to "Extends:".
- Build the inverse-relations table from `all-inverse-fields`, with `[inherited]` marking.
- Optionally, add an "Ancestors" breadcrumb for deep hierarchies.
- Note: the user's working branch `use-multitool-graph` has refactored the graph code into
  `graph-spec`, and the working copy has uncommitted `html.clj` edits there. Those edits are graph
  and cluster layout only, not `kind->html`. Do phase 2 on top of that branch, not `main`, to avoid
  conflicts.

**Phase 3: Datomic** (`src/clj/hyperphor/alzabo/export/datomic.clj`, `test/.../schema_inheritance_test.clj`)

- Use the declaring-kind namespace, plus the `:flatten-inheritance?` opt-out, as above.

**Phase 4: other consumers**

- `search/lexicon.cljc` `kind-dict`: index `all-fields` under each kind. This needs the full schema,
  not just `:kinds`. It's a small signature change in `merged-dict`.
- `datagen.clj` `generate-entities`: build the field-list from `all-fields`. Leave
  `kind-dependencies` as it is. Its test deliberately excludes inherited fields, though using
  `all-fields` there is arguably more correct too: a generated employee needs an address.
- `export/graphql_gen.clj`: for a kind with subkinds, emit `interface X` (or keep the type and add
  the interface), and give subkinds `implements`. Each GraphQL type must re-list its inherited
  fields, so use `all-fields`.
- `json.clj`: keywordize `:extends` on import.

**Phase 5 (optional):** add `:abstract?` to the `::kind` spec and update the consumers above.

## Out of scope / cross-repo

- `nlq` (`nlq/src/clj/hyperphor/nlq/schema.clj`, around lines 165 and 236) reads local
  `(:fields kdef)`, so kinds with `:extends` would lose inherited columns there. Postgres-generated
  schemas don't produce `:extends` today, so this doesn't matter yet. Once phase 1 ships, it should
  switch to `alzs/all-fields`.
- `inverse-fields` is keyed by field name, so two kinds that each have a field with the same name
  pointing at the same target collide. This is a separate, pre-existing bug, noted here because
  phase 1's `all-inverse-fields` inherits it.
