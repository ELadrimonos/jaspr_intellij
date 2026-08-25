# Changelog

## [1.2.0] - 2026-08-25
### Added
- Dart/Melos workspace detection: the plugin now recognizes when a project is part of a Dart pub workspace or a Melos-managed monorepo, walking up from a member package to find the workspace root.
- Automatic, idempotent injection of a delimited `melos.scripts` entry (in `melos.yaml` or the root `pubspec.yaml`) that runs `jaspr daemon` scoped to a package — merges safely alongside scripts the user or `melos bootstrap` already declared, without clobbering them.
- New "Melos script" field in the Jaspr Run/Debug Configuration editor (dropdown of known scripts, editable) — when set, the daemon is launched via `melos run <script>` instead of directly, with all other run-configuration flags (port, mode, dart-defines, etc.) forwarded through.
- New "Create Melos Script..." action under **Tools → Jaspr**, for registering an additional Jaspr app's script in a monorepo with more than one Jaspr package.
- Proactive console warning when a run configuration has no "Melos script" set inside a detected workspace, before the daemon fails.

### Fixed
- Fixed project creation issue where generated files were not copied if the CLI failed on dependency constraints (e.g. `pub get` errors)[cite: 4].
- Fixed failing unit/integration test suite.
- Fixed `--input` and `--dart-define-from-file` paths being resolved against the workspace root instead of the target package directory when running via a Melos script, causing "Specified entry point ... does not exist" failures.

### Changed
- Added support and compatibility for IntelliJ IDEs version 2026.2.x.

## [1.1.0] - 2026-05-04
### Added
- Introduced `JasprLegacy` annotation to isolate legacy daemon-based functionality.
- Updated tests to support Jaspr 0.22.4 and 0.23.0.

### Changed
- Updated compatibility with Jaspr 0.23.0.
- Switched HTML conversion to CLI-based implementation for Jaspr >= 0.23.0.
- Maintained backward compatibility with pre-0.23.0 versions using the tooling daemon.

## [1.0.0] - 2026-04-16
### Added
- Initial stable release of the plugin.
- Run configuration to execute `jaspr daemon` with full lifecycle management (graceful shutdown and Chrome cleanup).
- Formatted console output with colour-coded logs (CLI, builder, server, client).
- Integration with `jaspr tooling-daemon`.
- Inlay hints showing server/client component rendering context.
- HTML to Jaspr conversion via context menu for `.html` files.
- Automatic Dart file generation with `StatelessComponent` scaffolding.
- New Project Wizard for Jaspr applications.
- File templates for `StatelessComponent`, `StatefulComponent`, and `InheritedComponent`.
- Live code snippets: `stlessc`, `stfulc`, `inhc`, `jhtml`, `jtext`, `jstyls`, `jevt`, `jclick`.
- Maintenance actions: Clean and Doctor.
- Tooling daemon status monitoring.
- Version mismatch detection between `jaspr_cli` and `pubspec.yaml`.
- CLI update action from the IDE.
- Inspections for common issues (e.g. multiple scope annotations).
- Debugger attachment support for server and client VM services.
- Annotation inspection (e.g. Cannot have multiple components annotated with @client in a single library.)