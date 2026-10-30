# Package Alzabo as a self-serve webapp (proposal)

Source: `design/pici-TODO.org`, `** package as webapp so I don't have to give demos`.
This is a proposal only. Nothing is deployed and there is no production code.

**Branch-base note.** This branch is cut from `main` (008bb09, alzabo 1.3.10), as the
task specified. The alzabo file paths below assume the unmerged `use-multitool-graph`
layout (2.0.x). The only app that consumes alzabo as a webapp, ontogeny, already pins
`com.hyperphor/alzabo "2.0.0"` and calls `hyperphor.alzabo.generate.schema-gen`, which
exists only on that branch.

Claims are tagged **[observed]** (I ran it) or **[inspected]** (I read the code).

## 1. The self-serve webapp mostly exists already: ontogeny

`/opt/mt/repos/hyperphor/ontogeny` is a `com.hyperphor/way` app that takes a one-line
domain description, generates an Alzabo schema with an LLM, and serves the rendered
Alzabo HTML doc.
- It is live at http://ontogeny.hyperphor.com/ **[observed]**. Its `/directory` page
  lists previously generated schemas (jazz, climate change, ...).
- It is deployed to Heroku (`bin/deploy.sh`, uberjar via the JVM application deployer).
  Graphviz comes from the apt buildpack via `Aptfile`. S3 (`s3://hyperphor/ontogeny`)
  holds the durable copies, because the dyno disk is ephemeral **[inspected]**.
- Generation runs as an async job with `/status` polling, since generation takes 2–5
  minutes and Heroku kills requests after 30 s. A zip download is available
  **[inspected]**.

So the TODO isn't really "build a webapp". It is "close the gaps that still force a live
demo". This proposal is about those gaps.

## 2. Gaps that still force a demo

1. **Visitors can only generate.** There's no way to bring an existing schema (paste or
   upload EDN) and see it rendered. That is the zero-cost, zero-latency path, and the
   one that matters to someone who already has a schema, which is Alzabo's actual
   library use case (nlq-aact, okc).
2. **No curated showcase.** Visitors see a blank form plus whatever past users
   generated. There's no "here are three good schemas, click one". Ontogeny's own
   `design/TODO.md` says "Needs some explanatory text".
   https://hyperphor.github.io/alzabo/ still serves a stale CANDEL build (title
   `CANDEL - Alzabo` **[observed]**), and CI has been broken since `4e122e5` (see the
   sibling proposal `design/alzabo-schema-index.md` on branch `todo/alzabo-schema-index`).
3. **Nothing guards LLM spend.** I grepped ontogeny's `src/` and `resources/config.edn`
   and found no auth, rate limit or budget **[inspected]**. Anyone with the URL can
   start generations. Per ontogeny's `design/cost.md`, each one costs about $0.24, or
   $0.55–0.65 for a large domain, and failed generations cost the same. For a page
   whose whole point is to be shared without the author present, this is the main
   operational risk.
4. **Rendering is not safe for untrusted input.** This matters as soon as gap 1 is
   closed, and partly already does (see §4).

## 3. Options

**A. Static gallery only.** This is option B of the schema-index proposal: generate
several schemas into one Pages site with a card index. It's cheap, needs no server and
costs nothing to run. But it is browse-only, with no "try your own schema". It's the
right answer for "show curated schemas", so it should be adopted, but it doesn't cover
self-serve.

**B. Extend ontogeny into the Alzabo playground (recommended, layered on A).** Add a
render-only "paste or upload a schema" path and a featured-examples panel to ontogeny,
plus a spend guard on generation. The alzabo side shrinks to library hardening (§4).
This keeps the boundary the synthetic-instances proposal
(`design/alzabo-synthetic-instances-ui.md`, branch `todo/alzabo-synthetic-instances-ui`)
also argues for: alzabo is the schema library, ontogeny is the demo app.

**C. A webapp inside alzabo** (a ring `server` command, or reviving the `:server`
command and figwheel setup). Rejected:
- It would add web-server deps to a library whose `project.clj` already says "separate
  out UI into separate project".
