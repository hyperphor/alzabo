# Ben's fork of Alzabo: what's worth pulling in

Investigation for `pici-TODO.org`: "look at Ben's fork". Read-only; nothing merged.

## Where it is

`CANDELbio/alzabo` (the upstream this repo is forked from) has three forks:

| Fork | Last push | Notes |
|---|---|---|
| `hyperphor/alzabo` | 2026-10 | this repo |
| `vendekagon-labs/alzabo` | 2022-11 | just a snapshot of `CANDELbio/alzabo` at `fffea8c`; no new commits |
| **`vendekagon-labs/alzabo-unify`** | 2024-01-06 | **Ben's fork**: "Alzabo fork for Unify meta model annotations (rather than CANDEL/pret)" |

Vendekagon Labs is Ben Kamphaus's org. In `alzabo-unify`, `main` stops at
`16b6467` (Mike, 2023-07-31), which is already in this repo's history. All of
Ben's work is on the **`unify-ify`** branch: 22 commits by Ben Kamphaus,
2023-12-29 to 2024-01-06, never merged into his own `main`. Diff stat against
`16b6467`: 27 files changed, about 3200 lines added and 3000 removed. Most of
that is the regenerated `test/resources/pretense` fixtures. The real code is
around 600 lines.

Ben's fork still uses the old `org.parkerici.alzabo.*` namespaces, multitool
0.0.15, and pre-category schema keys (`:reference?`, `:unique`, `:component`).
This repo has since moved to `hyperphor.alzabo.*`, split out `import/` and
`export/`, added `:category`, `:unique?`, `:component?`, and icons, and rewrote
`html/schema->html`. So **none of it applies as a patch**. Anything worth
having has to be ported by hand.

## What the branch does

