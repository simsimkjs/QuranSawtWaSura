# تشغيل نسخة الويب عبر خادم محلي يدعم Range (ضروري للقفز داخل ملفات الصوت).
# الاستخدام من أي مكان:
#   .\web\serve.ps1            (المنفذ 8000)
#   .\web\serve.ps1 -Port 9000

param(
    [int]$Port = 8000
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$serverScript = Join-Path $PSScriptRoot "serve.py"

Push-Location $repoRoot
try {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py $serverScript -p $Port -d $repoRoot
    }
    elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python $serverScript -p $Port -d $repoRoot
    }
    elseif (Get-Command npx -ErrorAction SilentlyContinue) {
        Write-Host "Python غير متوفر — سأستخدم npx serve (يدعم Range أيضًا)."
        & npx --yes serve -l $Port .
    }
    else {
        Write-Error "لم أجد Python أو Node.js. ثبّت أحدهما، أو شغّل أي خادم ملفات ثابت يدعم Range من جذر المشروع."
    }
}
finally {
    Pop-Location
}
