# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

- **The queries ents builds name components by id, not by name.**
  `with-query`, `query`, `system!` and `observer!` no longer build their
  flecs query with `binding-expr`. A private `spec-expr` writes each
  component as its raw id in the world (`"#491, #492"`), registering it
  first, and returns the component names alongside. `with-query` now
  expands to a call to the private `iter-specs` (through its var) instead
  of `iter-rows` and `binding-expr`, and evaluates its specs once rather
  than twice. `iter-rows` and `iter-specs` share one driver, and
  `register-poly!` builds the query for systems and observers itself.
  `binding-expr` and `iter-rows` are unchanged and stay public, though
  nothing in ents calls `binding-expr` any more. Code that expanded
  `with-query` before this change still calls the old functions, so at
  the REPL, re-evaluate those forms to pick up the fix for dashed names.

- **Query errors carry the component names.** When flecs rejects a query
  that ents built, the "invalid query" ex-data is
  `{:expr "#491" :terms {"#491" "my-pos"}}`, and "flecs rejected the
  system/observer" carries `:expr` and `:terms` as well as `:name`. An
  expr passed to `iter-rows` is reported as written, `{:expr ...}`, as
  before.

- **Component alignment comes from the jolt.ffi layout.** ents looked each
  field type up in its own alignment table, which only had the sized
  numeric types. It now uses `ffi/layout-alignment`, and the table is
  gone. `coerce` truncates a double for the integer type names as well as
  the sized ones.

- **`set-c!` checks every value before writing any.** `write-component!`
  looks up each field's type and offset, and writes the value into a
  scratch copy of the component first, which is where jolt.ffi rejects a
  bad value. Only then does it ensure the component and write each value at
  its offset, rather than through `ffi/write-field`, which finds the field
  by name on every call. `set-c!` costs about the same as before.

- **`set-c!` names the field it can't write.** An unknown field throws
  "unknown field :mana for component Health", where jolt.ffi said the field
  path "names a struct or array". An array or struct field throws "field
  :v of component Shapes is an array or struct; set-c! writes scalar
  fields only", where jolt.ffi named its own write types. The ex-data
  holds `:component` and `:field`, and `:type` for an array or struct
  field. Writing those fields never worked; only the message is new.

- **Registering a component looks for an existing one first.** The shim's
  `fj_component` looks the name up as a full path from the root, then as a
  C symbol, and returns an existing component whose size and alignment
  match, without setting ents' zeroing constructor on it. It refuses one
  that doesn't match. A short name isn't searched for along flecs' lookup
  path, so a component named `Poly` is still a new component, not
  `flecs.core.Poly`. The shim gains `fj_lookup_component`,
  `fj_component_size` and `fj_component_alignment`, bound in `ents.ffi` as
  `lookup-component`, `component-size` and `component-alignment`.

- **A world records what flecs created for itself.** `fj_init` keeps the
  last component id and the largest entity id as they are when `ecs_init`
  returns, in the world's binding context, which flecs frees with the
  world. `fj_is_builtin` (`f/builtin?`) checks an id against them, so
  everything flecs created with the world, core and addons, counts as
  flecs' own, and C modules imported later don't.

- **ents' component id cache holds each component's layout.** `comp-id`
  caches `[id size alignment]` per name and world, and compares a
  definition's size and alignment with them on every lookup, as
  registration already did. A lookup costs about 0.2µs more. The
  mismatch error now ends by saying a registered layout can't change:
  "...; a registered component's layout can't change, so define it as it
  was registered, or use a new world".

- **Registering a component checks whether its name is already in use.**
  The shim's `fj_component` refuses a name whose entity isn't a component
  but has been used as a tag or as a pair's relationship, the same check
  flecs makes before giving an entity a size. The new `fj_used_as_id`
  (`f/used-as-id`) lets `comp-id` explain the refusal.

- **`add!`, `remove!`, `entity!` and `delete!` check for flecs' own
  entities.** Adding or removing an id checks whether it, or a pair's
  relationship, is one of flecs' own components with a size (`f/builtin?`
  and `f/component-size`), and `delete!` checks whether the entity is one
  of flecs' own. flecs' tags and traits pass as before.

### Fixed

- **An optional query term (`?Velocity`) binds `nil` where it isn't set.**
  flecs has no column for an optional term on a table without that
  component, and returns a NULL field pointer. ents read through it anyway,
  so any query, system or observer with an optional term threw "invalid
  memory reference" as soon as it reached such a table. ents now checks
  whether each field is set, per table.

