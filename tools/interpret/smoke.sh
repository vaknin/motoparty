#!/usr/bin/env bash
# Sends spoken phrases to Gemini exactly as the app's cloud interpreter does (PROTOCOL.md
# "Interpretation", "Voice actions") and prints, per phrase, the action list it answered in a
# compact form, whether that is what phrases.tsv expects, and the round-trip time. The prompt and
# schema are the app's own (android/app/src/main/res/raw/interpret_prompt.txt, interpret_schema.json).
#
#   tools/interpret/smoke.sh                        every line of tools/interpret/phrases.tsv
#   tools/interpret/smoke.sh "play some moby"       one phrase, nothing playing
#   tools/interpret/smoke.sh "drop the next two" @queue           one phrase, the sample queue
#   tools/interpret/smoke.sh "any" "" "play a moby album" "Which Moby album?"   a reply to a question
#   GEMINI_MODEL=… tools/interpret/smoke.sh         another model
#
# phrases.tsv: phrase <tab> context <tab> expected answer <tab> [first request <tab> question].
# - context: empty = nothing playing; "title – artist" = that track playing, nothing queued or
#   played; "@queue" = the sample below (Porcelain – Moby at 1:14, 12 upcoming, 3 played, a
#   lastVoice).
# - expected: a glob (`*` allowed) over the compact answer: the actions joined by "; ", each as
#   play <kind> <query> | play similar | add [next|instead] [<count>] <kind> <query> |
#   add [next|instead] [<count>] similar | remove 1,2 | remove artist <name> | move 3 to 1 |
#   clear | jump 4 | jump -2 | seek +30 | seek -10 | seek to 120 | repeat <mode> | tell <about> |
#   volume up | volume down | ask <question> | <fallback or -> | the type name for the rest;
#   `-` = conversation (no action besides none). Lowercase.
# - The two last columns make the line the second turn: the phrase is the reply to that question.
# Each phrase is one free-tier request (15 a minute, 500 a day per Flash-Lite model), so the
# full file is spaced 4.2 s apart.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
raw=$root/android/app/src/main/res/raw
model=${GEMINI_MODEL:-gemini-3.5-flash-lite}
key=$(sed -n 's/^gemini\.apiKey=//p' "$root/android/local.properties" | tr -d '[:space:]')
[[ -n $key ]] || { echo "gemini.apiKey is not set in android/local.properties" >&2; exit 1; }
out=$(mktemp); trap '[[ -n ${KEEP_RAW:-} ]] && cp "$out" "$KEEP_RAW"; rm -f "$out"' EXIT

# The context window (PROTOCOL.md "Voice actions"), from a context column.
context() {
    case $1 in
    '') jq -cn '{playing: null, repeat: "off", upNext: [], queueLength: 0, played: [], lastVoice: null}' ;;
    @queue) jq -cn '{
        playing: {title: "Porcelain", artist: "Moby", album: "Play", atS: 74, lengthS: 241},
        repeat: "off",
        upNext: ["Natural Blues – Moby", "Why Does My Heart Feel So Bad? – Moby", "Yellow – Coldplay",
                 "Fix You – Coldplay", "Bohemian Rhapsody – Queen", "Don'\''t Stop Me Now – Queen",
                 "Paradise – Coldplay", "Angel – Massive Attack", "Bodyrock – Moby",
                 "Viva la Vida – Coldplay", "Somebody to Love – Queen", "South Side – Moby"]
                | to_entries | map("\(.key + 1). \(.value)"),
        queueLength: 12,
        played: ["Teardrop – Massive Attack", "Under Pressure – Queen", "Unfinished Sympathy – Massive Attack"]
                | to_entries | map("-\(.key + 1). \(.value)"),
        lastVoice: "removed 2: Clocks – Coldplay, The Scientist – Coldplay (3 min ago)"}' ;;
    *) jq -cn --arg now "$1" '($now | split(" – ")) as $p | {
        playing: {title: $p[0], artist: ($p[1:] | join(" – ")), atS: 60, lengthS: 240},
        repeat: "off", upNext: [], queueLength: 0, played: [], lastVoice: null}' ;;
    esac
}

