# Claude Brains — custom JetBrains plugin

A chat-UI front-end for the `claude` CLI inside WebStorm / any JetBrains IDE,
instead of the bare-terminal experience the official Anthropic plugin gives
you. It wraps the CLI as a subprocess — no API key needed, it reuses whatever
login your `claude` CLI already has (your Claude Code subscription).

## Features

- **Rich chat rendering in an embedded browser (JCEF)** — real streaming
  markdown, **syntax-highlighted** code blocks (highlight.js) and copy-code
  buttons, themed to match the IDE. Falls back to a plain Swing renderer where
  JCEF isn't available.
- **True token-by-token streaming** (uses the CLI's `--include-partial-messages`
  deltas), including muted **reasoning/thinking** shown above the answer
- **Persistent sessions** — conversations, their `--resume` context, model
  choice and usage totals survive an IDE restart (stored per project)
- **Tool-activity view** — the files Claude reads/edits and commands it runs
  show up live with result status (`🔧` running, `✓` ok, `✗` failed) and their
  **output collapsed underneath** (failures open by default)
- **Collapsible reasoning**, and **copy / export** the conversation as Markdown
- **Native slash commands** — `/clear`, `/cost`, `/model`, `/help` work in-panel;
  interactive-only CLI commands get an honest "not available here" note instead
  of being silently sent as prompt text
- **Blocked-tool surfacing** — if the permission mode makes the CLI refuse a
  tool, the chat says so (with which tools) instead of failing silently
- **Diff review of file edits** — every `Edit`/`MultiEdit`/`Write` Claude makes
  gets a `✎ Edit foo.kt  diff  revert` line: **diff** opens IntelliJ's native
  diff viewer (before vs current), **revert** restores the pre-edit content.
  "Before" is reconstructed race-free from the edit itself, and revert is only
  offered when that reconstruction is provably exact. Turns that touched several
  files also get a **revert all** link, and the diff window carries its own
  **Revert This Edit** button.
- **Inline review, in the file itself** — when a turn ends, the files Claude
  edited open with the new lines highlighted, the lines they replaced drawn in
  red directly above, and **Accept / Reject on each change** (in the gutter and
  inline). A bar above the prompt counts what's left and offers **Accept all /
  Reject all**. Rejecting is an ordinary undoable edit, so Ctrl+Z works.
- **`@` file references** — type `@` in the prompt to autocomplete a project
  file path; "Reference This File in Chat" does the same from the editor, so
  Claude reads the file itself instead of you pasting (and paying for) it
- **Image input** — paste a screenshot straight into the prompt (it's written to
  a temp PNG and referenced by path, which is how the headless CLI consumes
  images), or pick a file with the 🖼 button
- **MCP servers view** in Settings — read-only `claude mcp list` output, plus a
  chat warning when a server fails to start
- **Clear failure messages** — a failed turn shows the CLI's actual reason, and
  an expired login tells you to re-authenticate instead of showing a blank reply
- **Usage analytics** — cumulative cost + input/output tokens per session, plus
  the current rate-limit window's reset countdown
- **Settings page** (Settings → Tools → Claude Brains): CLI path, default model,
  **permission mode** (`default` / `acceptEdits` / `plan` / `bypassPermissions`),
  allowed/disallowed tools
- A **Stop** button to cancel a running turn
- Multiple named, **parallel** chat sessions (tabs), each with its own
  `--resume` context
- Slash-command autocomplete (`/help`, `/model`, `/clear`, ...)
- Model selector dropdown per session (`opus` / `sonnet` / `haiku` aliases)
- **Send Selection to Claude** / **Send Whole File to Claude** — right-click
  in the editor, no copy-paste
- **Floating prompt** popup near the caret — `Alt+Shift+C`

## Requirements

- IntelliJ IDEA (Community is enough) or another JetBrains IDE, **just for
  building/testing the plugin** — you don't need it to *use* the finished
  plugin, which installs into WebStorm like any other plugin
