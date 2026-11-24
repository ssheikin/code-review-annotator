# Code Review Annotator

IntelliJ plugin for annotating code diffs with review comments. 
Comments are ready to paste into an AI agent.

Inspired by [tuicr](https://github.com/agavra/tuicr).

<video src="https://github.com/user-attachments/assets/a2ba847d-1220-45ed-91dc-4010ac04e092" controls width="600"></video>

## Install

### From source

Requires [Nix](https://nixos.org/) with flakes enabled, or JDK 21+ and Gradle.

```sh
# With Nix (recommended)
direnv allow   # or: nix develop
./gradlew buildPlugin

# Without Nix
./gradlew buildPlugin
```

The plugin zip is generated at `build/distributions/code-review-annotator-0.1.1.zip`.

In GoLand/IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk...** → select the zip.

### Development sandbox

```sh
./gradlew runIde
```

This launches a sandboxed GoLand instance with the plugin loaded.

## Usage

### 1. Add comments

There are three scopes for comments:

| Scope      | How to add                                                                                 |
|------------|--------------------------------------------------------------------------------------------|
| **Review** | **Code Review** tool window (bottom panel) → toolbar → **Add Review Comment**              |
| **File**   | Right-click a file in the **Commit** view → **Add File Comment...**                        |
| **Line**   | Right-click in the editor → **Add Review Comment Here...**, or <kbd>Ctrl+Shift+Alt+C</kbd> |

Each comment has a type:

- **MUST_DO**: a problem that needs to be fixed
- **SUGGESTION**: an improvement to consider
- **QUESTION**: something to clarify

While typing a comment, press <kbd>Alt+1</kbd>, <kbd>Alt+2</kbd> or <kbd>Alt+3</kbd> to pick the type.

### 2. Browse comments

Open the **Code Review** tool window (bottom panel). All comments are listed, sorted by scope → file → line.

Double-click a comment to navigate to its file and line in the editor.

In the editor, click the arrow on an inline comment to collapse it to one line.

Press <kbd>Ctrl+Shift+Alt+Down</kbd> or <kbd>Ctrl+Shift+Alt+Up</kbd> to jump to the next or previous
line comment.

### 3. Export to clipboard

Click **Export Comments to Clipboard** in the tool window toolbar, or press <kbd>Ctrl+Shift+Alt+E</kbd>.

This copies all comments as numbered markdown:

```
I reviewed your code and have the following comments. Please address them.

1. **[SUGGESTION]** `src/auth.go:42` - Consider adding unit tests
2. **[MUST_DO]** `src/handler.go:10-15` - Race condition on shared map
   ```
   ...
   ```
3. **[QUESTION]** `src/handler.go:20` - Why not reuse the existing pool?
   ```
   pool := newPool()
   ```
```

Line comments quote up to 15 lines of the code they point at.

Line comments follow their code. When lines are added or removed above it, or an agent rewrites
the file, the comment moves to the nearest place where its code still appears. When that code is
gone, the comment is marked **code changed**, and the export quotes the original code.

### Submit with a custom command

By default, **Submit Review** pastes the review into the active terminal tab. To run a command
in a new terminal tab instead, set **Settings → Tools → Code Review Annotator → Submit command**.
`{file}` expands to the single-quoted path of a file that holds the review markdown, which
assumes a POSIX shell such as bash, zsh or fish:

```sh
claude "$(cat {file})"
```

### 4. Clear session

Click **Clear Session** in the tool window toolbar. This removes all comments and gutter icons.

**Submit Review** and **Clear Session** move the review to the history. **Review History** in the
toolbar lists the last 50 reviews; pick one to restore it.

**Export Reviews to File** saves the current review and the history to an XML file.
**Import Reviews from File** loads such a file: its review replaces the current one, and its
history merges into the local history.

**Undo** and **Redo** in the toolbar revert any comment change, including a clear or a submit.
With the comment list focused, the IDE undo and redo shortcuts work too.

Comments persist across IDE restarts (stored in `.idea/workspace.xml`).