Ben's goal was to make Alzabo the schema browser for
[Unify](https://github.com/vendekagon-labs/unify), the successor to CANDEL/pret.
That meant (a) reading Unify's renamed metamodel and (b) running as a
containerised service that renders docs straight from a live Datomic DB.

1. **`unify.clj`: Unify schema directory to Alzabo.** This is nearly a
   line-for-line copy of what is now `import/candel.clj`, with these changes:
   - metamodel attributes renamed `:kind/*` to `:unify.kind/*` and `:ref/*` to
     `:unify.ref/*`
   - `metamodel.edn` destructured as `[entity-meta reference-meta]` (2
     elements; CANDEL had 3)
   - `:unique-id` also falls back to `:unify.kind/global-id`
   - `:version` and `:title` read from the `:unify.schema/version` /
     `:unify.schema/name` entity in `schema.edn`, instead of the hardcoded
     `"CANDEL"` and the `:candel/schema` entity in `enums.edn`
   - an extra special-case enum `[:measurement-matrix :measurement-type]`
   - `schema->alzabo` split out as a pure function of
     `(schema-data entity-meta reference-meta enums)`, so file input and DB
     input share one code path
   - also `->metamodel`: an Alzabo schema back to a Unify metamodel (the old
     `candel/metamodel`, renamed)
2. **`unify/query.clj`: schema from a live Datomic DB.** About 150 lines of
   Datomic queries that pull installed attributes, `:unify.kind/*` and
   `:unify.ref/*` entities, the version entity, and enums. Enums are found
   heuristically: an entity with only `:db/id` and `:db/ident`. The results
   are reshaped into the same form the schema files have and fed to
   `schema->alzabo`. Needs `com.datomic/peer` and `BASE_DATOMIC_URI`
   (`db-name->uri` handles the `sql` protocol's odd URI shape).
3. **`service.clj`: Pedestal HTTP service.** `GET /health`, plus
   `POST /render/:db-name`, which introspects that DB and writes HTML docs
   under `resources/public/<schema-name>/<version>/`. It also serves
   `resources/public` statically. Per-request config works by turning
   `config/config` into a `^:dynamic` var and `binding` it to a plain map.
4. **CLI changes in `core.clj`.** The old `:server` (which only opened
   `index.html` in a desktop browser) is renamed **`:browser`**. `:server` and
   `:dev-server` now start the Pedestal service. `-main-guts` is renamed
   `main*`. The `:candel` source is replaced by `:unify`, which reads from
   `UNIFY_SCHEMA_DIRECTORY`.
5. **Docker.** A `Dockerfile` (clojure:lein + graphviz, runs `lein run
   resources/unify-db-config.edn server`) and a `docker-service` script.
6. **Deps.** Clojure 1.11.1, `com.datomic/peer`, `postgresql` (for Datomic's
   SQL storage), Pedestal 0.6.2, slf4j-simple. `resources` added to
   `:source-paths`.
7. **Removed:** `candel.clj`, the `roundtrip.clj` experiment, CANDEL `:pret-path`
   config. Test fixtures were regenerated to Unify conventions.
8. **Noise:** cljfmt-style whitespace churn in most files, and leftover debug
   `prn`/`println` calls in `html.clj` (`write-graphviz`, `schema->html`).

## Recommendations

### Worth pulling in (port by hand)

- **Unify import, as a parameterisation of `import/candel.clj` rather than a
  second copy.** The only real differences are the metamodel attribute
  namespace (`kind`/`ref` vs `unify.kind`/`unify.ref`), the metamodel shape,
  where version and title come from, and the `:global-id` fallback. Steps:
  1. Pull out a pure `schema->alzabo [schema-data entity-meta reference-meta
     enums]` the way Ben did. That's a good refactor on its own.
  2. Add a `:unify` source that reads with the `unify.*` namespaces.
  3. Keep the current repo's output keys (`:category`, `:unique?`,
     `:component?`) rather than Ben's older ones.
  4. Copy Ben's regenerated pretense fixtures as a *separate* test fixture
     (e.g. `test/resources/unify/`) rather than replacing the CANDEL ones.

  Value: moderate, and only if Unify is still a target. It is the one part of
  the fork that is solid, cheap, and fits Alzabo's import/export direction.
- **Rename the misleading `:server` command to `:browser`.** Today `:server`
  only calls `browse-file` (and calls `(schema)` with the wrong arity: it takes
  `[schema-file]`). Ben's split matches the open question in
  `design/commands.md` ("server: is this needed any more?"). This is a trivial
  change and should be folded into the command redesign.

### Maybe, as an optional module

- **`->metamodel` (Alzabo to Unify metamodel).** This repo's `:datomic` command
  has the equivalent `candel/metamodel` call commented out. If Unify import
  goes in, a matching `export/unify` would round-trip. It reads
  `resources/candel/metamodel-fixed.edn` by relative path, which would need to
  become `io/resource` to work from a jar.
- **Live Datomic introspection (`unify/query.clj`).** Building a schema from a
  live DB is a useful idea, and the same pattern as `hyperphor/nlq`'s Postgres
  `gen-alz-schema`. But it pulls in `com.datomic/peer`, which is heavy and
  shouldn't be a hard dependency of a schema library. If wanted, put it behind
  a separate namespace with a `:provided` or profile-scoped dep, or in a
  separate small library. The enum heuristic ("only `:db/id` +
  `:db/ident`") will misclassify plain ident-only entities; Ben flags that
  Unify might model enums explicitly later.

### Not worth pulling in

- **Pedestal service and Docker.** These are tied to Ben's Unify deployment
  (DB-per-request rendering into `resources/public`). Pedestal plus Jetty is a
  lot of weight for a library whose `html` output is now self-contained static
  files (this repo copies `client.js` into the output dir). It also cuts
  against the TODO to "breakout ui into separate project". If a render service
  is ever wanted, it belongs in a separate app (e.g. built on
  `hyperphor/way`).
- **Dynamic `config` var with `binding` to a map.** Current `html/schema->html`
  already takes `output-path` explicitly, and `config/config` is now variadic
  `get-in`. Ben's trick would also break any zero-arity `(config)` call, since
  a map called with no args throws. `design/commands.md` already plans to get
  rid of separate config.
- **Removing `candel.clj` and `roundtrip.clj`**, and the
  `metamodel-fixed.edn` rename. This repo has already reworked CANDEL import
  without the pret dependency (`e0a709e`).
- Debug prints, whitespace reformatting, dependency bumps (this repo's deps are
  newer already), and `resources` on `:source-paths`.

## Licensing note

Ben's fork carries the Apache 2.0 `LICENSE` file, the same as this repo, but its
`project.clj` and the upstream `CANDELbio` `project.clj` it branched from both
declare GPLv3. This repo is Apache 2.0. Porting the ideas is low-risk, since
they are mostly Mike's own CANDEL code with renames. Before copying Ben's new
code verbatim (`unify/query.clj`, `service.clj`), it would be worth a quick
check with Ben.