- JDK 17+
- The [Claude Code CLI](https://docs.claude.com/en/docs/claude-code/overview)
  installed and on your PATH (`npm install -g @anthropic-ai/claude-code`),
  logged in already (`claude` once from a terminal to authenticate)
- Internet access for Gradle to download the IntelliJ Platform SDK on first
  build (a few hundred MB, one-time)

## Build & run locally

```bash
# 1. Unzip this project, cd into it
cd claude-code-chat-plugin

# 2. Launch a sandboxed IDE instance with the plugin installed
./gradlew runIde
```

`runIde` downloads IntelliJ Community 2024.1 (per `build.gradle.kts`) the
first time — that's normal, just slow. A separate sandbox IDE window opens
with the plugin already active; open the "Claude Brains" tool window on
the right-hand sidebar.

To build an installable `.zip` you can add to WebStorm directly (**Settings
→ Plugins → ⚙️ → Install Plugin from Disk...**):

```bash
./gradlew buildPlugin
# output: build/distributions/claude-brains-0.1.0.zip
```

## Installing in WebStorm (and keeping it updated)

**Recommended — as a plugin repository, so the IDE handles updates itself:**

1. WebStorm → **Settings → Plugins → ⚙️ → Manage Plugin Repositories… → +**
2. Add:
   `https://raw.githubusercontent.com/repozitorro/claude-brains/main/updatePlugins.xml`
3. Search **Claude Brains** on the *Marketplace* tab and install it.

From then on, updates arrive the normal way: tag a release
(`git tag v0.2.0 && git push origin v0.2.0`), CI publishes it, and WebStorm
offers the update.

**One-off — install a zip directly:**

```bash
./gradlew buildPlugin
```

then **Settings → Plugins → ⚙️ → Install Plugin from Disk…** and pick
`build/distributions/claude-brains-<version>.zip`. Every later update means
repeating this by hand, which is why the repository route above exists. A zip
is also attached to every CI run and every GitHub release, if you'd rather not
build locally.

**While developing**, skip installing altogether:

```bash
./gradlew runIde
```

That launches a sandboxed IDE with the plugin loaded, leaving your real
WebStorm untouched.

## Status

The build is verified end-to-end: `./gradlew buildPlugin` produces an
installable zip, and the CLI protocol parsing in `ClaudeCliService` was
checked against the real **Claude Code CLI 2.1.205** stream-json output.

Things you may still want to adjust:

1. **CLI protocol drift** — the JSON event shape from `claude -p ...
   --output-format stream-json --verbose --include-partial-messages` is an
   implementation detail that can change between CLI versions. If a future
   version breaks parsing, re-sample it and update `handleLine`:

   ```bash
   claude -p "hi" --output-format stream-json --verbose \
     --include-partial-messages > sample.jsonl
   cat sample.jsonl
   ```

   The events that matter: `stream_event` → `event.content_block_delta` →
   `delta.text_delta` (answer) / `delta.thinking_delta` (reasoning), and the
   terminal `result` event (cost/usage). Every line carries `session_id`.

2. **`intellij { version.set("2024.1") }`** in `build.gradle.kts` — point
   this at whatever WebStorm/IntelliJ platform build you actually want to
   target (check **Help → About** in your IDE for the build number).

## Project layout

```
src/main/kotlin/com/claudecode/chatplugin/
  ClaudeCliService.kt       # spawns `claude`, parses streamed JSON (text/thinking/tool_use/result)
  ClaudeSessionManager.kt   # owns + persists all parallel sessions (per-project storage)
  ClaudeCodeSettings.kt     # persisted CLI path / model / permission mode / tool policy
  model/
    ChatMessage.kt          # role, text, thinking, tool calls, file edits
    ClaudeSession.kt        # per-session --resume id, cumulative usage, rate-limit snapshot
    ToolCall.kt             # a non-edit tool call + its result status
    FileEdit.kt             # one Edit/MultiEdit/Write, with race-free before/after reconstruction
src/test/kotlin/.../model/FileEditTest.kt        # diff reconstruction + revert-safety
src/test/kotlin/.../ui/MessageRendererTest.kt    # rendered fragments + diff/revert link tokens
  ui/
    ChatToolWindowFactory.kt   # one tab per session
    ChatPanel.kt               # input + model selector + usage analytics; drives a ChatView
    ChatView.kt                # rendering-surface interface (index-addressed messages)
    JcefChatView.kt            # embedded-browser view: markdown + highlight.js + JS bridge
    SwingChatView.kt           # JEditorPane fallback view
    MessageRenderer.kt         # message -> HTML fragment (answer, thinking, tools, edit links)
    ProjectFileSearch.kt       # cached project-file index behind the `@` autocomplete
    ImageAttachments.kt        # clipboard screenshot -> temp PNG for path-based image input
    TranscriptExporter.kt      # conversation -> Markdown (copy / export)
    DiffReviewer.kt            # opens IntelliJ's diff viewer; reverts an edit
    ReviewBar.kt               # Accept all / Reject all above the prompt
  review/
    Hunk.kt                    # one reviewable change, tracked by a RangeMarker
    PendingEdit.kt             # a file's changes, split into hunks by the platform diff
    EditReviewService.kt       # what's still unreviewed; accept/reject at every scope
    EditReviewDecorator.kt     # editor highlighting, red removed-lines inlay, controls
    EditReviewDecorations.kt   # keeps open editors decorated as they come and go
    ClaudeBrainsConfigurable.kt # Settings > Tools > Claude Brains page
    SlashCommands.kt           # autocomplete list
src/main/resources/webview/    # chat.html shell + highlight.min.js + hljs themes (bundled)
  actions/
    SendSelectionToClaudeAction.kt
    SendFileToClaudeAction.kt
    FloatingPromptAction.kt   # Alt+Shift+C
    NewSessionAction.kt
src/main/resources/META-INF/plugin.xml   # registers everything above
```

## License

[MIT](LICENSE).

### Third-party

`src/main/resources/webview/` bundles [highlight.js](https://highlightjs.org/)
11.9.0 and two of its themes (BSD-3-Clause, license header kept in the file) so
the chat view works offline.

## Not included yet (ideas for later)

- Adding/removing MCP servers from the UI (deliberately left to `claude mcp add`
  so the plugin never rewrites your MCP config)
- Batch "revert all edits from this turn" and an accept/reject toolbar inside
  the diff window itself (today: per-file `diff` / `revert` links in the chat)
- A true "% of your usage limit" readout — the CLI stream exposes the rate-limit
  *window* (type + reset time) but not an exact remaining-percentage number;
  `/usage` inside the CLI is the source for that