- It would duplicate `way` and ontogeny.
- `design/commands.md` already questions whether `server` is needed ("It's all static
  files").

**D. Fully in-browser rendering** (port `html.clj` to cljc, and render the graph
client-side via Mermaid or a wasm Graphviz). This gives static hosting, no graphviz on
any server, and BYO-schema with no backend. The `todo/alzabo-mermaid-output` branch is
a first step. It's a large port, and the cmapx click-map would need replacing.
Long-term only. Not planned here.

## 4. Constraints on rendering untrusted schemas (alzabo side)

BYO-schema means rendering EDN a stranger wrote. Today ontogeny already renders
LLM-written text that is steered by user input. Four issues:

1. **No HTML escaping.** `html.clj` uses the legacy `hiccup.core` API (`(:use
   [hiccup.core])`), which does not escape strings. I confirmed this with the repo's
   hiccup 2.0.0: `(hiccup.core/html [:td "<script>alert(1)</script>"])` returns the
   script tag verbatim, while `hiccup2.core/html` escapes it **[observed]**.
   - All `:doc`, `:label`, enum value docs etc. land as raw HTML. `linkify` emits raw
     `<a>` strings by design.
   - The whole schema is also embedded as text in `div#aschema` for the search widget
     **[inspected]**.
   - The result is stored XSS on whatever origin serves the docs. On ontogeny that is
     already reachable through prompt-steered LLM output. It's low stakes today (no
     login or cookies), but it's a blocker for uploads.
   - Fix: switch to `hiccup2.core/html` and wrap only linkify's output in `raw`. This
     is the existing `alzabo-hiccup2` TODO (`pici-TODO.org`, "Hiccup2"). It's worth
     doing first, because both B and the gallery depend on it.
2. **`:include` reads arbitrary files.** `schema/read-schema` resolves `:include`
   entries relative to the file's directory with no confinement. A schema at
   `/tmp/alz-inc-spike/up/schema.edn` with `:include ["../other.edn"]` merged in the
   kinds from `/tmp/alz-inc-spike/other.edn` **[observed]**. An uploaded schema could
   therefore pull in other users' generated schemas, or any EDN on the server.
   `edn/read-string` itself is safe (no eval).
   - Fix: add `schema/parse-schema [edn-string & {:keys [allow-include?]}]`, which
     reads from a string, rejects `:include` by default and still runs `infer-enums`.
     `read-schema` becomes slurp plus the same code path with includes allowed.
3. **Resource limits.** `html.clj` shells out to `dot` with no timeout (`sh-errchecked`
   on main, multitool graph rendering on the branch). A pathological schema can hang a
   render.
   - Fix: add a timeout option on the dot call. This belongs in
     `hyperphor.multitool.graph` on the 2.0 branch, so it's a multitool change.
   - Also cap the request size and kind count at the app level.
4. **Global state.** On 2.0, `html/schema->html` takes `output-path` explicitly and
   reads rendering options from the schema, not config. But ontogeny's `doc_gen.clj`
   still goes through `alz-config/set-config!` plus
   `alzabo/do-command :documentation`, which uses the global `config/the-config` atom.
   That's why ontogeny serializes renders behind one lock **[inspected]**.
   - Fix: ontogeny should call `schema->html` directly. Then the render-only route
     needn't share the generation lock, and a quick paste-and-render won't queue
     behind a 3-minute generation.
   - Keep `clear-directory` pointed only at fresh per-slug dirs.
   - There's a second reason to bypass the command layer. The live copy's
     *uncommitted* `core.clj` edit makes `do-command-new :documentation` call
     `browse-file` (`java.awt.Desktop`), which would throw on a headless dyno
     **[inspected]**. Server code should never go through CLI commands.

**Sequencing with uncommitted work.** The live copy has uncommitted `html.clj` edits
(xlabels, clusters, category-sorted nodes) on top of `use-multitool-graph`. The hiccup2
switch touches every `html` call in that file, so it should land only after those edits
are committed. It should be done as the existing `alzabo-hiccup2` task, using the
`<script>`-in-`:doc` test (§6) as its acceptance check. A mechanical API swap can fail
in either direction: it can escape linkify's anchors, which breaks the links, or wrap
whole docs in `raw`, which keeps the XSS.

## 5. Recommendation

1. **Alzabo hardening first** (small, library-only): escaping (hiccup2), `parse-schema`
   without includes, and the dot timeout. Release as 2.0.x after `use-multitool-graph`
   merges.
