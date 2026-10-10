# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

- **An optional query term (`?Velocity`) binds `nil` where it isn't set.**
  flecs has no column for an optional term on a table without that
  component, and returns a NULL field pointer. ents read through it anyway,
  so any query, system or observer with an optional term threw "invalid
  memory reference" as soon as it reached such a table. ents now checks
  whether each field is set, per table.