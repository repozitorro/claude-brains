# Changelog

All notable changes to Claude Brains. The topmost section is what the built
plugin advertises as its release notes — see `patchPluginXml` in build.gradle.kts.

Each bullet stays on one line: the build reads them with a simple parser, not a
markdown engine.

## 0.11.2

- **Shift+Enter breaks a line** instead of sending. Leaving it out of the send handler was never enough — the IDE dispatches its own keymap before the text area sees a key, so the shortcut has to be claimed on the input itself. The placeholder now says so.
- **"in" counts what the turn actually sent.** It read `87 in / 32.3k out`, as though Claude had written four hundred times more than it was given: the figure left out everything served from cache, which on a long conversation is nearly all of it.
- **Prices lose the digits that say nothing.** `$2.8111` is now `$2.81`, while a first turn at `$0.0043` keeps every place it needs.
- **A tool row shows the command, not the `cd` in front of it.** `Bash cd "D:\Work\…" && npm run graphify -- query…` spent its whole width choosing a directory.

## 0.11.1

- **Replies no longer run together where a tool ran.** Each time Claude stops to use a tool the reply resumes in a new block, and those were being appended to the same paragraph — "…не вигадувати свій.Тепер створю…", three sentences in one.
- **The context figure is the context again.** It read `1.3M / 1.0M (128%)`, which cannot happen: it was counting every request a turn made, and each one re-reads the cached prefix. It now reports the last request, which is the thing the window bounds.
- **The agent dropdown stays out of the way until it has something to offer.** Which agents exist is only knowable once the CLI has answered once, so before that it held a single entry and did nothing.
- Both new dropdowns are wide enough for their own labels; they were clipping them to "Defa…ffort" and "Defa…gent".
- A tool blocked in **Auto** or **Don't ask** now explains that those modes decide for themselves — it used to blame a missing terminal, which is not why.

## 0.11.0

- **Claude can ask you a question, and you can answer it.** When it needs to know something it puts the question in the chat with its options as buttons, instead of guessing. Since 0.10.4 those were arriving and being drawn as a permission card — **Run** or **Skip** in answer to *tabs or spaces*.
- **The "/" popup lists what your CLI actually has**, skills and your own commands included, read from the CLI itself each turn. The old list was seventeen names typed by hand and had drifted: it knew nothing of the eighteen skills on one machine.
- **An agent and an effort level per chat**, beside the model. Effort — low through max — is the most direct lever there is on what a turn costs.
- **A spending cap and extra directories**, in Settings. The cap guards against a runaway turn; extra directories are for the repository next door that the work genuinely spans. Both are off by default.
- **Branch a conversation from any of your messages.** The new chat keeps everything up to that point and continues under its own id, so the original is left exactly as it was — verified: the original transcript does not gain a byte.
- The toolbar no longer runs its dropdowns underneath the buttons beside them, and the agent selector no longer calls itself "Default effort".
- A chat's agent and effort now survive an IDE restart, as its model and permission mode already did.

## 0.10.4

- **You are asked once, however many times the CLI asks.** It abandons a permission request after about a minute and repeats it, up to four times, before giving up on the turn — so thinking for two minutes used to hand you a stack of identical cards. Repeats now wait on the answer you are already giving.
- **A card stops offering Run once nobody is listening.** When the turn ends, the chat closes, or the wait runs out, the card says what happened instead of inviting a click into nothing. Note the real budget is the CLI's: about four minutes.
- **The plugin no longer litters your `~/.claude`.** Reading the usage percentages runs the CLI, and every run filed a transcript — one a minute, for as long as the panel was open, sitting among your real conversations where `claude --resume` looks. It now names its own session and removes that file afterwards. If you have a backlog, `grep -rl '"content":"/usage' ~/.claude/projects --include=*.jsonl | xargs rm` clears it.

## 0.10.3

- **Fixes Run and Skip, which broke the whole panel in 0.10.2.** Clicking one followed the link instead of answering: the conversation was replaced by a browser error page while the turn sat waiting for an answer that could no longer be sent. Every action link in the page is now caught by a single handler that exists before the markup does, so no future button can land in the same gap.
- **A notification when a limit you were up against resets.** Only for windows that had actually got tight — one resets every few hours whether or not anyone was waiting. It waits for evidence rather than for the clock: the next report has to say both a smaller percentage and a different reset time.
- When a window is nearly full, the plugin books **one** further usage read for the moment it is due, so the reset is noticed even if you closed the panel and walked away.

## 0.10.2

