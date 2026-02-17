# jaspr_intellij

![Build](https://github.com/ELadrimonos/jaspr_intellij/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Overview

**jaspr_intellij** is a free and open-source IntelliJ IDEA plugin that adds development support for the Jaspr framework.

Currently implemented features:

- Live Code Templates for:
  - `stlessc` — New **StatelessComponent** class
  - `stfulc` — New **StatefulComponent** class
  - `inhc` — New **InheritedComponent** class
  - `jhtml` — Inserts a html component
  - `jtext` — Insert a text component
  - `jstyls` — Creates a style sheet
  - `jevt` — Insert a generic event handler
  - `jclick` — Insert a click event handler

- New File templates for:
  - `StatelessComponent`
  - `StatefulComponent`
  - `InheritedComponent`

This plugin focuses on improving productivity when building Jaspr applications by reducing boilerplate and accelerating component creation.

## Roadmap

Based on the discussion in https://github.com/schultek/jaspr/issues/685#issuecomment-3621910414

- [ ] Integrate "Serve" command with debugging support.
  - Launch the `jaspr daemon` command.
  - Attach the IDE debugger to both client and server processes.
- [X] Create new Project option.
- [X] Snippets (such as creating new Stateless/Stateful components).
- [ ] Component Scopes.
  - Show editor hints indicating where a component is rendered using the `jaspr tooling-daemon` command.
- [ ] HTML conversion.
  - Convert HTML to valid Jaspr code using the `jaspr tooling-daemon` command.
- [ ] Clean and Doctor commands.

## Installation (Placeholder)

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "jaspr_intellij"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/ELadrimonos/jaspr_intellij/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
This plugin is independent and not affiliated with Jaspr, Flutter, Dart, Google, or JetBrains.