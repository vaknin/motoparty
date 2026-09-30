#!/usr/bin/env bash
# Sends spoken phrases to Gemini exactly as the app's cloud interpreter does (PROTOCOL.md
# "Commands", Interpretation) and prints, per phrase, the command text it maps to, whether that is
# what phrases.tsv expects, and the round-trip time. The prompt and schema are the app's own
# (android/app/src/main/res/raw/interpret_prompt.txt, interpret_schema.json).
#
#   tools/interpret/smoke.sh                      every line of tools/interpret/phrases.tsv
#   tools/interpret/smoke.sh "play some moby"     one phrase, nothing playing
#   tools/interpret/smoke.sh "any" "" "play a moby album" "Which Moby album?"   a reply to a question
#   GEMINI_MODEL=… tools/interpret/smoke.sh       another model
#
# phrases.tsv: phrase <tab> playing ("title – artist" or empty) <tab> expected command text, a
# glob (`*` allowed), `-` = conversation, `ask <question> | <fallback or ->` = a clarifying question.
# Two more columns make the line the second turn: the first request and the question that was
# asked, with the phrase being the reply to it. Each phrase is one free-tier request (15 a minute, 500 a
# day for the Lite model on 2026-09-20), so the full file is spaced 4.2 s apart.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
raw=$root/android/app/src/main/res/raw
model=${GEMINI_MODEL:-gemini-3.5-flash-lite}
key=$(sed -n 's/^gemini\.apiKey=//p' "$root/android/local.properties" | tr -d '[:space:]')
[[ -n $key ]] || { echo "gemini.apiKey is not set in android/local.properties" >&2; exit 1; }
out=$(mktemp); trap '[[ -n ${KEEP_RAW:-} ]] && cp "$out" "$KEEP_RAW"; rm -f "$out"' EXIT

# one <phrase> <playing> [<first request> <question>]: prints "<ms>\t<command text or - or !error>"
one() {
    local input code t0 t1
    input=$(jq -cn --arg p "$1" --arg now "$2" --arg first "${3:-}" --arg q "${4:-}" \
        '{phrase: $p, lang: "en-US", playing: (if $now == "" then null else $now end), upNext: []}
         + (if $q == "" then {} else {asked: {phrase: $first, question: $q}} end)')
    t0=$(date +%s%N)
    code=$(jq -n --arg model "$model" --rawfile prompt "$raw/interpret_prompt.txt" --arg input "$input" \
        --slurpfile schema "$raw/interpret_schema.json" \
        '{model: $model, system_instruction: $prompt, input: [{type: "text", text: $input}],
          generation_config: {thinking_level: "minimal"},
          response_format: {type: "text", mime_type: "application/json", schema: $schema[0]},
          store: false}' |
        curl -sS --max-time 20 -X POST https://generativelanguage.googleapis.com/v1beta/interactions \
            -H "x-goog-api-key: $key" -H 'Content-Type: application/json' \
            --data-binary @- -o "$out" -w '%{http_code}') || code=000
    t1=$(date +%s%N)
    local ms=$(( (t1 - t0) / 1000000 ))
    if [[ $code != 200 ]]; then
        printf '%s\t!HTTP %s %s\n' "$ms" "$code" "$(jq -r '.error.message // empty' "$out" 2>/dev/null | head -c 120 | head -n1)"
        return
    fi
    # The same mapping as the app's Interpretation.commandText (fixtures/interpret.json).
    local text
    text=$(jq -r '[.steps[]? | select(.type == "model_output") | .content[]? | select(.type == "text") | .text]
        | join("") | fromjson
        | (if (.query // "") == "" then "-" else "play \(.kind // "song") \(.query)" end) as $play
        | if .action == "play" then $play
          elif .action == "queue" then
            (if .kind == "similar" then "similar" elif $play == "-" then null else ($play | ltrimstr("play ")) end) as $src
            | if $src == null then "-" else
                ["queue", (if .where == "next" or .where == "instead" then .where else null end),
                 (if (.count | type) == "number" and .count >= 1 and .count <= 50 and .count == (.count | floor) then (.count | tostring) else null end),
                 $src] | map(select(. != null)) | join(" ") end
          elif .action == "ask" then "ask \(.question // "") | \($play)"
          elif .action == "volumeUp" then "volume up" elif .action == "volumeDown" then "volume down"
          elif .action == "nowplaying" then "what is playing" elif .action == "end" then "over"
          elif .action == "none" then "-" else .action end' "$out" 2>/dev/null) || text='!unreadable answer'
    printf '%s\t%s\n' "$ms" "${text,,}"
}

if [[ $# -gt 0 ]]; then one "$1" "${2:-}" "${3:-}" "${4:-}"; exit; fi

ok=0; n=0; times=()
# Tabs are whitespace to `read` (empty fields would collapse), so split on U+001F instead.
while IFS=$'\x1f' read -r phrase playing expect first question; do
    [[ -z $phrase ]] && continue
    (( n > 0 )) && sleep 4.2
    IFS=$'\t' read -r ms got < <(one "$phrase" "$playing" "$first" "$question")
    mark=FAIL
    # shellcheck disable=SC2053
    if [[ $got == $expect ]]; then mark=ok; ok=$((ok + 1)); fi
    n=$((n + 1)); times+=("$ms")
    [[ -n $question ]] && phrase="$first / $question / $phrase"
    printf '%-4s %5s ms  %-45s → %s%s\n' "$mark" "$ms" "\"$phrase\"" "$got" "$([[ $mark == FAIL ]] && echo "   (expected $expect)")"
done < <(tr '\t' '\037' < "$here/phrases.tsv")
sorted=($(printf '%s\n' "${times[@]}" | sort -n))
echo "$ok/$n as expected; latency p50 ${sorted[$((n / 2))]} ms, p95 ${sorted[$(( (n * 95 + 99) / 100 - 1 ))]} ms, max ${sorted[-1]} ms"