- **Queries, systems and observers work over components with a dash in the
  name.** A component defined as `(component my-pos ...)` could be added,
  set and read, but every `with-query`, `query`, `system!` and `observer!`
  over it failed with "invalid query" or "flecs rejected the
  system/observer", because flecs' query DSL can't parse a `-` in an
  identifier. These now build their query from component ids (`#491`)
  rather than names, and the error data names each id under `:terms`. A
  hand-written expr passed to `iter-rows` or `count-q` still names its
  components, so dashed names can't appear in one.

- **Components with `:bool`, `:char`, `:short`, `:int`, `:long`, `:size_t`,
  `:pointer` and the other jolt.ffi type names work.** Any field type
  outside the sized numeric types (`:int32`, `:float` and so on) threw
  "nil where a java.lang.Number is required" when the component was first
  used. A double given to an integer field of any of these types is
  truncated, as it already was for `:int32`. `:bool` and `:char` values
  go to jolt.ffi as they are. A `:bool` is stored by truthiness, so `nil`
  and `false` store false and anything else stores true, `0` included. A
  `:char` takes a char (`\A`).

- **A `set-c!` that fails changes nothing.** Fields were written one at a
  time, and the component was only marked modified after the last. A value
  that couldn't be written (out of range, not a number, an unknown field)
  threw part way through, leaving the earlier fields changed and OnSet
  observers never told. On an entity without the component, the component
  was added anyway. The same went for `entity!` with component values. Now
  nothing is written and nothing is added unless every value can be.
  Values jolt.ffi accepts but wraps, such as `-1` into a `:uint32`, are
  still written as jolt writes them.

- **Defining a component that flecs or a C module already registered no
  longer aborts the process.** `fj_component` always registered the ents
  name as both the name and the C symbol. When a component of that name
  existed with another C symbol, such as `flecs.core.Component`
  (`EcsComponent`) or any component a flecs C module registers, flecs hit a
  fatal assert, whether the ents component was named by path or by C
  symbol. `FLECS_SOFT_ASSERT` doesn't cover it. ents now reuses the
  existing component when its layout matches, keeping its own hooks, so
  components from C modules can be read and written from ents:

  ```clojure
  (def CoreComponent (ec/component flecs.core.Component [:size :int32] [:alignment :int32]))
  (ec/get-c w Position CoreComponent) ;=> {:size 8, :alignment 4}
  ```

  A different layout throws, giving both: "component flecs.core.Component
  is already registered with size 8 and alignment 4, but this definition
  has size 4 and alignment 4", with `:registered` and `:defined` in the
  ex-data.

  flecs' own components can be read but not written: `set-c!` and
  `entity!` values throw "component flecs.core.Component is one of flecs'
  own", since writing one would corrupt flecs (`{:size 99}` on Position's
  record led to a fatal assert). Components of C modules imported after
  the world was created can be written.

- **A component redefined with a different layout is refused in a world
  that already uses it.** Re-evaluating a component's `def` at the REPL
  with another field, while a world was alive, gave a record whose layout
  no longer matched the one flecs had registered. ents looked the
  component up by name and used it anyway, so `get-c` read past the end
  of the value flecs stores, and `set-c!` hit a fatal flecs assert or
  wrote past it. `get-c`, `set-c!`, queries, systems and observers now
  throw the same error registration gives. Re-evaluating an identical
  `def` works as before.

- **A component named like a tag or relationship that's in use is refused
  cleanly.** Registering a component whose name was already used as a tag
  (`(entity! w :t :InUse)`) or as a pair's relationship made flecs refuse
  to give it a size part way through registration. flecs reported the
  error but carried on, the following write threw "invalid memory
  reference", the world was left mid-operation so later reads came back
  empty, and destroying it hit a fatal assert. Registration now throws
  "component InUse can't be registered: InUse is already in use as a tag
  or relationship", with the entity in the ex-data. flecs counts a tag as
  in use even after it's been removed, so the name can't become a
  component then either. A name used only as a pair's target is fine.

- **flecs' own entities can't be deleted, and its components can't be
  added or removed.** Since flecs' own components can be named from ents,
  `(delete! w CoreComponent)` or `(delete! w :ChildOf)` aborted the
  process, `(remove! w Position CoreComponent)` silently took Position's
  component record away, and `(add! w :e CoreComponent)` turned `:e` into
  a component of size 0. These now throw, for example "component
  Component is one of flecs' own; ents doesn't remove flecs' components".
  The same goes for one of flecs' components as a pair's relationship,
  and for `entity!` and `anon!` args. flecs' tags and traits, such as
  `:Prefab`, `:Singleton` and `[:ChildOf parent]`, can be added and
  removed as before.
