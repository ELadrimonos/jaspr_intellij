# jaspr_intellij

![Build](https://github.com/ELadrimonos/jaspr_intellij/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Overview

**jaspr_intellij** is a free and open-source IntelliJ IDEA plugin that adds first-class development support for the [Jaspr](https://github.com/schultek/jaspr) framework.

## Features

**Run & Debug**
- Runs `jaspr daemon` directly from the IDE run configuration, with full lifecycle management (graceful shutdown, Chrome cleanup).
- Formatted run console: CLI, builder, server, and client log lines are colour-coded and separated by source.

**Component Scopes**
- Connects to the `jaspr tooling-daemon` in the background when a Jaspr project is opened.
- Displays inline editor hints indicating whether a component is rendered on the server or the client.

**Project & File Templates**
- New Project wizard for scaffolding a Jaspr app from scratch.
- File templates for `StatelessComponent`, `StatefulComponent`, and `InheritedComponent`.

**Live Code Snippets**
- `stlessc` — new `StatelessComponent` class
- `stfulc` — new `StatefulComponent` class
- `inhc` — new `InheritedComponent` class
- `jhtml` — html component
- `jtext` — text component
- `jstyls` — style sheet
- `jevt` — generic event handler
- `jclick` — click event handler

**Tooling**
- Clean and Doctor commands available from the IDE.
- Version mismatch notification when `jaspr_cli` and `pubspec.yaml` versions differ.

## Roadmap

- [x] Run configuration with `jaspr daemon` and formatted console output.
- [x] Component Scopes via `jaspr tooling-daemon`.
- [x] New Project wizard.
- [x] Live code snippets.
- [x] Clean and Doctor commands.
- [ ] Debugger attachment to server and client VM services.
- [x] HTML → Jaspr conversion via `jaspr tooling-daemon`.

## Installation

- **Marketplace:** <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > search for `jaspr_intellij`
- **Manual:** download the [latest release](https://github.com/ELadrimonos/jaspr_intellij/releases/latest) and install via <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk…</kbd>

---
This plugin is independent and not affiliated with Jaspr, Flutter, Dart, Google, or JetBrains.