# The answer as one compact line (see the header); `-` for conversation.
compact='
  def src: if .kind == "similar" then "similar" else "\(.kind // "song") \(.query // "")" end;
  def cnt: if (.count | type) == "number" and .count >= 1 and .count <= 50 and .count == (.count | floor)
           then (.count | tostring) else null end;
  def nums: (.at // []) | map(tostring) | join(",");
  [.actions[]? | select(.type != "none") |
    if .type == "play" then "play \(src)"
    elif .type == "add" then
      ["add", (if .where == "next" or .where == "instead" then .where else null end), cnt, src]
      | map(select(. != null)) | join(" ")
    elif .type == "remove" then (if (.at // []) != [] then "remove \(nums)" else "remove artist \(.artist // "")" end)
    elif .type == "move" then "move \(nums) to \(.to)"
    elif .type == "jump" then "jump \(.at)"
    elif .type == "seek" then
      (if (.by // 0) != 0 then "seek \(if .by > 0 then "+" else "" end)\(.by)" else "seek to \(.to)" end)
    elif .type == "repeat" then "repeat \(.mode)"
    elif .type == "tell" then "tell \(.about)"
    elif .type == "ask" then "ask \(.question // "") | \(if (.query // "") == "" then "-" else "play \(src)" end)"
    elif .type == "volumeUp" then "volume up" elif .type == "volumeDown" then "volume down"
    else .type end]
  | if length == 0 then "-" else join("; ") end'

# one <phrase> <context> [<first request> <question>]: prints "<ms>\t<compact answer or !error>"
one() {
    local input code t0 t1
    input=$(jq -cn --arg p "$1" --argjson ctx "$(context "$2")" --arg first "${3:-}" --arg q "${4:-}" \
        '{phrase: $p, lang: "en-US"} + $ctx
         + (if $q == "" then {} else {asked: {phrase: $first, question: $q}} end)')
    t0=$(date +%s%N)
    code=$(jq -n --arg model "$model" --rawfile prompt "$raw/interpret_prompt.txt" --arg input "$input" \
        --slurpfile schema "$raw/interpret_schema.json" \
        '{model: $model, system_instruction: $prompt, input: [{type: "text", text: $input}],
          generation_config: {thinking_level: "minimal"},
          response_format: {type: "text", mime_type: "application/json", schema: $schema[0]},
          store: false}' |
        curl -sS --max-time 20 -X POST https://generativelanguage.googleapis.com/v1beta/interactions \
            -H @<(printf 'x-goog-api-key: %s\n' "$key") -H 'Content-Type: application/json' \
            --data-binary @- -o "$out" -w '%{http_code}') || code=000
    t1=$(date +%s%N)
    local ms=$(( (t1 - t0) / 1000000 ))
    if [[ $code != 200 ]]; then
        printf '%s\t!HTTP %s %s\n' "$ms" "$code" "$(jq -r '.error.message // empty' "$out" 2>/dev/null | head -c 120 | head -n1)"
        return
    fi
    local text
    text=$(jq -r '[.steps[]? | select(.type == "model_output") | .content[]? | select(.type == "text") | .text]
        | join("") | fromjson | '"$compact" "$out" 2>/dev/null) || text='!unreadable answer'
    printf '%s\t%s\n' "$ms" "${text,,}"
}

if [[ $# -gt 0 ]]; then one "$1" "${2:-}" "${3:-}" "${4:-}"; exit; fi

ok=0; n=0; times=()
# Tabs are whitespace to `read` (empty fields would collapse), so split on U+001F instead.
while IFS=$'\x1f' read -r phrase ctx expect first question; do
    [[ -z $phrase ]] && continue
    (( n > 0 )) && sleep 4.2
    IFS=$'\t' read -r ms got < <(one "$phrase" "$ctx" "$first" "$question")
    mark=FAIL
    # shellcheck disable=SC2053
    if [[ $got == $expect ]]; then mark=ok; ok=$((ok + 1)); fi
    n=$((n + 1)); times+=("$ms")
    [[ -n $question ]] && phrase="$first / $question / $phrase"
    printf '%-4s %5s ms  %-45s → %s%s\n' "$mark" "$ms" "\"$phrase\"" "$got" "$([[ $mark == FAIL ]] && echo "   (expected $expect)")"
done < <(tr '\t' '\037' < "$here/phrases.tsv")
sorted=($(printf '%s\n' "${times[@]}" | sort -n))
echo "$ok/$n as expected; latency p50 ${sorted[$((n / 2))]} ms, p95 ${sorted[$(( (n * 95 + 99) / 100 - 1 ))]} ms, max ${sorted[-1]} ms"