- **The question is now asked on the command it is about.** It used to be a block of its own at the end of the conversation, so it drifted to the bottom as Claude kept writing above it, and the same command appeared twice — once as the row that ran it, once as the card that asked. One row now: the command, **Run** and **Skip**, then its output in the same place.
- **A row waiting on you opens itself**, and keeps one word afterwards — `allowed` or `skipped` — so the transcript shows which calls you decided rather than the CLI.
- **Auto and Don't ask no longer stop to ask.** Those modes exist so that nobody is asked anything; being interrupted mid-turn in **Auto** was the mode failing at its one job. The modes that do ask are Accept edits, CLI default, Plan and Manual.
- **Every permission mode now says what it does.** Three of them had no description at all, and two described behaviour that stopped being true when asking became possible.
- `cd "D:\Work\project" && npm run graphify` was headed **Command cd**. The CLI writes that preamble constantly, having no other way to choose a directory, so the heading now looks past it to the program actually being run.

## 0.10.1

- **Claude now asks before it acts, instead of refusing and explaining afterwards.** A card appears in the conversation with the command it wants to run and where it would run it, and **Run** or **Skip**. The turn is genuinely paused until you answer — nothing has happened yet.
- **Always allow a program**, e.g. `npm`, for the rest of the chat. Offered only when the line is one program's arguments and nothing else: a `;` or `&&` anywhere and the offer is withdrawn, because the button could no longer say what it grants.
- This can be switched off in Settings, which puts the old after-the-fact **Allow / No** message back. It rests on a CLI flag that is absent from `claude --help`, so the older path stays in place for the day it stops working.
- **Tools installed for your user only now work.** `~/.local/bin`, npm's prefix, cargo, bun and pip's `--user` scripts are added to the CLI's PATH when they exist — that is where a "command not found" for something plainly installed usually comes from. Settings takes extra directories and `KEY=VALUE` lines for anything installed somewhere unguessable.
- **Command output opens and scrolls.** A chevron now marks the rows that have output — they were always openable, but nothing said so — and ten times more of that output is kept, so a test run or a query result no longer stops mid-sentence.
- (0.10.0 was tagged but never built: its tests read a Windows path on a Linux runner and failed. Nothing was published under it.)

## 0.9.0

- **Claude now sees your unsaved changes.** The CLI reads files from disk, so anything typed and not saved was invisible to it — you could ask about a file you had just edited and get an answer about the version before your edit. Unsaved files in the project are written out before each turn.
- **The change is shown in the conversation.** Instead of a line saying a file was edited, the changed lines appear under it — removed in red, added in green. The **diff** link is still there for the full picture.
- **The IDE's errors are offered back to Claude.** After a turn that changed files, any errors the IDE finds in them appear in the chat with one click to send them back.
- **Type while Claude is answering.** Messages now queue instead of being refused, and go as soon as the turn ends. **Stop** cancels the queue too.
- **The review strip sits at the foot of the file**, centred and stationary, with green **Accept file** and red **Reject file**.
- **Restore files to before any message**, for when it went wrong several turns back. The conversation is left alone — Claude still remembers all of it.
- **Send the terminal to the chat**: the selection, or the tail of a long build log.
- **`@` finds symbols**, not only file paths — type a class name and get its file.
- **Allowed tools take a plain command.** Write `git` rather than `Bash(git *) PowerShell(git *)`.
- **A CLAUDE.md button**: the standing instructions Claude reads in this project, opened or created from the panel.
- **A log window** showing exactly what the CLI was asked and what it replied, for when something goes wrong.
- A blocked tool now links straight to Settings instead of describing the way there.

## 0.8.0

- **You can now allow a blocked tool from the chat.** When something is refused, the message offers `Allow Bash(git *)?` with **Allow in this chat**, **Always allow** and **No**. Allowing sends your message again straight away, so you don't retype it. "In this chat" lasts until the tab is closed and is written nowhere; "always" adds it to Settings.
- The suggested allowance now covers **every** tool that was refused. The same `git add` can come back as both Bash and PowerShell, and suggesting only one of them unblocked half the turn.
- Note this is a question asked after the turn ends, not a prompt caught before the command runs — the CLI decides on its own in this mode and only reports what it refused, so agreeing means asking again.

## 0.7.3

- **When a tool is blocked, the chat now tells you something you can act on.** It used to suggest switching to **Accept edits** even if you were already on it — that mode allows file edits, and a command like `git add` is a different permission it never covered. The message now says which it was, quotes what was actually refused (`Bash × 3 — git add src/App.kt`), and suggests a narrow allowance such as `Bash(git *)` rather than opening up every command.
- **Plan** is now described as the deliberate read-only mode it is, instead of being offered settings that cannot help while it is on.

## 0.7.2

- Fixes finding `claude.cmd` on Windows, which 0.7.1 got wrong: the search built its paths with the separator of whatever machine compiled it. 0.7.1 was never published.

## 0.7.1

- **The usage readout stops re-reading every transcript you have ever made.** It refreshes once a minute, and it was parsing the CLI's entire session history each time to answer a question about the last few hours. Files that cannot contain anything recent are no longer opened at all.
- Internal: the protocol fixture the tests replay is now a real capture from CLI 2.1.223, and release notes are generated from `CHANGELOG.md` instead of being hand-written as HTML inside the plugin descriptor.

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
