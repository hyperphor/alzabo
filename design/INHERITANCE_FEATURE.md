# Alzabo Schema Inheritance Feature

## Overview

The Alzabo schema system now supports **single and multiple inheritance** for kinds (entity types). Kinds can extend other kinds to inherit their fields, creating hierarchies and mixins.

## Usage

### Basic Syntax

```clojure
;; Single inheritance
:child-kind
{:extends :parent-kind
 :fields {...}}

;; Multiple inheritance
:child-kind
{:extends [:parent1 :parent2]
 :fields {...}}
```

### Example: Jazz Music Database

See `test/resources/schema/jazz.edn` for a complete example featuring:

**3-level inheritance hierarchy:**
```
person (base)
  ├─> musician (adds: instrument, style, performances)
  │     └─> composer (adds: compositions, ascap-member?)
  └─> producer (adds: production-company, produced-sessions)

recording (base)
  └─> album (adds: tracks, liner-notes, cover-art)
        └─> compilation (adds: compilation-type, original-releases)

performance (base)
  ├─> recording-session (adds: session-date, studio, producer, engineer)
  └─> live-performance (adds: venue, concert-date, audience-size, bootleg?)
```

## Features

### 1. Field Inheritance

Child kinds automatically inherit all fields from parent kinds:

```clojure
:person {:fields {:name {:type :string}
                  :birth-date {:type :instant}}}

:musician {:extends :person
           :fields {:instrument {:type :instrument-enum}}}
;; musician has: name, birth-date (inherited) + instrument (local)
```

### 2. Multiple Inheritance

Kinds can extend multiple parents:

```clojure
:document {:extends [:entity :timestamped]
           :fields {...}}
;; Inherits from both entity and timestamped
```

### 3. Deep Merge

Child kinds can override/enhance parent field definitions:
- Child properties override parent properties
- Documentation strings are concatenated: `"Child doc (extends: Parent doc)"`

### 4. Multi-Level Inheritance

Inheritance is transitive:

```clojure
:composer {:extends :musician}
;; composer inherits from musician, which inherits from person
;; so composer gets fields from both musician AND person
```

### 5. Validation

The schema validator checks:
- ✓ All parent kinds exist
- ✓ No circular inheritance (A extends B extends A)
- ✓ Throws clear error messages on violations

## HTML Documentation

### Kind Pages

Each kind's HTML page shows:

**Extends Section** (green-bordered box):
```
Extends: parent1, parent2
```
With clickable links to parent kinds.

**Fields Table**:
- Local fields shown normally
- Inherited fields marked with `[inherited]` suffix
- All fields sorted alphabetically

### Example Output

For `composer` (extends musician, which extends person):
```
Extends: musician

Fields:
- ascap-member? (boolean) - ASCAP membership status
- biography (string) - Biographical information [inherited]
- birth-date (instant) - Date of birth [inherited]
- compositions (composition, many) - Original compositions
- instrument (instrument-enum, many) - Primary instrument(s) played [inherited]
- name (string, unique) - Full name [inherited]
- performances (performance, many) - Performances by this musician [inherited]
- style (style-enum, many) - Musical styles/genres [inherited]
```

## Graph Visualization

### Inheritance Edges

Inheritance relationships are shown with **distinctive visual style**:

- **Style**: Dashed lines
- **Color**: Green (#4CAF50)
- **Arrowhead**: Empty (hollow)
- **Direction**: Child → Parent

### Field Reference Edges

Field references use different styling:
- **Style**: Solid lines
- **Arrowhead**: Normal or diamond (for :many cardinality)
- **Labels**: Field names (if edge-labels enabled)

### Example Dot Output

```dot
person [label="person", style="filled"];
musician [label="musician", style="filled"];
composer [label="composer", style="filled"];

musician -> person [style="dashed", arrowhead="empty", color="#4CAF50"];
composer -> musician [style="dashed", arrowhead="empty", color="#4CAF50"];
```

## Datomic Export

The Datomic exporter **flattens inheritance hierarchies**:

- Each kind exports all fields (local + inherited)
- Fields use the kind's namespace: `:composer/name`, `:composer/instrument`, etc.
- No native inheritance in Datomic, so we materialize all fields

Example for `composer`:
```clojure
[;; From person (via musician)
 {:db/ident :composer/name, :db/valueType :db.type/string, ...}
 {:db/ident :composer/birth-date, :db/valueType :db.type/instant, ...}
 ;; From musician
 {:db/ident :composer/instrument, :db/valueType :db.type/ref, ...}
 {:db/ident :composer/performances, :db/valueType :db.type/ref, ...}
 ;; Local to composer
 {:db/ident :composer/compositions, :db/valueType :db.type/ref, ...}
 {:db/ident :composer/ascap-member?, :db/valueType :db.type/boolean, ...}]
```

## API Functions

### Schema Utilities (`hyperphor.alzabo.schema`)

```clojure
(normalize-extends extends)
;; Convert :extends value to vector: :parent -> [:parent], [:p1 :p2] -> [:p1 :p2]

(get-parents schema kind-name)
;; Get direct parent kinds: (get-parents schema :composer) -> [:musician]

(get-ancestors schema kind-name)
;; Get all ancestors: (get-ancestors schema :composer) -> [:musician :person]

(inherited-fields schema kind-name)
;; Get fields inherited from parents (deep merged)

(all-fields schema kind-name)
;; Get all fields (local + inherited, merged)

(validate-inheritance schema)
;; Validate all inheritance relationships (called by validate-schema)
```

## Testing

Comprehensive test suite in `test/clj/hyperphor/alzabo/schema_inheritance_test.clj`:

- ✓ Single inheritance
- ✓ Multiple inheritance
- ✓ Multi-level inheritance
- ✓ Deep field merging
- ✓ Cycle detection
- ✓ Missing parent detection
- ✓ Datomic export with inheritance

Run tests:
```bash
lein test hyperphor.alzabo.schema-inheritance-test
```

## Demo Schemas

### 1. Simple Demo (`test/resources/schema/inheritance-demo.edn`)
Basic inheritance patterns for learning

### 2. Jazz Database (`test/resources/schema/jazz.edn`)
Real-world schema with realistic multi-level hierarchies

Generate documentation:
```bash
lein run test/resources/jazz-config.edn documentation
# Output: target/jazz/*.html, schema.dot.svg
```

## Implementation Details

### Files Modified

1. `src/cljc/hyperphor/alzabo/schema.cljc`
   - Added `:extends` field to `::kind` spec
   - Inheritance utility functions
   - Validation functions

2. `src/clj/hyperphor/alzabo/html.clj`
   - Updated `kind->html` to show inheritance
   - Added inheritance edges to Graphviz output
   - Field marking for inherited fields

3. `src/clj/hyperphor/alzabo/export/datomic.clj`
   - Use `all-fields` to flatten inheritance

### Design Decisions

- **Multiple inheritance**: Supported (like interfaces/mixins)
- **Field name**: `:extends` (neutral, matches common usage)
- **Field merge**: Deep merge with doc concatenation
- **Arrow direction**: Child → Parent (standard UML convention)
- **Visual style**: Dashed green lines (clearly distinguishable)
- **Datomic export**: Flatten hierarchy (Datomic has no native inheritance)

## Future Enhancements

Potential additions:
- Abstract kinds (cannot be instantiated)
- Interface kinds (only field signatures, no data)
- Mixin composition helpers
- Inheritance constraints (sealed hierarchies)
- Conflict resolution strategies for multiple inheritance
