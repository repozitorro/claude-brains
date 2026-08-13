# Changelog

All notable changes to Claude Brains. The topmost section is what the built
plugin advertises as its release notes — see `patchPluginXml` in build.gradle.kts.

Each bullet stays on one line: the build reads them with a simple parser, not a
markdown engine.

## 0.7.0

- **Claude can edit files without being asked to first.** New chats now run as **Accept edits**. The old default left the CLI asking for permission — and there is no terminal here to answer in, so it simply refused, which looked like Claude not working. Every edit is still marked up in the editor for you to accept or reject; commands still follow your CLI's own rules. A mode you picked yourself is untouched.
- **Your unsaved changes are safe.** If Claude edits a file you have unsaved work in, the file is now left exactly as it is and the chat tells you both versions exist — instead of quietly reloading over what you had typed.
- **The CLI is found on Windows.** npm installs it as `claude.cmd`, which Windows would not locate from a bare name — so the plugin reported it missing on machines where it works fine in a terminal.
- **Long prompts no longer hit a limit.** The prompt is sent to the CLI directly rather than on the command line, which on Windows capped everything at about 32k characters.
- **Typing `@` no longer freezes the editor** on a large project: the file list is built in the background.

## 0.6.1

- **The review strip over an editor is now the platform's own floating toolbar** rather than a panel positioned by hand. Where it sits, when it appears and how it layers over the text are no longer ours to get wrong — which is what made its buttons unclickable in the first place.
- Its four controls are ordinary actions now, so they turn up in **Find Action** and can be given keyboard shortcuts.

## 0.6.0

- **Chats no longer disappear when the IDE closes.** Shutting WebStorm down was being mistaken for closing the tabs by hand, so some conversations were deleted on the way out — a different few each time. Already-lost chats can't be brought back, but no more will go.
- **Rename a chat** from the tool window's toolbar. "Chat 3" stops meaning anything by the third one.
- **Stepping between changes now crosses files.** The next / previous buttons walked one file and wrapped inside it; a turn usually changes several, so they carry on into the next changed file and say how many are left.
- **Accept is green, reject is red** — inside a change, on the strip over the editor, and above the prompt.
- **Reject all asks first**, saying how much goes back and that Undo can return it.

## 0.5.2

- **Stepping between changes no longer throws.** Drawing a change asked the platform for its document in a way that is only allowed inside a read action, and painting doesn't run in one — so every repaint of a change reported an error. The document is held directly now. The fault was reachable only once the next / previous buttons started working in 0.5.1.

## 0.5.1

- **A turn that needed permission no longer hangs the chat.** The CLI was asking for confirmation and waiting for an answer this panel has no way to send, so the turn never finished — no reply, no error, just a chat stuck thinking. It now can't wait, and says which tools were blocked and how to allow them.
- **Accept and reject are green and red.** They were told apart only by reading them, and reject wore the same red as the deleted lines behind it.
- **The strip's next / previous buttons work.** They were drawn where you could see them but were not laid out, so a click passed straight through — and the caret they moved was invisible in an unfocused editor, which looked like nothing happening.
- **The limits readout is legible.** It was pinned to an absolute 10pt, so it ignored the IDE's font size and display scaling entirely, in the palette's *disabled* grey. It now follows the interface font, turns amber past 75% and red past 90%, and the bar under it is no longer a hairline.

## 0.5.0

- **A reply can no longer put live HTML or scripts into the chat panel.** Markdown was rendered with raw HTML passed straight through, so anything Claude quoted — a file, a web page, the output of a tool — could introduce working markup into a panel that holds a bridge back into the IDE. It is escaped now.
- **Closing a chat stops the turn it was running.** The CLI process used to carry on with nowhere left to report to.
- **Closed chats are actually released.** Every one used to stay in memory for the life of the project, and its usage readout kept ticking once a minute even after the project was closed.
- **Usage Statistics opens where the embedded browser is missing**, the way the chat itself has since 0.4.5 — it was the one place left that threw instead of falling back.
- The usage poll can no longer leave stuck `claude` processes behind. Its timeout could never be reached, so a run that did not return was never cleaned up — once a minute, indefinitely.
- Under the hood: reading the CLI's protocol is now separate from running it, and covered by replaying a recorded turn, so a change in the CLI shows up as a failing test rather than as a chat that renders nothing. Every build is also checked against the platforms the plugin says it supports.

## 0.4.5

