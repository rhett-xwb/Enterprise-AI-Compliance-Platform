$ErrorActionPreference = 'Stop'
$inputFile = Join-Path $PSScriptRoot '企业智能法律合规与审批平台开发流程说明.docx'
$qaDir = Join-Path $PSScriptRoot '.development-guide-qa'
New-Item -ItemType Directory -Path $qaDir -Force | Out-Null
$pdfFile = Join-Path $qaDir 'guide.pdf'
$wordApp = $null
$wordDoc = $null
try {
    $wordApp = New-Object -ComObject Word.Application
    $wordApp.Visible = $false
    $wordApp.DisplayAlerts = 0
    $wordDoc = $wordApp.Documents.Open($inputFile, $false, $true)
    $wordDoc.ExportAsFixedFormat($pdfFile, 17)
    Write-Output "Rendered PDF: $pdfFile"
} finally {
    if ($wordDoc) { $wordDoc.Close(0) }
    if ($wordApp) { $wordApp.Quit() }
}
