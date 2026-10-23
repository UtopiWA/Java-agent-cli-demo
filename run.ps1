param(
    [string]$ProjectRoot = $PSScriptRoot
)

$ErrorActionPreference = "Stop"
$agentRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$project = (Resolve-Path -LiteralPath $ProjectRoot).Path
$jar = Join-Path $agentRoot "target\agent-cli-0.1.0.jar"

$needsBuild = -not (Test-Path -LiteralPath $jar)
if (-not $needsBuild) {
    $jarTime = (Get-Item -LiteralPath $jar).LastWriteTimeUtc
    $newerSource = Get-ChildItem -LiteralPath (Join-Path $agentRoot "src") -Recurse -File |
        Where-Object { $_.LastWriteTimeUtc -gt $jarTime } |
        Select-Object -First 1
    $needsBuild = $null -ne $newerSource -or
        (Get-Item -LiteralPath (Join-Path $agentRoot "pom.xml")).LastWriteTimeUtc -gt $jarTime
}

if ($needsBuild) {
    Write-Host "[build] Building Agent JAR..."
    & mvn -q -f (Join-Path $agentRoot "pom.xml") -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Agent 构建失败，退出码 $LASTEXITCODE"
    }
}

if ([string]::IsNullOrWhiteSpace($env:GLM_API_KEY)) {
    $agentEnv = Join-Path $agentRoot ".env"
    if (Test-Path -LiteralPath $agentEnv) {
        $keyLine = Get-Content -LiteralPath $agentEnv -Encoding UTF8 |
            Where-Object { $_ -match '^\s*GLM_API_KEY\s*=' } |
            Select-Object -First 1
        if ($null -ne $keyLine) {
            $env:GLM_API_KEY = ($keyLine -replace '^\s*GLM_API_KEY\s*=\s*', '').Trim()
        }
    }
}

Push-Location -LiteralPath $project
try {
    & java -jar $jar
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
