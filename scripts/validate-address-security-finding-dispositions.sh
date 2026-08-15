#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ledger="${1:-$root/specs/006-address-security-findings/finding-dispositions.md}"
[[ $# -le 1 && -r "$ledger" ]] || { echo 'DeepSeek finding ledger is missing or unreadable.' >&2; exit 1; }

# The ledger is untrusted Markdown. awk reads cells only; it never evaluates or echoes cell content.
awk '
function trim(s){sub(/^[[:space:]]+/,"",s);sub(/[[:space:]]+$/,"",s);return s}
function bad(s){s=tolower(trim(s));return s==""||s=="-"||s=="pending"||s=="tbd"||s=="todo"||s=="n/a"}
BEGIN{split("L1 L2 L3 I1 I2 I3 I4 I5 I6 I7 I8",ids," ");for(i in ids) expected[ids[i]]=1}
/^[[:space:]]*\|/ {
 n=split($0,c,"|"); first=trim(c[2]);
 if(first=="Finding"){header=1;for(i=2;i<n;i++){h=tolower(trim(c[i])); col[h]=i}next}
 if(!header || first ~ /^-+$/)next;
 id=first; if(!(id in expected)){unexpected=1;next}; seen[id]++;
 if(trim(c[col["severity"]]) != (id ~ /^L/ ? "Low" : "Info")) badrow=1;
 if(bad(c[col["rationale"]]) || c[col["requirements"]] !~ /FR-[0-9][0-9][0-9]/ || bad(c[col["implementation evidence"]]) || bad(c[col["regression evidence"]]) || bad(c[col["documentation evidence"]]) || bad(c[col["residual risk"]]) || bad(c[col["owner"]]) || bad(c[col["review trigger"]])) badrow=1;
 status=tolower(trim(c[col["status"]])); if(status!="planned"&&status!="implemented"&&status!="verified"&&status!="accepted")badrow=1;
 if(status=="verified" && (c[col["regression evidence"]] ~ /[Pp]lanned/ || c[col["implementation evidence"]] ~ /[Pp]lanned/))badrow=1;
}
END{required="finding severity disposition rationale requirements implementation evidence regression evidence documentation evidence residual risk owner status review trigger"; split(required,r," "); if(!header){badrow=1}; for(i in expected)if(seen[i]!=1)badrow=1; if(unexpected||badrow){print "DeepSeek finding disposition validation failed." > "/dev/stderr";exit 1};print "DeepSeek finding disposition validation passed: 11 accountable findings."}
' "$ledger"