- **Fixes the panel coming up empty on WebStorm 2026.2**, where the embedded browser moved into its own bundled plugin: touching it threw, and that took the whole tool window with it. The dependency is declared now, and a missing browser costs only the rich renderer — the chat falls back to its Swing view.

## 0.4.4

- **Accept / Reject sit next to the change** they belong to, instead of at the far right of a wide editor
- **Deleting a chat always asks first** — closing the tab used to discard the conversation silently. The button wears a trash can now.
- A thin bar under the limits shows how full the session window is, shifting from amber to red as it fills

## 0.4.3

- **Context window readout** — how full the model's context is for this conversation, so it's clear when it is heading for a compaction
- **Delete a chat** deliberately, with a confirmation that says what is lost. The CLI's own transcript on disk is left alone.
- Buttons everywhere now take a hand cursor, including the painted Accept / Reject inside a change

## 0.4.2

- **Inline review actually engages now.** It had required the editor's document to match a separately-read disk snapshot character for character, and any divergence silently disabled it. The pre-edit content is reconstructed from the document itself instead, and it survives unrelated later edits.
- Changed files open whether or not they can be marked up.
- **Accept / Reject moved to the right edge of each change**, and a strip floats over the bottom of the editor to step between changes and take the whole file at once.
- Limits moved onto their own line under the model selectors; the composer no longer repeats the reset countdown.

## 0.4.1

- **Real usage percentages**, refreshed every minute — session and week, taken from the CLI's own `/usage` report, beside the reset countdown. Costs nothing and pauses while the panel is hidden.
- **Inline review and revert now work on CRLF files.** The CLI reports its edits with plain newlines even when the file uses CRLF, so on Windows projects nothing matched and both features silently declined to run. Reverting also keeps the file's own line endings instead of rewriting every line.

## 0.4.0

- **Review Claude's edits in the file.** When a turn ends the changed files open with new lines highlighted, the lines they replaced shown in red above them, and **Accept / Reject on each change** — in the gutter and inline. A bar above the prompt counts what's left and offers **Accept all / Reject all**. Rejecting is an ordinary undoable edit.
- **Rate-limit readout**: which window you are in, a ticking countdown to its reset, and the tokens spent inside it. Claude Code never publishes a remaining quota, so this shows real consumption rather than an invented percentage.

## 0.3.5

- Pasting a screenshot into the prompt, properly this time. The previous attempts hooked places the shortcut never reaches: inside the IDE, Ctrl+V is taken by the global paste action, which writes into the field directly. Image paste is now an action registered on the prompt itself, which outranks the global one, and it picks up whatever shortcut your keymap uses for paste.

## 0.3.4

- A proper icon: a top-down brain in line art, replacing the placeholder
- **Pasting a screenshot into the prompt now works.** It was bound to Ctrl+V, which the IDE claims for its own paste action, so it usually did nothing; it now goes through the text area's transfer handler — and dragging an image in works too
- A larger, roomier prompt font

## 0.3.3

- An expired login is now actionable: the failed turn shows a **Sign in** banner instead of only an error, and `/login` starts the sign-in rather than reporting itself unavailable. The CLI reports a stale login as valid, so this can only be caught when a request fails.

## 0.3.2

- Fixes the tool window opening empty ("Nothing to show") in 0.3.1: building the panel threw before any tab was added
- **Sign-in screen** when the Claude Code CLI isn't signed in, instead of a chat that fails on the first prompt. Signing in runs the CLI's own `auth login` in a terminal — no credentials are entered into the plugin. A missing CLI gets its own message and install hint.

## 0.3.1

- Pick a **specific model** per chat — Opus 5, Opus 4.8, Sonnet 5, Sonnet 4.6, Haiku 4.5, Fable 5 — or a family alias that follows the newest release
- **Permission mode per chat**, straight from the toolbar, with the full set the CLI accepts
- Redesigned prompt composer: one rounded card that highlights on focus, with the image button, usage readout and Send inside it

## 0.2.0

- **Usage statistics** — token usage by project, git branch, model and day, read from the CLI's own session transcripts, plus usage since the current rate-limit window opened
- Redesigned chat panel: colours and font taken from the IDE theme, status dots instead of emoji, code blocks with a language header and copy button, collapsible tool output, and auto-scroll that stops following once you scroll up to read

## 0.1.0

- Streaming chat with reasoning, rendered in an embedded browser (markdown + syntax highlighting), with a Swing fallback
- Tool activity with result status and collapsible output
- File edits reviewable in the native diff viewer, with per-file and whole-turn revert
- Persistent parallel sessions, usage analytics, permission modes, MCP server view, @-file references, image input, transcript export
