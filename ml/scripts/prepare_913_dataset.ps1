param(
    [string]$ExportDir = "C:\Users\redsito\Downloads\labrumiologia-yolo26m-1035-limpio-2026-09-10-a1dec3b1",
    [string[]]$ImageRoots = @(
        "C:\Users\redsito\Downloads\Modelos lab",
        "C:\Users\redsito\Downloads\PROYECTO FINAL"
    ),
    [string]$OutputDir = "$PSScriptRoot\..\exports\rumiologia_913"
)

$ErrorActionPreference = "Stop"

function Get-NormalizedStem([string]$Stem) {
    $withoutLabelStudioHash = $Stem -replace '^[0-9a-fA-F]{8}-', ''
    return ($withoutLabelStudioHash -replace '[^A-Za-z0-9]', '').ToLowerInvariant()
}

$labelsDir = Join-Path $ExportDir "labels"
$classesPath = Join-Path $ExportDir "classes.txt"
if (-not (Test-Path -LiteralPath $labelsDir)) { throw "No existe $labelsDir" }
if (-not (Test-Path -LiteralPath $classesPath)) { throw "No existe $classesPath" }

$imageMap = @{}
foreach ($root in $ImageRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    Get-ChildItem -LiteralPath $root -File -Recurse |
        Where-Object { $_.Extension -match '^\.(jpg|jpeg|png|webp)$' } |
        ForEach-Object {
            $key = Get-NormalizedStem $_.BaseName
            if (-not $imageMap.ContainsKey($key)) { $imageMap[$key] = $_ }
        }
}

$imagesOut = Join-Path $OutputDir "images"
$labelsOut = Join-Path $OutputDir "labels"
New-Item -ItemType Directory -Force -Path $imagesOut, $labelsOut | Out-Null

$matched = 0
$missing = [System.Collections.Generic.List[string]]::new()
foreach ($label in Get-ChildItem -LiteralPath $labelsDir -Filter '*.txt' -File) {
    $key = Get-NormalizedStem $label.BaseName
    if (-not $imageMap.ContainsKey($key)) {
        $missing.Add($label.BaseName)
        continue
    }

    $image = $imageMap[$key]
    Copy-Item -LiteralPath $image.FullName -Destination (Join-Path $imagesOut ($label.BaseName + $image.Extension.ToLowerInvariant())) -Force
    Copy-Item -LiteralPath $label.FullName -Destination (Join-Path $labelsOut $label.Name) -Force
    $matched++
}

Copy-Item -LiteralPath $classesPath -Destination (Join-Path $OutputDir "classes.txt") -Force
$missing | Set-Content -LiteralPath (Join-Path $OutputDir "excluded_missing_images.txt") -Encoding utf8

$summary = [ordered]@{
    matched_images = $matched
    matched_labels = $matched
    excluded_missing_images = $missing.Count
    classes = (Get-Content -LiteralPath $classesPath).Count
    generated_at = (Get-Date).ToString("o")
}
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDir "dataset_summary.json") -Encoding utf8

if ($matched -ne 913 -or $missing.Count -ne 122) {
    throw "Conteo inesperado: $matched coincidentes y $($missing.Count) faltantes"
}

Write-Host "Dataset listo: $OutputDir"
Write-Host "Imágenes/etiquetas: $matched; excluidas: $($missing.Count)"
