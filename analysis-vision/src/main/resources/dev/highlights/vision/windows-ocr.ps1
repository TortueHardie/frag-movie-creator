param([Parameter(Mandatory)][string]$List, [Parameter(Mandatory)][string]$Out, [string]$Language = 'fr-FR')
# OCR intégré à Windows (Windows.Media.Ocr, sans installation) sur une liste d'images, une par ligne dans $List.
# Sortie $Out (UTF-8) : une ligne JSON par image, {"file":…,"lines":[{"text":…,"x":…,"y":…,"w":…,"h":…}]}.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait() | Out-Null
    $task.Result
}
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language $Language))
if ($null -eq $engine) { $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
if ($null -eq $engine) { throw "aucune langue OCR installée" }
$writer = New-Object System.IO.StreamWriter($Out, $false, (New-Object System.Text.UTF8Encoding($false)))
try {
    foreach ($path in [System.IO.File]::ReadAllLines($List)) {
        if ([string]::IsNullOrWhiteSpace($path)) { continue }
        $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
        $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        try {
            $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
            $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        } finally {
            $stream.Dispose()
        }
        $lines = @(foreach ($line in $result.Lines) {
            $x0 = [double]::MaxValue; $y0 = [double]::MaxValue; $x1 = 0.0; $y1 = 0.0
            foreach ($w in $line.Words) {
                $r = $w.BoundingRect
                $x0 = [Math]::Min($x0, $r.X); $y0 = [Math]::Min($y0, $r.Y)
                $x1 = [Math]::Max($x1, $r.X + $r.Width); $y1 = [Math]::Max($y1, $r.Y + $r.Height)
            }
            [ordered]@{ text = $line.Text; x = [int]$x0; y = [int]$y0; w = [int]($x1 - $x0); h = [int]($y1 - $y0) }
        })
        $writer.WriteLine((ConvertTo-Json -Compress -Depth 4 ([ordered]@{ file = $path; lines = $lines })))
    }
} finally {
    $writer.Dispose()
}
