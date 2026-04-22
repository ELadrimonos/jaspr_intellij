# jaspr_intellij Changelog

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