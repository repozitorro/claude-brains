package com.claudecode.chatplugin.stats

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Renders a [UsageReport] as a standalone HTML page.
 *
 * Chart choices follow the data's job: stat tiles for the headline figures,
 * one-hue ranked bars where a single measure is compared across projects or
 * days, and a single stacked bar (with legend and labels) where the four token
 * kinds carry identity. Every chart is backed by a table of the same numbers.
 *
 * Colours: neutrals and font come from the IDE theme; the categorical slots are
 * the validated four-hue set, stepped per mode.
 */
object UsageStatsPage {

    class Theme(
        val bg: String,
        val fg: String,
        val muted: String,
        val surface: String,
        val border: String,
        val accent: String,
        val font: String,
        val fontSize: Int,
        val dark: Boolean
    ) {
        /** Validated categorical slots 1–4 (output / input / cache write / cache read). */
        val series: List<String>
            get() = if (dark) listOf("#3987e5", "#d95926", "#199e70", "#c98500")
            else listOf("#2a78d6", "#eb6834", "#1baf7a", "#eda100")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun pct(part: Long, whole: Long): Double =
        if (whole <= 0) 0.0 else part * 100.0 / whole

    private fun shortProject(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }

    fun render(report: UsageReport, theme: Theme): String {
        val t = UsageStats::formatTokens
        val o = report.overall

        val tiles = buildString {
            append(tile("Tokens, all time", t(o.total), "${report.entryCount} assistant replies"))
            append(tile("Output tokens", t(o.output), "what Claude actually wrote"))
            append(tile("Projects", report.projects.size.toString(),
                "${report.projects.sumOf { it.branches.size }} branches"))
            append(
                if (report.window != null) {
                    tile(
                        "Current limit window", t(report.window.total),
                        report.windowResetMs?.let { "resets ${relative(it)}" } ?: "since window start"
                    )
                } else {
                    tile("Current limit window", "—", "send a message to learn the window")
                }
            )
        }

        val composition = compositionBar(o, theme)
        val projects = projectSection(report)
        val days = daysSection(report)
        val models = modelsSection(report)

        return """
<!doctype html>
<html><head><meta charset="utf-8"><title>Claude Brains — usage</title>
<style>
:root {
  --bg:${theme.bg}; --fg:${theme.fg}; --muted:${theme.muted};
  --surface:${theme.surface}; --border:${theme.border}; --accent:${theme.accent};
  --s1:${theme.series[0]}; --s2:${theme.series[1]}; --s3:${theme.series[2]}; --s4:${theme.series[3]};
  --ui:${theme.font}, -apple-system, system-ui, sans-serif;
  --mono:'JetBrains Mono','SF Mono',Consolas,monospace;
}
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--fg); font-family:var(--ui);
       font-size:${theme.fontSize}px; -webkit-font-smoothing:antialiased; }
.wrap { max-width:900px; margin:0 auto; padding:20px 20px 40px; display:flex; flex-direction:column; gap:26px; }
h1 { font-size:${theme.fontSize + 5}px; font-weight:600; margin:0 0 3px; }
h2 { font-size:${theme.fontSize}px; font-weight:600; margin:0 0 10px;
     letter-spacing:.04em; text-transform:uppercase; color:var(--muted); }
.sub { color:var(--muted); margin:0; line-height:1.5; }
.num { font-variant-numeric:tabular-nums; }

.tiles { display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:10px; }
.tile { background:var(--surface); border:1px solid var(--border); border-radius:8px; padding:12px 14px; }
.tile .k { font-size:11px; letter-spacing:.05em; text-transform:uppercase; color:var(--muted); }
.tile .v { font-size:${theme.fontSize + 11}px; font-weight:600; margin:4px 0 2px; }
.tile .n { font-size:11px; color:var(--muted); }

/* Stacked composition: 2px surface gaps between segments, rounded ends. */
.stack { display:flex; height:26px; gap:2px; margin-bottom:10px; }
.stack span { display:block; height:100%; }
.stack span:first-child { border-radius:4px 0 0 4px; }
.stack span:last-child { border-radius:0 4px 4px 0; }
.legend { display:flex; flex-wrap:wrap; gap:6px 18px; }
.legend div { display:flex; align-items:baseline; gap:7px; font-size:12px; }
.swatch { width:9px; height:9px; border-radius:2px; flex:none; }

table { width:100%; border-collapse:collapse; font-size:12px; }
th { text-align:left; font-weight:500; color:var(--muted); font-size:11px;
     letter-spacing:.04em; text-transform:uppercase; padding:0 8px 6px 0; }
td { padding:5px 8px 5px 0; border-top:1px solid var(--border); vertical-align:middle; }
td.r, th.r { text-align:right; }
.bar { height:8px; border-radius:4px; background:var(--accent); min-width:2px; }
.barcell { width:38%; }
.name { font-family:var(--mono); font-size:11px; }
.dim { color:var(--muted); }

.days { display:flex; align-items:flex-end; gap:3px; height:110px; }
.days div { flex:1; background:var(--accent); border-radius:4px 4px 0 0; min-height:2px; }
.dayaxis { display:flex; justify-content:space-between; color:var(--muted); font-size:11px; margin-top:6px; }

details.proj { border-top:1px solid var(--border); }
details.proj > summary { cursor:pointer; padding:7px 0; list-style:none; display:flex;
      align-items:center; gap:10px; }
details.proj > summary::-webkit-details-marker { display:none; }
details.proj > summary::before { content:''; width:0; height:0; flex:none;
      border-left:4px solid var(--muted); border-top:3px solid transparent; border-bottom:3px solid transparent; }
details.proj[open] > summary::before { transform:rotate(90deg); }
.note { background:var(--surface); border:1px solid var(--border); border-left:2px solid var(--accent);
        border-radius:0 6px 6px 0; padding:11px 14px; color:var(--muted); line-height:1.55; font-size:12px; }
.note b { color:var(--fg); font-weight:600; }
@media (prefers-reduced-motion:reduce) { * { transition:none !important; } }
</style></head>
<body><div class="wrap">

<header>
  <h1>Usage statistics</h1>
  <p class="sub">Read from the Claude Code CLI's own session transcripts — every project you have
  used the CLI in, not just this one.</p>
</header>

<section class="tiles">$tiles</section>

<section>
  <h2>What the tokens were</h2>
  $composition
</section>

<section>
  <h2>By project and branch</h2>
  $projects
</section>

<section>
  <h2>Last ${report.days.size} active days</h2>
  $days
</section>

<section>
  <h2>By model</h2>
  $models
</section>

<p class="note">
  <b>Cost and “% of limit” are not shown because the CLI does not record them.</b>
  Transcripts store token counts only — no prices, and no limit figures. Any dollar
  amount here would be a guess at pricing that changes over time, and a percentage
  would need a limit the CLI never reports. The window tile counts the tokens spent
  since the current rate-limit window opened, which is the closest honest measure.
</p>

</div></body></html>
        """.trimIndent()
    }

    private fun tile(key: String, value: String, note: String): String =
        """<div class="tile"><div class="k">${esc(key)}</div>
           <div class="v num">${esc(value)}</div><div class="n">${esc(note)}</div></div>"""

    private fun compositionBar(o: Bucket, theme: Theme): String {
        val parts = listOf(
            Triple("Output", o.output, theme.series[0]),
            Triple("Input", o.input, theme.series[1]),
            Triple("Cache writes", o.cacheWrite, theme.series[2]),
            Triple("Cache reads", o.cacheRead, theme.series[3])
        ).filter { it.second > 0 }
        if (parts.isEmpty()) return """<p class="sub">No usage recorded yet.</p>"""

        val stack = parts.joinToString("") { (name, value, color) ->
            """<span style="width:${"%.3f".format(java.util.Locale.ROOT, pct(value, o.total))}%;background:$color"
                  title="${esc(name)}: ${UsageStats.formatTokens(value)} tokens"></span>"""
        }
        val legend = parts.joinToString("") { (name, value, color) ->
            """<div><span class="swatch" style="background:$color"></span>
               <span>${esc(name)}</span>
               <span class="dim num">${UsageStats.formatTokens(value)} ·
               ${"%.1f".format(java.util.Locale.ROOT, pct(value, o.total))}%</span></div>"""
        }
        return """<div class="stack">$stack</div><div class="legend">$legend</div>"""
    }

    private fun projectSection(report: UsageReport): String {
        if (report.projects.isEmpty()) return """<p class="sub">Nothing recorded yet.</p>"""
        val max = report.projects.maxOf { it.bucket.total }.coerceAtLeast(1)

        return report.projects.joinToString("") { p ->
            val width = pct(p.bucket.total, max)
            val branchRows = p.branches.joinToString("") { (branch, b) ->
                """<tr>
                     <td class="name">${esc(branch)}</td>
                     <td class="barcell"><div class="bar" style="width:${"%.2f".format(java.util.Locale.ROOT, pct(b.total, p.bucket.total))}%"
                         title="${UsageStats.formatTokens(b.total)} tokens"></div></td>
                     <td class="r num">${UsageStats.formatTokens(b.total)}</td>
                     <td class="r num dim">${UsageStats.formatTokens(b.output)} out</td>
                     <td class="r num dim">${b.messages}</td>
                   </tr>"""
            }
            """<details class="proj">
                 <summary>
                   <span style="flex:1"><b>${esc(shortProject(p.project))}</b>
                     <span class="dim name">${esc(p.project)}</span></span>
                   <span class="num">${UsageStats.formatTokens(p.bucket.total)}</span>
                 </summary>
                 <div style="padding:0 0 12px 14px">
                   <div class="bar" style="width:${"%.2f".format(java.util.Locale.ROOT, width)}%;margin-bottom:10px"></div>
                   <table>
                     <thead><tr><th>Branch</th><th class="barcell">Share</th>
                       <th class="r">Tokens</th><th class="r">Output</th><th class="r">Replies</th></tr></thead>
                     <tbody>$branchRows</tbody>
                   </table>
                 </div>
               </details>"""
        }
    }

    private fun daysSection(report: UsageReport): String {
        if (report.days.isEmpty()) return """<p class="sub">No dated activity yet.</p>"""
        val max = report.days.maxOf { it.second.total }.coerceAtLeast(1)
        val bars = report.days.joinToString("") { (day, b) ->
            """<div style="height:${"%.2f".format(java.util.Locale.ROOT, pct(b.total, max))}%"
                 title="$day: ${UsageStats.formatTokens(b.total)} tokens, ${b.messages} replies"></div>"""
        }
        val first = report.days.first().first
        val last = report.days.last().first
        return """<div class="days">$bars</div>
                  <div class="dayaxis"><span>${esc(first)}</span><span>${esc(last)}</span></div>"""
    }

    private fun modelsSection(report: UsageReport): String {
        if (report.models.isEmpty()) return """<p class="sub">No models recorded yet.</p>"""
        val max = report.models.maxOf { it.second.total }.coerceAtLeast(1)
        val rows = report.models.joinToString("") { (model, b) ->
            """<tr>
                 <td class="name">${esc(model)}</td>
                 <td class="barcell"><div class="bar" style="width:${"%.2f".format(java.util.Locale.ROOT, pct(b.total, max))}%"></div></td>
                 <td class="r num">${UsageStats.formatTokens(b.total)}</td>
                 <td class="r num dim">${UsageStats.formatTokens(b.output)} out</td>
                 <td class="r num dim">${b.messages}</td>
               </tr>"""
        }
        return """<table>
                    <thead><tr><th>Model</th><th class="barcell">Tokens</th>
                      <th class="r">Total</th><th class="r">Output</th><th class="r">Replies</th></tr></thead>
                    <tbody>$rows</tbody>
                  </table>"""
    }

    private fun relative(epochMs: Long): String {
        val d = Duration.between(Instant.now(), Instant.ofEpochMilli(epochMs))
        if (d.isNegative) return "now"
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return if (h > 0) "in ${h}h ${m}m" else "in ${m}m"
    }

    @Suppress("unused")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