2. **Ontogeny becomes the self-serve Alzabo site**:
   - A **"Render a schema" panel**: paste EDN or upload a `.edn` file, and get the same
     `/schema/<slug>/index.html` doc that generation produces. Same storage, S3 sync,
     directory listing and zip download. No LLM, so it's instant and free.
   - A **featured-examples panel** on the landing page: 3–5 curated schemas (hand-written
     jazz, generated jazz with icons and categories, bioinformatics, optionally AACT)
     pre-rendered at boot from schema files bundled in the jar, and linked as cards.
     The card markup can be shared with the schema-index gallery (see step 3).
   - A **spend guard on `/generate`**: a daily generation cap held in memory, plus a
     per-IP throttle (for example 3/hour). Over the cap, show "Generation is paused;
     try a featured example or render your own schema". Optionally add a shared demo
     passphrase (env var) that bypasses the cap, to hand to people the author wants to
     let in. Choosing between these is an open question (§7).
   - Short **about text** (ontogeny already has a draft in `design/about.md`).
3. **Static gallery (schema-index proposal) for the Pages site**, as the zero-cost
   mirror and the link to put in the README. If alzabo grows a card-index renderer
   there (`site->html`), ontogeny's featured panel should reuse it instead of writing
   its own markup.

## 6. Implementation plan

### Alzabo (on top of `use-multitool-graph`)
- `src/clj/hyperphor/alzabo/html.clj`:
  - Move to `hiccup2.core/html`. Mark linkify output and the inline `<script>` as
    `raw`. Make sure the `div#aschema` payload is escaped text (the search widget reads
    `.textContent`, so escaping is transparent to it).
  - Pass a dot timeout through.
- `src/cljc/hyperphor/alzabo/schema.cljc`: add `parse-schema` (string in, `:include`
  rejected unless allowed). `read-schema` delegates to it.
- `test/clj/hyperphor/alzabo/html_test.clj`: render a one-kind schema whose `:doc`
  contains `<script>` and assert it comes out escaped. Like the existing html tests,
  this needs `jsu/client.js` built.
- `test/cljc/hyperphor/alzabo/schema_test.clj` (or similar): `parse-schema` throws
  on `:include` by default.
- Version bump and Clojars release are done by the author.

### Multitool
- `hyperphor.multitool.graph`: a timeout option on the `dot` subprocess.

### Ontogeny
- `src/clj/hyperphor/ontogeny/doc_gen.clj`:
  - Add `render-schema [schema label]`. It shares `render!`'s slug, meta-sidecar and S3
    logic, but calls `html/schema->html` directly instead of
    `config/set-config!`/`do-command`.
  - Move `generate` onto the same path, then drop or narrow the global lock.
- `src/clj/hyperphor/ontogeny/handler.clj`:
  - `POST /api/ontogeny/render` with an EDN body, size-capped at around 512 KB. It
    calls `alzabo.schema/parse-schema`, then `render-schema`, and returns `{:path ...}`
    synchronously (renders take seconds, well under Heroku's 30 s).
  - This is POST, breaking the app's GET-only convention, because a schema doesn't fit
    in a query string. It needs way's body/multipart handling and CSRF settings
    checked.
  - Add the spend guard to `generate-endpoint`.
- `src/clj/hyperphor/ontogeny/core.clj`: pre-render the featured schemas at boot,
  skipping any whose slug already exists locally or in S3.
- `resources/featured/*.edn`: the curated schemas, copied from alzabo's
  `use-multitool-graph` resources.
- `src/cljs/hyperphor/ontogeny/frontend/core.cljs` and `events.cljs`: a tab or panel
  for paste/upload, a featured-cards row, and a "generation paused" state.
- `resources/config.edn`: `:generation-limits {:per-day N :per-ip-hour M}`, plus an
  optional `#env ONTOGENY_DEMO_PASSPHRASE`.

### Alzabo Pages gallery
- As in `design/alzabo-schema-index.md` (CI fix first, then `site->html`).
- Link both the gallery and ontogeny from the alzabo README.

## 7. Open questions

- **Spend guard style.** Hard daily cap, per-IP throttle, passphrase, or a
  "bring your own API key" field? The last one removes the cost risk entirely, but
  means handling user secrets.
- **Should uploaded schemas be listed publicly in `/directory`?** Generated ones are
  today. Uploads may be private work. Option: unlisted by default (reachable by URL
  only), with a "list publicly" checkbox.
- **Import formats.** Should the render panel also accept OpenAPI YAML, GraphQL, or
  LinkML (`import/linkml.clj` on the 2.0 branch) and convert server-side? That's cheap
  to wire up, but each importer then becomes an untrusted-input surface.
- **Naming and URL.** Should the "Alzabo playground" stay branded Ontogeny at
  ontogeny.hyperphor.com, or get an alzabo-named host pointing at the same app?
- **CI.** The schema-index branch reports alzabo's Pages CI broken since `4e122e5`. The
  gallery half of this plan depends on that fix.
