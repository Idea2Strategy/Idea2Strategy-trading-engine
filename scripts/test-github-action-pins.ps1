$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$expected = @{
    "actions/checkout"           = @{ Sha = "3d3c42e5aac5ba805825da76410c181273ba90b1"; Tag = "v7.0.1" }
    "actions/setup-java"         = @{ Sha = "b6effb05e454b25005698d916606bdc6ffcbf961"; Tag = "v5.7.0" }
    "gradle/actions/setup-gradle" = @{ Sha = "9c971963bec38e04b3d30dcc455b5382be2fdbfb"; Tag = "v6.3.0" }
}
$pattern = '^\s*(?:-\s+)?uses:\s+([^\s@]+)@([^\s#]+)(?:\s+#\s+(\S+))?\s*$'
$observed = 0
foreach ($workflow in Get-ChildItem (Join-Path $root ".github/workflows") -File -Filter "*.yml") {
    $lineNumber = 0
    foreach ($line in Get-Content $workflow.FullName) {
        $lineNumber++
        if ($line -notmatch 'uses:') { continue }
        if ($line -notmatch $pattern) { throw "$($workflow.Name):$lineNumber has an invalid action reference" }
        $action, $revision, $tag = $Matches[1], $Matches[2], $Matches[3]
        if (-not $expected.ContainsKey($action)) { throw "$($workflow.Name):$lineNumber uses unreviewed action $action" }
        if ($revision -ne $expected[$action].Sha) { throw "$($workflow.Name):$lineNumber must pin $action@$($expected[$action].Sha)" }
        if ($tag -ne $expected[$action].Tag) { throw "$($workflow.Name):$lineNumber must annotate $action with # $($expected[$action].Tag)" }
        $observed++
    }
}
if ($observed -eq 0) { throw "No workflow action references found" }
Write-Host "GitHub Action immutable Node.js 24 pins passed ($observed references)."
