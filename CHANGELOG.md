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
